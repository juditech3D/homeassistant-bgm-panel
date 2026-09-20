package com.judit.hapanel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import kotlin.concurrent.thread

/**
 * L'historique des coups de sonnette.
 *
 * Une grille de captures, de la plus récente à la plus ancienne, chacune portant sa
 * date. On y vient pour répondre à une question simple — qui a sonné pendant mon
 * absence — donc l'ordre est l'ordre du temps, et rien d'autre ne s'interpose.
 *
 * Trois façons de supprimer, parce qu'elles répondent à trois besoins distincts :
 *
 * - la **croix** d'une vignette, pour écarter une image au passage ;
 * - les **cases à cocher**, pour faire le ménage sur plusieurs d'un coup ;
 * - **tout supprimer**, quand on veut repartir de zéro.
 *
 * Toutes passent par une confirmation qui nomme ce qui va disparaître. Une suppression
 * de fichier ne se rattrape pas, et le panneau est tactile : un doigt s'égare vite.
 */
class HistoryActivity : AppCompatActivity() {

    private val prefs by lazy { Prefs(this) }

    private lateinit var grid: RecyclerView
    private lateinit var subtitle: TextView
    private lateinit var deleteSelection: View
    private lateinit var selectAll: View
    private lateinit var deleteTests: View
    private lateinit var empty: TextView
    private lateinit var dayFilter: Spinner

    /** Ce qui est affiche : toutes les captures, ou celles d'un seul jour. */
    private var entries: List<File> = emptyList()

    /** Les jours proposes par le filtre. L'index 0 vaut toujours « tous ». */
    private var days: List<String> = listOf("")

    /** Le jour retenu, vide pour tous. */
    private var day: String = ""

    /** Les captures cochées, par chemin : une vue recyclée ne doit pas perdre l'état. */
    private val selected = HashSet<String>()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
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
        setContentView(R.layout.activity_history)

        subtitle = findViewById(R.id.history_subtitle)
        empty = findViewById(R.id.history_empty)
        grid = findViewById(R.id.history_grid)
        grid.layoutManager = GridLayoutManager(this, COLUMNS)
        grid.adapter = Adapter()

        findViewById<View>(R.id.back_button).setOnClickListener { finish() }

        // Ouverte depuis l'alerte des cameras, la galerie arrive deja filtree sur le
        // jour qu'on vient de lire : on cherche alors qui a sonne aujourd'hui, pas
        // l'historique entier.
        day = intent.getStringExtra(EXTRA_DAY).orEmpty()

        dayFilter = findViewById(R.id.history_day)
        buildDayFilter()

        selectAll = findViewById<View>(R.id.history_select_all).apply {
            setOnClickListener { toggleAll() }
        }
        deleteSelection = findViewById<View>(R.id.history_delete_selection).apply {
            setOnClickListener { confirmDelete(entries.filter { it.path in selected }) }
        }
        // Porte sur ce qui est affiche : filtre sur un jour, « tout supprimer » ne doit
        // pas emporter le reste de l'historique sans prevenir.
        deleteTests = findViewById<View>(R.id.history_delete_tests).apply {
            setOnClickListener {
                confirmDelete(entries.filter { DoorbellHistory.isTest(it) })
            }
        }
        // Porte sur ce qui est affiche : filtre sur un jour, « tout supprimer » ne doit
        // pas emporter le reste de l'historique sans prevenir.
        findViewById<View>(R.id.history_delete_all).setOnClickListener {
            confirmDelete(entries)
        }

        reload()

