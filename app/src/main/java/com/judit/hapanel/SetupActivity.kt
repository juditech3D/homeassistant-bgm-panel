package com.judit.hapanel

import android.content.Intent
import android.os.Bundle
import android.view.View
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
        setupSection(R.id.header_features, R.id.section_features, false)
        setupSection(R.id.header_connection, R.id.section_connection, !prefs.isConfigured)
        setupSection(R.id.header_entities, R.id.section_entities, false)
        setupSection(R.id.header_display, R.id.section_display, false)
        setupSection(R.id.header_network, R.id.section_network, false)
        setupSection(R.id.header_zigbee, R.id.section_zigbee, false)
        setupSection(R.id.header_audio, R.id.section_audio, false)

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

        findViewById<Button>(R.id.launcher_take).setOnClickListener {
            if (LauncherRole.take(this)) {
                montrerEtatLanceur()
                Toast.makeText(this, R.string.launcher_taken, Toast.LENGTH_SHORT).show()
            } else {
                // Sans root, Android reserve ce choix a sa propre boite de dialogue.
                Toast.makeText(this, R.string.launcher_use_system, Toast.LENGTH_LONG).show()
                LauncherRole.openSystemChooser(this)
            }
        }

        findViewById<Button>(R.id.launcher_release).setOnClickListener {
            LauncherRole.release(this)
            montrerEtatLanceur()
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

    /** Relie un en-tête à sa section et gère le repli, en préfixant d'un chevron. */
    private fun setupSection(headerId: Int, sectionId: Int, expandedAtStart: Boolean) {
        val header = findViewById<TextView>(headerId)
        val section = findViewById<View>(sectionId)
        val label = header.text.toString()

        fun render(expanded: Boolean) {
            section.visibility = if (expanded) View.VISIBLE else View.GONE
            header.text = (if (expanded) "▾  " else "▸  ") + label
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
