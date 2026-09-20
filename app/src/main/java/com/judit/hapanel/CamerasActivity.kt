package com.judit.hapanel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Vue caméras : une grille de vignettes, l'appui ouvre le plein écran.
 *
 * Les vignettes se rafraîchissent **l'une après l'autre**, jamais toutes ensemble.
 * Cinq flux simultanés mettraient à genoux un panneau de 2 Go — l'expérience de la
 * caméra à double optique, qui décodait 23 Mo par image, a laissé des traces — et de
 * toute façon on ne regarde qu'une caméra à la fois.
 *
 * Les images viennent de go2rtc quand il est configuré. C'est la source qui fonctionne
 * réellement : beaucoup de caméras n'exposent aucune image fixe via Home Assistant, leur
 * entité n'annonçant que le flux vidéo, et `/api/camera_proxy` répond alors 500.
 */
class CamerasActivity : AppCompatActivity() {

    /** Une caméra proposée : son identifiant interne et son nom lisible. */
    private data class Camera(val id: String, val name: String)

    private lateinit var prefs: Prefs
    private lateinit var grid: RecyclerView
    private lateinit var empty: TextView
    private lateinit var fullscreen: CameraView

    private val ui = Handler(Looper.getMainLooper())
    /** Toutes celles que le panneau a decouvertes. */
    private val discovered = ArrayList<Camera>()

    /** Celles reellement affichees : la selection de l'utilisateur, ou tout. */
    private val cameras = ArrayList<Camera>()
    private val thumbnails = HashMap<String, Bitmap>()

    /**
     * Proportion largeur/hauteur de chaque camera, relevee sur sa premiere image.
     *
     * Toutes les cameras ne filment pas en paysage : celle a deux optiques de ce
     * panneau empile ses deux vues et produit du 2304x2592, franchement en portrait.
     * Lui imposer une carte paysage la reduirait a une bande minuscule au milieu de
     * deux bandes noires. Chaque carte prend donc la forme de son image.
     */
    private val ratios = HashMap<String, Float>()

    @Volatile
    private var running = false

