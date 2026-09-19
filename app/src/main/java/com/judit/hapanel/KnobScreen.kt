package com.judit.hapanel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pilote le petit écran rond situé dans le bouton rotatif.
 *
 * Ce n'est pas un écran Android : le système ne connaît qu'un seul display (le
 * 1024x600). Celui-ci est un GC9A01 de 240x240 en RGB565 branché sur SPI, exposé comme
 * framebuffer brut en /dev/graphics/fb0. Le nœud est en crwxrwxrwx, donc accessible
 * sans root.
 *
 * La dalle est montée **en miroir horizontal** dans le bouton : vérifié avec une mire à
 * quatre quadrants, le rouge dessiné en haut à gauche ressort en haut à droite.
 *
 * La symétrie est appliquée **au moment d'écrire les pixels**, pas au dessin. C'est
 * délibéré : inverser le canevas inverserait aussi le sens de rotation des arcs et
 * obligerait à raisonner à l'envers sur tous les angles. Ici le code de dessin reste
 * parfaitement normal, et le miroir est une opération unique et isolée.
 */
class KnobScreen {

    companion object {
        const val WIDTH = 240
        const val HEIGHT = 240
        private const val FB = "/dev/graphics/fb0"
        private const val TAG = "KnobScreen"
        private const val BYTES_PER_PIXEL = 2
        private const val FRAME_BYTES = WIDTH * HEIGHT * BYTES_PER_PIXEL

        /** Mettre à false si la dalle était un jour montée à l'endroit. */
        private const val MIRROR_HORIZONTAL = true
    }

    private val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.RGB_565)
    private val canvas = Canvas(bitmap)
    private val source = ByteBuffer.allocate(FRAME_BYTES).order(ByteOrder.nativeOrder())
    private val frame = ByteArray(FRAME_BYTES)

    private val arcBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 16f
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(40, 40, 48)
    }
    private val arcFg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 16f
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(0, 170, 255)
    }
    private val bigText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 56f
    }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(170, 175, 185)
        textAlign = Paint.Align.CENTER
        textSize = 20f
    }

    private val arcRect = RectF(26f, 26f, WIDTH - 26f, HEIGHT - 26f)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    @Volatile
    var available: Boolean = File(FB).canWrite()
        private set

    /** Écran de repos : heure et date, comme le firmware d'origine. */
    fun drawClock() {
        canvas.drawColor(Color.BLACK)
        val now = Date()
        bigText.textSize = 62f
        canvas.drawText(timeFormat.format(now), WIDTH / 2f, HEIGHT / 2f + 10f, bigText)
        canvas.drawText(dateFormat.format(now), WIDTH / 2f, HEIGHT / 2f + 48f, smallText)
        push()
    }

    /**
     * Écran de réglage : arc de progression, valeur au centre, nom de l'entité dessous.
     *
     * L'arc est ouvert en bas. Il part du bas à gauche (135°) et se remplit dans le sens
     * horaire sur 270°, comme le ferait un cadran : tourner à droite fait monter l'arc.
     */
    fun drawValue(name: String, value: String, fraction: Float, accent: Int = Color.rgb(0, 170, 255)) {
        canvas.drawColor(Color.BLACK)
        arcFg.color = accent

        canvas.drawArc(arcRect, 135f, 270f, false, arcBg)
        canvas.drawArc(arcRect, 135f, 270f * fraction.coerceIn(0f, 1f), false, arcFg)

        bigText.textSize = if (value.length > 4) 44f else 56f
        canvas.drawText(value, WIDTH / 2f, HEIGHT / 2f + 12f, bigText)
        canvas.drawText(ellipsize(name, 16), WIDTH / 2f, HEIGHT / 2f + 48f, smallText)
        push()
    }

    /** Message court centré (erreurs de connexion, etc.). */
    fun drawMessage(line1: String, line2: String = "") {
        canvas.drawColor(Color.BLACK)
        bigText.textSize = 30f
        canvas.drawText(ellipsize(line1, 12), WIDTH / 2f, HEIGHT / 2f, bigText)
        if (line2.isNotEmpty()) {
            canvas.drawText(ellipsize(line2, 18), WIDTH / 2f, HEIGHT / 2f + 34f, smallText)
        }
        push()
    }

    fun clear() {
        canvas.drawColor(Color.BLACK)
        push()
    }

    private fun ellipsize(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - 1) + "…"

    /**
     * Déverse le bitmap dans le framebuffer, en inversant chaque ligne si la dalle est
     * montée en miroir. Le format mémoire du bitmap RGB_565 correspond exactement à
     * celui attendu par le framebuffer, il n'y a donc aucune conversion de couleur.
     */
    private fun push() {
        source.rewind()
        bitmap.copyPixelsToBuffer(source)
        val src = source.array()

        if (MIRROR_HORIZONTAL) {
            for (y in 0 until HEIGHT) {
                val row = y * WIDTH * BYTES_PER_PIXEL
                for (x in 0 until WIDTH) {
                    val from = row + x * BYTES_PER_PIXEL
                    val to = row + (WIDTH - 1 - x) * BYTES_PER_PIXEL
                    frame[to] = src[from]
                    frame[to + 1] = src[from + 1]
                }
            }
        } else {
            System.arraycopy(src, 0, frame, 0, FRAME_BYTES)
        }

        try {
            FileOutputStream(FB).use { it.write(frame, 0, FRAME_BYTES) }
            available = true
        } catch (e: Exception) {
            if (available) Log.w(TAG, "écriture sur $FB impossible : ${e.message}")
            available = false
        }
    }
}
