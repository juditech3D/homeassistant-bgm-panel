package com.judit.hapanel

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Le choix du fond : écran de veille, ou fond du tableau de bord.
 *
 * Une grille de vignettes plutôt qu'une liste déroulante. Un fond se choisit à l'œil,
 * et l'application d'origine — qui procède ainsi — a raison sur ce point.
 *
 * La grille réunit trois provenances :
 *
 * - **ce que fait déjà l'application** : l'écran animé, et le diaporama d'un dossier ;
 * - **les fonds du constructeur**, lus dans son APK sur l'appareil (voir
 *   [VendorWallpapers]) ;
 * - **un lien collé**, vers une photo ou une vidéo.
 *
 * L'écran animé reste la première case : c'est le réglage d'origine du panneau, et
 * l'ajout des fonds du constructeur ne doit pas le reléguer.
 */
class WallpaperPickerActivity : AppCompatActivity() {

    /** Ce que la grille peut proposer. */
    private sealed class Choice {
        /** L'écran de veille animé de l'application. */
        object Animated : Choice()

        /** Le diaporama du dossier de photos. */
        object Photos : Choice()

        /** Le dégradé livré avec l'application, pour le tableau de bord. */
        object Default : Choice()

        /** Un fond tiré de l'application d'origine. */
        data class Vendor(val item: VendorWallpapers.Item) : Choice()

        /** Le fichier déjà téléchargé depuis un lien. */
        data class Custom(val file: File) : Choice()

        /** La case qui ouvre la saisie d'un lien. */
        object Link : Choice()
    }

    private val prefs by lazy { Prefs(this) }
    private lateinit var grid: RecyclerView
    private lateinit var state: TextView

    /** Vrai quand on règle le fond du tableau de bord, faux pour l'écran de veille. */
    private val forDashboard by lazy { intent.getBooleanExtra(EXTRA_DASHBOARD, false) }

    private var choices: List<Choice> = emptyList()

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MdiIcons.load(this)
        setContentView(R.layout.activity_wallpaper_picker)

        findViewById<TextView>(R.id.picker_heading).setText(
            if (forDashboard) R.string.wallpaper_title_dashboard else R.string.wallpaper_title_saver
        )
        findViewById<View>(R.id.back_button).setOnClickListener { finish() }

        state = findViewById(R.id.wallpaper_state)
        grid = findViewById(R.id.wallpaper_grid)
        grid.layoutManager = GridLayoutManager(this, COLUMNS)

