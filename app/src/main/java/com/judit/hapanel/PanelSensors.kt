package com.judit.hapanel

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread

/**
 * Remonte vers Home Assistant les capteurs embarqués du panneau : luminosité
 * ambiante et proximité (utilisable comme détection de présence devant l'écran).
 *
 * La publication passe par l'API REST /api/states, sur un thread dédié.
 */
class PanelSensors(
    context: Context,
    private val client: HaClient,
    /** Faux : on lit quand même les capteurs, mais on ne publie rien vers le serveur. */
    private val publish: Boolean = true,
    private val periodMs: Long = 60_000L
) : SensorEventListener {

    private companion object {
        const val TAG = "PanelSensors"
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /**
     * Sur ce panneau, les capteurs sont déclarés `wakeUp` : `getDefaultSensor(type)`
     * renvoie bien un objet, mais l'inscription est refusée. Il faut demander
     * explicitement la variante réveil, et ne retomber sur l'ordinaire qu'en secours.
     */
    private fun pick(type: Int): Sensor? =
        sensorManager.getDefaultSensor(type, true) ?: sensorManager.getDefaultSensor(type)

    private val light: Sensor? = pick(Sensor.TYPE_LIGHT)
    private val proximity: Sensor? = pick(Sensor.TYPE_PROXIMITY)

    private val thread = HandlerThread("panel-sensors").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var lastLux: Float? = null
    @Volatile private var lastProximity: Float? = null
    @Volatile private var running = false

    /**
     * Notifié quand quelqu'un s'approche du panneau. Sert à réveiller l'écran.
     * N'est appelé que sur la transition « loin → près », pas tant que la présence dure.
     */
    var onProximityNear: (() -> Unit)? = null

    @Volatile private var wasNear = false

    private val publishTask = object : Runnable {
        override fun run() {
            if (!running) return
            publishNow()
            handler.postDelayed(this, periodMs)
        }
    }

    fun start() {
        if (running) return
        running = true

        val lightOk = light?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        } ?: false
        val proximityOk = proximity?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        } ?: false

        hasWorkingSensors = lightOk || proximityOk

        if (!hasWorkingSensors) {
            // Sur ce panneau, la ROM déclare les deux capteurs mais le matériel est
            // absent : le pilote répond « no proximity sensor find » et la couche
            // Android échoue avec « Couldn't open /dev/psensor ». Inutile d'insister,
            // et surtout inutile de publier des capteurs fantômes vers Home Assistant.
            android.util.Log.w(TAG, "aucun capteur physique : luminosité et proximité inopérantes")
            running = false
            sensorManager.unregisterListener(this)
            return
        }

        android.util.Log.i(
            TAG,
            "capteurs — luminosité : ${if (lightOk) "inscrit" else "absente"}" +
                " · proximité : ${if (proximityOk) "inscrit" else "absente"}"
        )

        // Premier envoi après quelques secondes, le temps que les capteurs se stabilisent.
        handler.postDelayed(publishTask, 5_000L)
    }

    /** Faux si le matériel ne fournit aucun des deux capteurs. */
    @Volatile var hasWorkingSensors = false
        private set

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
    }

    fun release() {
        stop()
        thread.quitSafely()
    }

    private fun publishNow() {
        if (!publish) return
        lastLux?.let {
            client.publishSensor(
                objectId = "panneau_luminosite",
                state = it.toInt().toString(),
                unit = "lx",
                friendlyName = "Panneau — luminosité",
                deviceClass = "illuminance"
            )
        }
        lastProximity?.let {
            val near = it < (proximity?.maximumRange ?: 5f)
            client.publishSensor(
                objectId = "panneau_presence",
                state = if (near) "on" else "off",
                unit = null,
                friendlyName = "Panneau — présence",
                deviceClass = null
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> lastLux = event.values[0]
            Sensor.TYPE_PROXIMITY -> {
                lastProximity = event.values[0]
                val near = event.values[0] < (proximity?.maximumRange ?: 5f)
                android.util.Log.d(TAG, "proximité : ${event.values[0]} → ${if (near) "près" else "loin"}")
                if (near && !wasNear) onProximityNear?.invoke()
                wasNear = near
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
