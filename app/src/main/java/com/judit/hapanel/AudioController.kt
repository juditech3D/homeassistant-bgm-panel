package com.judit.hapanel

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.AudioManager.STREAM_MUSIC
import android.media.MediaMetadata
import android.media.ToneGenerator
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.util.Log
import kotlin.concurrent.thread

/**
 * Pilote l'audio du panneau : volume de l'amplificateur intégré, et commande des
 * applications de lecture présentes sur l'appareil (Spotify, Pandora…).
 *
 * L'accès aux sessions média passe par [MediaAccessService], qu'Android n'autorise
 * qu'aux écouteurs de notifications. Sans cette autorisation, le volume reste
 * commandable mais le titre en cours et les commandes de transport sont indisponibles.
 */
class AudioController(private val context: Context) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sessions =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent = ComponentName(context, MediaAccessService::class.java)

    // ------------------------------------------------------------------ volume

    /** Volume musique, ramené sur 0..1. */
    fun volume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0f
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    fun setVolume(normalised: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = Math.round(normalised.coerceIn(0f, 1f) * max)
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (e: SecurityException) {
            // Peut être refusé si le mode « Ne pas déranger » est actif.
            Log.w(TAG, "volume refusé : ${e.message}")
        }
    }

    fun isMusicActive(): Boolean = audio.isMusicActive

    // ----------------------------------------------------------- sessions média

    /** Vrai si l'autorisation d'accès aux notifications a été accordée. */
    fun hasMediaAccess(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return enabled.contains(context.packageName)
    }

    /** La session active la plus pertinente : celle qui joue, sinon la première. */
    private fun activeController(): MediaController? = try {
        val all = sessions.getActiveSessions(listenerComponent)
        all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: all.firstOrNull()
    } catch (e: SecurityException) {
        null
    } catch (e: Exception) {
        Log.w(TAG, "sessions média indisponibles : ${e.message}")
        null
    }

    fun isPlaying(): Boolean =
        activeController()?.playbackState?.state == PlaybackState.STATE_PLAYING

    /** « Titre — Artiste », ou une chaîne vide si rien n'est connu. */
    fun nowPlaying(): String {
        val md = activeController()?.metadata ?: return ""
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        return when {
            title.isEmpty() -> ""
            artist.isEmpty() -> title
            else -> "$title — $artist"
        }
    }

    /** Le paquet de l'application qui tient la session, pour information. */
    fun playerPackage(): String = activeController()?.packageName.orEmpty()

    fun playPause() {
        val c = activeController() ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
    }

    fun next() = activeController()?.transportControls?.skipToNext()

    fun previous() = activeController()?.transportControls?.skipToPrevious()

    /**
     * Bip court sur la sortie musique, au volume courant.
     *
     * C'est le seul moyen d'apprécier la puissance réelle en réglant le volume : un
     * pourcentage à l'écran ne dit rien de ce qu'on va entendre. Le générateur est
     * conservé d'un bip à l'autre, sa création coûtant plusieurs dizaines de
     * millisecondes — trop pour être refaite à chaque cran.
     */
    fun beep() {
        try {
            val tone = toneGenerator ?: ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
                .also { toneGenerator = it }
            tone.stopTone()
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_DURATION_MS)
        } catch (e: Exception) {
            // Le générateur peut être indisponible si la sortie audio est occupée.
            Log.w(TAG, "bip impossible : ${e.message}")
        }
    }

    /**
     * Joue une mélodie de confirmation de quelques secondes.
     *
     * Un bip court ne prouve pas grand-chose sur une enceinte Bluetooth : le temps qu'il
     * sorte, la liaison peut s'être établie ou non, et l'oreille n'a rien à juger. Une
     * suite de notes tenues laisse le temps d'entendre d'où vient le son, de vérifier le
     * volume, et de constater qu'il ne hache pas.
     *
     * Synthétisée plutôt que jouée depuis un fichier : aucune ressource à embarquer, et
     * le rendu est le même sur toutes les sorties.
     */
    fun playTestTone(onFinished: (() -> Unit)? = null) {
        thread(isDaemon = true, name = "test-audio") {
            try {
                val frequence = 44100
                // Un arpège majeur ascendant puis la tonique tenue : franc à l'oreille,
                // et l'on entend tout de suite si la liaison hache.
                val notes = listOf(
                    523.25 to 400, 659.25 to 400, 783.99 to 400,
                    1046.50 to 700, 783.99 to 300, 1046.50 to 1200
                )
                val total = notes.sumOf { it.second } * frequence / 1000
                val echantillons = ShortArray(total)

                var position = 0
                notes.forEach { (hauteur, duree) ->
                    val n = duree * frequence / 1000
                    for (i in 0 until n) {
                        // Enveloppe en cloche : sans elle, chaque note claque à son début
                        // et à sa fin, ce qui s'entend comme un défaut de transmission.
                        val avance = i.toDouble() / n
                        val enveloppe = kotlin.math.sin(Math.PI * avance)
                        val valeur = kotlin.math.sin(2 * Math.PI * hauteur * i / frequence)
                        echantillons[position + i] =
                            (valeur * enveloppe * Short.MAX_VALUE * 0.55).toInt().toShort()
                    }
                    position += n
                }

                val piste = android.media.AudioTrack(
                    AudioManager.STREAM_MUSIC, frequence,
                    android.media.AudioFormat.CHANNEL_OUT_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    echantillons.size * 2,
                    android.media.AudioTrack.MODE_STATIC
                )
                piste.write(echantillons, 0, echantillons.size)
                piste.play()
                Thread.sleep(notes.sumOf { it.second }.toLong() + 250)
                piste.release()
            } catch (e: Exception) {
                Log.w(TAG, "test audio impossible : ${e.message}")
            }
            onFinished?.invoke()
        }
    }

    private var toneGenerator: ToneGenerator? = null

    fun release() {
        try {
            toneGenerator?.release()
        } catch (e: Exception) {
            // rien à faire
        }
        toneGenerator = null
    }

    /** Lance une application de lecture par son nom de paquet. */
    fun launchPlayer(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName.trim())
            ?: return false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "lancement de $packageName impossible : ${e.message}")
            false
        }
    }

    private companion object {
        const val TAG = "AudioController"

        /** Volume du générateur lui-même ; la puissance perçue vient du volume système. */
        const val TONE_VOLUME = 90

        /**
         * Tonalité volontairement longue : un bip trop court ne permet pas de juger
         * la puissance sonore, qui est précisément ce qu'on cherche à régler. En
         * tournant vite, chaque cran relance la tonalité, ce qui donne un son continu.
         */
        const val TONE_DURATION_MS = 300
    }
}
