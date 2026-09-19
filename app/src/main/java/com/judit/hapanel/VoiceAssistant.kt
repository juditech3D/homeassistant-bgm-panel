package com.judit.hapanel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread

/**
 * Assistant vocal adossé au pipeline Assist de Home Assistant.
 *
 * Le panneau ne fait que capturer le micro et jouer la réponse : toute l'intelligence —
 * transcription, interprétation, synthèse vocale — reste sur le serveur. C'est délibéré :
 * ce matériel n'a ni la puissance ni la mémoire pour faire tourner un moteur local, et
 * cela évite d'envoyer quoi que ce soit à un service tiers.
 *
 * **Déclenchement à la demande, pas de mot d'éveil.** Le micro ne s'ouvre que sur une
 * action explicite et se referme seul. Rien n'est capté en dehors de ces quelques
 * secondes : c'est le choix le plus simple, le plus fiable sur ce matériel, et de loin le
 * plus respectueux de la vie privée.
 *
 * Le [mode privé][Prefs.microphoneEnabled] coupe la fonction entièrement.
 */
class VoiceAssistant(
    private val context: Context,
    private val client: HaClient,
    private val prefs: Prefs
) {

    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    @Volatile var state: State = State.IDLE
        private set

    /** Dernière transcription et dernière réponse, pour affichage. */
    @Volatile var lastHeard: String = ""
        private set
    @Volatile var lastReply: String = ""
        private set

    var onStateChanged: ((State) -> Unit)? = null

    private val ui = Handler(Looper.getMainLooper())
    private var recorder: AudioRecord? = null
    private var recordThread: Thread? = null
    private var player: MediaPlayer? = null

    @Volatile private var handlerId = -1
    @Volatile private var capturing = false

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    val isAvailable: Boolean
        get() = prefs.microphoneEnabled && hasPermission()

    /** Démarre ou arrête l'écoute, selon l'état courant. */
    fun toggle() {
        if (state == State.LISTENING) stopListening() else startListening()
    }

    fun startListening() {
        if (!prefs.microphoneEnabled) {
            Log.i(TAG, "mode privé actif : micro refusé")
            return
        }
        if (!hasPermission()) {
            Log.w(TAG, "permission micro absente")
            return
        }
        if (state != State.IDLE) return

        client.pipelineListener = { type, data -> onPipelineEvent(type, data) }
        if (!client.startAssistPipeline(SAMPLE_RATE)) {
            Log.w(TAG, "serveur injoignable : conversation impossible")
            return
        }
        setState(State.LISTENING)
    }

    fun stopListening() {
        capturing = false
        if (state == State.LISTENING) setState(State.THINKING)
    }

    fun cancel() {
        capturing = false
        releaseRecorder()
        stopSpeaking()
        setState(State.IDLE)
    }

    // ------------------------------------------------ événements du pipeline

    private fun onPipelineEvent(type: String, data: org.json.JSONObject) {
        when (type) {
            "run-start" -> {
                handlerId = data.optJSONObject("runner_data")
                    ?.optInt("stt_binary_handler_id", -1) ?: -1
                if (handlerId >= 0) beginCapture() else {
                    Log.w(TAG, "aucun canal audio fourni par le serveur")
                    setState(State.IDLE)
                }
            }

            "stt-end" -> {
                lastHeard = data.optJSONObject("stt_output")?.optString("text").orEmpty()
                Log.i(TAG, "entendu : $lastHeard")
                setState(State.THINKING)
            }

            "intent-end" -> {
                lastReply = data.optJSONObject("intent_output")
                    ?.optJSONObject("response")
                    ?.optJSONObject("speech")
                    ?.optJSONObject("plain")
                    ?.optString("speech").orEmpty()
                Log.i(TAG, "réponse : $lastReply")
            }

            "tts-end" -> {
                val url = data.optJSONObject("tts_output")?.optString("url").orEmpty()
                if (url.isNotEmpty()) speak(url) else setState(State.IDLE)
            }

            "run-end" -> if (state != State.SPEAKING) setState(State.IDLE)

            "error" -> {
                Log.w(TAG, "pipeline : ${data.optString("message")}")
                cancel()
            }
        }
    }

    // ---------------------------------------------------------- capture micro

    private fun beginCapture() {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "micro indisponible")
            setState(State.IDLE)
            return
        }

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 4
            )
        } catch (e: Exception) {
            Log.w(TAG, "ouverture du micro impossible : ${e.message}")
            setState(State.IDLE)
            return
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "micro non initialisé")
            rec.release()
            setState(State.IDLE)
            return
        }

        recorder = rec
        capturing = true
        rec.startRecording()

        recordThread = thread(name = "assist-capture", isDaemon = true) {
            val buffer = ByteArray(CHUNK_BYTES)
            val startedAt = System.currentTimeMillis()
            try {
                while (capturing) {
                    val read = rec.read(buffer, 0, buffer.size)
                    if (read > 0) client.sendAudioChunk(handlerId, buffer, read)

                    // Garde-fou : on ne laisse jamais le micro ouvert indéfiniment,
                    // même si le serveur ne détecte pas la fin de la parole.
                    if (System.currentTimeMillis() - startedAt > MAX_LISTEN_MS) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "capture interrompue : ${e.message}")
            } finally {
                capturing = false
                client.endAudioStream(handlerId)
                releaseRecorder()
                ui.post { if (state == State.LISTENING) setState(State.THINKING) }
            }
        }
    }

    private fun releaseRecorder() {
        try {
            recorder?.stop()
            recorder?.release()
        } catch (e: Exception) {
            // rien à faire
        }
        recorder = null
    }

    // ----------------------------------------------------------- réponse parlée

    private fun speak(relativeUrl: String) {
        setState(State.SPEAKING)
        stopSpeaking()
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                        .build()
                )
                setDataSource(prefs.httpBase() + relativeUrl)
                setOnCompletionListener { setState(State.IDLE) }
                setOnErrorListener { _, _, _ -> setState(State.IDLE); true }
                prepareAsync()
                setOnPreparedListener { it.start() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "lecture de la réponse impossible : ${e.message}")
            setState(State.IDLE)
        }
    }

    private fun stopSpeaking() {
        try {
            player?.reset()
            player?.release()
        } catch (e: Exception) {
            // rien à faire
        }
        player = null
    }

    fun release() {
        cancel()
        client.pipelineListener = null
    }

    private fun setState(s: State) {
        if (state == s) return
        state = s
        ui.post { onStateChanged?.invoke(s) }
    }

    private companion object {
        const val TAG = "VoiceAssistant"

        /** Le pipeline Assist attend du 16 kHz mono 16 bits. */
        const val SAMPLE_RATE = 16_000
        const val CHUNK_BYTES = 1024

        /** Au-delà, on coupe : mieux vaut rater une phrase que laisser le micro ouvert. */
        const val MAX_LISTEN_MS = 15_000L
    }
}
