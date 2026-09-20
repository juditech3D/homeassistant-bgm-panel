package com.judit.hapanel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Grille de tuiles du tableau de bord principal (écran 1024x600).
 *
 * Les entités sont regroupées par pièce quand Home Assistant en déclare : chaque groupe
 * est précédé d'un intertitre qui occupe toute la largeur. Sans pièce connue, la grille
 * reste une simple suite de tuiles — un intertitre unique et creux n'apporterait rien.
 *
 * Les intertitres partagent la numérotation des tuiles, ce dont il faut tenir compte
 * partout : la sélection au bouton rotatif les enjambe, et `entityAt` rend null pour eux.
 */
class TileAdapter(
    private val onTap: (position: Int) -> Unit,
    private val onLongPress: (position: Int) -> Unit = {},
    private val onRoomTap: (room: String, entities: List<Entity>) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** Une ligne de la grille : soit un intertitre de pièce, soit une entité. */
    private sealed class Row {
        class Header(val title: String) : Row()
        class Cell(var entity: Entity) : Row()
    }

    private val rows = ArrayList<Row>()

    /**
     * Pièce de chaque entité, telle que Home Assistant la déclare. Renseignée en tâche
     * de fond par le tableau de bord ; vide, le regroupement ne s'applique pas.
     */
    var areas: Map<String, String> = emptyMap()

    var selected: Int = -1
        private set

    /**
     * Hauteur imposee aux tuiles, en pixels. Calculee par le tableau de bord d'apres la
     * place disponible : avec quatre entites on veut de grandes tuiles lisibles de loin,
     * avec vingt il en faut de plus petites. A zero, la hauteur du gabarit s'applique.
     */
    var tileHeight: Int = 0
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /** Nombre de tuiles, intertitres exclus. Sert au calcul de la disposition. */
    val tileCount: Int get() = rows.count { it is Row.Cell }

    fun isHeader(position: Int): Boolean = rows.getOrNull(position) is Row.Header

    /**
     * Effectif de la piece la plus fournie, ou le total si rien n'est regroupe.
     *
     * C'est lui qui commande la densite : une piece de six lampes doit les serrer sur
     * une ligne ou deux, pas les etaler sur trois rangees de tuiles enormes pendant que
     * les autres pieces attendent plus bas.
     */
    fun largestGroup(): Int {
        var maximum = 0
        var courant = 0
        rows.forEach { row ->
            when (row) {
                is Row.Header -> {
                    if (courant > maximum) maximum = courant
                    courant = 0
                }
                is Row.Cell -> courant++
            }
        }
        return maxOf(maximum, courant)
    }

    fun submit(newItems: List<Entity>) {
        val ancienne = selectedEntity()?.entityId
        rows.clear()

        // Ordre des pièces : alphabétique, et celles sans pièce à la fin.
        val parPiece = newItems.groupBy { areas[it.entityId].orEmpty() }
        val pieces = parPiece.keys.filter { it.isNotEmpty() }.sorted()

        // Une seule pièce nommée suffit désormais à regrouper. L'intertitre n'est plus
        // un simple titre : il compte ce qui est allumé et sert de commande — une tape,
        // ou un appui au bouton rotatif, éteint la pièce entière. Même seul, il gagne
        // largement la ligne qu'il occupe.
        if (pieces.isEmpty()) {
            newItems.forEach { rows.add(Row.Cell(it)) }
        } else {
            pieces.forEach { piece ->
                rows.add(Row.Header(piece.uppercase()))
                parPiece[piece].orEmpty().forEach { rows.add(Row.Cell(it)) }
            }
            parPiece[""].orEmpty().takeIf { it.isNotEmpty() }?.let { sansPiece ->
                rows.add(Row.Header(AUTRES))
                sansPiece.forEach { rows.add(Row.Cell(it)) }
            }
        }

        // La sélection suit l'entité, pas son rang : un regroupement change les rangs.
        selected = ancienne?.let { indexOfEntity(it) } ?: -1
        if (selected < 0) selected = firstCell()
        notifyDataSetChanged()
    }

    fun update(entity: Entity): Boolean {
        val idx = indexOfEntity(entity.entityId)
        if (idx < 0) return false
        (rows[idx] as Row.Cell).entity = entity
        notifyItemChanged(idx)
        // L'intertitre compte ce qui est allumé : sans ce rappel, il garderait le
        // compte d'avant et, pire, commanderait la pièce d'après un état périmé.
        headerOf(idx)?.let { notifyItemChanged(it) }
        return true
    }

    /** Rang de l'intertitre qui coiffe cette ligne, s'il y en a un. */
    private fun headerOf(position: Int): Int? {
        for (i in position downTo 0) if (rows[i] is Row.Header) return i
        return null
    }

    /**
     * Les entités que coiffe cet intertitre : tout ce qui suit jusqu'au suivant.
     *
     * Recalculé à chaque fois plutôt que mémorisé. Une liste figée à la construction
     * portait l'état des entités au moment du regroupement : l'intertitre annonçait
     * « 3 allumées » alors que tout était éteint, et renvoyait indéfiniment l'ordre
     * d'extinction. Bug réel, constaté sur le panneau.
     */
    private fun membersOf(headerPosition: Int): List<Entity> {
        val membres = ArrayList<Entity>()
        for (i in headerPosition + 1 until rows.size) {
            val row = rows[i]
            if (row is Row.Header) break
            if (row is Row.Cell) membres.add(row.entity)
        }
        return membres
    }

    fun select(position: Int) {
        if (rows.isEmpty()) return
        // Les intertitres sont sélectionnables au même titre que les tuiles : le bouton
        // rotatif doit pouvoir atteindre une pièce pour l'allumer ou l'éteindre entière.
        if (rows.getOrNull(position) == null) return
        if (position == selected) return
        val previous = selected
        selected = position
        if (previous >= 0) notifyItemChanged(previous)
        notifyItemChanged(selected)
    }

    /**
     * Déplace la sélection d'un rang, intertitres compris, en bouclant aux extrémités.
     * Le bouton rotatif tourne sans fin : la sélection doit faire de même.
     */
    fun moveSelection(delta: Int) {
        if (delta == 0 || rows.isEmpty()) return
        val pas = if (delta > 0) 1 else -1
        var position = if (selected >= 0) selected else 0

        repeat(kotlin.math.abs(delta)) {
            position += pas
            if (position < 0) position = rows.size - 1
            if (position >= rows.size) position = 0
        }
        select(position)
    }

    /** La pièce sélectionnée, si c'est un intertitre qui l'est. */
    fun selectedRoom(): Pair<String, List<Entity>>? =
        (rows.getOrNull(selected) as? Row.Header)?.let { it.title to membersOf(selected) }

    fun selectedEntity(): Entity? = (rows.getOrNull(selected) as? Row.Cell)?.entity

    fun entityAt(position: Int): Entity? = (rows.getOrNull(position) as? Row.Cell)?.entity

    private fun indexOfEntity(entityId: String): Int =
        rows.indexOfFirst { it is Row.Cell && it.entity.entityId == entityId }

    private fun firstCell(): Int = rows.indexOfFirst { it is Row.Cell }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_TILE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_tile_header, parent, false))
        } else {
            TileHolder(inflater.inflate(R.layout.item_tile, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> bindHeader(holder as HeaderHolder, row)
            is Row.Cell -> bindTile(holder as TileHolder, row.entity, position)
        }
    }

    /**
     * Intertitre de piece. Il compte ce qui est allume et sert de commande : une tape
     * eteint toute la piece, ou l'allume si tout est deja eteint.
     */
    private fun bindHeader(holder: HeaderHolder, row: Row.Header) {
        val position = holder.bindingAdapterPosition
        val membres = if (position >= 0) membersOf(position) else emptyList()
        val commandables = membres.filter { it.domain in COMMANDABLES }
        val allumees = commandables.count { it.isOn }

        holder.title.text = when {
            commandables.isEmpty() -> row.title
            allumees == 0 -> row.title
            else -> "${row.title}   ·   $allumees allumée${if (allumees > 1) "s" else ""}"
        }
        holder.title.isEnabled = commandables.isNotEmpty()
        // Sélectionné au bouton rotatif : l'intertitre s'accentue, comme une tuile.
        holder.title.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                holder.itemView.context,
                if (holder.bindingAdapterPosition == selected) R.color.accent
                else R.color.text_secondary
            )
        )
        // Toucher l'intertitre le **sélectionne**, comme une tuile : l'écran rond
        // affiche alors la pièce entière et l'appui sur le bouton rotatif l'allume ou
        // l'éteint. Basculer directement au doigt surprendrait — sur une tuile, toucher
        // ne fait jamais qu'élire.
        holder.title.setOnClickListener { onTap(holder.bindingAdapterPosition) }

        // L'appui long, lui, commande la pièce sans passer par le bouton : c'est le
        // geste de raccourci, pour qui préfère tout faire au doigt.
        holder.title.setOnLongClickListener {
            // Relu au moment du geste : entre l'affichage et le doigt, une lampe a pu
            // changer d'état depuis Home Assistant.
            val actuels = membersOf(holder.bindingAdapterPosition)
                .filter { it.domain in COMMANDABLES }
            if (actuels.isNotEmpty()) onRoomTap(row.title, actuels)
            true
        }
    }

    private fun bindTile(holder: TileHolder, e: Entity, position: Int) {
        holder.name.text = e.friendlyName
        holder.value.text = e.tileValue()

        holder.icon.typeface = MdiIcons.typeface()
        holder.icon.text = MdiIcons.glyphFor(e)

        // Filigrane de fond : la meme icone, en grand et en transparence, teintee selon
        // le domaine. Une entite allumee la montre un peu plus franchement -- l'etat se
        // lit alors a distance, avant meme de dechiffrer le texte.
        holder.watermark.typeface = MdiIcons.typeface()
        holder.watermark.text = MdiIcons.glyphFor(e)
        holder.watermark.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                holder.itemView.context, domainColor(e.domain)
            )
        )
        holder.watermark.alpha = if (e.isOn) WATERMARK_ON else WATERMARK_OFF

        // Seconde mesure de la meme sonde : l'humidite sous la temperature.
        val secondaire = e.attributes.optString(SensorMerge.SECONDARY)
        holder.secondary.visibility = if (secondaire.isEmpty()) View.GONE else View.VISIBLE
        holder.secondary.text = secondaire

        // Voile colore suivant la mesure. La teinte porte l'information avant meme que
        // le chiffre ne soit lu : une piece trop fraiche se repere de l'autre bout du
        // couloir. Tres transparent, pour ne pas ecraser la lisibilite du texte.
        val teinte = e.attributes.optInt(SensorMerge.TINT, 0)
        if (teinte == 0) {
            holder.tint.visibility = View.GONE
        } else {
            holder.tint.visibility = View.VISIBLE
            holder.tint.backgroundTintList = android.content.res.ColorStateList.valueOf(teinte)
            holder.tint.alpha = TINT_ALPHA
            holder.watermark.setTextColor(teinte)
            holder.value.setTextColor(teinte)
        }
        if (teinte == 0) holder.value.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                holder.itemView.context, R.color.tile_value
            )
        )

        // Barre de niveau : seulement sur une entite reglable **et allumee**. Sur une
        // entite binaire elle n'apprendrait rien, et sur une lampe eteinte elle ne
        // montrerait qu'un rail vide -- du bruit sur toutes les tuiles a la fois.
        if (e.adjustable != null && e.isOn) {
            holder.level.visibility = View.VISIBLE
            holder.level.progress = (e.normalisedValue() * 100).toInt()
        } else {
            holder.level.visibility = View.GONE
        }

        if (tileHeight > 0 && holder.itemView.layoutParams.height != tileHeight) {
            holder.itemView.layoutParams = holder.itemView.layoutParams.apply {
                height = tileHeight
            }
        }

        holder.itemView.isSelected = position == selected
        // Propagé aux enfants par la hiérarchie de vues : c'est ce qui colore l'icône.
        holder.itemView.isActivated = e.isOn
        holder.itemView.setOnClickListener { onTap(holder.bindingAdapterPosition) }
        // L'appui long range l'entite dans une piece. C'est le seul autre geste utile
        // sur une tuile, et il n'y a pas la place pour un bouton par case.
        holder.itemView.setOnLongClickListener {
            onLongPress(holder.bindingAdapterPosition)
            true
        }
    }

    class TileHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: TextView = view.findViewById(R.id.tile_icon)
        val name: TextView = view.findViewById(R.id.tile_name)
        val value: TextView = view.findViewById(R.id.tile_value)
        val level: ProgressBar = view.findViewById(R.id.tile_level)
        val watermark: TextView = view.findViewById(R.id.tile_watermark)
        val secondary: TextView = view.findViewById(R.id.tile_secondary)
        val tint: View = view.findViewById(R.id.tile_tint)
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.header_title)
    }

    private companion object {
        const val TYPE_TILE = 0
        const val TYPE_HEADER = 1

        /** Intertitre des entités auxquelles Home Assistant n'attribue aucune pièce. */
        const val AUTRES = "AUTRES"

        /** Domaines qu'une commande de pièce peut allumer ou éteindre. */
        val COMMANDABLES = setOf("light", "switch", "fan", "media_player")

        /**
         * Opacites du filigrane. Assez marque pour se deviner, assez discret pour ne
         * jamais disputer la lisibilite a la valeur affichee par-dessus.
         */
        const val WATERMARK_ON = 0.18f
        const val WATERMARK_OFF = 0.07f

        /** Opacite du voile colore : assez pour teinter, trop peu pour genner la lecture. */
        const val TINT_ALPHA = 0.16f

        /** Teinte du filigrane, par domaine Home Assistant. */
        fun domainColor(domain: String): Int = when (domain) {
            "light" -> R.color.domain_light
            "cover", "fan" -> R.color.domain_cover
            "climate", "water_heater" -> R.color.domain_climate
            "media_player", Entity.PANEL_DOMAIN -> R.color.domain_media
            "sensor", "binary_sensor", "weather" -> R.color.domain_sensor
            "lock", "alarm_control_panel", "camera" -> R.color.domain_security
            else -> R.color.domain_default
        }
    }
}
