package com.judit.hapanel

import android.content.Context
import android.graphics.Typeface
import android.util.Log

/**
 * Icônes Material Design, celles-là mêmes qu'utilise l'interface de Home Assistant.
 *
 * La police et la table nom → point de code sont embarquées dans les ressources
 * `assets/` : 7448 icônes, aucun accès réseau à l'exécution.
 *
 * Home Assistant expose l'icône d'une entité dans son attribut `icon`, sous la forme
 * `mdi:lightbulb` — mais **seulement lorsqu'elle a été personnalisée**. Pour toutes les
 * autres, l'interface web calcule une icône par défaut côté navigateur, à partir du
 * domaine et de la classe d'appareil. On reproduit ici cette logique.
 */
object MdiIcons {

    private const val TAG = "MdiIcons"
    private const val FONT_ASSET = "mdi.ttf"
    private const val MAP_ASSET = "mdi_codepoints.txt"

    @Volatile
    private var typeface: Typeface? = null
    private var codepoints: Map<String, Int> = emptyMap()

    /** Icône de repli quand un nom est introuvable dans la table. */
    private const val FALLBACK = "help-circle-outline"

    fun load(context: Context) {
        if (typeface != null) return
        try {
            typeface = Typeface.createFromAsset(context.assets, FONT_ASSET)
        } catch (e: Exception) {
            Log.w(TAG, "police introuvable : ${e.message}")
        }
        try {
            val map = HashMap<String, Int>(8000)
            context.assets.open(MAP_ASSET).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val sep = line.indexOf(' ')
                    if (sep > 0) {
                        val name = line.substring(0, sep)
                        val cp = line.substring(sep + 1).trim().toIntOrNull(16)
                        if (cp != null) map[name] = cp
                    }
                }
            }
            codepoints = map
        } catch (e: Exception) {
            Log.w(TAG, "table des points de code illisible : ${e.message}")
        }
    }

    fun typeface(): Typeface? = typeface

    /**
     * Le caractère à afficher pour cette entité. Les points de code MDI se situent
     * au-delà du plan multilingue de base, ils demandent donc une paire de substitution :
     * d'où `Character.toChars` plutôt qu'un simple `Char`.
     */
    fun glyphFor(entity: Entity): String {
        val name = iconName(entity)
        val cp = codepoints[name] ?: codepoints[FALLBACK] ?: return ""
        return String(Character.toChars(cp))
    }

    /** Le nom MDI retenu : celui de Home Assistant s'il existe, sinon notre défaut. */
    private fun iconName(entity: Entity): String {
        val custom = entity.attributes.optString("icon")
        if (custom.startsWith("mdi:")) return custom.removePrefix("mdi:")

        val deviceClass = entity.attributes.optString("device_class")
        return defaultIcon(entity.domain, deviceClass, entity.isOn)
    }

    /**
     * Icônes par défaut, reprenant les choix de l'interface de Home Assistant pour les
     * domaines et classes d'appareil les plus courants.
     */
    private fun defaultIcon(domain: String, deviceClass: String, isOn: Boolean): String =
        when (domain) {
            "light" -> if (isOn) "lightbulb" else "lightbulb-outline"
            "switch" -> when (deviceClass) {
                "outlet" -> "power-socket-eu"
                else -> if (isOn) "toggle-switch-variant" else "toggle-switch-variant-off"
            }
            "climate" -> "thermostat"
            "cover" -> when (deviceClass) {
                "curtain" -> "curtains"
                "garage" -> "garage"
                "door" -> "door"
                "gate" -> "gate"
                "awning", "shade", "blind" -> "blinds"
                else -> "window-shutter"
            }
            "fan" -> "fan"
            "media_player" -> when (deviceClass) {
                "tv" -> "television"
                "speaker" -> "speaker"
                else -> "cast"
            }
            "lock" -> if (isOn) "lock-open-variant" else "lock"
            "vacuum" -> "robot-vacuum"
            "water_heater" -> "water-boiler"
            "automation" -> "robot"
            "script" -> "script-text"
            "scene" -> "palette"
            "person" -> "account"
            "device_tracker" -> "account-arrow-right"
            "camera" -> "video"
            "binary_sensor" -> binarySensorIcon(deviceClass, isOn)
            "sensor" -> sensorIcon(deviceClass)
            else -> "shape-outline"
        }

    private fun sensorIcon(deviceClass: String): String = when (deviceClass) {
        "temperature" -> "thermometer"
        "humidity" -> "water-percent"
        "pressure", "atmospheric_pressure" -> "gauge"
        "illuminance" -> "brightness-5"
        "battery" -> "battery"
        "power" -> "flash"
        "energy" -> "lightning-bolt"
        "current" -> "current-ac"
        "voltage" -> "sine-wave"
        "gas" -> "meter-gas"
        "water" -> "water"
        "carbon_dioxide" -> "molecule-co2"
        "pm25", "pm10" -> "air-filter"
        "signal_strength" -> "wifi"
        "timestamp" -> "clock"
        else -> "eye"
    }

    private fun binarySensorIcon(deviceClass: String, isOn: Boolean): String = when (deviceClass) {
        "motion" -> if (isOn) "motion-sensor" else "motion-sensor-off"
        "door" -> if (isOn) "door-open" else "door-closed"
        "window" -> if (isOn) "window-open" else "window-closed"
        "moisture" -> if (isOn) "water-alert" else "water-off"
        "smoke" -> if (isOn) "smoke" else "smoke-detector"
        "presence" -> if (isOn) "home" else "home-outline"
        "opening" -> if (isOn) "square-outline" else "square"
        "battery" -> if (isOn) "battery-outline" else "battery"
        "connectivity" -> if (isOn) "check-network-outline" else "close-network-outline"
        else -> if (isOn) "checkbox-marked-circle" else "checkbox-blank-circle-outline"
    }
}
