package com.judit.hapanel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Grille de tuiles du tableau de bord principal (écran 1024x600). */
class TileAdapter(
    private val onTap: (position: Int) -> Unit
) : RecyclerView.Adapter<TileAdapter.TileHolder>() {

    private val items = ArrayList<Entity>()
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

    fun submit(newItems: List<Entity>) {
        items.clear()
        items.addAll(newItems)
        if (selected >= items.size) selected = items.size - 1
        if (selected < 0 && items.isNotEmpty()) selected = 0
        notifyDataSetChanged()
    }

    fun update(entity: Entity): Boolean {
        val idx = items.indexOfFirst { it.entityId == entity.entityId }
        if (idx < 0) return false
        items[idx] = entity
        notifyItemChanged(idx)
        return true
    }

    fun select(position: Int) {
        if (items.isEmpty()) return
        val clamped = position.coerceIn(0, items.size - 1)
        if (clamped == selected) return
        val previous = selected
        selected = clamped
        if (previous >= 0) notifyItemChanged(previous)
        notifyItemChanged(selected)
    }

    fun moveSelection(delta: Int) {
        if (items.isEmpty()) return
        val next = (selected + delta).let {
            when {
                it < 0 -> items.size - 1
                it >= items.size -> 0
                else -> it
            }
        }
        select(next)
    }

    fun selectedEntity(): Entity? = items.getOrNull(selected)

    fun entityAt(position: Int): Entity? = items.getOrNull(position)

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false)
        return TileHolder(v)
    }

    override fun onBindViewHolder(holder: TileHolder, position: Int) {
        val e = items[position]
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
    }

    class TileHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: TextView = view.findViewById(R.id.tile_icon)
        val name: TextView = view.findViewById(R.id.tile_name)
        val value: TextView = view.findViewById(R.id.tile_value)
        val level: ProgressBar = view.findViewById(R.id.tile_level)
        val watermark: TextView = view.findViewById(R.id.tile_watermark)
    }

    private companion object {
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