        // Ouvrir la galerie vaut consultation : la pastille s'eteint, et ne se
        // rallumera qu'au prochain coup de sonnette.
        DoorbellHistory.markAllSeen(prefs)
    }

    /**
     * Remplit la liste des jours.
     *
     * L'ecouteur est pose apres la selection initiale : sans cela, le choix par defaut
     * passerait pour un geste de l'utilisateur et declencherait un rechargement inutile
     * a chaque ouverture.
     */
    private fun buildDayFilter() {
        days = listOf("") + DoorbellHistory.days(prefs)
        dayFilter.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.history_all_days)) +
                days.drop(1).map { DoorbellHistory.dayLabel(this, it) }
        )
        dayFilter.setSelection(days.indexOf(day).coerceAtLeast(0))
        dayFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val choisi = days.getOrElse(pos) { "" }
                if (choisi == day) return
                day = choisi
                reload()
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
    }

    private fun reload() {
        val toutes = DoorbellHistory.entries(prefs)
        entries = if (day.isBlank()) toutes
        else toutes.filter { DoorbellHistory.dayOf(it) == day }
        selected.retainAll(entries.map { it.path }.toSet())

        val vide = entries.isEmpty()
        empty.visibility = if (vide) View.VISIBLE else View.GONE
        grid.visibility = if (vide) View.GONE else View.VISIBLE
        selectAll.visibility = if (vide) View.GONE else View.VISIBLE
        // Le bouton n'apparait que s'il y a des essais a effacer : proposer un menage
        // sans rien a nettoyer ne ferait qu'encombrer le bandeau.
        deleteTests.visibility =
            if (entries.any { DoorbellHistory.isTest(it) }) View.VISIBLE else View.GONE

        subtitle.text = getString(
            R.string.history_count, entries.size, DoorbellHistory.freeMegabytes(prefs)
        )
        refreshSelectionButton()
        grid.adapter?.notifyDataSetChanged()
    }

    private fun toggleAll() {
        if (selected.size == entries.size) selected.clear()
        else entries.forEach { selected.add(it.path) }
        refreshSelectionButton()
        grid.adapter?.notifyDataSetChanged()
    }

    private fun refreshSelectionButton() {
        deleteSelection.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        (deleteSelection as? TextView)?.text =
            getString(R.string.history_delete_selected, selected.size)
    }

    /**
     * Demande confirmation avant de supprimer, en nommant ce qui va partir.
     *
     * Une seule capture est nommée par sa date ; au-delà, on annonce le nombre. Lister
     * quarante dates dans une boîte de dialogue ne renseignerait personne.
     */
    private fun confirmDelete(cibles: List<File>) {
        if (cibles.isEmpty()) return
        val message = if (cibles.size == 1) {
            getString(R.string.history_confirm_one, DoorbellHistory.labelOf(this, cibles[0]))
        } else {
            getString(R.string.history_confirm_many, cibles.size)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.history_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.history_delete) { _, _ ->
                cibles.forEach {
                    it.delete()
                    selected.remove(it.path)
                }
                // Un jour vide n'a plus a figurer dans le filtre ; si c'etait celui
                // qu'on regardait, on revient a la vue complete.
                if (day.isNotBlank() && DoorbellHistory.countOn(prefs, day) == 0) day = ""
                buildDayFilter()
                reload()
            }
            .setNegativeButton(R.string.picker_cancel, null)
            .show()
    }

    /** Une capture en grand, pour reconnaître un visage qu'une vignette ne montre pas. */
    private fun showFull(fichier: File) {
        val vue = ImageView(this).apply {
            adjustViewBounds = true
            setBackgroundColor(getColor(R.color.background))
        }
        val dialogue = AlertDialog.Builder(this)
            .setTitle(DoorbellHistory.labelOf(this, fichier))
            .setView(vue)
            .setNegativeButton(R.string.back, null)
            .setPositiveButton(R.string.history_delete) { _, _ -> confirmDelete(listOf(fichier)) }
            .show()

        thread(isDaemon = true) {
            val image = decode(fichier, 2)
            runOnUiThread {
                if (isFinishing || image == null) return@runOnUiThread
                if (dialogue.isShowing) vue.setImageBitmap(image)
            }
        }
    }

    private fun decode(fichier: File, echelle: Int) = try {
        BitmapFactory.decodeFile(
            fichier.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = echelle }
        )
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------ grille

    private inner class Adapter : RecyclerView.Adapter<Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        )

        override fun getItemCount() = entries.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val fichier = entries[position]
            holder.label.text = DoorbellHistory.labelOf(this@HistoryActivity, fichier)
            holder.image.setImageBitmap(null)
            holder.image.tag = fichier.path
            holder.image.setOnClickListener { showFull(fichier) }

            holder.remove.setOnClickListener { confirmDelete(listOf(fichier)) }
            holder.test.visibility =
                if (DoorbellHistory.isTest(fichier)) View.VISIBLE else View.GONE

            // L'écouteur est retiré avant de poser l'état : sans cela, le recyclage
            // d'une vue cocherait la capture précédente en silence.
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = fichier.path in selected
            holder.check.setOnCheckedChangeListener { _, coche ->
                if (coche) selected.add(fichier.path) else selected.remove(fichier.path)
                refreshSelectionButton()
            }

            thread(isDaemon = true) {
                val vignette = decode(fichier, 4)
                runOnUiThread {
                    if (isFinishing || vignette == null) return@runOnUiThread
                    if (holder.image.tag == fichier.path) holder.image.setImageBitmap(vignette)
                }
            }
        }
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.history_image)
        val label: TextView = view.findViewById(R.id.history_label)
        val check: CheckBox = view.findViewById(R.id.history_check)
        val test: TextView = view.findViewById(R.id.history_test)
        val remove: TextView = view.findViewById<TextView>(R.id.history_remove).apply {
            typeface = MdiIcons.typeface()
            text = MdiIcons.glyph("close")
        }
    }

    companion object {
        private const val COLUMNS = 3

        /** Le jour sur lequel ouvrir la galerie, au format `AAAA-MM-JJ`. */
        const val EXTRA_DAY = "day"

        fun open(activity: Activity, day: String = "") {
            activity.startActivity(
                Intent(activity, HistoryActivity::class.java).putExtra(EXTRA_DAY, day)
            )
        }
    }
}
