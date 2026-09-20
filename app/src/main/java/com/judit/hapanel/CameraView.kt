package com.judit.hapanel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.View
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Affiche le flux d'une caméra de Home Assistant, en plein écran.
 *
 * On passe par le **proxy caméra de Home Assistant** plutôt que d'interroger Frigate
 * directement : l'adresse et le jeton sont déjà connus, il n'y a rien de plus à
 * configurer, et cela fonctionne avec n'importe quelle caméra — Frigate, ONVIF, une
 * webcam — sans rien changer ici.
 *
 * Le flux est du **MJPEG multipart** : une suite d'images JPEG séparées par une
 * frontière. On l'analyse à la main plutôt que d'embarquer une bibliothèque vidéo :
 * c'est une centaine de lignes, sans dépendance, et surtout sans décodeur matériel à
 * solliciter — ce PX30 n'a pas de marge. Si le flux échoue, on se rabat sur des images
 * fixes rafraîchies, ce qui reste utilisable pour voir qui sonne.
 */
class CameraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /**
     * Un délai de lecture borné est indispensable : sans lui, un flux qui accepte la
     * connexion puis n'envoie rien laisse l'affichage bloqué sur « Connexion… »
     * indéfiniment, sans la moindre erreur. On préfère échouer vite et basculer sur les
     * images fixes.
     */
    private val streamHttp = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(STREAM_READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /**
     * Client distinct pour les images fixes, avec un délai bien plus long. Une caméra
     * RTSP peut mettre plusieurs secondes à produire son premier instantané : le délai
     * court réservé au flux continu ferait échouer toutes les images.
     */
    private val snapshotHttp = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(SNAPSHOT_READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }

    @Volatile private var frame: Bitmap? = null
    @Volatile private var running = false
    @Volatile private var label = ""

    /**
     * Échecs consécutifs. Une caméra hors service répond souvent « 200 » avec un corps
     * vide : sans ce compteur, l'écran resterait indéfiniment sur « Connexion… » sans
     * rien dire, ce qui laisse croire à un défaut de l'application.
     */
    @Volatile private var failures = 0
    private var worker: Thread? = null

    private val destination = Rect()

    /**
     * Décalage vertical du cadrage, en pixels d'écran. Les caméras en portrait — une
     * double optique fait ici 2304 × 2592 — sont bien plus hautes que larges : les
     * afficher en entier les réduirait à une bande illisible. On les cale donc sur la
     * largeur et l'on fait défiler.
     */
    private var panX = 0f
    private var panY = 0f
    private var maxPanX = 0f
    private var maxPanY = 0f

    /** Facteur de zoom appliqué par-dessus le cadrage de base. */
    private var zoom = 1f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var moved = false

    /** Appelé sur un appui simple, sans glissement ni pincement : referme la caméra. */
    var onTap: (() -> Unit)? = null

    private val scaleDetector = android.view.ScaleGestureDetector(
        context,
        object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                val before = zoom
                zoom = (zoom * detector.scaleFactor).coerceIn(1f, MAX_ZOOM)

                // On zoome autour des doigts plutôt que du centre : sans cela, la zone
                // qu'on cherche à agrandir s'échappe de l'écran.
                val growth = zoom / before
                panX = (panX + detector.focusX) * growth - detector.focusX
                panY = (panY + detector.focusY) * growth - detector.focusY

                moved = true
                invalidate()
                return true
            }
        }
    )

    /**
     * Démarre l'affichage de la caméra. Sans effet si déjà en cours.
     *
     * [accessToken] est l'attribut `access_token` de l'entité. Les routes caméra de Home
     * Assistant attendent ce jeton **dans l'URL**, et non l'en-tête `Authorization` :
     * sans lui, le serveur répond 500. Il tourne régulièrement, mais on le relit à chaque
     * affichage depuis l'état courant de l'entité.
     */
    fun start(prefs: Prefs, entityId: String, friendlyName: String, accessToken: String) {
        if (running) return
        if (entityId.isBlank()) {
            Log.w(TAG, "aucune caméra configurée")
            return
        }
        running = true
        label = friendlyName
        failures = 0
        // Chaque affichage repart d'un cadrage neuf : on ne veut pas retrouver le zoom
        // laissé par la sonnerie précédente.
        zoom = 1f
        panX = 0f
        panY = 0f
        visibility = VISIBLE

        worker = thread(name = "camera", isDaemon = true) {
            if (entityId.startsWith(Prefs.FRIGATE_PREFIX)) {
                val cam = entityId.removePrefix(Prefs.FRIGATE_PREFIX)
                Log.i(TAG, "caméra Frigate : $cam")
                // latest.jpg est la dernière image traitée par Frigate : elle reste
                // disponible même quand la caméra décroche momentanément.
                pollUrl("${prefs.frigateUrl}/api/$cam/latest.jpg", null)
            } else if (entityId.startsWith(Prefs.GO2RTC_PREFIX)) {
                val source = entityId.removePrefix(Prefs.GO2RTC_PREFIX)
                Log.i(TAG, "caméra go2rtc : $source")
                pollUrl("${prefs.go2rtcUrl}/api/frame.jpeg?src=$source", null)
            } else {
                val suffix = if (accessToken.isNotEmpty()) "?token=$accessToken" else ""
                Log.i(TAG, "caméra Home Assistant : $entityId")
                if (!streamMjpeg(prefs, entityId, suffix) && running) {
                    pollUrl(
                        "${prefs.httpBase()}/api/camera_proxy/$entityId$suffix",
                        prefs.token
                    )
                }
            }
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        visibility = GONE
        frame?.recycle()
        frame = null
    }

    // ------------------------------------------------------------ flux MJPEG

    /** Retourne false si le flux n'a pas pu démarrer, pour basculer sur les images fixes. */
    private fun streamMjpeg(prefs: Prefs, entityId: String, suffix: String): Boolean {
        val request = Request.Builder()
            .url("${prefs.httpBase()}/api/camera_proxy_stream/$entityId$suffix")
            .header("Authorization", "Bearer ${prefs.token}")
            .build()

        return try {
            streamHttp.newCall(request).execute().use { response ->
                Log.i(TAG, "flux : code ${response.code}, type ${response.header("Content-Type")}")
                if (!response.isSuccessful) {
                    Log.w(TAG, "flux refusé : ${response.code}")
                    return false
                }
                val input = response.body?.byteStream() ?: return false
                val frames = readParts(input)
                Log.i(TAG, "flux terminé après $frames image(s)")
                // Sans la moindre image, le flux ne vaut rien : on bascule sur le repli.
                frames > 0
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "flux indisponible : ${e.message}")
            false
        }
    }

    /**
     * Lit les images successives. Chaque partie annonce sa taille par `Content-Length`,
     * ce qui évite d'avoir à chercher la frontière dans les données binaires — bien plus
     * sûr, un JPEG pouvant contenir n'importe quelle séquence d'octets.
     */
    private fun readParts(input: InputStream): Int {
        var frames = 0
        while (running) {
            var length = -1
            while (running) {
                val line = readLine(input) ?: return frames
                if (line.startsWith("Content-Length", ignoreCase = true)) {
                    length = line.substringAfter(':').trim().toIntOrNull() ?: -1
                }
                if (line.isEmpty() && length > 0) break
            }
            if (length <= 0) return frames

            val data = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(data, read, length - read)
                if (n < 0) return frames
                read += n
            }
            publish(BitmapFactory.decodeByteArray(data, 0, read))
            if (frames == 0) Log.i(TAG, "première image reçue ($read octets)")
            frames++
        }
        return frames
    }

    /**
     * Pincement pour zoomer, glissement pour déplacer, appui simple pour refermer.
     *
     * Le clic n'est reconnu que si le doigt n'a pas bougé : sans cette distinction, tout
     * déplacement de l'image refermerait la caméra.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                moved = false
            }

            android.view.MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (kotlin.math.abs(dx) > TAP_SLOP || kotlin.math.abs(dy) > TAP_SLOP) {
                        moved = true
                    }
                    panX = (panX - dx).coerceIn(0f, maxPanX)
                    panY = (panY - dy).coerceIn(0f, maxPanY)
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                }
            }

            android.view.MotionEvent.ACTION_UP -> if (!moved) {
                performClick()
                onTap?.invoke()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    // -------------------------------------------------------- repli : snapshots

    /**
     * Rafraîchit une image fixe à intervalle régulier. Sert aussi bien pour go2rtc que
     * pour le proxy de Home Assistant : seuls l'URL et l'éventuel jeton changent.
     */
    private fun pollUrl(url: String, bearer: String?) {
        Log.i(TAG, "images fixes depuis $url")
        val request = Request.Builder()
            .url(url)
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .build()

        var first = true
        while (running) {
            try {
                val bytes = snapshotHttp.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.bytes()
                    } else {
                        Log.w(TAG, "image refusée : code ${response.code}")
                        null
                    }
                }
                val decoded = if (bytes != null && bytes.isNotEmpty()) {
                    decodeScaled(bytes)
                } else null

                if (decoded != null) {
                    failures = 0
                    publish(decoded)
                    if (first) {
                        Log.i(TAG, "image reçue (${bytes?.size} octets)")
                        first = false
                    }
                } else {
                    failures++
                    if (failures == 1) {
                        // Cas courant d'une caméra déconnectée : le serveur répond bien,
                        // mais sans données.
                        Log.w(
                            TAG,
                            context.getString(R.string.camera_no_image, bytes?.size ?: 0)
                        )
                    }
                    if (failures >= FAILURES_BEFORE_GIVING_UP) postInvalidate()
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "image indisponible : ${e.message}")
                failures++
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "mémoire insuffisante pour cette caméra")
                failures++
            }
            try {
                Thread.sleep(SNAPSHOT_PERIOD_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /**
     * Décode en réduisant à la taille de l'écran, et en RGB565.
     *
     * Indispensable, pas une optimisation : certaines caméras produisent des images
     * énormes. Une caméra à double objectif de 2304 × 2592 pèse **23 Mo** une fois
     * décodée en ARGB, et l'on en décode une toutes les 700 ms. Sur ce panneau de 2 Go,
     * cela provoque un `OutOfMemoryError` en quelques images — lequel n'est pas une
     * `Exception` et passait donc à travers les `catch`, tuant le fil en silence :
     * l'écran restait indéfiniment sur « Connexion… ».
     *
     * Réduite à la taille de la dalle et en RGB565, la même image tombe sous le mégaoctet.
     */
    private fun decodeScaled(data: ByteArray): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)

        var sample = 1
        val targetW = width.coerceAtLeast(1)
        val targetH = height.coerceAtLeast(1)
        while (bounds.outWidth / (sample * 2) >= targetW &&
            bounds.outHeight / (sample * 2) >= targetH
        ) {
            sample *= 2
        }

        BitmapFactory.decodeByteArray(
            data, 0, data.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    } catch (e: OutOfMemoryError) {
        // Attrapé explicitement : ce n'est pas une Exception, un catch ordinaire le
        // laisserait remonter et tuer le fil de lecture.
        Log.w(TAG, "image trop lourde pour être décodée (${data.size} octets)")
        null
    } catch (e: Exception) {
        Log.w(TAG, "image illisible : ${e.message}")
        null
    }

    /**
     * Appele a chaque image recue, sur le fil de lecture.
     *
     * L'image passee appartient a cette vue et sera recyclee : qui veut la garder en
     * fait une copie sans tarder. C'est par la que l'historique des sonneries prend sa
     * capture.
     */
    var onFrame: ((Bitmap) -> Unit)? = null

    private fun publish(bitmap: Bitmap?) {
        if (bitmap == null) return
        val old = frame
        frame = bitmap
        old?.recycle()
        onFrame?.invoke(bitmap)
        postInvalidate()
    }

    // ------------------------------------------------------------------ rendu

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val bmp = frame

        if (bmp == null || bmp.isRecycled) {
            labelPaint.textSize = 26f
            val message = if (failures >= FAILURES_BEFORE_GIVING_UP) {
                context.getString(R.string.camera_unavailable, label)
            } else {
                context.getString(R.string.camera_connecting)
            }
            canvas.drawText(message, 24f, height / 2f, labelPaint)
            return
        }

        // Une image plus haute que large est calée sur la largeur puis défilée ; sinon
        // on la montre en entier, quitte à laisser des bandes — sur une caméra de
        // sonnette, rogner ferait perdre le visage.
        val portrait = bmp.height * width > bmp.width * height
        val base = if (portrait) {
            width.toFloat() / bmp.width
        } else {
            minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        }
        val scale = base * zoom

        val w = (bmp.width * scale).toInt()
        val h = (bmp.height * scale).toInt()

        maxPanX = (w - width).coerceAtLeast(0).toFloat()
        maxPanY = (h - height).coerceAtLeast(0).toFloat()
        panX = panX.coerceIn(0f, maxPanX)
        panY = panY.coerceIn(0f, maxPanY)

        val left = if (maxPanX > 0) -panX.toInt() else (width - w) / 2
        val top = if (maxPanY > 0) -panY.toInt() else (height - h) / 2
        destination.set(left, top, left + w, top + h)
        canvas.drawBitmap(bmp, null, destination, paint)

        if (label.isNotEmpty()) {
            labelPaint.textSize = 22f
            canvas.drawText(label, 24f, 36f, labelPaint)
        }

        // Repère discret : sans lui, rien n'indique qu'il reste de l'image à voir.
        if (maxPanX > 0 || maxPanY > 0 || zoom > 1f) {
            labelPaint.textSize = 18f
            canvas.drawText(
                context.getString(R.string.camera_scroll_hint),
                24f, height - 20f, labelPaint
            )
        }
    }

    private companion object {
        const val TAG = "CameraView"
        /** Délai entre deux instantanés. Inutile d'aller plus vite que la caméra. */
        const val SNAPSHOT_PERIOD_MS = 700L

        /** Court exprès : un flux muet doit céder la place aux images fixes. */
        const val STREAM_READ_TIMEOUT_S = 6L

        /** Généreux : une caméra RTSP met du temps à produire son premier instantané. */
        const val SNAPSHOT_READ_TIMEOUT_S = 25L

        /** Au-delà, on l'annonce à l'écran plutôt que de laisser tourner « Connexion… ». */
        const val FAILURES_BEFORE_GIVING_UP = 3

        const val MAX_ZOOM = 5f

        /** En deçà, le doigt est considéré immobile : c'est un appui, pas un glissement. */
        const val TAP_SLOP = 12f
    }
}
