package com.judit.hapanel.dlna

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log

/**
 * Lecture du flux envoyé par le contrôleur DLNA.
 *
 * S'appuie sur `MediaPlayer` plutôt que sur une bibliothèque tierce : le panneau est
 * modeste (PX30, 2 Go) et les formats que Music Assistant produit par défaut — MP3,
 * AAC, FLAC — sont tous gérés nativement par Android 8.1.
 *
 * Toutes les méthodes sont sûres à appeler dans n'importe quel ordre : un contrôleur
 * DLNA peut très bien demander « Pause » alors que rien ne joue.
 */
class RendererPlayer {

    /** États de transport au sens UPnP, tels que le contrôleur les attend. */
    enum class TransportState { STOPPED, PLAYING, PAUSED_PLAYBACK, TRANSITIONING, NO_MEDIA_PRESENT }

    private var player: MediaPlayer? = null

    @Volatile var state: TransportState = TransportState.NO_MEDIA_PRESENT
        private set

    /** URI du flux courant, renvoyé au contrôleur dans GetMediaInfo. */
    @Volatile var currentUri: String = ""
        private set

    /** Métadonnées DIDL-Lite fournies par le contrôleur, restituées telles quelles. */
    @Volatile var currentMetadata: String = ""
        private set

    /** Notifié à chaque changement d'état, pour republier vers Home Assistant. */
    var onStateChanged: ((TransportState) -> Unit)? = null

    @Synchronized
    fun setUri(uri: String, metadata: String) {
        currentUri = uri
        currentMetadata = metadata
        release()

        if (uri.isEmpty()) {
            update(TransportState.NO_MEDIA_PRESENT)
            return
        }

        update(TransportState.TRANSITIONING)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(uri)
                setOnPreparedListener {
                    // Un contrôleur DLNA enchaîne presque toujours SetAVTransportURI
                    // puis Play ; on démarre donc dès que le flux est prêt.
                    it.start()
                    update(TransportState.PLAYING)
                }
                setOnCompletionListener { update(TransportState.STOPPED) }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "erreur de lecture ($what/$extra) sur $uri")
                    update(TransportState.STOPPED)
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.w(TAG, "flux illisible : ${e.message}")
            update(TransportState.STOPPED)
        }
    }

    @Synchronized
    fun play() {
        val p = player
        if (p == null) {
            // Play sans URI : on retente avec le dernier flux connu.
            if (currentUri.isNotEmpty()) setUri(currentUri, currentMetadata)
            return
        }
        try {
            p.start()
            update(TransportState.PLAYING)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "play impossible : ${e.message}")
        }
    }

    @Synchronized
    fun pause() {
        try {
            player?.takeIf { it.isPlaying }?.pause()
            update(TransportState.PAUSED_PLAYBACK)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "pause impossible : ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        release()
        update(TransportState.STOPPED)
    }

    @Synchronized
    fun seekTo(seconds: Int) {
        try {
            player?.seekTo(seconds * 1000)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "seek impossible : ${e.message}")
        }
    }

    /** Position courante en secondes. */
    fun positionSeconds(): Int = try {
        (player?.currentPosition ?: 0) / 1000
    } catch (e: IllegalStateException) {
        0
    }

    /** Durée totale en secondes, 0 si inconnue — cas courant des flux continus. */
    fun durationSeconds(): Int = try {
        val d = player?.duration ?: 0
        if (d > 0) d / 1000 else 0
    } catch (e: IllegalStateException) {
        0
    }

    private fun release() {
        try {
            player?.reset()
            player?.release()
        } catch (e: Exception) {
            // Rien à faire : on jette l'instance de toute façon.
        }
        player = null
    }

    private fun update(s: TransportState) {
        if (state == s) return
        state = s
        onStateChanged?.invoke(s)
    }

    companion object {
        private const val TAG = "RendererPlayer"

        /** Formate des secondes au format horaire attendu par UPnP. */
        fun upnpTime(seconds: Int): String {
            val s = seconds.coerceAtLeast(0)
            return String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        }

        /** Analyse un temps UPnP « H:MM:SS » en secondes. */
        fun parseUpnpTime(value: String): Int {
            val parts = value.trim().split(':')
            return try {
                when (parts.size) {
                    3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 +
                        parts[2].substringBefore('.').toInt()
                    2 -> parts[0].toInt() * 60 + parts[1].substringBefore('.').toInt()
                    else -> 0
                }
            } catch (e: NumberFormatException) {
                0
            }
        }
    }
}
