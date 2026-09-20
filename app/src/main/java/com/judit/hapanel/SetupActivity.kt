package com.judit.hapanel

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Écran de configuration. Sert aussi de point d'entrée : si le panneau est déjà
 * configuré, on file directement au tableau de bord.
 *
 * Les champs sont gardés en propriétés plutôt qu'en variables locales : plusieurs
 * boutons — enregistrer, tester la sonnerie, choisir les entités — doivent tous
 * enregistrer l'ensemble des réglages, et dupliquer ce code serait la garantie d'oublier
 * un champ un jour.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var chimePlayer: ChimePlayer

    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var tokenField: EditText
    private lateinit var pinnedField: EditText
    private lateinit var tlsField: CheckBox
    private lateinit var publishField: CheckBox
    private lateinit var timeoutField: EditText
    private lateinit var brightnessField: EditText
    private lateinit var wakeProximityField: CheckBox
    private lateinit var saverDelayField: EditText
    private lateinit var saverModeField: Spinner
    private lateinit var photoFolderField: EditText
    private lateinit var languageField: Spinner
    private lateinit var chimeChoiceField: Spinner
    private lateinit var chimeOnDoorbellField: CheckBox
    private lateinit var assistantEnabledField: CheckBox
    private lateinit var doorbellCameraField: Spinner
    private lateinit var doorbellSecondsField: EditText
    private lateinit var go2rtcField: EditText
    private lateinit var featureKnobScreen: CheckBox
    private lateinit var featurePanelVolume: CheckBox
    private lateinit var featureVendorHw: CheckBox
    private lateinit var featureDoorbell: CheckBox
    private lateinit var featureDlna: CheckBox
    private lateinit var featureBluetooth: CheckBox
    private lateinit var wifiFallbackField: CheckBox
    private lateinit var updateSourceField: EditText
    private lateinit var updateAutoField: CheckBox
    private lateinit var updateState: TextView
    private lateinit var featureZigbee: CheckBox
    private lateinit var zigbeeDeviceField: EditText
    private lateinit var zigbeePortField: EditText
    private lateinit var zigbeeState: TextView

    private lateinit var updater: Updater

    /**
     * Les caméras proposées, entité par entité. L'index 0 vaut toujours « Aucune ».
     * Rempli en tâche de fond : interroger le serveur ne doit pas retarder l'affichage
     * des réglages, qui doivent rester utilisables même serveur injoignable.
     */
    private var cameraValues: List<String> = listOf("")

    /** L'ordre fixe la correspondance entre les entrées du menu et les valeurs stockées. */
    private val saverModeValues = listOf("anime", "photos")

    /** L'entrée 0 est toujours le carillon synthétisé par l'application. */
    private var chimeValues: List<String> = listOf("")

    // La langue choisie dans les reglages s'impose avant que la moindre ressource soit
    // lue : posee plus tard, elle laisserait les textes deja resolus dans l'ancienne.
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        if (prefs.isConfigured && !intent.getBooleanExtra(EXTRA_FORCE, false)) {
            startMain()
            return
        }

        setContentView(R.layout.activity_setup)
        chimePlayer = ChimePlayer(this, prefs)
        updater = Updater(this)

        bindFields()
        fillFromPrefs()
        wireButtons()

        // La connexion est repliée dès que le panneau est configuré : elle ne sert plus,
        // et une fausse manœuvre au doigt sur l'adresse ou le jeton le déconnecterait.
        setupSection(
            R.id.header_features, R.id.section_features, false,
            "tune-variant", R.color.domain_default, R.string.section_features
        )
        setupSection(
            R.id.header_connection, R.id.section_connection, !prefs.isConfigured,
            "server-network", R.color.status_ok, R.string.section_connection
        )
        setupSection(
            R.id.header_entities, R.id.section_entities, false,
            "view-grid-outline", R.color.domain_light, R.string.section_entities
        )
        setupSection(
            R.id.header_display, R.id.section_display, false,
            "monitor", R.color.domain_cover, R.string.section_display
        )
        setupSection(
            R.id.header_launcher, R.id.section_launcher, false,
            "home-outline", R.color.domain_climate, R.string.section_launcher
        )
        setupSection(
            R.id.header_network, R.id.section_network, false,
            "wifi", R.color.domain_sensor, R.string.section_network
        )
        setupSection(
            R.id.header_zigbee, R.id.section_zigbee, false,
            "z-wave", R.color.domain_media, R.string.section_zigbee
        )
        setupSection(
            R.id.header_audio, R.id.section_audio, false,
            "music", R.color.domain_security, R.string.section_audio
        )

        // Toucher le fond referme le clavier, qui masque sinon la moitié de l'écran.
        findViewById<View>(R.id.setup_root).setOnTouchListener { v, _ ->
            hideKeyboard(v)
            v.performClick()
            false
        }
    }

    private fun bindFields() {
        hostField = findViewById(R.id.host)
        portField = findViewById(R.id.port)
        tokenField = findViewById(R.id.token)
        pinnedField = findViewById(R.id.pinned)
        tlsField = findViewById(R.id.tls)
        publishField = findViewById(R.id.publish)
        timeoutField = findViewById(R.id.screen_timeout)
        brightnessField = findViewById(R.id.screen_brightness)
        wakeProximityField = findViewById(R.id.wake_proximity)
        saverDelayField = findViewById(R.id.screensaver_delay)
        saverModeField = findViewById(R.id.screensaver_mode)
        photoFolderField = findViewById(R.id.photo_folder)
        languageField = findViewById(R.id.language_choice)
        chimeChoiceField = findViewById(R.id.chime_choice)
        chimeOnDoorbellField = findViewById(R.id.chime_on_doorbell)
        assistantEnabledField = findViewById(R.id.assistant_enabled)
        doorbellCameraField = findViewById(R.id.doorbell_camera)
        doorbellSecondsField = findViewById(R.id.doorbell_camera_seconds)
        go2rtcField = findViewById(R.id.go2rtc_url)
        featureKnobScreen = findViewById(R.id.feature_knob_screen)
        featurePanelVolume = findViewById(R.id.feature_panel_volume)
        featureVendorHw = findViewById(R.id.feature_vendor_hw)
        featureDoorbell = findViewById(R.id.feature_doorbell)
        featureDlna = findViewById(R.id.feature_dlna)
        featureBluetooth = findViewById(R.id.feature_bluetooth)
        wifiFallbackField = findViewById(R.id.wifi_fallback)
        updateSourceField = findViewById(R.id.update_source)
        updateAutoField = findViewById(R.id.update_auto)
        updateState = findViewById(R.id.update_state)
        featureZigbee = findViewById(R.id.feature_zigbee)
        zigbeeDeviceField = findViewById(R.id.zigbee_device)
        zigbeePortField = findViewById(R.id.zigbee_port)
        zigbeeState = findViewById(R.id.zigbee_state)
    }

    private fun fillFromPrefs() {
        hostField.setText(prefs.host)
        portField.setText(prefs.port.toString())
        tokenField.setText(prefs.token)
        pinnedField.setText(prefs.pinned)
        tlsField.isChecked = prefs.useTls
        publishField.isChecked = prefs.publishSensors
        timeoutField.setText(prefs.screenTimeoutSeconds.toString())
        brightnessField.setText(prefs.screenBrightness.toString())
        wakeProximityField.isChecked = prefs.wakeOnProximity
        saverDelayField.setText(prefs.screensaverSeconds.toString())
        photoFolderField.setText(prefs.photoFolder)
        chimeOnDoorbellField.isChecked = prefs.chimeOnDoorbell
        assistantEnabledField.isChecked = prefs.assistantEnabled
        featureKnobScreen.isChecked = prefs.knobScreenEnabled
        featurePanelVolume.isChecked = prefs.panelVolumeEnabled
        featureVendorHw.isChecked = prefs.vendorHardwareEnabled
        featureDoorbell.isChecked = prefs.doorbellEnabled
        featureDlna.isChecked = prefs.dlnaEnabled
        featureBluetooth.isChecked = prefs.bluetoothEnabled
        wifiFallbackField.isChecked = prefs.wifiFallback
        updateSourceField.setText(prefs.updateSource)
        updateAutoField.isChecked = prefs.updateAuto
        updateState.text = getString(R.string.update_installed, updater.installedVersion)
        featureZigbee.isChecked = prefs.zigbeeEnabled
        zigbeeDeviceField.setText(prefs.zigbeeDevice)
        zigbeePortField.setText(prefs.zigbeePort.toString())

        doorbellSecondsField.setText(prefs.doorbellCameraSeconds.toString())
        // Pré-rempli avec l'hôte du serveur Home Assistant : go2rtc y tourne le plus
        // souvent, aux côtés de Frigate.
        go2rtcField.setText(
            prefs.go2rtcUrl.ifEmpty { "http://${prefs.host}:1984" }
        )
        loadCameras()

        saverModeField.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.saver_animated), getString(R.string.saver_photos))
        )
        saverModeField.setSelection(
            saverModeValues.indexOf(prefs.screensaverMode).coerceAtLeast(0)
        )

        languageField.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            LocaleHelper.CHOICES.map { LocaleHelper.label(this, it) }
        )
        languageField.setSelection(
            LocaleHelper.CHOICES.indexOf(prefs.language).coerceAtLeast(0)
        )
        // Pose apres setSelection, sinon la selection initiale passerait pour un choix
        // de l'utilisateur et l'ecran se reconstruirait a chaque ouverture.
        languageField.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val choix = LocaleHelper.CHOICES.getOrElse(pos) { LocaleHelper.SYSTEM }
                if (choix == prefs.language) return

                // Ce qui est deja saisi est mis a l'abri avant de tout reconstruire --
                // mais seulement si l'enregistrement peut aboutir, pour ne pas faire
                // surgir un reproche alors qu'on ne demandait qu'un changement de langue.
                val complet = hostField.text.isNotBlank() && tokenField.text.isNotBlank()
                if (complet) saveAll()

                prefs.language = choix
                recreate()
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

        val files = chimePlayer.availableChimes()
        chimeValues = listOf("") + files.map { it.absolutePath }
        chimeChoiceField.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.chime_builtin)) + files.map { it.name }
        )
        chimeChoiceField.setSelection(chimeValues.indexOf(prefs.chimeFile).coerceAtLeast(0))
    }

    /**
     * Interroge le serveur pour proposer la liste des caméras plutôt que de faire saisir
     * un `entity_id` à la main — personne ne les connaît par cœur, et une faute de frappe
     * ne se verrait qu'au moment où quelqu'un sonne.
     *
     * En attendant la réponse, la liste affiche la valeur déjà enregistrée : les réglages
     * restent utilisables même si le serveur est injoignable.
     */
    private fun loadCameras() {
        val current = prefs.doorbellCamera
        showCameras(
            values = if (current.isEmpty()) listOf("") else listOf("", current),
            labels = listOf(getString(R.string.camera_loading)) +
                if (current.isEmpty()) emptyList() else listOf(current)
        )

        if (!prefs.isConfigured) return

        kotlin.concurrent.thread(isDaemon = true) {
            // go2rtc d'abord : c'est la source qui fonctionne réellement sur ce panneau.
            val streams = HaClient.fetchGo2rtcStreams(prefs.go2rtcUrl)
            val cameras = try {
                HaClient.fetchStates(prefs)
                    .filter { it.domain == "camera" }
                    .sortedBy { it.friendlyName.lowercase() }
            } catch (e: Exception) {
                emptyList()
            }

            runOnUiThread {
                if (streams.isEmpty() && cameras.isEmpty()) {
                    showCameras(listOf(""), listOf(getString(R.string.camera_none_found)))
                    return@runOnUiThread
                }
                showCameras(
                    values = listOf("") +
                        streams.map { Prefs.GO2RTC_PREFIX + it } +
                        cameras.map { it.entityId },
                    labels = listOf(getString(R.string.camera_none)) +
                        streams.map { getString(R.string.camera_go2rtc, it) } +
                        cameras.map { "${it.friendlyName}  (${it.entityId})" }
                )
            }
        }
    }

    private fun showCameras(values: List<String>, labels: List<String>) {
        cameraValues = values
        doorbellCameraField.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        doorbellCameraField.setSelection(
            values.indexOf(prefs.doorbellCamera).coerceAtLeast(0)
        )
    }

    private fun wireButtons() {
        // Le panneau n'a aucun bouton retour matériel : sans celui-ci, on reste coincé.
        findViewById<Button>(R.id.back_button).setOnClickListener { startMain() }

        findViewById<Button>(R.id.connect).setOnClickListener {
            if (saveAll()) startMain()
        }

        findViewById<Button>(R.id.choose_entities).setOnClickListener {
            // Le sélecteur interroge le serveur : il lui faut l'adresse et le jeton.
            if (saveAll()) startActivity(Intent(this, EntityPickerActivity::class.java))
        }

        // Le Wi-Fi et le Bluetooth se règlent hors de cet écran : ce sont des listes
        // vivantes, qui se rafraîchissent, et non des champs à enregistrer.
        // Ecran d'accueil. Sans ce reglage, le menu du constructeur s'affiche a chaque
        // demarrage avant l'application -- un battement inutile. Le retour en arriere
        // reste possible a tout moment, et l'application garde son icone dans ce menu.
        val etatLanceur = findViewById<TextView>(R.id.launcher_state)
        fun montrerEtatLanceur() {
            etatLanceur.setText(
                if (LauncherRole.isDefault(this)) R.string.launcher_is_default
                else R.string.launcher_is_vendor
            )
        }
        montrerEtatLanceur()

        // Un seul bouton pour le role d'accueil, qui bascule dans un sens ou dans
        // l'autre selon l'etat courant : deux boutons dont un seul a du sens a la fois
        // laissaient deviner lequel appuyer.
        val bouton = findViewById<Button>(R.id.launcher_take)
        fun montrerBouton() {
            bouton.setText(
                if (LauncherRole.isDefault(this)) R.string.launcher_give_back
                else R.string.launcher_take
            )
        }
        montrerBouton()

        bouton.setOnClickListener {
            if (LauncherRole.isDefault(this)) {
                LauncherRole.giveBack(this)
            } else if (!LauncherRole.take(this)) {
                // Sans root, Android reserve ce choix a sa propre boite de dialogue.
                Toast.makeText(this, R.string.launcher_use_system, Toast.LENGTH_LONG).show()
                LauncherRole.openSystemChooser(this)
            }
            montrerEtatLanceur()
            montrerBouton()
        }

        // Aller voir le menu du constructeur sans rien changer au role d'accueil : on
        // peut vouloir y lancer une de ses applications et revenir.
        findViewById<Button>(R.id.launcher_release).setOnClickListener {
            LauncherRole.openVendorLauncher(this)
        }

        findViewById<Button>(R.id.open_network).setOnClickListener {
            startActivity(Intent(this, NetworkActivity::class.java))
        }

        // La sonde et le pont se disputeraient le port serie : on arrete le service le
        // temps d'interroger le coprocesseur, il repartira en revenant au tableau de bord.
        findViewById<Button>(R.id.zigbee_probe).setOnClickListener {
            zigbeeDeviceField.text.toString().trim().takeIf { d -> d.isNotEmpty() }
                ?.let { d -> prefs.zigbeeDevice = d }
            ZigbeeBridgeService.stop(this)
            zigbeeState.setText(R.string.zigbee_probing)
            kotlin.concurrent.thread(isDaemon = true) {
                val reponse = ZigbeeBridgeService.probe(this)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    zigbeeState.text = if (reponse != null && reponse.startsWith("1ac1")) {
                        getString(R.string.zigbee_found, reponse)
                    } else {
                        getString(R.string.zigbee_not_found)
                    }
                }
            }
        }

        findViewById<Button>(R.id.update_check).setOnClickListener {
            prefs.updateSource = updateSourceField.text.toString()
            prefs.updateAuto = updateAutoField.isChecked
            UpdateFlow(this, prefs).check(updateState)
        }

        findViewById<Button>(R.id.chime_test).setOnClickListener {
            // Enregistrer d'abord, sinon on entendrait le carillon précédent.
            prefs.chimeFile = chimeValues.getOrElse(chimeChoiceField.selectedItemPosition) { "" }
            chimePlayer.play()
        }

        // Tonalite de quelques secondes sur la sortie musique. Contrairement au carillon,
        // qui passe par le flux des notifications, elle emprunte le meme chemin que la
        // musique : c'est donc elle qui dit si le son part bien vers l'enceinte Bluetooth.
        findViewById<Button>(R.id.audio_test).setOnClickListener {
            AudioController(this).playTestTone()
        }

        // Le test rejoue la séquence entière depuis le tableau de bord, exactement comme
        // un vrai coup de sonnette : la caméra s'affiche par-dessus le tableau de bord,
        // ce qui ne peut pas se faire depuis cet écran.
        findViewById<Button>(R.id.doorbell_test).setOnClickListener {
            if (!saveAll()) return@setOnClickListener
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_TEST_DOORBELL, true)
            )
            finish()
        }
    }

    /** Enregistre tous les réglages. Retourne false si la connexion est incomplète. */
    private fun saveAll(): Boolean {
        val host = hostField.text.toString().trim()
        val token = tokenField.text.toString().trim()
        if (host.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, R.string.need_host_token, Toast.LENGTH_LONG).show()
            return false
        }

        prefs.host = host
        prefs.token = token
        prefs.port = portField.text.toString().trim().toIntOrNull() ?: 8123
        prefs.pinned = pinnedField.text.toString()
        prefs.useTls = tlsField.isChecked
        prefs.publishSensors = publishField.isChecked
        prefs.wakeOnProximity = wakeProximityField.isChecked
        prefs.chimeOnDoorbell = chimeOnDoorbellField.isChecked
        prefs.assistantEnabled = assistantEnabledField.isChecked
        prefs.doorbellCamera = cameraValues.getOrElse(doorbellCameraField.selectedItemPosition) { "" }
        prefs.go2rtcUrl = go2rtcField.text.toString()
        prefs.knobScreenEnabled = featureKnobScreen.isChecked
        prefs.panelVolumeEnabled = featurePanelVolume.isChecked
        prefs.vendorHardwareEnabled = featureVendorHw.isChecked
        prefs.doorbellEnabled = featureDoorbell.isChecked
        prefs.dlnaEnabled = featureDlna.isChecked
        prefs.bluetoothEnabled = featureBluetooth.isChecked
        prefs.wifiFallback = wifiFallbackField.isChecked
        prefs.updateSource = updateSourceField.text.toString()
        prefs.updateAuto = updateAutoField.isChecked
        prefs.zigbeeEnabled = featureZigbee.isChecked
        zigbeeDeviceField.text.toString().trim().takeIf { it.isNotEmpty() }
            ?.let { prefs.zigbeeDevice = it }
        zigbeePortField.text.toString().trim().toIntOrNull()
            ?.let { prefs.zigbeePort = it }
        prefs.screensaverMode = saverModeValues
            .getOrElse(saverModeField.selectedItemPosition) { "anime" }
        prefs.chimeFile = chimeValues.getOrElse(chimeChoiceField.selectedItemPosition) { "" }

        timeoutField.text.toString().trim().toIntOrNull()
            ?.let { prefs.screenTimeoutSeconds = it }
        brightnessField.text.toString().trim().toIntOrNull()
            ?.let { prefs.screenBrightness = it }
        saverDelayField.text.toString().trim().toIntOrNull()
            ?.let { prefs.screensaverSeconds = it }
        doorbellSecondsField.text.toString().trim().toIntOrNull()
            ?.let { prefs.doorbellCameraSeconds = it }
        photoFolderField.text.toString().trim().takeIf { it.isNotEmpty() }
            ?.let { prefs.photoFolder = it }

        return true
    }

    /** Le sélecteur écrit directement dans les réglages : on relit à son retour. */
    override fun onResume() {
        super.onResume()
        if (this::pinnedField.isInitialized) pinnedField.setText(prefs.pinned)
    }

    /**
     * Relie une carte d'en-tête à sa section et gère le repli.
     *
     * La présentation s'inspire de l'interface du constructeur : une pastille colorée
     * porte l'icône de la rubrique, le libellé suit, et un chevron dit si la section est
     * dépliée. On repère une rubrique à sa couleur avant d'avoir lu son nom.
     */
    private fun setupSection(
        headerId: Int,
        sectionId: Int,
        expandedAtStart: Boolean,
        glyphe: String,
        couleur: Int,
        libelle: Int
    ) {
        val header = findViewById<View>(headerId)
        val section = findViewById<View>(sectionId)

        val icone = header.findViewById<TextView>(R.id.section_icon)
        val titre = header.findViewById<TextView>(R.id.section_title)
        val chevron = header.findViewById<TextView>(R.id.section_chevron)

        icone.typeface = MdiIcons.typeface()
        icone.text = MdiIcons.glyph(glyphe)
        icone.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(couleur))
        titre.setText(libelle)
        chevron.typeface = MdiIcons.typeface()

        fun render(expanded: Boolean) {
            section.visibility = if (expanded) View.VISIBLE else View.GONE
            chevron.text = MdiIcons.glyph(if (expanded) "chevron-down" else "chevron-right")
        }

        render(expandedAtStart)
        header.setOnClickListener {
            render(section.visibility != View.VISIBLE)
            hideKeyboard(header)
        }
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        currentFocus?.clearFocus()
    }

    /** Le bouton retour d'Android ramène au tableau de bord plutôt que de tout quitter. */
    override fun onBackPressed() {
        startMain()
    }

    private fun startMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        const val EXTRA_FORCE = "force_setup"
    }
}
