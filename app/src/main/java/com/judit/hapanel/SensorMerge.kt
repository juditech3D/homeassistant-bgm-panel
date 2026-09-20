package com.judit.hapanel

import android.graphics.Color
import org.json.JSONObject

/**
 * Réunit sur une seule tuile les mesures d'une même sonde.
 *
 * Un capteur d'ambiance publie une entité par grandeur : température d'un côté,
 * humidité de l'autre. Affichées séparément, elles occupent deux cases pour un seul
 * appareil, portent deux fois son nom, et obligent à comparer deux tuiles pour se faire
 * une idée du confort d'une pièce. Réunies, elles disent la même chose en un coup d'œil.
 *
 * Le rapprochement se fait sur l'identifiant : `sensor.sonde_cuisine_temperature` et
 * `sensor.sonde_cuisine_humidite` partagent la racine `sensor.sonde_cuisine`. C'est
 * moins sûr qu'un registre des appareils — que l'API WebSocket n'expose pas simplement —
 * mais les intégrations nomment très régulièrement ainsi, et une racine qui ne
 * correspondrait à rien laisse simplement les deux entités séparées.
 */
object SensorMerge {

    /** Suffixes reconnus, en français comme en anglais. */
    private val TEMPERATURE = listOf("_temperature", "_temperature_2", "_temp")
    private val HUMIDITE = listOf("_humidite", "_humidity", "_hum")
    private val BATTERIE = listOf("_batterie", "_battery", "_niveau_de_batterie", "_battery_level")

    /** Attribut portant la mesure secondaire, affichée sous la principale. */
    const val SECONDARY = "panel_secondary"

    /** Attribut portant la teinte de la tuile, en ARGB. */
    const val TINT = "panel_tint"

    /**
     * Fusionne ce qui peut l'être.
     *
     * [shown] sont les entités à afficher, [all] l'ensemble connu du serveur : la mesure
     * complémentaire est reprise de là, même si elle n'a pas été épinglée. C'est le
     * comportement attendu — on épingle « la sonde », pas « la température de la sonde ».
     */
    fun merge(shown: List<Entity>, all: List<Entity>): List<Entity> {
        val parId = all.associateBy { it.entityId }
        val absorbees = HashSet<String>()
        val sortie = ArrayList<Entity>(shown.size)

        shown.forEach { entity ->
            if (entity.entityId in absorbees) return@forEach

            val racine = racineTemperature(entity.entityId)
            if (racine == null) {
                sortie.add(entity)
                return@forEach
            }

            val humidite = HUMIDITE.firstNotNullOfOrNull { parId[racine + it] }
            val batterie = BATTERIE.firstNotNullOfOrNull { parId[racine + it] }

            if (humidite == null && batterie == null) {
                sortie.add(teinte(entity))
                return@forEach
            }

            humidite?.let { absorbees.add(it.entityId) }
            batterie?.let { absorbees.add(it.entityId) }
            sortie.add(fusionner(entity, humidite, batterie))
        }

        // Une humidité épinglée seule reste affichée : mieux vaut la montrer telle
        // quelle que de la faire disparaître faute de température associée.
        return sortie.filter { it.entityId !in absorbees }
    }

    private fun racineTemperature(entityId: String): String? =
        TEMPERATURE.firstOrNull { entityId.endsWith(it) }?.let { entityId.removeSuffix(it) }

    /**
     * Construit la tuile commune : température en grand, le reste en dessous.
     *
     * La batterie rejoint la ligne secondaire plutôt qu'une tuile à elle : c'est une
     * information de maintenance, qu'on veut voir sans qu'elle occupe une case entière.
     */
    private fun fusionner(temperature: Entity, humidite: Entity?, batterie: Entity?): Entity {
        val degres = temperature.state.toDoubleOrNull()
        val pourcent = humidite?.state?.toDoubleOrNull()
        val charge = batterie?.state?.toDoubleOrNull()

        val attributs = JSONObject().apply {
            // Le nom perd le suffixe de grandeur : la tuile parle de la sonde, pas de
            // l'une de ses mesures.
            put("friendly_name", nomDeSonde(temperature.friendlyName))
            put("icon", "mdi:thermometer")
            put("unit_of_measurement", temperature.attributes.optString("unit_of_measurement"))
            put("device_class", "temperature")
            val details = ArrayList<String>(2)
            if (pourcent != null) details.add(String.format("Humidité %.0f %%", pourcent))
            if (charge != null) details.add(String.format("Batterie %.0f %%", charge))
            if (details.isNotEmpty()) put(SECONDARY, details.joinToString("  ·  "))
            put(TINT, couleur(degres, pourcent))
        }
        return Entity(temperature.entityId, temperature.state, attributs)
    }

    /** Une température seule mérite tout de même sa teinte. */
    private fun teinte(entity: Entity): Entity {
        val degres = entity.state.toDoubleOrNull() ?: return entity
        val attributs = JSONObject(entity.attributes.toString())
            .put(TINT, couleur(degres, null))
        return Entity(entity.entityId, entity.state, attributs)
    }

    /**
     * Retire le suffixe de grandeur du nom : « Sonde arrière cuisine Température »
     * devient « Sonde arrière cuisine ».
     */
    private fun nomDeSonde(nom: String): String {
        val mots = listOf(" température", " temperature", " temp", " humidité", " humidity")
        var resultat = nom
        mots.forEach { mot ->
            if (resultat.endsWith(mot, ignoreCase = true)) {
                resultat = resultat.dropLast(mot.length)
            }
        }
        return resultat.trim().ifEmpty { nom }
    }

    /**
     * Teinte de la tuile : froide sous 18 °C, tempérée autour de 21, chaude au-delà
     * de 25. Les seuils suivent le confort d'une pièce à vivre plutôt qu'une échelle
     * météorologique — l'idée est de repérer d'un regard la pièce trop fraîche.
     *
     * Sans température, on se rabat sur l'humidité : trop sec ou trop humide se signale
     * aussi bien.
     */
    fun couleur(degres: Double?, humidite: Double?): Int = when {
        degres != null -> when {
            degres < 14 -> FROID
            degres < 18 -> FRAIS
            degres < 24 -> CONFORT
            degres < 27 -> TIEDE
            else -> CHAUD
        }

        humidite != null -> when {
            humidite < 35 -> TIEDE
            humidite <= 65 -> CONFORT
            else -> FRAIS
        }

        else -> Color.TRANSPARENT
    }

    private val FROID = Color.rgb(64, 132, 244)
    private val FRAIS = Color.rgb(77, 182, 172)
    private val CONFORT = Color.rgb(102, 187, 106)
    private val TIEDE = Color.rgb(255, 183, 77)
    private val CHAUD = Color.rgb(229, 115, 115)
}
