package com.judit.hapanel

import android.content.Context

/** Réglages persistants : adresse du serveur, jeton, et entités épinglées au tableau de bord. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("hapanel", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString(KEY_HOST, "") ?: ""
        set(v) = sp.edit().putString(KEY_HOST, v.trim()).apply()

    var port: Int
        get() = sp.getInt(KEY_PORT, 8123)
        set(v) = sp.edit().putInt(KEY_PORT, v).apply()

    var useTls: Boolean
        get() = sp.getBoolean(KEY_TLS, false)
        set(v) = sp.edit().putBoolean(KEY_TLS, v).apply()

    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(KEY_TOKEN, v.trim()).apply()

    /** Liste d'entity_id séparés par des virgules. Vide = découverte automatique. */
    var pinned: String
        get() = sp.getString(KEY_PINNED, "") ?: ""
        set(v) = sp.edit().putString(KEY_PINNED, v.trim()).apply()

    var publishSensors: Boolean
        get() = sp.getBoolean(KEY_PUBLISH, true)
        set(v) = sp.edit().putBoolean(KEY_PUBLISH, v).apply()

    /**
     * Délai avant l'écran de veille, en secondes. 0 = pas d'écran de veille.
     * Première étape : l'écran reste allumé mais affiche photos ou animation.
     */
    var screensaverSeconds: Int
        get() = sp.getInt(KEY_SAVER_DELAY, 60)
        set(v) = sp.edit().putInt(KEY_SAVER_DELAY, v.coerceAtLeast(0)).apply()

    /** `photos` ou `anime`. */
    var screensaverMode: String
        get() = sp.getString(KEY_SAVER_MODE, "anime") ?: "anime"
        set(v) = sp.edit().putString(KEY_SAVER_MODE, v).apply()

    // ------------------------------------------------------------ fonctionnalités
    //
    // Chaque fonction optionnelle s'active séparément. Tous les panneaux n'ont pas le
    // même matériel, ni les mêmes usages : sans ces interrupteurs, l'application
    // tenterait d'ouvrir un micro absent, d'écrire dans un /proc/vendor inexistant ou
    // d'annoncer un lecteur réseau dont personne ne veut.

    /** Récepteur DLNA : rend le panneau visible comme lecteur dans Home Assistant. */
    var dlnaEnabled: Boolean
        get() = sp.getBoolean(KEY_DLNA, true)
        set(v) = sp.edit().putBoolean(KEY_DLNA, v).apply()

    /** Sonnette de la borne DB et carillon associé. */
    var doorbellEnabled: Boolean
        get() = sp.getBoolean(KEY_DOORBELL, true)
        set(v) = sp.edit().putBoolean(KEY_DOORBELL, v).apply()

    /** Écran rond du bouton rotatif : horloge et retour visuel des réglages. */
    var knobScreenEnabled: Boolean
        get() = sp.getBoolean(KEY_KNOB_SCREEN, true)
        set(v) = sp.edit().putBoolean(KEY_KNOB_SCREEN, v).apply()

    /**
     * Matériel constructeur exposé en /proc/vendor : anneau lumineux, avertisseur,
     * sourdine, relais, RS485. Absent sur d'autres modèles de panneau.
     */
    var vendorHardwareEnabled: Boolean
        get() = sp.getBoolean(KEY_VENDOR_HW, true)
        set(v) = sp.edit().putBoolean(KEY_VENDOR_HW, v).apply()

    /** Carte de volume de l'amplificateur intégré sur le tableau de bord. */
    var panelVolumeEnabled: Boolean
        get() = sp.getBoolean(KEY_PANEL_VOLUME, true)
        set(v) = sp.edit().putBoolean(KEY_PANEL_VOLUME, v).apply()

    /**
     * Micro autorisé. À faux, c'est le **mode privé** : l'assistant vocal est
     * entièrement inopérant, le micro n'est jamais ouvert.
     */
    var microphoneEnabled: Boolean
        get() = sp.getBoolean(KEY_MIC, true)
        set(v) = sp.edit().putBoolean(KEY_MIC, v).apply()

    /** Afficher la carte de l'assistant vocal sur le tableau de bord. */
    var assistantEnabled: Boolean
        get() = sp.getBoolean(KEY_ASSISTANT, true)
        set(v) = sp.edit().putBoolean(KEY_ASSISTANT, v).apply()

    /** Dossier où déposer les carillons de la sonnette. */
    var chimeFolder: String
        get() = sp.getString(KEY_CHIME_FOLDER, DEFAULT_CHIME_FOLDER) ?: DEFAULT_CHIME_FOLDER
        set(v) = sp.edit().putString(KEY_CHIME_FOLDER, v.trim()).apply()

    /** Chemin complet du carillon retenu. Vide = carillon synthétisé par l'application. */
    var chimeFile: String
        get() = sp.getString(KEY_CHIME_FILE, "") ?: ""
        set(v) = sp.edit().putString(KEY_CHIME_FILE, v.trim()).apply()

    /** Jouer le carillon quand la sonnette physique (borne DB) est actionnée. */
    var chimeOnDoorbell: Boolean
        get() = sp.getBoolean(KEY_CHIME_ON_DOORBELL, true)
        set(v) = sp.edit().putBoolean(KEY_CHIME_ON_DOORBELL, v).apply()

    /**
     * Adresse de go2rtc, par exemple `http://192.168.1.10:1984`.
     *
     * go2rtc accompagne Frigate et sert des images JPEG légères sur
     * `/api/frame.jpeg?src=<flux>`, sans authentification. C'est la source à privilégier :
     * beaucoup de caméras n'exposent aucune image fixe via Home Assistant — leurs
     * entités n'annoncent que le flux vidéo — et l'API caméra de HA répond alors 500.
     */
    var go2rtcUrl: String
        get() = sp.getString(KEY_GO2RTC, "") ?: ""
        set(v) = sp.edit().putString(KEY_GO2RTC, v.trim().trimEnd('/')).apply()

    /**
     * Adresse de Frigate, par exemple `http://192.168.1.10:5000`.
     *
     * Frigate sert `/api/<caméra>/latest.jpg`, **la dernière image qu'il a traitée**.
     * C'est plus robuste que go2rtc : une caméra momentanément injoignable continue
     * d'afficher sa dernière vue plutôt qu'un écran vide.
     *
     * Installé en module complémentaire de Home Assistant, Frigate n'expose pas son port
     * par défaut — il faut l'ajouter dans la configuration du module.
     */
    var frigateUrl: String
        get() = sp.getString(KEY_FRIGATE, "") ?: ""
        set(v) = sp.edit().putString(KEY_FRIGATE, v.trim().trimEnd('/')).apply()

    /**
     * Entité caméra à afficher quand on sonne, par exemple `camera.portail`.
     * Préfixée de `go2rtc:` ou `frigate:` pour désigner une autre source qu'une entité.
     * Vide = aucun affichage. Fonctionne avec Frigate comme avec toute autre caméra,
     * l'image passant par le proxy de Home Assistant.
     */
    var doorbellCamera: String
        get() = sp.getString(KEY_DOORBELL_CAMERA, "") ?: ""
        set(v) = sp.edit().putString(KEY_DOORBELL_CAMERA, v.trim()).apply()

    /** Durée d'affichage de la caméra après un coup de sonnette, en secondes. */
    var doorbellCameraSeconds: Int
        get() = sp.getInt(KEY_DOORBELL_SECONDS, 30)
        set(v) = sp.edit().putInt(KEY_DOORBELL_SECONDS, v.coerceIn(5, 300)).apply()

    /** Dossier où déposer les photos du diaporama. */
    var photoFolder: String
        get() = sp.getString(KEY_PHOTO_FOLDER, ScreensaverView.DEFAULT_FOLDER)
            ?: ScreensaverView.DEFAULT_FOLDER
        set(v) = sp.edit().putString(KEY_PHOTO_FOLDER, v.trim()).apply()

    /**
     * Délai avant extinction complète de l'écran, en secondes. 0 = jamais.
     * Seconde étape, comptée depuis la dernière interaction — donc supérieure au
     * délai d'écran de veille pour avoir un sens.
     */
    var screenTimeoutSeconds: Int
        get() = sp.getInt(KEY_TIMEOUT, 300)
        set(v) = sp.edit().putInt(KEY_TIMEOUT, v.coerceAtLeast(0)).apply()

    /** Luminosité de l'écran principal, en pourcentage. */
    var screenBrightness: Int
        get() = sp.getInt(KEY_BRIGHTNESS, 100)
        set(v) = sp.edit().putInt(KEY_BRIGHTNESS, v.coerceIn(5, 100)).apply()

    /** Réveiller l'écran quand le capteur de proximité détecte quelqu'un. */
    var wakeOnProximity: Boolean
        get() = sp.getBoolean(KEY_WAKE_PROXIMITY, true)
        set(v) = sp.edit().putBoolean(KEY_WAKE_PROXIMITY, v).apply()

    val isConfigured: Boolean
        get() = host.isNotEmpty() && token.isNotEmpty()

    fun httpBase(): String {
        val scheme = if (useTls) "https" else "http"
        return "$scheme://$host:$port"
    }

    fun wsUrl(): String {
        val scheme = if (useTls) "wss" else "ws"
        return "$scheme://$host:$port/api/websocket"
    }

    // Public parce que GO2RTC_PREFIX est utilisé ailleurs pour distinguer les sources.
    companion object {
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_TLS = "tls"
        const val KEY_TOKEN = "token"
        const val KEY_PINNED = "pinned"
        const val KEY_PUBLISH = "publish_sensors"
        const val KEY_TIMEOUT = "screen_timeout"
        const val KEY_BRIGHTNESS = "screen_brightness"
        const val KEY_WAKE_PROXIMITY = "wake_on_proximity"
        const val KEY_SAVER_DELAY = "screensaver_delay"
        const val KEY_SAVER_MODE = "screensaver_mode"
        const val KEY_PHOTO_FOLDER = "photo_folder"
        const val KEY_CHIME_FOLDER = "chime_folder"
        const val KEY_CHIME_FILE = "chime_file"
        const val KEY_CHIME_ON_DOORBELL = "chime_on_doorbell"
        const val DEFAULT_CHIME_FOLDER = "/sdcard/HAPanel/carillons"
        const val KEY_MIC = "microphone_enabled"
        const val KEY_ASSISTANT = "assistant_enabled"
        const val KEY_DOORBELL_CAMERA = "doorbell_camera"
        const val KEY_DOORBELL_SECONDS = "doorbell_camera_seconds"
        const val KEY_GO2RTC = "go2rtc_url"
        const val KEY_DLNA = "feature_dlna"
        const val KEY_DOORBELL = "feature_doorbell"
        const val KEY_KNOB_SCREEN = "feature_knob_screen"
        const val KEY_VENDOR_HW = "feature_vendor_hw"
        const val KEY_PANEL_VOLUME = "feature_panel_volume"

        const val KEY_FRIGATE = "frigate_url"

        /** Préfixes distinguant la source d'une entité Home Assistant. */
        const val GO2RTC_PREFIX = "go2rtc:"
        const val FRIGATE_PREFIX = "frigate:"
    }
}
