package com.judit.hapanel

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Tableau de bord du panneau.
 *
 * Le bouton rotatif arrive ici sous forme de touches Android ordinaires : le keylayout
 * générique du système mappe déjà les scancodes du pilote `knod-aispeech` (467, 468,
 * 473) vers F2, F3 et F8. Pas besoin de root ni de lire /dev/input.
 *
 *   F2 = rotation à droite · F3 = rotation à gauche · F8 = appui
 *
 * Interaction :
 *   - toucher une tuile      → la sélectionne (sans l'allumer ni l'éteindre)
 *   - tourner le bouton      → règle directement la valeur de l'entité sélectionnée
 *                              (luminosité d'une lampe, consigne d'un thermostat,
 *                              volume, position d'un volet…)
 *   - appuyer sur le bouton  → allume ou éteint l'entité sélectionnée
 *
 * Sur une entité qui n'a rien à régler (un simple interrupteur), la rotation déplace
 * la sélection : cela évite que le bouton soit inerte.
 *
 * Note matérielle : l'appui produit une impulsion d'environ 130 µs, pas un maintien.
 * L'appui long est donc impossible à détecter et n'est utilisé nulle part.
 */
class MainActivity : AppCompatActivity(), HaClient.Listener {

    private lateinit var prefs: Prefs
    private lateinit var client: HaClient
    private lateinit var adapter: TileAdapter
    private lateinit var status: TextView
    private lateinit var statusDot: TextView
    private lateinit var clock: TextView
    private lateinit var dateLabel: TextView
    private lateinit var gridLayout: GridLayoutManager
    private lateinit var mediaCard: MediaCardView
    private lateinit var greeting: TextView
    private lateinit var weatherBox: View
    private lateinit var weatherIcon: TextView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherCondition: TextView
    private lateinit var empty: TextView
    private lateinit var tiles: RecyclerView

    private val knob = KnobScreen()
    private var sensors: PanelSensors? = null
    private var hardware: PanelHardware? = null
    private var audio: AudioController? = null
    private var chime: ChimePlayer? = null
    private var assistant: VoiceAssistant? = null
    private lateinit var screen: ScreenManager
    private lateinit var screensaver: ScreensaverView
    private lateinit var camera: CameraView

    private val hideCamera = Runnable { stopDoorbellCamera() }

    private lateinit var knobThread: HandlerThread
    private lateinit var knobHandler: Handler
    private val ui = Handler(Looper.getMainLooper())

    /** Valeur en cours de réglage, non encore confirmée par Home Assistant. */
    private var pendingValue: Float? = null
    private var lastInteraction = 0L

    /** Instant de la dernière impulsion de rotation, pour l'accélération. */
    private var lastRotation = 0L

    /**
     * Toutes les entités connues du serveur, conservées pour pouvoir réappliquer la
     * sélection au retour du sélecteur sans redemander la liste à Home Assistant.
     */
    private var allEntities: List<Entity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (!prefs.isConfigured) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        // Avant setContentView : les vues qui portent des icônes fixes — la carte
        // musique notamment — les résolvent dès leur construction. Chargée après, la
        // police arriverait trop tard et ces icônes resteraient vides.
        MdiIcons.load(this)

        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Le panneau est normalement câblé en RJ45. Ce n'est que si le filaire ne répond
        // pas qu'il faut proposer le Wi-Fi : encastré dans une boîte électrique, il n'a
        // plus d'autre moyen de revenir sur le réseau.
        NetworkActivity.openIfNoNetwork(this, prefs)

        // Recherche d'une mise à jour, sans rien installer sans accord. Différée de
        // quelques secondes : le tableau de bord et la connexion à Home Assistant
        // passent d'abord.
        if (prefs.updateAuto) {
            window.decorView.postDelayed({
                if (!isFinishing) UpdateFlow(this, prefs).check()
            }, UPDATE_CHECK_DELAY_MS)
        }

        // L'alimentation de l'écran rond retombe à chaque redémarrage du panneau :
        // c'était l'app constructeur, désormais désactivée, qui la rétablissait.
        // Sans cet appel l'écran reste noir bien que les écritures réussissent.
        if (prefs.knobScreenEnabled) VendorHw.setKnobScreenPower(true)

        status = findViewById(R.id.status)
        statusDot = findViewById(R.id.status_dot)
        clock = findViewById(R.id.clock)
        dateLabel = findViewById(R.id.date)
        greeting = findViewById(R.id.greeting)
        weatherBox = findViewById(R.id.weather)
        weatherIcon = findViewById(R.id.weather_icon)
        weatherTemp = findViewById(R.id.weather_temp)
        weatherCondition = findViewById(R.id.weather_condition)
        weatherIcon.typeface = MdiIcons.typeface()
        mediaCard = findViewById(R.id.media_card)
        mediaCard.onService = { service, entityId, data ->
            client.callService("media_player", service, entityId, data)
        }
        empty = findViewById(R.id.empty)
        tiles = findViewById(R.id.tiles)

        adapter = TileAdapter { position -> onTileTapped(position) }
        gridLayout = GridLayoutManager(this, TILE_COLUMNS)
        tiles.layoutManager = gridLayout
        tiles.adapter = adapter

        // Accès direct au sélecteur, pour ajouter ou retirer des entités sans repasser
        // par tout l'écran de configuration.
        findViewById<Button>(R.id.entities_button).setOnClickListener {
            startActivity(Intent(this, EntityPickerActivity::class.java))
        }

        findViewById<Button>(R.id.settings_button).setOnClickListener {
            startActivity(
                Intent(this, SetupActivity::class.java)
                    .putExtra(SetupActivity.EXTRA_FORCE, true)
            )
            finish()
        }

        knobThread = HandlerThread("knob-screen").apply { start() }
        knobHandler = Handler(knobThread.looper)

        client = HaClient(prefs)
        client.listener = this

        screensaver = findViewById(R.id.screensaver)
        screensaver.mode = if (prefs.screensaverMode == "photos") {
            ScreensaverView.Mode.PHOTOS
        } else {
            ScreensaverView.Mode.ANIMATED
        }
        screensaver.photoFolder = prefs.photoFolder

        screen = ScreenManager(prefs).apply {
            onScreensaverChanged = { showing ->
                runOnUiThread {
                    if (showing) screensaver.startSaver() else screensaver.stopSaver()
                }
            }
            // Si le root manque, on ne peut qu'assombrir la fenêtre au lieu d'éteindre.
            onFallbackBrightness = { level ->
                runOnUiThread {
                    window.attributes = window.attributes.apply { screenBrightness = level }
                }
            }
        }

        // Les capteurs sont toujours lus : le réveil par proximité doit fonctionner même
        // si l'on ne publie rien vers Home Assistant. Ne pas lier les deux — c'était un
        // défaut qui rendait le réveil silencieusement inopérant.
        sensors = PanelSensors(this, client, publish = prefs.publishSensors).apply {
            onProximityNear = {
                if (prefs.wakeOnProximity) runOnUiThread { screen.noteActivity() }
            }
        }
        audio = AudioController(this)
        camera = findViewById<CameraView>(R.id.camera).apply {
            onTap = { stopDoorbellCamera(); screen.noteActivity() }
        }
        chime = ChimePlayer(this, prefs)
        assistant = VoiceAssistant(this, client, prefs).apply {
            onStateChanged = { refreshLocalTiles() }
        }
        hardware = PanelHardware(client, audio, screen, chime, prefs, assistant) {
            showDoorbellCamera()
        }

        // La permission micro n'est demandée que si l'assistant est activé : inutile
        // d'inquiéter quelqu'un qui ne s'en servira pas.
        if (prefs.assistantEnabled && assistant?.hasPermission() == false) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
        // Un endormissement ou un réveil doit remonter tout de suite, pas au prochain
        // cycle de publication.
        screen.onSleepChanged = { hardware?.publishNow() }

        // Rend le panneau visible comme media_player dans Home Assistant. Le service
        // vit indépendamment de cette activité pour que la lecture ne s'interrompe pas.
        if (prefs.dlnaEnabled) {
            com.judit.hapanel.dlna.DlnaRendererService.start(this)
        } else {
            com.judit.hapanel.dlna.DlnaRendererService.stop(this)
        }

        // Expose le coprocesseur Zigbee sur le reseau, pour Zigbee2MQTT ou ZHA. Le
        // service vit hors de cette activite : le reseau Zigbee ne doit pas s'arreter
        // quand l'ecran se met en veille.
        if (prefs.zigbeeEnabled) {
            ZigbeeBridgeService.start(this)
        } else {
            ZigbeeBridgeService.stop(this)
        }
    }

    /**
     * Met l'horloge du bandeau a l'heure et se reprogramme au changement de minute.
     *
     * On vise le debut de la minute suivante plutot qu'un battement fixe : une horloge
     * qui affiche les minutes doit changer quand la minute change, pas trente secondes
     * apres.
     */
    private val tickHorloge = object : Runnable {
        override fun run() {
            val maintenant = java.util.Date()
            clock.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(maintenant)
            dateLabel.text = java.text.SimpleDateFormat(
                "EEEE d MMMM", java.util.Locale.getDefault()
            ).format(maintenant).replaceFirstChar { it.uppercase() }

            // La salutation suit l'heure : elle donne au bandeau un ton d'accueil
            // plutot que de tableau de bord technique.
            val heure = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            greeting.setText(
                when (heure) {
                    in 5..11 -> R.string.greeting_morning
                    in 12..17 -> R.string.greeting_afternoon
                    else -> R.string.greeting_evening
                }
            )

            val restant = 60_000L - (System.currentTimeMillis() % 60_000L)
            ui.postDelayed(this, restant + 200L)
        }
    }

    /**
     * Repartit les tuiles sur toute la surface disponible.
     *
     * Une grille a nombre de colonnes fixe laissait quatre entites tassees dans le coin
     * superieur gauche, les trois quarts de l'ecran vides. On choisit desormais le
     * nombre de colonnes d'apres le nombre d'entites, puis on etire les tuiles pour
     * remplir la hauteur.
     */
    private fun layoutTiles(nombre: Int) {
        if (nombre <= 0) return
        tiles.post {
            val largeur = tiles.width - tiles.paddingStart - tiles.paddingEnd
            val hauteur = tiles.height - tiles.paddingTop - tiles.paddingBottom
            if (largeur <= 0 || hauteur <= 0) return@post

            val densite = resources.displayMetrics.density

            // Reparti sur deux dimensions plutot que sur une seule ligne : quatre
            // entites donnent une grille 2x2, pas quatre colonnes ecrasees contre le
            // haut de l'ecran. Les paliers sont regles pour du 1024x600.
            val souhaitees = when {
                nombre <= 1 -> 1
                nombre <= 4 -> 2
                nombre <= 6 -> 3
                nombre <= 12 -> 4
                nombre <= 20 -> 5
                else -> TILE_COLUMNS
            }
            // En dessous de 150 dp de large, une tuile devient illisible a distance.
            val maximumTenable = (largeur / (150 * densite)).toInt().coerceAtLeast(1)
            val colonnes = minOf(souhaitees, maximumTenable, TILE_COLUMNS).coerceAtLeast(1)

            val lignes = (nombre + colonnes - 1) / colonnes
            // Au-dela de ce que l'ecran peut montrer, la grille defile : on garde alors
            // une hauteur confortable plutot que d'ecraser les tuiles.
            val lignesVisibles = lignes.coerceAtMost(MAX_TILE_ROWS)

            if (gridLayout.spanCount != colonnes) gridLayout.spanCount = colonnes
            // Plafonnee : une tuile de 400 px de haut n'a qu'un grand vide au milieu,
            // l'icone en haut et la valeur tout en bas.
            adapter.tileHeight = (hauteur / lignesVisibles).coerceIn(
                (96 * densite).toInt(), (230 * densite).toInt()
            )
        }
    }

    override fun onStart() {
        super.onStart()
        ui.post(tickHorloge)
        status.text = getString(R.string.status_connecting)
        client.connect()
        sensors?.start()
        hardware?.start()
        screen.start()
        knobHandler.post(knobRefresh)

        // Test demandé depuis les réglages : on rejoue la sonnerie complète, carillon
        // et caméra, comme si quelqu'un avait appuyé sur le bouton.
        if (intent.getBooleanExtra(EXTRA_TEST_DOORBELL, false)) {
            intent.removeExtra(EXTRA_TEST_DOORBELL)
            ui.postDelayed({
                chime?.play()
                showDoorbellCamera()
            }, TEST_DOORBELL_DELAY_MS)
        }
    }

    /** Au retour du sélecteur, la liste a pu changer : on la réapplique. */
    override fun onResume() {
        super.onResume()
        if (allEntities.isNotEmpty()) applySelection()
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(tickHorloge)
        client.disconnect()
        sensors?.stop()
        hardware?.stop()
        screen.stop()
        knobHandler.removeCallbacksAndMessages(null)
        ui.removeCallbacks(releaseRing)
        VendorHw.setRingWhite(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        sensors?.release()
        hardware?.release()
        audio?.release()
        chime?.release()
        assistant?.release()
        if (this::knobThread.isInitialized) knobThread.quitSafely()
    }

    // ------------------------------------------------------------ bouton rotatif

    /**
     * Le premier toucher sur un écran endormi ne sert qu'à réveiller : il serait
     * déroutant d'éteindre une lampe en voulant simplement rallumer le panneau.
     */
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        // Pendant l'affichage de la caméra, c'est elle qui gère le tactile : zoom au
        // pincement et déplacement au doigt. Elle prévient par onTap quand il s'agit
        // d'un simple appui, qui referme.
        if (camera.visibility == View.VISIBLE) {
            screen.noteActivity()
            return super.dispatchTouchEvent(event)
        }
        if (screen.isAsleep || screen.isScreensaverShowing) {
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) screen.noteActivity()
            return true
        }
        screen.noteActivity()
        return super.dispatchTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Même principe au bouton rotatif : on réveille sans rien déclencher d'autre.
        if ((screen.isAsleep || screen.isScreensaverShowing) && keyCode in KNOB_KEYS) {
            screen.noteActivity()
            return true
        }
        if (keyCode in KNOB_KEYS) screen.noteActivity()

        return when (keyCode) {
            KeyEvent.KEYCODE_F2 -> { onRotate(+1); true }
            KeyEvent.KEYCODE_F1 -> { onRotate(-1); true }
            // L'appui émet F3 (scancode 468) : mesuré six fois sur six. F8 avait été
            // observé une fois lors des tout premiers relevés, on le conserve par
            // sécurité car il ne peut rien déclencher d'autre.
            KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F8 -> { onPress(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /**
     * Rotation : règle la valeur de l'entité sélectionnée. Si elle n'a rien à régler,
     * on déplace la sélection plutôt que de ne rien faire.
     */
    private fun onRotate(direction: Int) {
        val now = System.currentTimeMillis()
        val sinceLastRotation = now - lastRotation
        lastRotation = now
        lastInteraction = now
        val entity = adapter.selectedEntity()

        // Le volume du panneau est local : on l'applique tout de suite, sans passer par
        // Home Assistant ni par la temporisation d'envoi.
        if (entity != null && entity.entityId == Entity.PANEL_VOLUME_ID) {
            val a = audio ?: return
            val step = if (sinceLastRotation < FAST_ROTATION_MS) STEP * FAST_FACTOR else STEP
            // On part de la cible précédente, pas du volume relu : AudioManager met un
            // instant à refléter l'écriture, et relire donnerait une valeur périmée —
            // plusieurs crans d'affilée retomberaient alors sur la même valeur.
            val base = panelVolumeTarget ?: a.volume()
            val next = (base + direction * step).coerceIn(0f, 1f)
            panelVolumeTarget = next
            a.setVolume(next)
            // Bip au volume qu'on vient de régler : un pourcentage à l'écran ne dit
            // rien de la puissance réellement entendue.
            a.beep()
            ui.removeCallbacks(clearPanelVolumeTarget)
            ui.postDelayed(clearPanelVolumeTarget, PANEL_VOLUME_SETTLE_MS)
            VendorHw.setRingWhite(true)
            ui.removeCallbacks(releaseRing)
            ui.postDelayed(releaseRing, RING_HOLD_MS)
            refreshPanelTile()
            refreshKnobNow()
            return
        }

        if (entity?.adjustable != null) {
            // L'encodeur émet nettement moins d'impulsions qu'il n'y a de crans :
            // sans accélération, parcourir toute la plage serait interminable.
            // Rotation lente = réglage fin, rotation rapide = grands écarts.
            val step = if (sinceLastRotation < FAST_ROTATION_MS) STEP * FAST_FACTOR else STEP
            val current = pendingValue ?: entity.normalisedValue()
            pendingValue = (current + direction * step).coerceIn(0f, 1f)

            // L'anneau passe au blanc pendant le réglage, et revient au rouge peu après.
            VendorHw.setRingWhite(true)
            ui.removeCallbacks(releaseRing)
            ui.postDelayed(releaseRing, RING_HOLD_MS)

            ui.removeCallbacks(sendPending)
            ui.postDelayed(sendPending, SEND_DEBOUNCE_MS)
        } else {
            adapter.moveSelection(direction)
            adapter.selected.let { if (it >= 0) tiles.smoothScrollToPosition(it) }
            pendingValue = null
        }
        refreshKnobNow()
    }

    /** Appui : allume ou éteint l'entité sélectionnée. */
    private fun onPress() {
        lastInteraction = System.currentTimeMillis()
        val entity = adapter.selectedEntity() ?: return

        // Appui sur la tuile de volume : bascule la sourdine de l'amplificateur.
        if (entity.entityId == Entity.PANEL_VOLUME_ID) {
            VendorHw.setMute(!VendorHw.isMuted())
            refreshPanelTile()
            refreshKnobNow()
            return
        }

        if (entity.entityId == Entity.PANEL_ASSISTANT_ID) {
            assistant?.toggle()
            refreshLocalTiles()
            return
        }

        if (entity.entityId == Entity.PANEL_MIC_ID) {
            setMicrophoneEnabled(!prefs.microphoneEnabled)
            return
        }

        // Un réglage encore en attente est abandonné, pas confirmé : l'envoyer
        // allumerait l'entité juste avant qu'on ne demande de la basculer, soit deux
        // ordres contradictoires coup sur coup. La temporisation étant de 250 ms, la
        // valeur réglée a de toute façon déjà été transmise pendant la rotation.
        ui.removeCallbacks(sendPending)
        pendingValue = null

        client.toggle(entity)
        refreshKnobNow()
    }

    /**
     * Toucher une tuile la sélectionne, sans changer son état — sauf pour l'assistant
     * et le mode privé, où l'on attend un effet immédiat : personne ne veut
     * sélectionner puis appuyer sur le bouton pour couper un micro.
     */
    private fun onTileTapped(position: Int) {
        if (position < 0) return
        lastInteraction = System.currentTimeMillis()
        adapter.select(position)
        pendingValue = null

        when (adapter.entityAt(position)?.entityId) {
            Entity.PANEL_ASSISTANT_ID -> { assistant?.toggle(); refreshLocalTiles() }
            Entity.PANEL_MIC_ID -> setMicrophoneEnabled(!prefs.microphoneEnabled)
        }
        refreshKnobNow()
    }

    /**
     * Affiche la caméra choisie pendant quelques secondes. Appelé quand on sonne :
     * l'écran est réveillé d'abord, faute de quoi la vidéo jouerait dans le noir.
     */
    fun showDoorbellCamera() {
        val entityId = prefs.doorbellCamera
        if (entityId.isBlank()) return

        runOnUiThread {
            screen.noteActivity()
            val entity = allEntities.firstOrNull { it.entityId == entityId }
            // L'état vu par Home Assistant explique la plupart des échecs : une caméra
            // « unavailable » ne produira jamais d'image, quoi qu'on fasse ici.
            android.util.Log.i(
                "CameraView",
                "entité $entityId : état=${entity?.state ?: "inconnue du serveur"}" +
                    " attributs=${entity?.attributes}"
            )
            val name = entity?.friendlyName ?: entityId
            val accessToken = entity?.attributes?.optString("access_token").orEmpty()
            camera.start(prefs, entityId, name, accessToken)
            ui.removeCallbacks(hideCamera)
            ui.postDelayed(hideCamera, prefs.doorbellCameraSeconds * 1000L)
        }
    }

    private fun stopDoorbellCamera() {
        ui.removeCallbacks(hideCamera)
        camera.stop()
    }

    private fun setMicrophoneEnabled(enabled: Boolean) {
        prefs.microphoneEnabled = enabled
        // Couper le micro doit interrompre une écoute en cours, pas seulement empêcher
        // la suivante.
        if (!enabled) assistant?.cancel()
        refreshLocalTiles()
        hardware?.publishNow()
    }

    /**
     * Volume visé pendant un réglage au bouton, le temps que le matériel se cale.
     * Nul en dehors d'un réglage : c'est alors le matériel qui fait foi.
     */
    private var panelVolumeTarget: Float? = null

    private val clearPanelVolumeTarget = Runnable {
        panelVolumeTarget = null
        refreshPanelTile()
    }

    /** L'état courant de l'amplificateur : la cible si on règle, sinon le matériel. */
    private fun currentPanelVolumeEntity(): Entity =
        Entity.panelVolume(panelVolumeTarget ?: audio?.volume() ?: 0f, VendorHw.isMuted())

    private fun currentAssistantEntity(): Entity = Entity.panelAssistant(
        assistant?.state ?: VoiceAssistant.State.IDLE,
        assistant?.isAvailable ?: false
    )

    /** Rafraîchit les cartes locales sans toucher au reste de la grille. */
    private fun refreshLocalTiles() {
        if (prefs.panelVolumeEnabled) adapter.update(currentPanelVolumeEntity())
        if (prefs.assistantEnabled) {
            adapter.update(currentAssistantEntity())
            adapter.update(Entity.panelMicrophone(prefs.microphoneEnabled))
        }
    }

    /** Rafraîchit la seule tuile locale, sans toucher au reste de la grille. */
    private fun refreshPanelTile() {
        adapter.update(currentPanelVolumeEntity())
    }

    private val sendPending = Runnable {
        val entity = adapter.selectedEntity() ?: return@Runnable
        val value = pendingValue ?: return@Runnable
        client.applyValue(entity, value)
    }

    private val releaseRing = Runnable { VendorHw.setRingWhite(false) }

    // ------------------------------------------------------------- écran rond

    private val knobRefresh = object : Runnable {
        override fun run() {
            drawKnob()
            knobHandler.postDelayed(this, KNOB_REFRESH_MS)
        }
    }

    private fun refreshKnobNow() {
        knobHandler.post { drawKnob() }
    }

    private fun drawKnob() {
        val idleFor = System.currentTimeMillis() - lastInteraction
        val entity = adapter.selectedEntity()

        if (entity == null || idleFor > IDLE_TIMEOUT_MS) {
            if (pendingValue != null) ui.post { pendingValue = null }
            knob.drawClock()
            return
        }

        val adjusting = pendingValue != null
        val fraction = pendingValue ?: entity.normalisedValue()
        val label = when {
            !adjusting -> entity.displayValue()
            entity.adjustable == Entity.Adjustable.TEMPERATURE -> {
                val min = entity.attributes.optDouble("min_temp", 7.0)
                val max = entity.attributes.optDouble("max_temp", 35.0)
                String.format("%.1f°", min + (max - min) * fraction)
            }
            else -> "${(fraction * 100).toInt()}%"
        }

        knob.drawValue(
            entity.friendlyName,
            label,
            fraction,
            if (adjusting) ADJUST_ACCENT else SELECT_ACCENT
        )
    }

    // ------------------------------------------------------- retours du client

    override fun onConnected() {
        // Pastille verte et un seul mot : l'adresse du serveur n'apprend rien à
        // l'usage quotidien, et l'afficher en permanence sur un écran mural l'expose
        // à quiconque passe devant — ou photographie le panneau.
        status.text = getString(R.string.status_connected)
        status.setTextColor(getColor(R.color.text_secondary))
        statusDot.setTextColor(getColor(R.color.status_ok))
    }

    override fun onDisconnected(reason: String) {
        // En panne, en revanche, la raison est ce qu'on veut lire.
        status.text = getString(R.string.status_disconnected, reason)
        status.setTextColor(getColor(R.color.status_error))
        statusDot.setTextColor(getColor(R.color.status_error))
    }

    override fun onStatesLoaded(entities: List<Entity>) {
        allEntities = entities

        // À la connexion, on aligne le matériel sur ce que Home Assistant demande,
        // sans quoi il resterait dans l'état où le redémarrage l'a laissé.
        hardware?.let { hw ->
            val commands = hw.commandEntities()
            entities.filter { it.entityId in commands }.forEach { hw.onEntityChanged(it) }
        }

        applySelection()
    }

    private fun applySelection() {
        val shown = selectEntities(allEntities)
        adapter.submit(shown)
        refreshWeather()
        refreshMediaCard()
        layoutTiles(shown.size)
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        refreshKnobNow()
    }

    override fun onStateChanged(entity: Entity) {
        // Les entités de commande du matériel ne sont pas des tuiles : elles pilotent
        // directement l'avertisseur, la sourdine, l'écran du bouton, les relais…
        hardware?.onEntityChanged(entity)

        if (entity.domain == "media_player") {
            allEntities = allEntities.map { if (it.entityId == entity.entityId) entity else it }
            refreshMediaCard()
        }

        if (adapter.update(entity)) {
            if (adapter.selectedEntity()?.entityId == entity.entityId && pendingValue == null) {
                refreshKnobNow()
            }
        }
    }

    /**
     * Montre la carte musique si le serveur expose au moins un lecteur, la masque sinon.
     *
     * La largeur rendue aux tuiles quand la carte disparait change leur repartition :
     * d'ou le recalcul de la grille dans la foulee.
     */
    /**
     * Affiche la meteo du bandeau, reprise de la premiere entite `weather` du serveur.
     * Masquee s'il n'y en a aucune : mieux vaut un bandeau sobre qu'un emplacement vide.
     */
    private fun refreshWeather() {
        if (!this::weatherBox.isInitialized) return
        val meteo = allEntities.firstOrNull { it.domain == "weather" }
        if (meteo == null) {
            weatherBox.visibility = View.GONE
            return
        }
        weatherBox.visibility = View.VISIBLE
        val temperature = meteo.attributes.optDouble("temperature", Double.NaN)
        weatherTemp.text = if (temperature.isNaN()) "—"
        else String.format("%.0f°", temperature)
        weatherCondition.text = conditionLabel(meteo.state)
        weatherIcon.text = MdiIcons.glyph(weatherGlyph(meteo.state))
    }

    private fun refreshMediaCard() {
        if (!this::mediaCard.isInitialized) return
        val visible = prefs.mediaCardEnabled && mediaCard.bind(allEntities)
        val cible = if (visible) View.VISIBLE else View.GONE
        if (mediaCard.visibility != cible) {
            mediaCard.visibility = cible
            layoutTiles(adapter.itemCount)
        }
    }

    /**
     * Choisit les entités à afficher : la liste retenue dans le sélecteur si elle
     * existe, sinon une découverte automatique limitée aux domaines utiles.
     */
    private fun selectEntities(all: List<Entity>): List<Entity> {
        val pinned = prefs.pinned
            .split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // Les cartes locales viennent en tête : volume, puis assistant et mode privé
        // s'ils sont activés. Ce sont les commandes les plus utilisées au quotidien.
        val local = ArrayList<Entity>()
        if (prefs.panelVolumeEnabled) local.add(currentPanelVolumeEntity())
        if (prefs.assistantEnabled) {
            local.add(currentAssistantEntity())
            local.add(Entity.panelMicrophone(prefs.microphoneEnabled))
        }

        if (pinned.isNotEmpty()) {
            val byId = all.associateBy { it.entityId }
            return local + pinned.mapNotNull { byId[it] }
        }

        return local + all
            .filter { it.domain in Entity.INTERESTING }
            .filter { it.state != "unavailable" && it.state != "unknown" }
            .sortedWith(
                compareBy(
                    { Entity.INTERESTING.indexOf(it.domain) },
                    { it.friendlyName.lowercase() }
                )
            )
            .take(MAX_AUTO_ENTITIES)
    }

    // Public parce que SetupActivity a besoin d'EXTRA_TEST_DOORBELL.
    companion object {
        const val TILE_COLUMNS = 6
        const val STEP = 0.05f

        /**
         * Délai avant la recherche de mise à jour au démarrage. Le tableau de bord et la
         * connexion à Home Assistant sont prioritaires : sur ce matériel modeste, une
         * requête réseau supplémentaire au même instant se voit à l'affichage.
         */
        const val UPDATE_CHECK_DELAY_MS = 8000L

        /** Au-dela, la grille defile plutot que d'ecraser les tuiles. */
        const val MAX_TILE_ROWS = 3

        /** Icone MDI correspondant a un etat d'entite `weather` de Home Assistant. */
        fun weatherGlyph(condition: String): String = when (condition) {
            "sunny" -> "weather-sunny"
            "clear-night" -> "weather-night"
            "partlycloudy" -> "weather-partly-cloudy"
            "cloudy" -> "weather-cloudy"
            "fog" -> "weather-fog"
            "hail" -> "weather-hail"
            "lightning" -> "weather-lightning"
            "lightning-rainy" -> "weather-lightning-rainy"
            "pouring" -> "weather-pouring"
            "rainy" -> "weather-rainy"
            "snowy" -> "weather-snowy"
            "snowy-rainy" -> "weather-snowy-rainy"
            "windy", "windy-variant" -> "weather-windy"
            "exceptional" -> "alert-circle-outline"
            else -> "weather-cloudy"
        }

        /** Libelle francais de la condition meteorologique. */
        fun conditionLabel(condition: String): String = when (condition) {
            "sunny" -> "Ensoleillé"
            "clear-night" -> "Ciel dégagé"
            "partlycloudy" -> "Peu nuageux"
            "cloudy" -> "Nuageux"
            "fog" -> "Brouillard"
            "hail" -> "Grêle"
            "lightning" -> "Orageux"
            "lightning-rainy" -> "Orages et pluie"
            "pouring" -> "Fortes pluies"
            "rainy" -> "Pluvieux"
            "snowy" -> "Neigeux"
            "snowy-rainy" -> "Pluie et neige"
            "windy", "windy-variant" -> "Venteux"
            "exceptional" -> "Conditions extrêmes"
            else -> condition.replaceFirstChar { it.uppercase() }
        }

        /** En dessous de ce délai entre deux impulsions, on considère la rotation rapide. */
        const val FAST_ROTATION_MS = 200L
        const val FAST_FACTOR = 4f

        const val SEND_DEBOUNCE_MS = 250L
        const val RING_HOLD_MS = 2_500L

        /** Délai après lequel on refait confiance au volume relu du matériel. */
        const val PANEL_VOLUME_SETTLE_MS = 1_500L
        const val REQ_MIC = 101

        /** Demande de rejouer la séquence de sonnerie au démarrage, depuis les réglages. */
        const val EXTRA_TEST_DOORBELL = "test_doorbell"

        /** Laisse le temps à la connexion de s'établir avant le test. */
        const val TEST_DOORBELL_DELAY_MS = 2_000L
        const val KNOB_REFRESH_MS = 1_000L
        const val IDLE_TIMEOUT_MS = 10_000L
        const val MAX_AUTO_ENTITIES = 48
        const val SELECT_ACCENT = 0xFF667080.toInt()
        const val ADJUST_ACCENT = 0xFF00AAFF.toInt()

        /** Les touches émises par l'encodeur, toutes sources de réveil. */
        val KNOB_KEYS = setOf(
            KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F8
        )
    }
}
