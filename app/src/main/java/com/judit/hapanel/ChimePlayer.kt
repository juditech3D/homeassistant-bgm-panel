package com.judit.hapanel

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.exp
import kotlin.math.sin

/**
 * Joue le carillon de la sonnette.
 *
 * Deux sources possibles : un fichier déposé par l'utilisateur dans un dossier du
 * panneau, ou un carillon **synthétisé par l'application**. Ce dernier existe pour qu'il
 * y ait toujours quelque chose à entendre dès la première installation, sans avoir à
 * embarquer un fichier audio dans l'APK ni à télécharger quoi que ce soit.
 *
 * Le carillon sort sur le flux musique, donc sur l'amplificateur du panneau, au volume
 * réglé par le bouton rotatif.
 */
class ChimePlayer(private val context: Context, private val prefs: Prefs) {

    private var player: MediaPlayer? = null

    /** Les carillons disponibles, dans l'ordre alphabétique. */
    fun availableChimes(): List<File> = try {
        File(prefs.chimeFolder).listFiles { f ->
            f.isFile && f.name.substringAfterLast('.', "").lowercase() in EXTENSIONS
        }?.sortedBy { it.name } ?: emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "dossier des carillons illisible : ${e.message}")
        emptyList()
    }

    /**
     * Joue le carillon retenu dans les réglages, ou celui synthétisé si aucun fichier
     * n'est choisi ou si le fichier a disparu.
     */
    @Synchronized
    fun play() {
        val chosen = prefs.chimeFile.takeIf { it.isNotEmpty() }?.let { File(it) }
        val source = if (chosen != null && chosen.isFile) chosen else builtInChime()
        if (source == null) {
            Log.w(TAG, "aucun carillon disponible")
            return
        }

        release()
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                        .build()
                )
                setDataSource(source.absolutePath)
                setOnCompletionListener { release() }
                prepare()
                start()
            }
            Log.i(TAG, "carillon joué : ${source.name}")
        } catch (e: Exception) {
            Log.w(TAG, "lecture du carillon impossible : ${e.message}")
        }
    }

    @Synchronized
    fun release() {
        try {
            player?.reset()
            player?.release()
        } catch (e: Exception) {
            // rien à faire
        }
        player = null
    }

    // ------------------------------------------------------ carillon synthétisé

    /**
     * Fabrique un « ding-dong » à deux notes et le garde en cache.
     *
     * Deux notes descendantes (mi puis do) avec décroissance exponentielle et quelques
     * harmoniques : c'est ce qui donne le timbre métallique d'un carillon plutôt qu'un
     * bip électronique.
     */
    private fun builtInChime(): File? {
        val cached = File(context.cacheDir, BUILT_IN_NAME)
        if (cached.isFile && cached.length() > 0) return cached

        return try {
            val samples = ArrayList<Short>(SAMPLE_RATE * 3)
            appendNote(samples, 659.25, 1.1)   // mi
            appendNote(samples, 523.25, 1.9)   // do

            FileOutputStream(cached).use { out ->
                out.write(wavHeader(samples.size))
                val bytes = ByteArray(samples.size * 2)
                samples.forEachIndexed { i, v ->
                    bytes[i * 2] = (v.toInt() and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
                }
                out.write(bytes)
            }
            cached
        } catch (e: Exception) {
            Log.w(TAG, "synthèse du carillon impossible : ${e.message}")
            null
        }
    }

    private fun appendNote(out: MutableList<Short>, freq: Double, seconds: Double) {
        val count = (SAMPLE_RATE * seconds).toInt()
        for (i in 0 until count) {
            val t = i.toDouble() / SAMPLE_RATE
            val envelope = exp(-2.2 * t)
            val wave = sin(2 * Math.PI * freq * t) +
                0.35 * sin(2 * Math.PI * freq * 2 * t) +
                0.18 * sin(2 * Math.PI * freq * 3.01 * t)
            out.add((16000 * envelope * wave / 1.5).toInt().coerceIn(-32767, 32767).toShort())
        }
    }

    /** En-tête WAV PCM 16 bits mono. */
    private fun wavHeader(sampleCount: Int): ByteArray {
        val dataSize = sampleCount * 2
        val header = ByteArray(44)
        fun putAscii(offset: Int, text: String) =
            text.forEachIndexed { i, c -> header[offset + i] = c.code.toByte() }
        fun putInt(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            header[offset + 2] = ((value shr 16) and 0xFF).toByte()
            header[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }

        putAscii(0, "RIFF"); putInt(4, 36 + dataSize); putAscii(8, "WAVE")
        putAscii(12, "fmt "); putInt(16, 16); putShort(20, 1); putShort(22, 1)
        putInt(24, SAMPLE_RATE); putInt(28, SAMPLE_RATE * 2); putShort(32, 2); putShort(34, 16)
        putAscii(36, "data"); putInt(40, dataSize)
        return header
    }

    private companion object {
        const val TAG = "ChimePlayer"
        const val SAMPLE_RATE = 44100
        const val BUILT_IN_NAME = "carillon-integre.wav"
        val EXTENSIONS = setOf("wav", "mp3", "ogg", "m4a", "aac", "flac")
    }
}
