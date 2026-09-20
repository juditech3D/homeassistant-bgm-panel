package com.judit.hapanel

import android.content.Context

/** Réglages persistants : adresse du serveur, jeton, et entités épinglées au tableau de bord. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("hapanel", Context.MODE_PRIVATE)

    /**
     * Un contexte pour les classes qui n'en recoivent pas, comme [HaClient].
     *
     * Il porte la langue du systeme, pas celle choisie dans les reglages : passer par
     * [LocaleHelper.wrap] reste necessaire pour en tirer un texte affiche.
     */
    val appContext: Context = context.applicationContext

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
    /**
     * Langue de l'interface : `systeme`, `fr` ou `en`.
     *
     * Android 8.1 n'offre pas la liste de langues par application des versions
     * recentes : le choix se pose donc ici, et [LocaleHelper] l'applique a chaque
     * ecran. `systeme` suit la langue du panneau, ce qui reste le defaut.
     */
    var language: String
        get() = sp.getString(KEY_LANGUAGE, "systeme") ?: "systeme"
        set(v) = sp.edit().putString(KEY_LANGUAGE, v).apply()

    /**
     * `anime`, `photos` ou `video`.
     *
     * `image` a existe un temps -- une image fixe du constructeur en guise de veille --
     * et se retrouve donc dans des reglages enregistres. Elle est ramenee a `anime` a la
     * lecture : une image immobile des heures durant marque la dalle, ce qui est
     * precisement ce qu'un ecran de veille doit eviter.
     */
    var screensaverMode: String
        get() = (sp.getString(KEY_SAVER_MODE, "anime") ?: "anime")
            .let { if (it == "image") "anime" else it }
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

    /**
     * Pont Zigbee : expose le coprocesseur du panneau sur le réseau, pour Zigbee2MQTT
     * ou ZHA. Voir [ZigbeeBridgeService]. Désactivé par défaut — tous les panneaux de
     * cette famille n'ont pas la radio, et l'exposer sans le vouloir n'aurait pas de sens.
     */
    var zigbeeEnabled: Boolean
        get() = sp.getBoolean(KEY_ZIGBEE, false)
        set(v) = sp.edit().putBoolean(KEY_ZIGBEE, v).apply()

    /** Port série du coprocesseur Zigbee. `/dev/ttyS3` sur le panneau de référence. */
    var zigbeeDevice: String
        get() = sp.getString(KEY_ZIGBEE_DEVICE, DEFAULT_ZIGBEE_DEVICE) ?: DEFAULT_ZIGBEE_DEVICE
        set(v) = sp.edit().putString(KEY_ZIGBEE_DEVICE, v.trim()).apply()

    /** Port TCP d'écoute du pont Zigbee. */
    var zigbeePort: Int
        get() = sp.getInt(KEY_ZIGBEE_PORT, DEFAULT_ZIGBEE_PORT)
        set(v) = sp.edit().putInt(KEY_ZIGBEE_PORT, v.coerceIn(1024, 65535)).apply()

    /**
     * Pièces attribuées depuis le panneau, sous la forme `entite=Pièce`, séparées par
     * des points-virgules.
     *
     * Home Assistant reste la source de référence : le panneau lit ses zones et s'y
     * conforme. Mais toutes les entités n'y sont pas rangées — chez l'auteur, 528 le
     * sont sur bien davantage — et il serait absurde d'avoir à ouvrir le serveur pour
     * classer une lampe. Une affectation faite ici **prime** sur celle du serveur, ce
     * qui permet aussi de corriger un rangement qui ne convient pas, sans rien toucher
     * à l'installation.
     */
    var localAreas: String
        get() = sp.getString(KEY_LOCAL_AREAS, "") ?: ""
        set(v) = sp.edit().putString(KEY_LOCAL_AREAS, v.trim()).apply()

    /** Les affectations locales, sous forme exploitable. */
    fun localAreaMap(): Map<String, String> = localAreas
        .split(';')
        .mapNotNull { paire ->
            val sep = paire.indexOf('=')
            if (sep <= 0) return@mapNotNull null
            val entite = paire.substring(0, sep).trim()
            val piece = paire.substring(sep + 1).trim()
            if (entite.isEmpty() || piece.isEmpty()) null else entite to piece
        }
        .toMap()

    /** Range une entité dans une pièce, ou l'en retire si [piece] est vide. */
    fun setLocalArea(entityId: String, piece: String) {
        val carte = localAreaMap().toMutableMap()
        if (piece.isBlank()) carte.remove(entityId) else carte[entityId] = piece.trim()
        localAreas = carte.entries.joinToString(";") { "${it.key}=${it.value}" }
    }

    /**
     * Caméras retenues dans la vue caméras, séparées par des virgules.
     * Vide = toutes celles que le panneau découvre.
     */
    var camerasShown: String
        get() = sp.getString(KEY_CAMERAS_SHOWN, "") ?: ""
        set(v) = sp.edit().putString(KEY_CAMERAS_SHOWN, v.trim()).apply()

    /** Colonne de lecture multiroom, à droite du tableau de bord. */
    var mediaCardEnabled: Boolean
        get() = sp.getBoolean(KEY_MEDIA_CARD, true)
        set(v) = sp.edit().putBoolean(KEY_MEDIA_CARD, v).apply()

    /** Audio Bluetooth : réception depuis un téléphone, ou émission vers une enceinte. */
    var bluetoothEnabled: Boolean
        get() = sp.getBoolean(KEY_BLUETOOTH, false)
        set(v) = sp.edit().putBoolean(KEY_BLUETOOTH, v).apply()

    /**
     * Sens de l'audio Bluetooth : `ENTREE` (un téléphone diffuse vers le panneau) ou
     * `SORTIE` (le panneau diffuse vers une enceinte). Voir [BluetoothController.Mode].
     */
    var bluetoothMode: String
        get() = sp.getString(KEY_BLUETOOTH_MODE, "ENTREE") ?: "ENTREE"
        set(v) = sp.edit().putString(KEY_BLUETOOTH_MODE, v).apply()

    /**
     * Où chercher les mises à jour : `proprietaire/depot` pour les publications GitHub,
     * ou l'URL d'un fichier JSON. Vide = aucune recherche. Voir [Updater].
     */
    var updateSource: String
        get() = sp.getString(KEY_UPDATE_SOURCE, DEFAULT_UPDATE_SOURCE) ?: ""
        set(v) = sp.edit().putString(KEY_UPDATE_SOURCE, v.trim()).apply()

    /**
     * Version dont l'installation a été reportée. Vide = aucune.
     *
     * Reportée, une mise à jour ne doit plus interrompre à chaque démarrage — mais elle
     * ne doit pas non plus s'oublier. Elle se rappelle alors par une pastille discrète
     * dans le bandeau, qu'on touche quand on est prêt.
     */
    var updatePostponed: String
        get() = sp.getString(KEY_UPDATE_POSTPONED, "") ?: ""
        set(v) = sp.edit().putString(KEY_UPDATE_POSTPONED, v.trim()).apply()

    /** Chercher une mise à jour au démarrage, sans rien installer sans accord. */
    var updateAuto: Boolean
        get() = sp.getBoolean(KEY_UPDATE_AUTO, true)
        set(v) = sp.edit().putBoolean(KEY_UPDATE_AUTO, v).apply()

    /**
     * Ouvrir l'écran de réseau au démarrage quand aucune liaison filaire n'est détectée.
     *
     * Le panneau est normalement câblé en RJ45 : tant que l'Ethernet répond, il n'y a
     * aucune raison de demander quoi que ce soit. C'est seulement quand il ne répond pas
     * que le Wi-Fi devient la seule issue — et qu'il faut pouvoir le configurer sans ADB.
     */
    var wifiFallback: Boolean
        get() = sp.getBoolean(KEY_WIFI_FALLBACK, true)
        set(v) = sp.edit().putBoolean(KEY_WIFI_FALLBACK, v).apply()

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
    /**
     * Le fond du tableau de bord : chemin d'un fichier, ou vide pour le degrade livre
     * avec l'application.
     *
     * C'est un chemin et non une ressource, parce que l'image peut venir de l'APK du
     * constructeur ou d'un lien : dans les deux cas elle est recopiee dans le stockage
     * prive, ce qui la rend independante de sa provenance.
     */
    var dashboardBackground: String
        get() = sp.getString(KEY_DASH_BG, "") ?: ""
        set(v) = sp.edit().putString(KEY_DASH_BG, v).apply()

    /** L'image ou la video de l'ecran de veille, quand [screensaverMode] les demande. */
    var screensaverImage: String
        get() = sp.getString(KEY_SAVER_IMAGE, "") ?: ""
        set(v) = sp.edit().putString(KEY_SAVER_IMAGE, v).apply()

    /**
     * D'ou vient le fond retenu : l'entree d'archive du constructeur, ou `lien`.
     *
     * Sert uniquement a cocher la bonne case dans la grille de choix. Le chemin du
     * fichier ne suffirait pas : toutes les copies du constructeur portent le meme nom.
     */
    var dashboardBackgroundSource: String
        get() = sp.getString(KEY_DASH_BG_SRC, "") ?: ""
        set(v) = sp.edit().putString(KEY_DASH_BG_SRC, v).apply()

    var screensaverImageSource: String
        get() = sp.getString(KEY_SAVER_IMAGE_SRC, "") ?: ""
        set(v) = sp.edit().putString(KEY_SAVER_IMAGE_SRC, v).apply()

    /**
     * Partage des fichiers du panneau sur le reseau local, par une page web.
     *
     * Eteint par defaut : le service ecrit des fichiers sur l'appareil, ce qui doit
     * rester un choix explicite et non un etat subi.
     */
    var fileShareEnabled: Boolean
        get() = sp.getBoolean(KEY_SHARE, false)
        set(v) = sp.edit().putBoolean(KEY_SHARE, v).apply()

    var fileSharePort: Int
        get() = sp.getInt(KEY_SHARE_PORT, 8080)
        set(v) = sp.edit().putInt(KEY_SHARE_PORT, v.coerceIn(1024, 65535)).apply()

    /** Vide = partage ouvert a tout le reseau local. Voir [FileShareService]. */
    var fileSharePassword: String
        get() = sp.getString(KEY_SHARE_PASSWORD, "") ?: ""
        set(v) = sp.edit().putString(KEY_SHARE_PASSWORD, v).apply()

    /**
     * L'entite meteo a afficher, ou vide pour la premiere venue.
     *
     * Home Assistant en expose souvent plusieurs -- le domicile, une ville suivie, un
     * fournisseur de secours. Le panneau doit montrer celle de l'endroit ou il est
     * accroche, qui n'est pas forcement la premiere que le serveur renvoie.
     */
    var weatherEntity: String
        get() = sp.getString(KEY_WEATHER, "") ?: ""
        set(v) = sp.edit().putString(KEY_WEATHER, v).apply()

    /**
     * Le dossier des photos du diaporama.
     *
     * Il valait `HAPanel/fonds` jusqu'a la 1.12, qui servait aussi aux fonds d'ecran :
     * on y voyait donc ses photos de famille defiler en veille et son fond de tableau de
     * bord au milieu d'elles. Les deux usages ont desormais chacun leur dossier.
     *
     * L'ancienne valeur est ramenee a la nouvelle **a la lecture seulement** : les
     * fichiers deja deposes ne sont pas deplaces. Rien ne dit qu'ils etaient la pour le
     * diaporama, et deplacer sans demander des fichiers qu'on n'a pas mis soi-meme est
     * le genre de service qu'on ne rend pas.
     */
    var photoFolder: String
        get() = (sp.getString(KEY_PHOTO_FOLDER, ScreensaverView.DEFAULT_FOLDER)
            ?: ScreensaverView.DEFAULT_FOLDER)
            .let { if (it == ANCIEN_DOSSIER_PHOTOS) ScreensaverView.DEFAULT_FOLDER else it }
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
        const val KEY_LANGUAGE = "language"
        const val KEY_PHOTO_FOLDER = "photo_folder"

        /** Le dossier que le diaporama partageait avec les fonds d'ecran, jusqu'a la 1.12. */
        const val ANCIEN_DOSSIER_PHOTOS = "/sdcard/HAPanel/fonds"
        const val KEY_WEATHER = "weather_entity"
        const val KEY_SHARE = "file_share"
        const val KEY_SHARE_PORT = "file_share_port"
        const val KEY_SHARE_PASSWORD = "file_share_password"
        const val KEY_DASH_BG = "dashboard_background"
        const val KEY_SAVER_IMAGE = "screensaver_image"
        const val KEY_DASH_BG_SRC = "dashboard_background_source"
        const val KEY_SAVER_IMAGE_SRC = "screensaver_image_source"
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
        const val KEY_BLUETOOTH = "feature_bluetooth"
        const val KEY_BLUETOOTH_MODE = "bluetooth_mode"
        const val KEY_WIFI_FALLBACK = "wifi_fallback"
        const val KEY_UPDATE_SOURCE = "update_source"
        const val KEY_UPDATE_AUTO = "update_auto"
        const val KEY_UPDATE_POSTPONED = "update_postponed"
        const val KEY_MEDIA_CARD = "feature_media_card"
        const val KEY_CAMERAS_SHOWN = "cameras_shown"
        const val KEY_LOCAL_AREAS = "local_areas"
        const val KEY_ZIGBEE = "feature_zigbee"
        const val KEY_ZIGBEE_DEVICE = "zigbee_device"
        const val KEY_ZIGBEE_PORT = "zigbee_port"

        /** Le port série du coprocesseur Zigbee sur le panneau de référence. */
        const val DEFAULT_ZIGBEE_DEVICE = "/dev/ttyS3"

        /** Port TCP par défaut du pont Zigbee, choisi hors des plages usuelles. */
        const val DEFAULT_ZIGBEE_PORT = 8888

        /**
         * Le dépôt du projet. C'est la source par défaut : qui installe l'application
         * depuis ce dépôt veut en recevoir les mises à jour.
         *
         * L'API des publications GitHub n'est ouverte que sur un **dépôt public**. Tant
         * que le dépôt reste privé, la vérification répond 404 : il faut alors pointer
         * cette source sur un fichier JSON servi ailleurs, par exemple depuis le dossier
         * `www/` de Home Assistant. Voir [Updater].
         */
        const val DEFAULT_UPDATE_SOURCE = "Juditech3D/homeassistant-bgm-panel"

        const val KEY_FRIGATE = "frigate_url"

        /** Préfixes distinguant la source d'une entité Home Assistant. */
        const val GO2RTC_PREFIX = "go2rtc:"
        const val FRIGATE_PREFIX = "frigate:"
    }
}
