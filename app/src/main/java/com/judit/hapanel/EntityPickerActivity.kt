package com.judit.hapanel

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.concurrent.thread

/**
 * Sélecteur d'entités : liste tout ce que Home Assistant expose, avec une case à
 * cocher par entité et un filtre de recherche.
 *
 * Saisir des entity_id à la main sur une dalle tactile est pénible et source
 * d'erreurs ; cet écran évite complètement la frappe.
 *
 * Les entités sont récupérées en REST, sans WebSocket : on n'a pas besoin du temps
 * réel ici, seulement de la liste.
 */
class EntityPickerActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var adapter: PickerAdapter
    private lateinit var statusView: TextView
    private lateinit var countView: TextView

    private val ui = Handler(Looper.getMainLooper())

    /** Toutes les entités reçues, dans l'ordre d'affichage. */
    private var all: List<Entity> = emptyList()

    /** entity_id → nom de pièce. Vide si Home Assistant n'en définit aucune. */
    private var areaOf: Map<String, String> = emptyMap()

    /** Pièces proposées dans le menu déroulant, dans l'ordre affiché. */
    private var areaChoices: List<String?> = listOf(null)

    private var selectedArea: String? = null

    /** entity_id retenus, dans l'ordre choisi par l'utilisateur. */
    private val chosen = LinkedHashSet<String>()

    // La langue choisie dans les reglages s'impose avant que la moindre ressource soit
    // lue : posee plus tard, elle laisserait les textes deja resolus dans l'ancienne.
    override fun attachBaseContext(base: android.content.Context) {
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
        prefs = Prefs(this)
        setContentView(R.layout.activity_picker)

        statusView = findViewById(R.id.picker_status)
        countView = findViewById(R.id.picker_count)

        chosen.addAll(
            prefs.pinned.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        )

        adapter = PickerAdapter(
            isChecked = { chosen.contains(it) },
            areaOf = { areaOf[it] },
            onToggle = { entityId, checked ->
                if (checked) chosen.add(entityId) else chosen.remove(entityId)
                updateCount()
            }
        )
        findViewById<RecyclerView>(R.id.picker_list).also {
            it.layoutManager = LinearLayoutManager(this)
            it.adapter = adapter
        }

        findViewById<EditText>(R.id.picker_search).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        findViewById<Button>(R.id.picker_select_shown).setOnClickListener {
            chosen.addAll(adapter.shownIds())
            adapter.notifyDataSetChanged()
            updateCount()
        }

        findViewById<Spinner>(R.id.picker_area).onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    selectedArea = areaChoices.getOrNull(pos)
                    applyFilter(findViewById<EditText>(R.id.picker_search).text.toString())
                }

                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }

        findViewById<Button>(R.id.picker_save).setOnClickListener { save() }
        findViewById<Button>(R.id.picker_clear).setOnClickListener {
            chosen.clear()
            adapter.notifyDataSetChanged()
            updateCount()
        }
        findViewById<Button>(R.id.picker_cancel).setOnClickListener { finish() }

        load()
    }

    private fun load() {
        statusView.text = getString(R.string.picker_loading)
        statusView.visibility = View.VISIBLE
        thread {
            try {
                // Les pièces sont facultatives : si Home Assistant n'en définit pas,
                // ou si l'appel échoue, le sélecteur reste utilisable sans ce filtre.
                val areas = HaClient.fetchAreas(prefs)
                val list = HaClient.fetchStates(prefs)
                    .filter { it.state != "unavailable" }
                    .sortedWith(
                        compareBy(
                            { Entity.INTERESTING.indexOf(it.domain).let { i -> if (i < 0) 99 else i } },
                            { it.domain },
                            { it.friendlyName.lowercase() }
                        )
                    )
                ui.post {
                    all = list
                    areaOf = areas
                    buildAreaChoices()
                    statusView.visibility = View.GONE
                    applyFilter("")
                    updateCount()
                }
            } catch (e: Exception) {
                ui.post {
                    statusView.text = getString(R.string.picker_error, e.message ?: "inconnue")
                    statusView.visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * Remplit le menu des pièces : « Toutes », puis chaque pièce par ordre alphabétique,
     * puis « Sans pièce » s'il existe des entités non rangées.
     */
    private fun buildAreaChoices() {
        val spinner = findViewById<Spinner>(R.id.picker_area)
        val names = areaOf.values.distinct().sorted()
        val hasOrphans = all.any { areaOf[it.entityId] == null }

        val choices = ArrayList<String?>()
        val labels = ArrayList<String>()
        choices.add(null); labels.add(getString(R.string.picker_all_areas))
        names.forEach { choices.add(it); labels.add(it) }
        if (hasOrphans) {
            choices.add(NO_AREA); labels.add(getString(R.string.picker_no_area))
        }

        areaChoices = choices
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        spinner.isEnabled = names.isNotEmpty()
        if (names.isEmpty()) {
            Toast.makeText(this, R.string.picker_no_areas_found, Toast.LENGTH_LONG).show()
        }
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        val area = selectedArea

        val filtered = all
            .filter { e ->
                when (area) {
                    null -> true
                    NO_AREA -> areaOf[e.entityId] == null
                    else -> areaOf[e.entityId] == area
                }
            }
            .filter { e ->
                q.isEmpty() ||
                    e.friendlyName.lowercase().contains(q) ||
                    e.entityId.lowercase().contains(q)
            }

        adapter.submit(filtered)
    }

    private fun updateCount() {
        countView.text = getString(R.string.picker_count, chosen.size, all.size)
    }

    private fun save() {
        // On conserve l'ordre d'affichage plutôt que l'ordre de cochage : c'est plus
        // prévisible pour retrouver ses tuiles sur le tableau de bord.
        val ordered = all.map { it.entityId }.filter { chosen.contains(it) }
        prefs.pinned = ordered.joinToString(",")
        Toast.makeText(
            this,
            if (ordered.isEmpty()) getString(R.string.picker_saved_auto)
            else getString(R.string.picker_saved, ordered.size),
            Toast.LENGTH_LONG
        ).show()
        finish()
    }

    // ------------------------------------------------------------------ adaptateur

    private class PickerAdapter(
        val isChecked: (String) -> Boolean,
        val areaOf: (String) -> String?,
        val onToggle: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<PickerAdapter.Holder>() {

        private val items = ArrayList<Entity>()

        fun submit(list: List<Entity>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        /** Les entity_id actuellement visibles, pour le bouton « Cocher la liste ». */
        fun shownIds(): List<String> = items.map { it.entityId }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_entity, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = items[position]
            holder.name.text = e.friendlyName
            val area = areaOf(e.entityId)
            holder.id.text = buildString {
                if (area != null) append(area).append("  ·  ")
                append(e.entityId).append("  ·  ").append(e.state)
            }
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = isChecked(e.entityId)
            holder.check.setOnCheckedChangeListener { _, checked -> onToggle(e.entityId, checked) }
            holder.itemView.setOnClickListener { holder.check.toggle() }
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.entity_name)
            val id: TextView = v.findViewById(R.id.entity_id)
            val check: android.widget.CheckBox = v.findViewById(R.id.entity_check)
        }
    }

    private companion object {
        /** Sentinelle pour l'entrée « Sans pièce » du menu déroulant. */
        const val NO_AREA = " sans-piece"
    }
}
