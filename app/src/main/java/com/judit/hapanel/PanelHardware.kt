package com.judit.hapanel

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Expose le matériel propre au panneau à Home Assistant : avertisseur, sourdine,
 * écran du bouton, anneau lumineux, relais, et l'entrée sonnette câblée sur la borne DB.
 *
 * ## Commande depuis Home Assistant
 *
 * L'application surveille des entités `input_boolean` et recopie leur état sur le
 * matériel. Ce choix évite d'ouvrir un port sur le panneau et de dépendre d'un courtier
 * MQTT : on réutilise la connexion WebSocket déjà établie.
 *
 * Il suffit de créer ces auxiliaires dans Home Assistant (Paramètres → Appareils et
 * services → Auxiliaires) ; seuls ceux qui existent sont pris en compte.
 *
 * ## Retour vers Home Assistant
 *
 * L'état réel du matériel est republié en capteurs, et la sonnette est signalée par une
 * impulsion de quelques secondes sur laquelle une automatisation peut se déclencher.
 */
class PanelHardware(
    private val client: HaClient,
    private val audio: AudioController? = null,
    private val screen: ScreenManager? = null,
    private val chime: ChimePlayer? = null,
    private val prefs: Prefs? = null,
    private val assistant: VoiceAssistant? = null,
    /** Appelé quand on sonne, pour afficher la caméra. */
    private val onDoorbell: (() -> Unit)? = null
) {

    /**
     * Dernier état vu pour les entités de déclenchement, afin de ne réagir qu'aux
     * changements. Sans cela, la republication de l'état à la connexion relancerait
     * un carillon à chaque reconnexion au serveur.
     */
    private val lastTriggerState = HashMap<String, String>()

    private val thread = HandlerThread("panel-hardware").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var running = false
    @Volatile private var doorbellReader: Thread? = null

    /** Dernier état publié, pour n'émettre que sur changement. */
    private val published = HashMap<String, String>()

    fun start() {
        if (running) return
        running = true

        // Chaque fonction est facultative : sur un panneau d'un autre modèle, ce
        // matériel peut être absent, et l'utilisateur peut simplement ne pas en vouloir.
        if (prefs?.knobScreenEnabled != false) VendorHw.setKnobScreenPower(true)
        if (prefs?.doorbellEnabled != false) startDoorbell()

        handler.post(publishTask)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        doorbellReader?.interrupt()
        doorbellReader = null
    }

    fun release() {
        stop()
        thread.quitSafely()
    }

    // ------------------------------------------------------ commandes venant de HA

    /**
     * Applique l'état d'une entité de commande au matériel.
     * Appelé pour chaque changement d'état reçu de Home Assistant.
     */
    fun onEntityChanged(entity: Entity) {
        val on = entity.state == "on"

        // Les commandes du matériel constructeur sont ignorées quand il est désactivé :
        // écrire dans un /proc/vendor absent ne ferait qu'encombrer le journal.
        if (prefs?.vendorHardwareEnabled == false && entity.entityId in VENDOR_COMMANDS) return

        when (entity.entityId) {
            CMD_BUZZER -> VendorHw.setHorn(on)
            CMD_MUTE -> VendorHw.setMute(on)
            CMD_KNOB_SCREEN -> VendorHw.setKnobScreenPower(on)
            CMD_RING -> VendorHw.setRingWhite(on)
            CMD_RELAY_1 -> VendorHw.setRelay(1, on)
            CMD_RELAY_2 -> VendorHw.setRelay(2, on)
            CMD_RELAY_3 -> VendorHw.setRelay(3, on)
            CMD_RELAY_4 -> VendorHw.setRelay(4, on)

            CMD_VOLUME -> {
                val percent = entity.state.toFloatOrNull() ?: return
                audio?.setVolume(percent / 100f)
            }
            CMD_PLAY -> audio?.let { if (it.isPlaying() != on) it.playPause() }
            CMD_CHIME, CMD_CHIME_ALT -> {
                // `input_button` porte un horodatage qui change à chaque appui, et
                // `input_boolean` bascule : dans les deux cas c'est le **changement**
                // qui déclenche, jamais l'état lui-même.
                val previous = lastTriggerState.put(entity.entityId, entity.state)
                val meaningful = entity.state != "unknown" && entity.state != "unavailable"
                if (previous != null && previous != entity.state && meaningful) {
                    if (entity.domain != "input_boolean" || on) {
                        chime?.play()
                        // Un déclenchement depuis Home Assistant affiche la caméra
                        // comme le ferait la sonnette physique.
                        onDoorbell?.invoke()
                    }
                }
                return
            }
            CMD_MICROPHONE -> {
                prefs?.microphoneEnabled = on
                // Couper depuis Home Assistant doit interrompre une écoute en cours.
                if (!on) assistant?.cancel()
            }
            CMD_MAIN_SCREEN -> screen?.let { if (on) it.wake() else it.sleep() }
            CMD_SCREEN_BRIGHTNESS -> {
                val percent = entity.state.toFloatOrNull()?.toInt() ?: return
                screen?.applyBrightness(percent)
            }
            CMD_LAUNCH -> {
                val pkg = entity.state.trim()
                // On ignore les états vides et les marqueurs d'indisponibilité, sans
                // quoi un redémarrage de Home Assistant relancerait une application.
                if (pkg.isNotEmpty() && pkg != "unknown" && pkg != "unavailable") {
                    audio?.launchPlayer(pkg)
                }
            }
            else -> return
        }
        Log.i(TAG, "commande ${entity.entityId} -> ${entity.state}")
        handler.post { publishState() }
    }

    /** Les entités de commande, pour que l'appelant sache lesquelles lui importent. */
    fun commandEntities(): Set<String> = COMMANDS

    /** Force une republication immédiate, sans attendre le prochain cycle. */
    fun publishNow() {
        handler.post { publishState() }
    }

    // ------------------------------------------------------------- sonnette (DB)

    /**
     * Lit l'entrée sonnette en direct sur /dev/input/event1.
     *
     * Ce périphérique émet `KEY_DOLLAR`, que le keylayout du système ne mappe pas :
     * Android ne le transmet donc jamais à l'application sous forme de touche. On le lit
     * au niveau du pilote, ce qui impose d'élargir les droits du nœud — d'où l'appel à
     * `su`. Si le root n'est pas disponible, on abandonne proprement : tout le reste
     * continue de fonctionner.
     */
    private fun startDoorbell() {
        if (!File(DOORBELL_DEV).exists()) {
            Log.w(TAG, "sonnette : $DOORBELL_DEV absent")
            return
        }
        if (!File(DOORBELL_DEV).canRead() && !grantDoorbellAccess()) {
            Log.w(TAG, "sonnette : accès refusé, fonction désactivée")
            return
        }

        doorbellReader = thread(name = "doorbell", isDaemon = true) {
            try {
                DataInputStream(FileInputStream(DOORBELL_DEV)).use { input ->
                    val record = ByteArray(EVENT_SIZE)
                    while (running && !Thread.currentThread().isInterrupted) {
                        input.readFully(record)
                        val buf = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
                        // struct input_event : timeval (2 x 64 bits), type, code, value
                        buf.position(16)
                        val type = buf.short.toInt() and 0xFFFF
                        val code = buf.short.toInt() and 0xFFFF
                        val value = buf.int
                        if (type == EV_KEY && code == KEY_DOLLAR && value == 1) {
                            onDoorbellRing()
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "sonnette : lecture interrompue — ${e.message}")
            }
        }
    }

    private fun grantDoorbellAccess(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "0", "sh", "-c", "chmod 666 $DOORBELL_DEV"))
        p.waitFor()
        File(DOORBELL_DEV).canRead()
    } catch (e: Exception) {
        Log.w(TAG, "sonnette : su indisponible — ${e.message}")
        false
    }

    private fun onDoorbellRing() {
        Log.i(TAG, "sonnette : appui détecté")
        if (prefs?.chimeOnDoorbell != false) chime?.play()
        onDoorbell?.invoke()
        handler.post {
            publish(SENSOR_DOORBELL, "on", "Panneau — sonnette", null)
            // Impulsion : on retombe à l'arrêt pour qu'un second coup de sonnette
            // produise bien une nouvelle transition, sur laquelle déclencher.
            handler.postDelayed({
                publish(SENSOR_DOORBELL, "off", "Panneau — sonnette", null)
            }, DOORBELL_PULSE_MS)
        }
    }

    // ------------------------------------------------- publication de l'état réel

    private val publishTask = object : Runnable {
        override fun run() {
            if (!running) return
            publishState()
            handler.postDelayed(this, PUBLISH_PERIOD_MS)
        }
    }

    private fun publishState() {
        // Sans le matériel constructeur, ces capteurs n'auraient aucun sens : mieux vaut
        // ne rien publier que d'inonder Home Assistant d'entités toujours à « off ».
        if (prefs?.vendorHardwareEnabled != false) {
            publish(SENSOR_BUZZER, boolState(VendorHw.isHornOn()), "Panneau — avertisseur", null)
            publish(SENSOR_MUTE, boolState(VendorHw.isMuted()), "Panneau — sourdine", null)
            publish(
                SENSOR_RING, if (VendorHw.isRingWhite()) "blanc" else "rouge",
                "Panneau — anneau", null
            )
            publish(
                SENSOR_KNOB_SCREEN, boolState(VendorHw.isKnobScreenPowered()),
                "Panneau — écran du bouton", null
            )
            publish(
                SENSOR_RS485, VendorHw.rs485Mode().ifEmpty { "inconnu" },
                "Panneau — bus RS485", null
            )
            for (i in 1..4) {
                publish(
                    "panneau_relais_$i", boolState(VendorHw.isRelayOn(i)),
                    "Panneau — relais $i", null
                )
            }
        }

        prefs?.let {
            publish(
                SENSOR_MICROPHONE, boolState(it.microphoneEnabled),
                "Panneau — micro", null
            )
        }

        screen?.let { s ->
            publish(
                SENSOR_MAIN_SCREEN, if (s.isAsleep) "off" else "on",
                "Panneau — écran principal", null
            )
            publish(
                SENSOR_SCREEN_BRIGHTNESS, s.brightnessPercent().toString(),
                "Panneau — luminosité écran", "%"
            )
        }

        audio?.let { a ->
            publish(
                SENSOR_VOLUME, (a.volume() * 100).toInt().toString(),
                "Panneau — volume", "%"
            )
            publish(
                SENSOR_PLAYING, if (a.isPlaying()) "on" else "off",
                "Panneau — lecture en cours", null
            )
            val track = a.nowPlaying()
            publish(
                SENSOR_TRACK, track.ifEmpty { "rien" }.take(250),
                "Panneau — titre", null
            )
            publish(
                SENSOR_PLAYER, a.playerPackage().ifEmpty { "aucun" },
                "Panneau — lecteur", null
            )
        }
    }

    private fun boolState(on: Boolean) = if (on) "on" else "off"

    /** N'émet que si la valeur a changé, pour ne pas inonder le serveur. */
    private fun publish(objectId: String, state: String, name: String, unit: String?) {
        if (published[objectId] == state) return
        published[objectId] = state
        client.publishSensor(objectId, state, unit, name, null)
    }

    companion object {
        private const val TAG = "PanelHardware"

        private const val DOORBELL_DEV = "/dev/input/event1"
        private const val EVENT_SIZE = 24
        private const val EV_KEY = 1
        private const val KEY_DOLLAR = 439
        private const val DOORBELL_PULSE_MS = 3_000L
        private const val PUBLISH_PERIOD_MS = 30_000L

        // Entités à créer dans Home Assistant pour commander le panneau.
        const val CMD_BUZZER = "input_boolean.panneau_avertisseur"
        const val CMD_MUTE = "input_boolean.panneau_sourdine"
        const val CMD_KNOB_SCREEN = "input_boolean.panneau_ecran_bouton"
        const val CMD_RING = "input_boolean.panneau_anneau"
        const val CMD_RELAY_1 = "input_boolean.panneau_relais_1"
        const val CMD_RELAY_2 = "input_boolean.panneau_relais_2"
        const val CMD_RELAY_3 = "input_boolean.panneau_relais_3"
        const val CMD_RELAY_4 = "input_boolean.panneau_relais_4"

        const val CMD_VOLUME = "input_number.panneau_volume"
        const val CMD_PLAY = "input_boolean.panneau_lecture"
        const val CMD_LAUNCH = "input_text.panneau_lancer_app"
        const val CMD_MAIN_SCREEN = "input_boolean.panneau_ecran_principal"
        const val CMD_SCREEN_BRIGHTNESS = "input_number.panneau_luminosite_ecran"

        /**
         * Déclenchement du carillon depuis Home Assistant. Les deux formes sont
         * acceptées : `input_button` est la plus juste sémantiquement pour une action,
         * `input_boolean` reste possible pour qui préfère un interrupteur.
         */
        const val CMD_CHIME = "input_button.panneau_carillon"
        const val CMD_CHIME_ALT = "input_boolean.panneau_carillon"

        /** Mode privé : à `off`, le micro n'est jamais ouvert. */
        const val CMD_MICROPHONE = "input_boolean.panneau_micro"

        private val COMMANDS = setOf(
            CMD_BUZZER, CMD_MUTE, CMD_KNOB_SCREEN, CMD_RING,
            CMD_RELAY_1, CMD_RELAY_2, CMD_RELAY_3, CMD_RELAY_4,
            CMD_VOLUME, CMD_PLAY, CMD_LAUNCH,
            CMD_MAIN_SCREEN, CMD_SCREEN_BRIGHTNESS,
            CMD_CHIME, CMD_CHIME_ALT, CMD_MICROPHONE
        )

        /** Celles qui exigent l'API constructeur /proc/vendor. */
        private val VENDOR_COMMANDS = setOf(
            CMD_BUZZER, CMD_MUTE, CMD_KNOB_SCREEN, CMD_RING,
            CMD_RELAY_1, CMD_RELAY_2, CMD_RELAY_3, CMD_RELAY_4
        )

        private const val SENSOR_DOORBELL = "panneau_sonnette"
        private const val SENSOR_BUZZER = "panneau_avertisseur"
        private const val SENSOR_MUTE = "panneau_sourdine"
        private const val SENSOR_RING = "panneau_anneau"
        private const val SENSOR_KNOB_SCREEN = "panneau_ecran_bouton"
        private const val SENSOR_RS485 = "panneau_rs485"
        private const val SENSOR_VOLUME = "panneau_volume"
        private const val SENSOR_PLAYING = "panneau_lecture"
        private const val SENSOR_TRACK = "panneau_titre"
        private const val SENSOR_PLAYER = "panneau_lecteur"
        private const val SENSOR_MAIN_SCREEN = "panneau_ecran_principal"
        private const val SENSOR_SCREEN_BRIGHTNESS = "panneau_luminosite_ecran"
        private const val SENSOR_MICROPHONE = "panneau_micro"
    }
}
