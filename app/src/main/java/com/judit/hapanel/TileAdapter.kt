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
    private val onLongPress: (position: Int) -> Unit = {}
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

    fun submit(newItems: List<Entity>) {
        val ancienne = selectedEntity()?.entityId
        rows.clear()

        // Ordre des pièces : alphabétique, et celles sans pièce à la fin.
        val parPiece = newItems.groupBy { areas[it.entityId].orEmpty() }
        val pieces = parPiece.keys.filter { it.isNotEmpty() }.sorted()

        // Il faut au moins une pièce nommée, et au moins deux groupes à distinguer —
        // fût-ce une pièce et le reste. Un intertitre unique coiffant toute la grille
        // ne servirait à rien et mangerait une ligne.
        if (pieces.isEmpty() || parPiece.keys.size < 2) {
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
        return true
    }

    fun select(position: Int) {
        if (rows.isEmpty()) return
        // Un intertitre n'est pas sélectionnable : on ignore la demande plutôt que de
        // glisser vers un voisin, ce qui surprendrait au doigt.
        if (rows.getOrNull(position) !is Row.Cell) return
        if (position == selected) return
        val previous = selected
        selected = position
        if (previous >= 0) notifyItemChanged(previous)
        notifyItemChanged(selected)
    }

    /**
     * Déplace la sélection d'une tuile, en enjambant les intertitres et en bouclant aux
     * extrémités. Le bouton rotatif tourne sans fin : la sélection doit faire de même.
     */
    fun moveSelection(delta: Int) {
        if (delta == 0 || rows.none { it is Row.Cell }) return
        val pas = if (delta > 0) 1 else -1
        var position = if (selected >= 0) selected else firstCell()

        repeat(kotlin.math.abs(delta)) {
            do {
                position += pas
                if (position < 0) position = rows.size - 1
                if (position >= rows.size) position = 0
            } while (rows[position] !is Row.Cell)
        }
        select(position)
    }

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
            is Row.Header -> (holder as HeaderHolder).title.text = row.title
            is Row.Cell -> bindTile(holder as TileHolder, row.entity, position)
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
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.header_title)
    }

    private companion object {
        const val TYPE_TILE = 0
        const val TYPE_HEADER = 1

        /** Intertitre des entités auxquelles Home Assistant n'attribue aucune pièce. */
        const val AUTRES = "AUTRES"

        /**
         * Opacites du filigrane. Assez marque pour se deviner, assez discret pour ne
         * jamais disputer la lisibilite a la valeur affichee par-dessus.
         */
        const val WATERMARK_ON = 0.18f
        const val WATERMARK_OFF = 0.07f

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
