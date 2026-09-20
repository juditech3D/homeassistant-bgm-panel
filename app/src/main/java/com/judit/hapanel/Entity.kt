package com.judit.hapanel

import android.content.Context
import org.json.JSONObject

/** Une entité Home Assistant, réduite à ce dont le panneau a besoin. */
data class Entity(
    val entityId: String,
    val state: String,
    val attributes: JSONObject
) {
    val domain: String get() = entityId.substringBefore('.')

    val friendlyName: String
        get() = attributes.optString("friendly_name").ifEmpty { entityId.substringAfter('.') }

    val isOn: Boolean get() = state == "on" || state == "open" || state == "playing"

    /**
     * Le type de réglage que le bouton rotatif peut ajuster sur cette entité,
     * ou null si elle n'est que binaire.
     */
    val adjustable: Adjustable?
        get() = when (domain) {
            "light" -> if (attributes.has("brightness")) Adjustable.BRIGHTNESS else null
            "climate" -> Adjustable.TEMPERATURE
            "media_player" -> if (attributes.has("volume_level")) Adjustable.VOLUME else null
            "cover" -> Adjustable.POSITION
            "fan" -> Adjustable.PERCENTAGE
            // Entités locales : seule celle du volume porte un niveau. L'assistant et
            // le micro sont binaires — les déclarer réglables affichait une barre de
            // niveau sur leur tuile, et le bouton rotatif prétendait les ajuster.
            PANEL_DOMAIN -> if (attributes.has("volume_level")) Adjustable.VOLUME else null
            else -> null
        }

    /** Valeur courante ramenée sur 0..1, pour l'arc de l'écran rond. */
    fun normalisedValue(): Float = when (adjustable) {
        Adjustable.BRIGHTNESS -> attributes.optInt("brightness", 0) / 255f
        Adjustable.VOLUME -> attributes.optDouble("volume_level", 0.0).toFloat()
        Adjustable.POSITION -> attributes.optInt("current_position", 0) / 100f
        Adjustable.PERCENTAGE -> attributes.optInt("percentage", 0) / 100f
        Adjustable.TEMPERATURE -> {
            val min = attributes.optDouble("min_temp", 7.0)
            val max = attributes.optDouble("max_temp", 35.0)
            val cur = attributes.optDouble("temperature", min)
            if (max > min) ((cur - min) / (max - min)).toFloat() else 0f
        }
        null -> if (isOn) 1f else 0f
    }.coerceIn(0f, 1f)

    /** Texte court affiché au centre de l'écran rond. */
    fun displayValue(): String = when (adjustable) {
        Adjustable.BRIGHTNESS -> "${(normalisedValue() * 100).toInt()}%"
        Adjustable.VOLUME -> "${(normalisedValue() * 100).toInt()}%"
        Adjustable.POSITION -> "${attributes.optInt("current_position", 0)}%"
        Adjustable.PERCENTAGE -> "${attributes.optInt("percentage", 0)}%"
        Adjustable.TEMPERATURE -> String.format("%.1f°", attributes.optDouble("temperature", 0.0))
        null -> if (isOn) "ON" else "OFF"
    }

    /** Texte affiché sur la tuile du tableau de bord. */
    fun tileValue(): String {
        // Les tuiles locales portent leur propre libellé, souvent plus parlant qu'un
        // état brut : « J'écoute », « COUPÉ »…
        attributes.optString("panel_label").let { if (it.isNotEmpty()) return it }

        attributes.optString("unit_of_measurement").let { unit ->
            if (unit.isNotEmpty()) return "$state $unit"
        }
        return when (adjustable) {
            Adjustable.BRIGHTNESS, Adjustable.VOLUME, Adjustable.POSITION, Adjustable.PERCENTAGE ->
                if (isOn) displayValue() else "OFF"
            Adjustable.TEMPERATURE -> displayValue()
            null -> state.uppercase()
        }
    }

    enum class Adjustable { BRIGHTNESS, TEMPERATURE, VOLUME, POSITION, PERCENTAGE }

    companion object {
        /** Domaine réservé aux entités locales, qui n'existent pas dans Home Assistant. */
        const val PANEL_DOMAIN = "panneau"

        /** La tuile de volume de l'amplificateur intégré. */
        const val PANEL_VOLUME_ID = "$PANEL_DOMAIN.volume"

        /** La tuile de l'assistant vocal. */
        const val PANEL_ASSISTANT_ID = "$PANEL_DOMAIN.assistant"

        /** La tuile du mode privé : micro autorisé ou coupé. */
        const val PANEL_MIC_ID = "$PANEL_DOMAIN.micro"

        /**
         * Tuile de l'assistant vocal. L'icône et le libellé suivent l'étape en cours,
         * pour qu'on sache d'un coup d'œil si le micro est ouvert.
         */
        fun panelAssistant(
            context: Context,
            state: VoiceAssistant.State,
            available: Boolean
        ): Entity {
            val (icon, label) = when {
                !available -> "microphone-off" to context.getString(R.string.assistant_unavailable)
                state == VoiceAssistant.State.LISTENING ->
                    "microphone" to context.getString(R.string.assistant_listening)
                state == VoiceAssistant.State.THINKING -> "dots-horizontal" to "…"
                state == VoiceAssistant.State.SPEAKING ->
                    "account-voice" to context.getString(R.string.assistant_answering)
                else -> "microphone-outline" to context.getString(R.string.assistant_speak)
            }
            return Entity(
                entityId = PANEL_ASSISTANT_ID,
                state = if (state != VoiceAssistant.State.IDLE) "on" else "off",
                attributes = JSONObject()
                    .put("friendly_name", context.getString(R.string.assistant_name))
                    .put("icon", "mdi:$icon")
                    .put("panel_label", label)
            )
        }

        /** Tuile du mode privé. Allumée = micro autorisé. */
        fun panelMicrophone(context: Context, enabled: Boolean): Entity = Entity(
            entityId = PANEL_MIC_ID,
            state = if (enabled) "on" else "off",
            attributes = JSONObject()
                .put(
                    "friendly_name",
                    context.getString(if (enabled) R.string.mic_on_name else R.string.mic_off_name)
                )
                .put("icon", if (enabled) "mdi:microphone" else "mdi:microphone-off")
                .put(
                    "panel_label",
                    context.getString(if (enabled) R.string.mic_on_state else R.string.mic_off_state)
                )
        )

        /**
         * Construit la tuile de volume du panneau à partir de l'état réel de
         * l'amplificateur. Reconstruite à chaque rafraîchissement plutôt que mise à
         * jour : c'est le matériel qui fait foi, pas une valeur mémorisée.
         */
        fun panelVolume(context: Context, level: Float, muted: Boolean): Entity = Entity(
            entityId = PANEL_VOLUME_ID,
            state = if (muted) "off" else "on",
            attributes = JSONObject()
                .put("friendly_name", context.getString(R.string.panel_volume_name))
                .put("volume_level", level.toDouble())
                .put("icon", if (muted) "mdi:volume-off" else "mdi:volume-high")
        )

        fun fromJson(o: JSONObject): Entity = Entity(
            entityId = o.getString("entity_id"),
            state = o.optString("state"),
            attributes = o.optJSONObject("attributes") ?: JSONObject()
        )

        /** Domaines retenus par la découverte automatique, dans l'ordre d'affichage. */
        val INTERESTING = listOf(
            "light", "switch", "climate", "cover", "fan", "media_player", "sensor", "binary_sensor"
        )
    }
}
