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
import android.widget.Toast
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
    private lateinit var gridLayout: GridLayoutManager
    private lateinit var mediaCard: MediaCardView
    private lateinit var greeting: TextView
    private lateinit var weatherCard: WeatherCardView
    private lateinit var camerasButton: TextView

    /** Les pieces ne sont interrogees qu'une fois : elles ne changent pratiquement pas. */
    private var areasLoaded = false

    /** Les pieces declarees par Home Assistant, avant toute correction locale. */
    private var serverAreas: Map<String, String> = emptyMap()

    private lateinit var panelControls: View
    private lateinit var volumeIcon: TextView
    private lateinit var volumeBar: android.widget.SeekBar
    private lateinit var assistantIcon: TextView
    private lateinit var micSwitch: android.widget.Switch

    /** Vrai pendant que le doigt tient la barre : on cesse d'ecraser la valeur reglee. */
    private var draggingVolume = false

    /**
     * Entite que le bouton rotatif pilote a la place de la tuile selectionnee.
     *
     * Le volume du panneau a quitte la grille pour le bandeau, mais on veut pouvoir le
     * regler au bouton comme avant : toucher son icone le place ici, l'ecran rond montre
     * le haut-parleur, et la rotation agit dessus. Le focus retombe des qu'on touche une
     * tuile ou apres un moment d'inactivite.
     */
    private var knobFocus: Entity? = null
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
        greeting = findViewById(R.id.greeting)
        weatherCard = findViewById(R.id.weather_card)
        mediaCard = findViewById(R.id.media_card)
        mediaCard.onService = { service, entityId, data ->
            client.callService("media_player", service, entityId, data)
        }
        empty = findViewById(R.id.empty)
        tiles = findViewById(R.id.tiles)

        adapter = TileAdapter(
            onTap = { position -> onTileTapped(position) },
            onLongPress = { position -> chooseAreaFor(position) },
            onRoomTap = { piece, membres -> toggleRoom(piece, membres) }
        )
        gridLayout = GridLayoutManager(this, TILE_COLUMNS)
        // Un intertitre de piece occupe toute la largeur ; une tuile, une colonne.
        gridLayout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.isHeader(position)) gridLayout.spanCount else 1
        }
        tiles.layoutManager = gridLayout
        tiles.adapter = adapter

        // Accès direct au sélecteur, pour ajouter ou retirer des entités sans repasser
        // par tout l'écran de configuration. Les deux commandes du bandeau sont des
        // glyphes de la police MDI, pas des boutons : un libellé y serait du bruit.
        findViewById<TextView>(R.id.entities_button).apply {
            typeface = MdiIcons.typeface()
            text = MdiIcons.glyph("view-grid-outline")
        }
        findViewById<TextView>(R.id.settings_button).apply {
            typeface = MdiIcons.typeface()
            text = MdiIcons.glyph("cog-outline")
        }
        // La vue cameras n'apparait que si le panneau en connait : une icone qui ouvre
        // une page vide vaut moins qu'une icone absente.
        bindPanelControls()

        camerasButton = findViewById(R.id.cameras_button)
        camerasButton.apply {
            typeface = MdiIcons.typeface()
            text = MdiIcons.glyph("cctv")
            setOnClickListener {
                startActivity(Intent(this@MainActivity, CamerasActivity::class.java))
            }
        }

        findViewById<TextView>(R.id.entities_button).setOnClickListener {
            startActivity(Intent(this, EntityPickerActivity::class.java))
        }

        findViewById<TextView>(R.id.settings_button).setOnClickListener {
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
        // Les commandes du bandeau sont branchees avant le materiel : sans ce second
        // passage, la barre de volume resterait a zero et l'icone en sourdine, puisque
        // le controleur audio n'existait pas encore quand elles ont ete peuplees.
        refreshPanelControls()
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
     * Met la salutation du bandeau a jour et se reprogramme au changement de minute.
     *
     * Ni horloge ni date ici : l'ecran rond du bouton rotatif les affiche deja, a
     * trente centimetres de la. Le battement reste cale sur la minute, pour que le
     * passage de « Bon apres-midi » a « Bonsoir » se fasse a l'heure juste.
     */
    private val tickHorloge = object : Runnable {
        override fun run() {
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

            if (this@MainActivity::weatherCard.isInitialized) weatherCard.refreshDate()

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
            // La densite suit la piece la plus fournie, pas le total : six lampes dans
            // une meme piece doivent se serrer, sans quoi elles s'etaleraient sur trois
            // rangees de tuiles enormes en repoussant les autres pieces hors de l'ecran.
            val reference = maxOf(adapter.largestGroup(), 1)
            val souhaitees = when {
                reference <= 1 && nombre <= 1 -> 1
                reference <= 2 -> 2
                reference <= 4 -> if (nombre <= 4) 2 else 3
                reference <= 6 -> 3
                reference <= 12 -> 4
                reference <= 20 -> 5
                else -> TILE_COLUMNS
            }
            // En dessous de 150 dp de large, une tuile devient illisible a distance.
            val maximumTenable = (largeur / (150 * densite)).toInt().coerceAtLeast(1)
            val colonnes = minOf(souhaitees, maximumTenable, TILE_COLUMNS).coerceAtLeast(1)

            // Les intertitres occupent une ligne chacun : la grille en tient compte.
            val lignesTitres = if (adapter.itemCount > adapter.tileCount) {
                adapter.itemCount - adapter.tileCount
            } else {
                0
            }
            val lignes = (nombre + colonnes - 1) / colonnes + lignesTitres
            // Au-dela de ce que l'ecran peut montrer, la grille defile : on garde alors
            // une hauteur confortable plutot que d'ecraser les tuiles.
            val lignesVisibles = lignes.coerceAtMost(MAX_TILE_ROWS)

            if (gridLayout.spanCount != colonnes) gridLayout.spanCount = colonnes
            // Plafonnee : une tuile de 400 px de haut n'a qu'un grand vide au milieu,
            // l'icone en haut et la valeur tout en bas.
            adapter.tileHeight = (hauteur / lignesVisibles).coerceIn(
                (96 * densite).toInt(), (200 * densite).toInt()
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
        refreshPanelControls()
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
        val entity = knobEntity()

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

        // Sur un intertitre, rien a regler : la rotation promene la selection.
        if (knobFocus == null && adapter.selectedRoom() != null) {
            adapter.moveSelection(direction)
            adapter.selected.let { if (it >= 0) tiles.smoothScrollToPosition(it) }
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

        // Un intertitre selectionne : l'appui commande la piece entiere.
        if (pressedOnRoom()) return

        val entity = knobEntity() ?: return

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

    /** Vrai si le bouton commande une piece entiere plutot qu'une entite. */
    private fun pressedOnRoom(): Boolean {
        if (knobFocus != null) return false
        val (piece, membres) = adapter.selectedRoom() ?: return false
        toggleRoom(piece, membres)
        refreshKnobNow()
        return true
    }

    /**
     * Toucher une tuile la sélectionne, sans changer son état — sauf pour l'assistant
     * et le mode privé, où l'on attend un effet immédiat : personne ne veut
     * sélectionner puis appuyer sur le bouton pour couper un micro.
     */
    private fun onTileTapped(position: Int) {
        if (position < 0) return
        lastInteraction = System.currentTimeMillis()

        // Un intertitre : on l'elit, l'ecran rond montre la piece, et c'est l'appui sur
        // le bouton rotatif qui l'allumera ou l'eteindra.
        if (adapter.isHeader(position)) {
            knobFocus = null
            adapter.select(position)
            refreshKnobNow()
            return
        }
        // Choisir une tuile reprend la main au bandeau : le bouton pilote de nouveau la
        // grille, faute de quoi il continuerait a regler le volume.
        knobFocus = null
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
    /**
     * Branche les commandes du panneau, dans le bandeau.
     *
     * Elles ne sont plus des tuiles : ce ne sont pas des entites de la maison, et elles
     * occupaient trois cases parmi les lampes. Le volume se regle a la barre, sans avoir
     * a selectionner quoi que ce soit au prealable.
     */
    private fun bindPanelControls() {
        panelControls = findViewById(R.id.panel_controls)
        volumeIcon = findViewById(R.id.panel_volume_icon)
        volumeBar = findViewById(R.id.panel_volume_bar)
        assistantIcon = findViewById(R.id.panel_assistant)
        micSwitch = findViewById(R.id.panel_mic)

        listOf(volumeIcon, assistantIcon).forEach { it.typeface = MdiIcons.typeface() }

        // Toucher l'icone confie le volume au bouton rotatif, comme du temps ou il
        // s'agissait d'une tuile : l'ecran rond montre le haut-parleur et la rotation
        // agit dessus. Un second appui rend la main.
        volumeIcon.setOnClickListener {
            lastInteraction = System.currentTimeMillis()
            knobFocus = if (knobFocus?.entityId == Entity.PANEL_VOLUME_ID) {
                null
            } else {
                currentPanelVolumeEntity()
            }
            refreshKnobNow()
            refreshPanelControls()
        }

        volumeBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar?, v: Int, user: Boolean) {
                if (user) lastInteraction = System.currentTimeMillis()
            }

            override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {
                draggingVolume = true
            }

            override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {
                draggingVolume = false
                audio?.setVolume((bar?.progress ?: 0) / 100f)
                // Le bip donne la mesure de ce qu'on vient de regler : sans lui, on ne
                // sait ce qu'on a fait qu'a la prochaine diffusion.
                audio?.beep()
                refreshPanelControls()
                hardware?.publishNow()
            }
        })

        assistantIcon.setOnClickListener {
            lastInteraction = System.currentTimeMillis()
            // Micro coupe, l'assistant ne peut rien faire. Plutot que de rester sans
            // reaction -- ce qui passerait pour une panne -- on dit pourquoi.
            if (!prefs.microphoneEnabled) {
                Toast.makeText(this, R.string.assistant_needs_mic, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            assistant?.toggle()
            refreshPanelControls()
        }

        micSwitch.setOnClickListener {
            lastInteraction = System.currentTimeMillis()
            setMicrophoneEnabled(micSwitch.isChecked)
        }

        refreshPanelControls()
    }

    /** Remet les commandes du bandeau en accord avec l'etat reel du panneau. */
    private fun refreshPanelControls() {
        if (!this::panelControls.isInitialized) return

        val volumeVisible = prefs.panelVolumeEnabled
        volumeIcon.visibility = if (volumeVisible) View.VISIBLE else View.GONE
        volumeBar.visibility = if (volumeVisible) View.VISIBLE else View.GONE
        volumeIcon.setTextColor(
            getColor(
                if (knobFocus?.entityId == Entity.PANEL_VOLUME_ID) R.color.accent
                else R.color.text_secondary
            )
        )
        if (volumeVisible && !draggingVolume) {
            val niveau = audio?.volume() ?: 0f
            volumeBar.progress = (niveau * 100).toInt()
            volumeIcon.text = MdiIcons.glyph(
                when {
                    niveau <= 0.01f -> "volume-off"
                    niveau < 0.34f -> "volume-low"
                    niveau < 0.67f -> "volume-medium"
                    else -> "volume-high"
                }
            )
        }

        val assistantVisible = prefs.assistantEnabled
        assistantIcon.visibility = if (assistantVisible) View.VISIBLE else View.GONE
        micSwitch.visibility = if (assistantVisible) View.VISIBLE else View.GONE
        if (assistantVisible) {
            micSwitch.isChecked = prefs.microphoneEnabled
            val etat = assistant?.state ?: VoiceAssistant.State.IDLE
            // Plus aucune icone de micro pour l'assistant : elle se confondait avec
            // celle du mode prive, juste a cote, alors que l'une commande l'autre.
            assistantIcon.text = MdiIcons.glyph(
                when (etat) {
                    VoiceAssistant.State.LISTENING -> "account-voice"
                    VoiceAssistant.State.THINKING -> "dots-horizontal"
                    VoiceAssistant.State.SPEAKING -> "account-voice"
                    else -> "assistant"
                }
            )
            // Grise quand le micro est coupe : l'assistant depend de lui, et le montrer
            // evite de chercher pourquoi rien ne se passe.
            assistantIcon.setTextColor(
                getColor(
                    when {
                        !prefs.microphoneEnabled -> R.color.text_tertiary
                        etat != VoiceAssistant.State.IDLE -> R.color.accent
                        else -> R.color.text_secondary
                    }
                )
            )
        }

        panelControls.visibility =
            if (volumeVisible || assistantVisible) View.VISIBLE else View.GONE
    }

    private fun refreshLocalTiles() = refreshPanelControls()

    /** Le volume a change cote materiel : on remet la barre en accord. */
    private fun refreshPanelTile() = refreshPanelControls()

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

    /** L'entite que le bouton pilote : le focus du bandeau, sinon la tuile selectionnee. */
    private fun knobEntity(): Entity? =
        knobFocus?.let { if (it.entityId == Entity.PANEL_VOLUME_ID) currentPanelVolumeEntity() else it }
            ?: adapter.selectedEntity()

    private fun drawKnob() {
        val idleFor = System.currentTimeMillis() - lastInteraction

        // Une piece selectionnee : l'ecran rond annonce ce que l'appui va faire.
        val piece = if (knobFocus == null) adapter.selectedRoom() else null
        if (piece != null && idleFor <= IDLE_TIMEOUT_MS) {
            val allumees = piece.second.count { it.isOn }
            knob.drawValue(
                piece.first,
                if (allumees > 0) "$allumees ON" else "OFF",
                if (piece.second.isEmpty()) 0f else allumees.toFloat() / piece.second.size,
                if (allumees > 0) ADJUST_ACCENT else SELECT_ACCENT
            )
            return
        }

        val entity = knobEntity()

        if (entity == null || idleFor > IDLE_TIMEOUT_MS) {
            if (pendingValue != null) ui.post { pendingValue = null }
            // Le focus du bandeau retombe en meme temps que l'ecran rond : sans cela, le
            // bouton continuerait a regler le volume longtemps apres qu'on l'a quitte.
            if (knobFocus != null) ui.post { knobFocus = null }
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
        // Les mesures d'une meme sonde sont reunies sur une seule tuile : temperature et
        // humidite disaient deux fois le nom du meme appareil.
        val shown = SensorMerge.merge(selectEntities(allEntities), allEntities)
        adapter.submit(shown)
        applyAreas()
        refreshWeather()
        refreshCamerasButton()
        refreshMediaCard()
        loadAreas()
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
     * Affiche la carte meteo, alimentee par la premiere entite `weather` du serveur.
     * Masquee s'il n'y en a aucune : la musique recupere alors toute la hauteur.
     */
    /**
     * Recupere la piece de chaque entite, pour regrouper les tuiles.
     *
     * Une seule fois par session : la carte des pieces ne bouge pratiquement jamais, et
     * l'interroger passe par un modele evalue sur toutes les entites du serveur — ce
     * n'est pas gratuit. Silencieux en cas d'echec : sans pieces, la grille reste une
     * simple suite de tuiles, ce qui reste parfaitement utilisable.
     */
    private fun loadAreas() {
        if (areasLoaded || !prefs.isConfigured) return
        areasLoaded = true
        kotlin.concurrent.thread(isDaemon = true) {
            val pieces = HaClient.fetchAreas(prefs)
            android.util.Log.i(
                TAG_AREAS,
                "${pieces.size} entites rattachees a " +
                    "${pieces.values.distinct().size} pieces. Une entite absente de cette " +
                    "liste n'a pas de piece dans Home Assistant : elle ira dans « Autres »."
            )
            if (pieces.isEmpty()) return@thread
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                serverAreas = pieces
                applyAreas()
                applySelection()
            }
        }
    }

    /**
     * Compose la carte des pieces : celle du serveur, corrigee par les affectations
     * faites sur le panneau. Le local prime — c'est la que l'on range ce que Home
     * Assistant laisse sans piece, et c'est la aussi qu'on rectifie un rangement.
     */
    private fun applyAreas() {
        adapter.areas = serverAreas + prefs.localAreaMap()
    }

    /**
     * Range une entite dans une piece, depuis le panneau.
     *
     * Les pieces proposees sont celles deja connues — du serveur comme du panneau — plus
     * la possibilite d'en creer une. Rien n'est envoye a Home Assistant : le rangement
     * reste propre a cet ecran, ce qui permet d'organiser le tableau de bord sans
     * toucher a l'installation.
     */
    private fun chooseAreaFor(position: Int) {
        val entity = adapter.entityAt(position) ?: return
        lastInteraction = System.currentTimeMillis()

        val connues = (serverAreas.values + prefs.localAreaMap().values)
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        val actuelle = adapter.areas[entity.entityId].orEmpty()

        val choix = connues + listOf(
            getString(R.string.area_new), getString(R.string.area_none)
        )
        val libelles = choix.mapIndexed { index, nom ->
            if (index < connues.size && nom == actuelle) "$nom  ·  ✓" else nom
        }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.area_choose, entity.friendlyName))
            .setItems(libelles) { _, index ->
                when (index) {
                    connues.size -> askNewArea(entity)
                    connues.size + 1 -> setArea(entity, "")
                    else -> setArea(entity, connues[index])
                }
            }
            .show()
    }

    /** Demande le nom d'une nouvelle piece, puis y range l'entite. */
    private fun askNewArea(entity: Entity) {
        val champ = android.widget.EditText(this).apply {
            hint = getString(R.string.area_name_hint)
            setSingleLine()
        }
        val boite = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(champ)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.area_new)
            .setView(boite)
            .setPositiveButton(R.string.save) { _, _ ->
                setArea(entity, champ.text.toString())
            }
            .setNegativeButton(R.string.picker_cancel, null)
            .show()
    }

    private fun setArea(entity: Entity, piece: String) {
        prefs.setLocalArea(entity.entityId, piece)
        applyAreas()
        applySelection()
    }

    /**
     * Allume ou eteint toute une piece d'une tape sur son intertitre.
     *
     * Si quoi que ce soit est allume, on eteint tout ; sinon on allume tout. C'est plus
     * previsible qu'une bascule entite par entite, qui laisserait la piece dans un etat
     * melange -- exactement ce qu'on cherchait a eviter en tapant sur la piece entiere.
     *
     * Une seule commande pour l'ensemble : Home Assistant accepte une liste d'entites,
     * ce qui evite autant d'allers-retours que de lampes et les allume d'un coup.
     */
    private fun toggleRoom(piece: String, membres: List<Entity>) {
        if (membres.isEmpty()) return
        lastInteraction = System.currentTimeMillis()

        val allumees = membres.count { it.isOn }
        val eteindre = allumees > 0
        val cibles = membres.joinToString(",") { it.entityId }

        client.callService(
            "homeassistant",
            if (eteindre) "turn_off" else "turn_on",
            cibles
        )

        Toast.makeText(
            this,
            getString(
                if (eteindre) R.string.room_all_off else R.string.room_all_on,
                piece.lowercase().replaceFirstChar { it.uppercase() }
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun refreshWeather() {
        if (!this::weatherCard.isInitialized) return
        weatherCard.visibility = if (weatherCard.bind(allEntities)) View.VISIBLE else View.GONE
    }

    /** Montre l'acces aux cameras des que le serveur ou go2rtc en expose une. */
    private fun refreshCamerasButton() {
        if (!this::camerasButton.isInitialized) return
        val disponible = prefs.go2rtcUrl.isNotBlank() ||
            allEntities.any { it.domain == "camera" }
        camerasButton.visibility = if (disponible) View.VISIBLE else View.GONE
    }

    private fun refreshMediaCard() {
        if (!this::mediaCard.isInitialized) return
        val visible = prefs.mediaCardEnabled && mediaCard.bind(allEntities)
        val cible = if (visible) View.VISIBLE else View.GONE
        if (mediaCard.visibility != cible) {
            mediaCard.visibility = cible
            layoutTiles(adapter.tileCount)
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

        // Les commandes du panneau -- volume, assistant, mode prive -- ne figurent plus
        // ici : elles vivent dans le bandeau. Ce ne sont pas des entites de la maison,
        // et elles occupaient trois cases parmi les lampes.

        if (pinned.isNotEmpty()) {
            val byId = all.associateBy { it.entityId }
            return pinned.mapNotNull { byId[it] }
        }

        return all
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

        const val TAG_AREAS = "HaPanelAreas"

        /** Au-dela, la grille defile plutot que d'ecraser les tuiles. */
        const val MAX_TILE_ROWS = 3


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
