package com.judit.hapanel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Carte de lecture multiroom, à droite du tableau de bord.
 *
 * Ce panneau est d'abord une centrale de sonorisation : la musique y mérite une place à
 * demeure plutôt qu'une tuile perdue au milieu des lampes. La carte montre ce qui joue,
 * commande la lecture et le volume, et permet de passer d'une pièce à l'autre d'un doigt
 * — c'est tout l'intérêt du multiroom.
 *
 * Elle se masque entièrement quand le serveur n'expose aucun lecteur : mieux vaut rendre
 * la largeur aux tuiles que d'afficher une carte vide.
 */
class MediaCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** Appelé pour toute commande à transmettre à Home Assistant. */
    var onService: ((service: String, entityId: String, data: JSONObject?) -> Unit)? = null

    /**
     * Appelé quand on touche la carte ailleurs que sur une commande : le tableau de bord
     * confie alors le lecteur au bouton rotatif, qui en règle le volume.
     */
    var onFocusRequest: ((Entity) -> Unit)? = null

    private val artwork: ImageView
    private val placeholder: TextView
    private val track: TextView
    private val artist: TextView
    private val playButton: TextView
    private val previousButton: TextView
    private val nextButton: TextView
    private val volumeIcon: TextView
    private val volume: SeekBar
    private val rooms: LinearLayout

    private var players: List<Entity> = emptyList()

    /** Le lecteur commandé. Null = on suit celui qui joue. */
    private var chosen: String? = null

    /** L'image affichée, pour ne pas la retélécharger à chaque rafraîchissement. */
    private var artworkUrl: String? = null

    /** L'utilisateur tient le curseur : on cesse d'écraser sa valeur. */
    private var draggingVolume = false

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_media_card, this, true)

        artwork = findViewById(R.id.media_art)
        placeholder = findViewById(R.id.media_placeholder)
        track = findViewById(R.id.media_track)
        artist = findViewById(R.id.media_artist)
        playButton = findViewById(R.id.media_play)
        previousButton = findViewById(R.id.media_previous)
        nextButton = findViewById(R.id.media_next)
        volumeIcon = findViewById(R.id.media_volume_icon)
        volume = findViewById(R.id.media_volume)
        rooms = findViewById(R.id.media_rooms)

        listOf(placeholder, playButton, previousButton, nextButton, volumeIcon).forEach {
            it.typeface = MdiIcons.typeface()
        }
        placeholder.text = MdiIcons.glyph("music")
        previousButton.text = MdiIcons.glyph("skip-previous")
        nextButton.text = MdiIcons.glyph("skip-next")
        volumeIcon.text = MdiIcons.glyph("volume-medium")

        previousButton.setOnClickListener { command("media_previous_track") }
        nextButton.setOnClickListener { command("media_next_track") }
        playButton.setOnClickListener { command("media_play_pause") }

        // Toucher la carte — hors des boutons, qui consomment leur propre appui —
        // confie le lecteur au bouton rotatif. C'est le même geste que sur une tuile :
        // toucher élit, le bouton règle.
        setOnClickListener { current()?.let { onFocusRequest?.invoke(it) } }

        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) = Unit

            override fun onStartTrackingTouch(bar: SeekBar?) {
                draggingVolume = true
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                draggingVolume = false
                val cible = current() ?: return
                onService?.invoke(
                    "volume_set", cible.entityId,
                    JSONObject().put("volume_level", (bar?.progress ?: 0) / 100.0)
                )
            }
        })
    }

    /**
     * Reçoit l'état courant des lecteurs.
     *
     * Retourne false quand il n'y en a aucun, ce qui invite l'appelant à masquer la
     * carte. Les lecteurs indisponibles sont écartés : un renderer éteint ne se commande
     * pas, et l'afficher laisserait croire à une panne.
     */
    fun bind(allEntities: List<Entity>): Boolean {
        players = allEntities
            .filter { it.domain == "media_player" }
            .filter { it.state != "unavailable" && it.state != "unknown" }
            .sortedBy { it.friendlyName.lowercase() }

        if (players.isEmpty()) return false

        val lecteur = current() ?: return false
        showTrack(lecteur)
        showTransport(lecteur)
        showVolume(lecteur)
        showRooms()
        return true
    }

    /**
     * Le lecteur à commander : celui choisi à la main, sinon celui qui joue, sinon le
     * premier. Suivre automatiquement la lecture évite d'avoir à désigner la pièce quand
     * la musique démarre ailleurs.
     */
    private fun current(): Entity? {
        chosen?.let { id -> players.firstOrNull { it.entityId == id }?.let { return it } }
        return players.firstOrNull { it.state == "playing" } ?: players.firstOrNull()
    }

    private fun showTrack(lecteur: Entity) {
        val titre = lecteur.attributes.optString("media_title")
        val interprete = lecteur.attributes.optString("media_artist")
            .ifEmpty { lecteur.attributes.optString("media_album_name") }

        track.text = titre.ifEmpty {
            if (lecteur.state == "playing") lecteur.friendlyName
            else context.getString(R.string.media_nothing)
        }
        artist.text = interprete.ifEmpty {
            if (titre.isEmpty()) lecteur.friendlyName
            else lecteur.friendlyName
        }
        loadArtwork(lecteur.attributes.optString("entity_picture"))
    }

    private fun showTransport(lecteur: Entity) {
        val joue = lecteur.state == "playing"
        playButton.text = MdiIcons.glyph(if (joue) "pause-circle" else "play-circle")
    }

    private fun showVolume(lecteur: Entity) {
        if (draggingVolume) return
        val niveau = lecteur.attributes.optDouble("volume_level", -1.0)
        if (niveau < 0) {
            volume.isEnabled = false
            return
        }
        volume.isEnabled = true
        volume.progress = (niveau * 100).toInt()
        volumeIcon.text = MdiIcons.glyph(
            when {
                lecteur.attributes.optBoolean("is_volume_muted") -> "volume-off"
                niveau < 0.34 -> "volume-low"
                niveau < 0.67 -> "volume-medium"
                else -> "volume-high"
            }
        )
    }

    /**
     * Reconstruit les pastilles de pièces.
     *
     * Reconstruction complète plutôt que mise à jour : la liste des lecteurs change
     * rarement, et un rapprochement fin entre vues et entités coûterait plus de code
     * qu'il n'en économiserait.
     */
    private fun showRooms() {
        val actif = current()?.entityId
        if (rooms.childCount != players.size) rooms.removeAllViews()

        players.forEachIndexed { index, lecteur ->
            val chip = rooms.getChildAt(index) as? TextView ?: TextView(context).apply {
                setBackgroundResource(R.drawable.room_chip)
                setPadding(PADDING_H, PADDING_V, PADDING_H, PADDING_V)
                textSize = 12f
                maxLines = 1
                layoutParams = LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = PADDING_V }
                rooms.addView(this)
            }
            chip.text = lecteur.friendlyName
            chip.isSelected = lecteur.entityId == actif
            chip.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    context,
                    if (chip.isSelected) R.color.text_primary else R.color.text_secondary
                )
            )
            chip.setOnClickListener {
                // Un choix explicite fige la pièce : sans cela, le lecteur qui démarre
                // ailleurs reprendrait la main sous le doigt de l'utilisateur.
                chosen = lecteur.entityId
                bind(playersPlusOthers())
            }
        }
    }

    /** La liste courante, pour un rafraîchissement immédiat après un choix de pièce. */
    private fun playersPlusOthers(): List<Entity> = players

    private fun command(service: String) {
        val cible = current() ?: return
        onService?.invoke(service, cible.entityId, null)
    }

    /**
     * Télécharge la pochette.
     *
     * L'URL fournie par Home Assistant est relative et signée : elle porte son propre
     * jeton, il n'y a donc pas d'en-tête d'authentification à ajouter. On ne retélécharge
     * pas une image déjà affichée — le tableau de bord se rafraîchit à chaque changement
     * d'état, ce qui ferait une requête par seconde sur un morceau en cours.
     */
    private fun loadArtwork(chemin: String) {
        if (chemin.isEmpty()) {
            artworkUrl = null
            artwork.visibility = View.GONE
            placeholder.visibility = View.VISIBLE
            return
        }
        if (chemin == artworkUrl) return
        artworkUrl = chemin

        val prefs = Prefs(context)
        val url = if (chemin.startsWith("http")) chemin else prefs.httpBase() + chemin

        thread(isDaemon = true) {
            val image = try {
                (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 5000
                    readTimeout = 5000
                    // Les URL non signées exigent le jeton ; les signées l'ignorent.
                    if (prefs.token.isNotEmpty()) {
                        setRequestProperty("Authorization", "Bearer ${prefs.token}")
                    }
                    inputStream.use { flux ->
                        BitmapFactory.decodeStream(
                            flux, null,
                            BitmapFactory.Options().apply {
                                // L'espace disponible est modeste : décoder en pleine
                                // résolution gâcherait la mémoire d'un panneau de 2 Go.
                                inSampleSize = 2
                                inPreferredConfig = Bitmap.Config.RGB_565
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "pochette illisible : ${e.message}")
                null
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "pochette trop lourde")
                null
            }

            post {
                // L'utilisateur a pu changer de morceau pendant le téléchargement.
                if (artworkUrl != chemin) return@post
                if (image == null) {
                    artwork.visibility = View.GONE
                    placeholder.visibility = View.VISIBLE
                } else {
                    artwork.setImageBitmap(image)
                    artwork.visibility = View.VISIBLE
                    placeholder.visibility = View.GONE
                }
            }
        }
    }

    private companion object {
        const val TAG = "HaPanelMedia"
        const val PADDING_H = 26
        const val PADDING_V = 12
    }
}