        showChoices(baseChoices())
        loadVendorWallpapers()
    }

    /** Ce qui s'affiche sans rien lire de l'archive : disponible immédiatement. */
    private fun baseChoices(): List<Choice> {
        val fixes = if (forDashboard) {
            listOf(Choice.Default)
        } else {
            listOf(Choice.Animated, Choice.Photos)
        }
        // Une image telechargee ne sert qu'au tableau de bord ; la veille, elle, n'admet
        // que ce qui bouge.
        val perso = customFile()
            .takeIf { it.exists() && (forDashboard != isVideo(it)) }
            ?.let { listOf(Choice.Custom(it)) }
            .orEmpty()
        return fixes + perso + listOf(Choice.Link)
    }

    private fun showChoices(liste: List<Choice>) {
        choices = liste
        if (grid.adapter == null) grid.adapter = Adapter() else grid.adapter?.notifyDataSetChanged()
    }

    /**
     * Va chercher les fonds du constructeur en tâche de fond.
     *
     * L'archive pèse une centaine de mégaoctets et chaque image est sondée pour ses
     * dimensions : c'est rapide, mais pas au point de tenir sur le fil d'affichage.
     */
    private fun loadVendorWallpapers() {
        // Les fonds du constructeur sont des images fixes : ils habillent le tableau de
        // bord, jamais la veille, ou l'immobilite marquerait la dalle.
        if (!forDashboard) {
            state.setText(R.string.wallpaper_saver_moving_only)
            return
        }
        state.setText(R.string.wallpaper_loading)
        thread(isDaemon = true) {
            val items = VendorWallpapers.list(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (items.isEmpty()) {
                    state.setText(R.string.wallpaper_none_vendor)
                    return@runOnUiThread
                }
                state.text = getString(R.string.wallpaper_found, items.size)
                val base = baseChoices().toMutableList()
                // Le lien reste en dernier : c'est une action, pas un fond.
                val lien = base.removeAt(base.lastIndex)
                showChoices(base + items.map { Choice.Vendor(it) } + lien)
            }
        }
    }

    // ------------------------------------------------------------------ choix

    private fun apply(choice: Choice) {
        when (choice) {
            Choice.Link -> askForLink()

            Choice.Default -> {
                prefs.dashboardBackground = ""
                prefs.dashboardBackgroundSource = ""
                done()
            }

            Choice.Animated -> {
                prefs.screensaverMode = "anime"
                done()
            }

            Choice.Photos -> {
                prefs.screensaverMode = "photos"
                done()
            }

            is Choice.Custom -> {
                retain(choice.file, LINK)
                done()
            }

            // Inatteignable : la grille de la veille ne propose aucun fond du
            // constructeur. Laisse en place pour que le `when` reste exhaustif.

            is Choice.Vendor -> {
                state.setText(R.string.wallpaper_copying)
                thread(isDaemon = true) {
                    val cible = File(filesDir, if (forDashboard) DASHBOARD_FILE else SAVER_FILE)
                    val copie = VendorWallpapers.copy(this, choice.item, cible)
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        if (copie == null) {
                            Toast.makeText(this, R.string.wallpaper_copy_failed, Toast.LENGTH_LONG)
                                .show()
                            state.text = ""
                        } else {
                            retain(copie, choice.item.entry)
                            done()
                        }
                    }
                }
            }
        }
    }

    /** Enregistre le fichier comme fond, du côté demandé. */
    private fun retain(file: File, source: String) {
        if (forDashboard) {
            prefs.dashboardBackground = file.absolutePath
            prefs.dashboardBackgroundSource = source
            return
        }
        // Cote veille, seule une video est admise : une photo y resterait immobile.
        if (!isVideo(file)) {
            Toast.makeText(this, R.string.wallpaper_saver_moving_only, Toast.LENGTH_LONG).show()
            return
        }
        prefs.screensaverImage = file.absolutePath
        prefs.screensaverImageSource = source
        prefs.screensaverMode = "video"
    }

    /** Ce qui est retenu aujourd'hui : entree d'archive, `lien`, ou rien. */
    private fun currentSource(): String =
        if (forDashboard) prefs.dashboardBackgroundSource else prefs.screensaverImageSource

    private fun done() {
        setResult(Activity.RESULT_OK)
        finish()
    }

    // ------------------------------------------------------------------ lien

    /**
     * Saisie d'un lien vers une photo ou une vidéo.
     *
     * Une adresse de page Pexels est acceptée telle qu'on la copie depuis le navigateur :
     * le numéro qu'elle porte en fin de chemin suffit à reconstruire l'adresse de
     * l'image. Pour une vidéo, en revanche, il faut **le lien du fichier lui-même** —
     * l'adresse d'une page vidéo ne permet pas de retrouver le fichier sans passer par
     * l'interface de programmation du site, ce que le panneau ne fait pas.
     */
    private fun askForLink() {
        val champ = EditText(this).apply {
            setHint(R.string.wallpaper_link_hint)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.wallpaper_link_title)
            .setMessage(R.string.wallpaper_link_help)
            .setView(champ)
            .setPositiveButton(R.string.wallpaper_link_fetch) { _, _ ->
                download(champ.text.toString().trim())
            }
            .setNegativeButton(R.string.picker_cancel, null)
            .show()
    }

    private fun download(saisie: String) {
        val url = directUrl(saisie)
        if (url == null) {
            Toast.makeText(this, R.string.wallpaper_link_invalid, Toast.LENGTH_LONG).show()
            return
        }

        state.setText(R.string.wallpaper_downloading)
        thread(isDaemon = true) {
            val erreur = fetch(url)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (erreur != null) {
                    state.text = ""
                    Toast.makeText(this, erreur, Toast.LENGTH_LONG).show()
                } else {
                    retain(customFile(), LINK)
                    done()
                }
            }
        }
    }

    /**
     * L'adresse du fichier à télécharger, déduite de ce qui a été collé.
     *
     * Une page Pexels — `pexels.com/…/photo/…-6985042/` — ne sert pas l'image elle-même ;
     * son numéro final, lui, donne l'adresse directe. Tout le reste est pris tel quel.
     */
    private fun directUrl(saisie: String): String? {
        if (saisie.isBlank()) return null
        if (!saisie.startsWith("http://") && !saisie.startsWith("https://")) return null

        if (saisie.contains("pexels.com") && !saisie.contains("images.pexels.com")) {
            val numero = Regex("""(\d{4,})/?\s*$""").find(saisie.trimEnd('/'))?.groupValues?.get(1)
            if (numero != null && saisie.contains("/photo")) {
                return "https://images.pexels.com/photos/$numero/pexels-photo-$numero.jpeg" +
                    "?auto=compress&cs=tinysrgb&w=1280"
            }
            // Une page vidéo Pexels ne mène pas au fichier : on le dit plutôt que de
            // telecharger une page HTML et de l'afficher comme un fond noir.
            if (saisie.contains("/video")) return null
        }
        return saisie
    }

    /** Télécharge dans le stockage privé. Renvoie null si tout s'est bien passé. */
    private fun fetch(url: String): String? {
        var connexion: HttpURLConnection? = null
        return try {
            connexion = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                // Certains hébergeurs refusent une requête sans identification de client.
                setRequestProperty("User-Agent", "HaPanel")
            }
            val code = connexion.responseCode
            if (code !in 200..299) return getString(R.string.update_http_error, code)

            val type = connexion.contentType.orEmpty()
            if (!type.startsWith("image/") && !type.startsWith("video/")) {
                return getString(R.string.wallpaper_link_not_media, type.ifBlank { "?" })
            }

            val cible = customFile(type.startsWith("video/"))
            // L'ancien fichier part : les deux genres ne peuvent pas coexister sous le
            // meme reglage, et laisser trainer une video de plusieurs mega-octets serait
            // du gaspillage sur une partition de 2,6 Go.
            customFile(true).delete()
            customFile(false).delete()
            connexion.inputStream.use { source ->
                cible.outputStream().use { source.copyTo(it) }
            }
            if (!cible.exists() || cible.length() == 0L) {
                getString(R.string.update_truncated)
            } else {
                null
            }
        } catch (e: Exception) {
            e.message ?: getString(R.string.update_failed)
        } finally {
            connexion?.disconnect()
        }
    }

    private fun customFile(video: Boolean = false): File =
        File(filesDir, if (video) "wallpaper_custom.mp4" else "wallpaper_custom.img")

    /** Le fichier déjà téléchargé, quel que soit son genre. */
    private fun customFile(): File =
        customFile(true).takeIf { it.exists() } ?: customFile(false)

    private fun isVideo(file: File) = file.name.endsWith(".mp4")

    // ------------------------------------------------------------------ grille

    private inner class Adapter : RecyclerView.Adapter<Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_wallpaper, parent, false)
        )

        override fun getItemCount() = choices.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val choice = choices[position]
            holder.image.setImageDrawable(null)
            holder.image.setImageBitmap(null)
            holder.check.visibility = View.GONE
            holder.glyph.visibility = View.GONE
            holder.itemView.setOnClickListener { apply(choice) }

            when (choice) {
                Choice.Animated -> {
                    holder.label.setText(R.string.saver_animated)
                    holder.glyph.visibility = View.VISIBLE
                    holder.glyph.text = MdiIcons.glyph("shimmer")
                    holder.image.setImageResource(R.drawable.dashboard_background)
                    holder.check.visibility =
                        if (prefs.screensaverMode == "anime") View.VISIBLE else View.GONE
                }

                Choice.Photos -> {
                    holder.label.setText(R.string.saver_photos)
                    holder.glyph.visibility = View.VISIBLE
                    holder.glyph.text = MdiIcons.glyph("folder-multiple-image")
                    holder.check.visibility =
                        if (prefs.screensaverMode == "photos") View.VISIBLE else View.GONE
                }

                Choice.Default -> {
                    holder.label.setText(R.string.wallpaper_default)
                    holder.image.setImageResource(R.drawable.dashboard_background)
                    holder.check.visibility =
                        if (prefs.dashboardBackground.isBlank()) View.VISIBLE else View.GONE
                }

                Choice.Link -> {
                    holder.label.setText(R.string.wallpaper_from_link)
                    holder.glyph.visibility = View.VISIBLE
                    holder.glyph.text = MdiIcons.glyph("link-variant")
                }

                is Choice.Custom -> {
                    holder.label.setText(
                        if (isVideo(choice.file)) R.string.wallpaper_custom_video
                        else R.string.wallpaper_custom_photo
                    )
                    holder.check.visibility =
                        if (currentSource() == LINK) View.VISIBLE else View.GONE
                    if (isVideo(choice.file)) {
                        holder.glyph.visibility = View.VISIBLE
                        holder.glyph.text = MdiIcons.glyph("filmstrip")
                    } else {
                        bindLocal(holder, choice.file)
                    }
                }

                is Choice.Vendor -> {
                    holder.label.text =
                        getString(R.string.wallpaper_numbered, choice.item.index + 1)
                    holder.check.visibility =
                        if (currentSource() == choice.item.entry) View.VISIBLE else View.GONE
                    bindVendor(holder, choice.item)
                }
            }
        }
    }

    /**
     * Les vignettes sont décodées hors du fil d'affichage, et vérifiées à l'arrivée.
     *
     * Le rang est posé sur la vue avant de lancer la lecture : une vignette qui revient
     * alors que la case a été recyclée pour une autre image doit être jetée, faute de
     * quoi la grille se peuple d'images à la mauvaise place dès qu'on la fait défiler.
     */
    private fun bindVendor(holder: Holder, item: VendorWallpapers.Item) {
        holder.image.tag = item.entry
        cache[item.entry]?.let { holder.image.setImageBitmap(it); return }
        thread(isDaemon = true) {
            val vignette = VendorWallpapers.thumbnail(this, item, THUMB_WIDTH)
            runOnUiThread {
                if (isFinishing || vignette == null) return@runOnUiThread
                cache[item.entry] = vignette
                if (holder.image.tag == item.entry) holder.image.setImageBitmap(vignette)
            }
        }
    }

    private fun bindLocal(holder: Holder, file: File) {
        holder.image.tag = file.absolutePath
        thread(isDaemon = true) {
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            val vignette = try {
                BitmapFactory.decodeFile(file.absolutePath, options)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing || vignette == null) return@runOnUiThread
                if (holder.image.tag == file.absolutePath) holder.image.setImageBitmap(vignette)
            }
        }
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.wallpaper_image)
        val label: TextView = view.findViewById(R.id.wallpaper_label)
        val check: TextView = view.findViewById(R.id.wallpaper_check)

        // La police d'icones se pose a la construction : un point de code MDI rendu
        // avec la police du systeme ne donne qu'un carre vide.
        val glyph: TextView = view.findViewById<TextView>(R.id.wallpaper_glyph).apply {
            typeface = MdiIcons.typeface()
        }
    }

    /** Les vignettes déjà décodées, pour que le défilement ne relise pas l'archive. */
    private val cache = HashMap<String, Bitmap>()

    companion object {
        /** Vrai pour régler le fond du tableau de bord, absent pour l'écran de veille. */
        const val EXTRA_DASHBOARD = "dashboard"

        /** Marque de provenance d'un fond telecharge. */
        const val LINK = "lien"

        /** Les copies locales, une par destination. */
        const val SAVER_FILE = "wallpaper_saver.png"
        const val DASHBOARD_FILE = "wallpaper_dashboard.png"

        private const val COLUMNS = 4
        private const val THUMB_WIDTH = 256

        fun open(activity: Activity, dashboard: Boolean) {
            activity.startActivity(
                Intent(activity, WallpaperPickerActivity::class.java)
                    .putExtra(EXTRA_DASHBOARD, dashboard)
            )
        }
    }
}