    // La langue choisie dans les reglages s'impose avant que la moindre ressource soit
    // lue : posee plus tard, elle laisserait les textes deja resolus dans l'ancienne.
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    /**
     * Affiche le nombre de sonneries du jour, s'il y en a eu.
     *
     * Relu a chaque retour sur cet ecran plutot que garde en memoire : quelqu'un peut
     * avoir sonne pendant qu'on regardait ailleurs, et une pastille figee serait pire
     * que pas de pastille du tout.
     */
    private fun refreshRangToday() {
        val jour = DoorbellHistory.today()
        val combien = DoorbellHistory.countOn(prefs, jour)
        val pastille = findViewById<TextView>(R.id.cameras_rang_today) ?: return
        pastille.visibility = if (combien > 0) View.VISIBLE else View.GONE
        if (combien > 0) {
            pastille.text = getString(R.string.history_rang_today, combien)
            pastille.setOnClickListener {
                HistoryActivity.open(this@CamerasActivity, jour)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRangToday()
    }

    /**
     * Tout geste sur cet ecran reporte la mise en veille.
     *
     * La minuterie appartient au tableau de bord, mais elle court aussi pendant qu'on
     * est ici : sans ce rappel, l'ecran s'eteindrait au milieu d'un reglage.
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        ScreenManager.noteInteraction()
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (ScreenManager.consumeWakeTouch(event)) return true
        return super.dispatchTouchEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MdiIcons.load(this)
        setContentView(R.layout.activity_cameras)

        prefs = Prefs(this)
        grid = findViewById(R.id.cameras_grid)
        empty = findViewById(R.id.cameras_empty)
        fullscreen = findViewById(R.id.cameras_fullscreen)

        findViewById<TextView>(R.id.cameras_history).apply {
            typeface = MdiIcons.typeface()
            text = MdiIcons.glyph("history")
            setOnClickListener { HistoryActivity.open(this@CamerasActivity) }
        }

        findViewById<TextView>(R.id.cameras_choose).apply {
            typeface = MdiIcons.typeface()
            text = MdiIcons.glyph("tune")
            setOnClickListener { chooseCameras() }
        }

        findViewById<TextView>(R.id.cameras_close).apply {
            typeface = MdiIcons.typeface()
            text = MdiIcons.glyph("close")
            setOnClickListener { finish() }
        }

        // Disposition decalee, et non une grille reguliere : c'est la seule qui accepte
        // des hauteurs differentes d'une carte a l'autre.
        grid.layoutManager = StaggeredGridLayoutManager(
            GRID_COLUMNS, StaggeredGridLayoutManager.VERTICAL
        )
        grid.adapter = CameraAdapter()

        // Le plein écran se referme d'une tape, comme à la sonnerie.
        fullscreen.onTap = { closeFullscreen() }

        empty.setText(R.string.cameras_loading)
        empty.visibility = View.VISIBLE
        loadCameras()
    }

    /**
     * Établit la liste des caméras : les flux go2rtc d'abord, puis les entités du
     * serveur. Une entité déjà couverte par un flux du même nom est écartée — sinon la
     * même caméra apparaîtrait deux fois, une fois utilisable et une fois non.
     */
    private fun loadCameras() {
        thread(isDaemon = true) {
            val streams = HaClient.fetchGo2rtcStreams(prefs.go2rtcUrl)
            val entities = try {
                HaClient.fetchStates(prefs)
                    .filter { it.domain == "camera" }
                    .filter { it.state != "unavailable" }
            } catch (e: Exception) {
                Log.w(TAG, "entités caméra illisibles : ${e.message}")
                emptyList()
            }

            val trouvees = ArrayList<Camera>()

            // Reduction au strict nom : « Preau », « preau » et « camera.preau » doivent
            // se reconnaitre comme une seule et meme camera.
            // Accents retires avant comparaison : sans cela « preau », le nom du flux,
            // et « prEau », celui de l'entite, passaient pour deux cameras distinctes.
            fun reduire(valeur: String) = java.text.Normalizer
                .normalize(valeur.lowercase(), java.text.Normalizer.Form.NFD)
                .filter { it.isLetterOrDigit() && it.code < 128 }
            val connus = HashSet<String>()

            fun retenir(id: String, brut: String) {
                val lisible = lisibleDepuis(brut)
                val cle = reduire(lisible)
                if (cle.isEmpty()) return

                // Rapprochement par inclusion, et non par egalite stricte. Le meme
                // appareil se presente sous des noms tres differents selon la source :
                // le flux go2rtc s'appelle « double », l'entite du serveur
                // « 192.168.1.13 Camera double ». Exiger l'egalite laissait passer tous
                // ces doublons. L'un contenant l'autre, l'inclusion les reconnait.
                if (connus.any { it.contains(cle) || cle.contains(it) }) return

                connus.add(cle)
                trouvees.add(Camera(id, lisible))
            }

            streams.forEach { retenir(Prefs.GO2RTC_PREFIX + it, it) }
            entities.forEach { retenir(it.entityId, it.friendlyName) }

            ui.post {
                discovered.clear()
                discovered.addAll(trouvees)
                applySelection()
                if (cameras.isNotEmpty()) startThumbnails()
            }
        }
    }

    /**
     * Applique la selection : la vue ne montre que les cameras retenues.
     *
     * Une selection vide vaut « toutes ». C'est le comportement utile au premier
     * lancement, ou personne n'a encore rien choisi, et apres l'apparition d'une camera
     * neuve : mieux vaut la montrer que la cacher sans raison.
     */
    private fun applySelection() {
        val retenues = prefs.camerasShown.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        cameras.clear()
        cameras.addAll(
            if (retenues.isEmpty()) discovered else discovered.filter { it.id in retenues }
        )
        grid.adapter?.notifyDataSetChanged()
        empty.setText(R.string.cameras_none)
        empty.visibility = if (cameras.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Boite de choix des cameras a afficher. */
    private fun chooseCameras() {
        if (discovered.isEmpty()) return
        val retenues = prefs.camerasShown.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val cochees = BooleanArray(discovered.size) {
            retenues.isEmpty() || discovered[it].id in retenues
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.cameras_choose)
            .setMultiChoiceItems(
                discovered.map { it.name }.toTypedArray(), cochees
            ) { _, index, coche -> cochees[index] = coche }
            .setPositiveButton(R.string.picker_save) { _, _ ->
                val choix = discovered.filterIndexed { index, _ -> cochees[index] }
                // Tout coche revient a ne rien filtrer : on enregistre une selection
                // vide, ce qui laissera paraitre les cameras ajoutees plus tard.
                prefs.camerasShown =
                    if (choix.size == discovered.size) "" else choix.joinToString(",") { it.id }
                applySelection()
            }
            .setNegativeButton(R.string.picker_cancel, null)
            .show()
    }

    /**
     * Rafraîchit les vignettes en ronde, une caméra à la fois.
     *
     * Une pause sépare chaque image : sur ce matériel, enchaîner les décodages sature
     * le processeur et rend la grille poussive au défilement.
     */
    private fun startThumbnails() {
        if (running) return
        running = true
        thread(isDaemon = true, name = "camera-thumbnails") {
            while (running) {
                for (index in cameras.indices) {
                    if (!running) return@thread
                    val camera = cameras.getOrNull(index) ?: continue
                    // Inutile de rafraîchir ce qui est caché par le plein écran.
                    if (fullscreen.visibility != View.VISIBLE) {
                        fetchThumbnail(camera)?.let { image ->
                            val proportion =
                                if (image.height > 0) image.width.toFloat() / image.height else 0f
                            ui.post {
                                thumbnails[camera.id] = image
                                if (proportion > 0f) ratios[camera.id] = proportion
                                grid.adapter?.notifyItemChanged(index)
                            }
                        }
                    }
                    Thread.sleep(BETWEEN_FRAMES_MS)
                }
                Thread.sleep(ROUND_PAUSE_MS)
            }
        }
    }

    /** Une image fixe de la caméra, décodée à la taille d'une vignette. */
    private fun fetchThumbnail(camera: Camera): Bitmap? {
        val url = when {
            camera.id.startsWith(Prefs.GO2RTC_PREFIX) -> {
                val flux = camera.id.removePrefix(Prefs.GO2RTC_PREFIX)
                if (prefs.go2rtcUrl.isBlank()) return null
                "${prefs.go2rtcUrl}/api/frame.jpeg?src=$flux"
            }

            else -> "${prefs.httpBase()}/api/camera_proxy/${camera.id}"
        }

        return try {
            (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 5000
                readTimeout = 8000
                if (!camera.id.startsWith(Prefs.GO2RTC_PREFIX) && prefs.token.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer ${prefs.token}")
                }
                if (responseCode !in 200..299) return null
                inputStream.use { flux ->
                    // Quatre fois plus petit : une vignette n'a pas besoin de la pleine
                    // résolution, et RGB_565 divise encore la mémoire par deux.
                    BitmapFactory.decodeStream(
                        flux, null,
                        BitmapFactory.Options().apply {
                            inSampleSize = 4
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                    )
                }
            }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "${camera.name} : image trop lourde")
            null
        } catch (e: Exception) {
            Log.w(TAG, "${camera.name} : ${e.message}")
            null
        }
    }

    private fun openFullscreen(camera: Camera) {
        fullscreen.start(prefs, camera.id, camera.name, "")
    }

    private fun closeFullscreen() {
        fullscreen.stop()
    }

    override fun onStop() {
        super.onStop()
        running = false
        closeFullscreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        ui.removeCallbacksAndMessages(null)
        thumbnails.clear()
    }

    /** Le plein écran se referme d'abord ; le bouton retour ne quitte qu'ensuite. */
    override fun onBackPressed() {
        if (fullscreen.visibility == View.VISIBLE) closeFullscreen() else super.onBackPressed()
    }

    private inner class CameraAdapter : RecyclerView.Adapter<CameraAdapter.Holder>() {

        override fun getItemCount(): Int = cameras.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(layoutInflater.inflate(R.layout.item_camera, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val camera = cameras[position]
            holder.name.text = camera.name

            // Hauteur calee sur la forme reelle de l'image, une fois celle-ci connue.
            // Avant la premiere vignette, on suppose du paysage, de loin le plus courant.
            val largeur = (grid.width - grid.paddingStart - grid.paddingEnd) / GRID_COLUMNS
            val proportion = ratios[camera.id] ?: DEFAULT_RATIO
            if (largeur > 0) {
                val hauteur = (largeur / proportion).toInt()
                    .coerceIn(MIN_CARD_HEIGHT, grid.height.coerceAtLeast(MIN_CARD_HEIGHT))
                if (holder.itemView.layoutParams.height != hauteur) {
                    holder.itemView.layoutParams = holder.itemView.layoutParams.apply {
                        height = hauteur
                    }
                }
            }
            holder.placeholder.typeface = MdiIcons.typeface()
            holder.placeholder.text = MdiIcons.glyph("cctv")

            val image = thumbnails[camera.id]
            if (image == null) {
                holder.image.visibility = View.GONE
                holder.placeholder.visibility = View.VISIBLE
            } else {
                holder.image.setImageBitmap(image)
                holder.image.visibility = View.VISIBLE
                holder.placeholder.visibility = View.GONE
            }

            holder.itemView.setOnClickListener { openFullscreen(camera) }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.camera_image)
            val placeholder: TextView = view.findViewById(R.id.camera_placeholder)
            val name: TextView = view.findViewById(R.id.camera_name)
        }
    }

    private companion object {
        const val TAG = "HaPanelCameras"

        /**
         * Nom presentable d'une camera.
         *
         * go2rtc et certaines integrations nomment un flux d'apres son URL complete :
         * « rtsp://192.168.1.10:8554/atelier ». Affiche tel quel, le libelle est
         * illisible et il etale l'adresse du serveur sur un ecran mural. On n'en garde
         * que le dernier segment, qui est le nom reel de la camera — et qui se confond
         * alors avec le flux du meme nom, evitant du meme coup les doublons.
         */
        fun lisibleDepuis(brut: String): String {
            val sansParametres = brut.substringBefore('?')
            val base =
                if (sansParametres.contains("://")) sansParametres.trimEnd('/').substringAfterLast('/')
                else sansParametres
            return base.ifEmpty { brut }
                .replace('_', ' ')
                // Beaucoup d'integrations prefixent le nom de l'adresse de la camera.
                // Sur un ecran mural, cette adresse n'apprend rien et encombre.
                .replace(Regex("""^\s*\d{1,3}[ .]\d{1,3}[ .]\d{1,3}[ .]\d{1,3}\s*"""), "")
                .trim()
                .replaceFirstChar { it.uppercase() }
        }
        const val GRID_COLUMNS = 3

        /** Proportion supposee tant qu'aucune image n'est arrivee : du 16/9. */
        const val DEFAULT_RATIO = 16f / 9f

        /** En dessous, une vignette ne montre plus rien d'utile. */
        const val MIN_CARD_HEIGHT = 120

        /** Pause entre deux vignettes, et entre deux tours complets. */
        const val BETWEEN_FRAMES_MS = 700L
        const val ROUND_PAUSE_MS = 6000L
    }
}
