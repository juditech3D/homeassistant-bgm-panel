package com.judit.hapanel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.util.Log
import android.view.View
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Écran de veille : diaporama de photos, ou fond animé.
 *
 * Inspiré de ce que fait WallPanel pour les tableaux de bord Home Assistant, mais
 * réimplémenté en natif — WallPanel est un module Lovelace qui tourne dans le
 * navigateur, or le WebView de ce panneau est inutilisable.
 *
 * Le PX30 est modeste et sans accélération sérieuse : tout est dessiné au Canvas, le
 * nombre de particules reste bas, et aucun flou n'est appliqué — un `BlurMaskFilter`
 * effondre la fluidité sur ce matériel.
 */
class ScreensaverView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /**
     * Rien d'immobile ici, volontairement : une image fixe affichee des heures marque la
     * dalle. Le diaporama change de photo, l'animation ne se repete pas, et la video --
     * qui ne passe pas par cette vue, un Canvas ne lisant pas de flux, mais par un
     * lecteur pose a cote dans le tableau de bord -- tourne en boucle.
     */
    enum class Mode { PHOTOS, ANIMATED }

    var mode: Mode = Mode.ANIMATED
    var photoFolder: String = DEFAULT_FOLDER


    // ------------------------------------------------------------------ photos

    private var photos: List<File> = emptyList()
    private var photoIndex = 0
    private var current: Bitmap? = null
    private var previous: Bitmap? = null

    /** Instant d'apparition de la photo courante, pour l'effet de zoom et le fondu. */
    private var photoShownAt = 0L

    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()

    /** Sens du mouvement de la photo courante, tiré au sort à chaque changement. */
    private var panX = 0f
    private var panY = 0f
    private var zoomIn = true

    // ---------------------------------------------------------------- animation

    private class Particle(
        var x: Float, var y: Float,
        var speed: Float, var angle: Float,
        var radius: Float, var hue: Float
    )

    private val particles = ArrayList<Particle>()
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint()
    private var backgroundReady = false

    private val random = Random(System.nanoTime())

    // ------------------------------------------------------------------ cycle

    fun startSaver() {
        visibility = VISIBLE
        when (mode) {
            Mode.PHOTOS -> {
                loadPhotoList()
                nextPhoto()
            }
            Mode.ANIMATED -> if (particles.isEmpty()) seedParticles()
        }
        postInvalidateOnAnimation()
    }

    fun stopSaver() {
        visibility = GONE
        // Les photos pèsent lourd en mémoire : on rend tout dès qu'on sort.
        current?.recycle(); current = null
        previous?.recycle(); previous = null
    }

    override fun onDraw(canvas: Canvas) {
        if (visibility != VISIBLE) return
        when (mode) {
            Mode.PHOTOS -> drawPhotos(canvas)
            Mode.ANIMATED -> drawAnimated(canvas)
        }
        postInvalidateOnAnimation()
    }

    // ----------------------------------------------------------- mode photos

    private fun loadPhotoList() {
        val dir = File(photoFolder)
        photos = try {
            dir.listFiles { f ->
                f.isFile && f.name.substringAfterLast('.', "").lowercase() in EXTENSIONS
            }?.sortedBy { it.name } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "dossier de photos illisible : ${e.message}")
            emptyList()
        }
        if (photos.isEmpty()) {
            Log.i(TAG, "aucune photo dans $photoFolder — repli sur le fond animé")
            mode = Mode.ANIMATED
            if (particles.isEmpty()) seedParticles()
        }
    }

    private fun nextPhoto() {
        if (photos.isEmpty()) return
        previous?.recycle()
        previous = current
        current = decodeScaled(photos[photoIndex % photos.size])
        photoIndex++
        photoShownAt = System.currentTimeMillis()

        // Direction du mouvement tirée au sort : deux photos de suite ne bougent pas
        // pareil, ce qui évite l'impression de boucle.
        val a = random.nextFloat() * 2f * Math.PI.toFloat()
        panX = cos(a)
        panY = sin(a)
        zoomIn = random.nextBoolean()
    }

    /**
     * Décode en réduisant à la volée : une photo d'appareil moderne dépasse les
     * 50 Mo une fois décompressée, ce que ce panneau ne supporterait pas.
     */
    private fun decodeScaled(file: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        var sample = 1
        val targetW = width.coerceAtLeast(1) * 3 / 2
        val targetH = height.coerceAtLeast(1) * 3 / 2
        while (bounds.outWidth / sample > targetW && bounds.outHeight / sample > targetH) {
            sample *= 2
        }

        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    } catch (e: Exception) {
        Log.w(TAG, "photo illisible ${file.name} : ${e.message}")
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "photo trop lourde : ${file.name}")
        null
    }

    private fun drawPhotos(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val elapsed = System.currentTimeMillis() - photoShownAt

        if (elapsed > PHOTO_DURATION_MS) {
            nextPhoto()
            return
        }

        // Fondu enchaîné : l'ancienne photo s'efface pendant que la nouvelle arrive.
        previous?.let { old ->
            if (elapsed < CROSSFADE_MS) {
                photoPaint.alpha = 255
                drawKenBurns(canvas, old, 1f)
            }
        }

        current?.let { bmp ->
            photoPaint.alpha =
                if (elapsed < CROSSFADE_MS) (255 * elapsed / CROSSFADE_MS).toInt() else 255
            drawKenBurns(canvas, bmp, elapsed.toFloat() / PHOTO_DURATION_MS)
        }
        photoPaint.alpha = 255
    }

    /**
     * Effet Ken Burns : la photo est cadrée pour couvrir l'écran, puis zoomée et
     * translatée lentement. Sans ce mouvement, un diaporama paraît figé.
     */
    private fun drawKenBurns(canvas: Canvas, bmp: Bitmap, progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        val cover = maxOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val zoom = if (zoomIn) 1f + KEN_BURNS_ZOOM * p else 1f + KEN_BURNS_ZOOM * (1f - p)
        val scale = cover * zoom

        val drawnW = bmp.width * scale
        val drawnH = bmp.height * scale
        val slackX = (drawnW - width) / 2f
        val slackY = (drawnH - height) / 2f

        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(
            -slackX + panX * slackX * (p - 0.5f),
            -slackY + panY * slackY * (p - 0.5f)
        )
        canvas.drawBitmap(bmp, matrix, photoPaint)
    }

    // --------------------------------------------------------- mode anime

    private fun seedParticles() {
        particles.clear()
        repeat(PARTICLE_COUNT) {
            particles.add(
                Particle(
                    x = random.nextFloat() * width.coerceAtLeast(1),
                    y = random.nextFloat() * height.coerceAtLeast(1),
                    speed = 0.15f + random.nextFloat() * 0.45f,
                    angle = random.nextFloat() * 2f * Math.PI.toFloat(),
                    radius = 1.5f + random.nextFloat() * 3.5f,
                    hue = 190f + random.nextFloat() * 60f
                )
            )
        }
    }

    private fun drawAnimated(canvas: Canvas) {
        if (!backgroundReady && width > 0) {
            backgroundPaint.shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(0xFF0A0E14.toInt(), 0xFF101A28.toInt(), 0xFF0B1118.toInt()),
                null, Shader.TileMode.CLAMP
            )
            backgroundReady = true
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        if (particles.isEmpty() && width > 0) seedParticles()

        val t = System.currentTimeMillis() / 1000.0
        for (p in particles) {
            // Dérive lente et sinueuse : l'angle oscille doucement, ce qui donne un
            // mouvement organique sans avoir à calculer du bruit de Perlin.
            p.angle += (sin(t * 0.3 + p.x * 0.01) * 0.02).toFloat()
            p.x += cos(p.angle) * p.speed
            p.y += sin(p.angle) * p.speed

            if (p.x < -20) p.x = width + 20f
            if (p.x > width + 20) p.x = -20f
            if (p.y < -20) p.y = height + 20f
            if (p.y > height + 20) p.y = -20f

            // Halo par dégradé radial : bien moins coûteux qu'un flou sur ce matériel.
            val color = Color.HSVToColor(floatArrayOf(p.hue, 0.55f, 0.95f))
            particlePaint.shader = RadialGradient(
                p.x, p.y, p.radius * 6f,
                intArrayOf(color, color and 0x00FFFFFF),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(p.x, p.y, p.radius * 6f, particlePaint)
        }
        particlePaint.shader = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        backgroundReady = false
        if (mode == Mode.ANIMATED) seedParticles()
    }

    companion object {
        private const val TAG = "Screensaver"

        const val DEFAULT_FOLDER = "/sdcard/HAPanel/fonds"
        private val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        private const val PHOTO_DURATION_MS = 20_000L
        private const val CROSSFADE_MS = 1_500L
        private const val KEN_BURNS_ZOOM = 0.12f

        /** Volontairement bas : le PX30 n'a pas de marge. */
        private const val PARTICLE_COUNT = 45
    }
}
