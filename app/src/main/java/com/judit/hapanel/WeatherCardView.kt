package com.judit.hapanel

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Carte météo, en haut de la colonne de droite.
 *
 * Elle porte aussi la date : celle-ci a quitté le bandeau en même temps que l'horloge,
 * puisque l'écran rond du bouton rotatif les affiche déjà à trente centimètres de là.
 * Elle se lit naturellement à côté du temps qu'il fait.
 *
 * Se masque entièrement quand le serveur n'expose aucune entité `weather` : mieux vaut
 * rendre la place à la musique qu'afficher une carte creuse.
 */
class WeatherCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val dateLine: TextView
    private val dayLine: TextView
    private val icon: TextView
    private val temperature: TextView
    private val condition: TextView
    private val details: TextView

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_weather_card, this, true)

        dateLine = findViewById(R.id.weather_date)
        dayLine = findViewById(R.id.weather_day)
        icon = findViewById(R.id.weather_icon)
        temperature = findViewById(R.id.weather_temp)
        condition = findViewById(R.id.weather_condition)
        details = findViewById(R.id.weather_details)

        icon.typeface = MdiIcons.typeface()
        refreshDate()
    }

    /** Réécrit la date affichée. Appelée au changement de minute par le tableau de bord. */
    fun refreshDate() {
        val maintenant = Date()
        dateLine.text = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
            .format(maintenant)
            .replaceFirstChar { it.uppercase() }
        dayLine.text = SimpleDateFormat("EEEE", Locale.getDefault())
            .format(maintenant)
            .replaceFirstChar { it.uppercase() }
    }

    /**
     * Reçoit l'entité météo du serveur. Retourne false s'il n'y en a pas, ce qui invite
     * l'appelant à masquer la carte.
     */
    fun bind(entities: List<Entity>): Boolean {
        val meteo = entities.firstOrNull { it.domain == "weather" } ?: return false

        refreshDate()

        val degres = meteo.attributes.optDouble("temperature", Double.NaN)
        temperature.text = if (degres.isNaN()) "—" else String.format("%.0f°", degres)
        condition.text = conditionLabel(meteo.state)
        icon.text = MdiIcons.glyph(glyphFor(meteo.state))

        // Humidité et vent quand le serveur les fournit. Beaucoup d'intégrations n'en
        // donnent qu'une partie : on n'affiche que ce qui existe réellement.
        val morceaux = ArrayList<String>(2)
        meteo.attributes.optDouble("humidity", Double.NaN).let {
            if (!it.isNaN()) morceaux.add(String.format("Humidité %.0f %%", it))
        }
        meteo.attributes.optDouble("wind_speed", Double.NaN).let {
            if (!it.isNaN()) {
                val unite = meteo.attributes.optString("wind_speed_unit").ifEmpty { "km/h" }
                morceaux.add(String.format("Vent %.0f %s", it, unite))
            }
        }
        if (morceaux.isEmpty()) {
            details.visibility = View.GONE
        } else {
            details.visibility = View.VISIBLE
            details.text = morceaux.joinToString("  ·  ")
        }
        return true
    }

    companion object {
        /** Icône MDI correspondant à un état d'entité `weather` de Home Assistant. */
        fun glyphFor(condition: String): String = when (condition) {
            "sunny" -> "weather-sunny"
            "clear-night" -> "weather-night"
            "partlycloudy" -> "weather-partly-cloudy"
            "cloudy" -> "weather-cloudy"
            "fog" -> "weather-fog"
            "hail" -> "weather-hail"
            "lightning" -> "weather-lightning"
            "lightning-rainy" -> "weather-lightning-rainy"
            "pouring" -> "weather-pouring"
            "rainy" -> "weather-rainy"
            "snowy" -> "weather-snowy"
            "snowy-rainy" -> "weather-snowy-rainy"
            "windy", "windy-variant" -> "weather-windy"
            "exceptional" -> "alert-circle-outline"
            else -> "weather-cloudy"
        }

        /** Libellé français de la condition météorologique. */
        fun conditionLabel(condition: String): String = when (condition) {
            "sunny" -> "Ensoleillé"
            "clear-night" -> "Ciel dégagé"
            "partlycloudy" -> "Peu nuageux"
            "cloudy" -> "Nuageux"
            "fog" -> "Brouillard"
            "hail" -> "Grêle"
            "lightning" -> "Orageux"
            "lightning-rainy" -> "Orages et pluie"
            "pouring" -> "Fortes pluies"
            "rainy" -> "Pluvieux"
            "snowy" -> "Neigeux"
            "snowy-rainy" -> "Pluie et neige"
            "windy", "windy-variant" -> "Venteux"
            "exceptional" -> "Conditions extrêmes"
            else -> condition.replaceFirstChar { it.uppercase() }
        }
    }
}
