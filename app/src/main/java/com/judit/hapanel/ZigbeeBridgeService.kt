package com.judit.hapanel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Expose le coprocesseur Zigbee du panneau sur le réseau, pour Zigbee2MQTT ou ZHA.
 *
 * Le panneau embarque un **NCP Silicon Labs EmberZNet** relié en UART sur `/dev/ttyS3`.
 * Il ne se manifeste ni par un pilote noyau, ni par un nœud de l'arbre matériel — un
 * coprocesseur Zigbee sur port série n'en a pas besoin — mais il répond à la trame de
 * réinitialisation du protocole ASH, ce qui suffit à l'identifier :
 *
 * ```
 * printf '\x1a\xc0\x38\xbc\x7e' > /dev/ttyS3   →   1a c1 02 0b 0a 52 7e
 * ```
 *
 * Zigbee2MQTT et ZHA tournent sur le serveur Home Assistant, pas ici. Ce service comble
 * la distance : il écoute sur un port TCP et relaie octet pour octet vers le port série.
 * Côté serveur, il suffit alors de désigner `tcp://<ip-du-panneau>:<port>` comme
 * adaptateur `ember` — ou `socket://<ip-du-panneau>:<port>` pour ZHA.
 *
 * **Un seul client à la fois.** Un coordinateur Zigbee ne se partage pas : deux clients
 * entrelaceraient leurs trames ASH et le dialogue deviendrait incohérent. Une nouvelle
 * connexion est donc refusée tant que la précédente tient.
 */
class ZigbeeBridgeService : Service() {

    private var server: ServerSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var running = false

    /** Vrai tant qu'un client tient la liaison. Garde l'unicité du coordinateur. */
    @Volatile
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        // Le pont doit survivre à la mise en veille de l'écran : le réseau Zigbee ne
        // s'arrête pas parce que personne ne regarde le panneau.
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hapanel:zigbee").apply {
            setReferenceCounted(false)
            acquire()
        }

        running = true
        thread(isDaemon = true, name = "zigbee-bridge") { serve() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        running = false
        try {
            server?.close()
        } catch (e: Exception) {
            // Sans conséquence : on ferme.
        }
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    /**
     * Prépare le port puis accepte les connexions.
     *
     * Le port série appartient à `system` et le débit ne peut pas se régler depuis Java :
     * les deux passent par `su`. `busybox stty` est présent sur ce panneau, `stty` tout
     * court non — et `microcom`, qui saurait le faire, exige un vrai terminal sur son
     * entrée et refuse de travailler dans un tube.
     */
    private fun serve() {
        val device = prefsDevice()
        if (!prepareSerial(device)) {
            Log.w(TAG, "port série $device inutilisable, pont abandonné")
            stopSelf()
            return
        }

        val port = Prefs(this).zigbeePort
        try {
            ServerSocket(port).also { server = it }.use { socket ->
                Log.i(TAG, "pont Zigbee à l'écoute sur le port $port, relais vers $device")
                while (running) {
                    val client = socket.accept()
                    if (busy) {
                        // Refus net plutôt qu'une file d'attente : mieux vaut que le
                        // serveur constate l'occupation que de mêler deux dialogues.
                        Log.w(TAG, "connexion refusée, un client tient déjà la liaison")
                        try {
                            client.close()
                        } catch (e: Exception) {
                            // Sans conséquence.
                        }
                        continue
                    }
                    busy = true
                    thread(isDaemon = true, name = "zigbee-client") { relay(client, device) }
                }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "écoute interrompue : ${e.message}")
        }
    }

    /** Règle le débit et ouvre les droits sur le port. Retourne false si c'est sans espoir. */
    private fun prepareSerial(device: String): Boolean {
        if (!File(device).exists()) {
            Log.w(TAG, "$device n'existe pas sur ce panneau")
            return false
        }
        // 115200 bauds, mode brut, sans écho ni contrôle de flux matériel : c'est ce
        // qu'attend le protocole ASH, et c'est ce que règle l'application d'usine.
        runAsRoot("busybox stty -F $device 115200 raw -echo -crtscts")
        // L'application n'est pas système : sans cela elle ne peut pas ouvrir le port.
        runAsRoot("chmod 666 $device")
        return File(device).canRead() && File(device).canWrite()
    }

    /**
     * Relaie dans les deux sens jusqu'à ce que l'une des extrémités se ferme.
     *
     * Aucun tampon de ligne, aucune transformation : les trames ASH sont binaires, et le
     * moindre octet ajouté ou traduit casserait leur contrôle d'intégrité.
     */
    private fun relay(client: Socket, device: String) {
        Log.i(TAG, "client ${client.inetAddress?.hostAddress} connecté")
        try {
            client.tcpNoDelay = true
            val serial = File(device)
            FileInputStream(serial).use { fromSerial ->
                FromSocketToSerial(client, serial).let { pump ->
                    val reader = thread(isDaemon = true, name = "zigbee-rx") {
                        try {
                            fromSerial.copyTo(client.getOutputStream(), BUFFER)
                        } catch (e: Exception) {
                            // Fermeture normale d'un côté ou de l'autre.
                        }
                    }
                    pump.run()
                    reader.interrupt()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "liaison interrompue : ${e.message}")
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
                // Sans conséquence.
            }
            busy = false
            Log.i(TAG, "client déconnecté")
        }
    }

    /** Sens client → port série, exécuté sur le fil appelant. */
    private class FromSocketToSerial(val client: Socket, val serial: File) {
        fun run() {
            FileOutputStream(serial).use { toSerial ->
                val input = client.getInputStream()
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    toSerial.write(buffer, 0, read)
                    // Sans vidage explicite, une trame courte peut rester en attente et
                    // le coordinateur conclure à un silence.
                    toSerial.flush()
                }
            }
        }
    }

    private fun prefsDevice(): String = Prefs(this).zigbeeDevice

    private fun runAsRoot(command: String) {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "0", "sh", "-c", command)).waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "su indisponible : ${e.message}")
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, getString(R.string.zigbee_title),
                    NotificationManager.IMPORTANCE_MIN
                )
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.zigbee_title))
            .setContentText(getString(R.string.zigbee_running, Prefs(this).zigbeePort))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
    }

    companion object {
        private const val TAG = "HaPanelZigbee"
        private const val CHANNEL = "hapanel-zigbee"
        private const val NOTIFICATION_ID = 43
        private const val BUFFER = 4096

        fun start(context: Context) {
            context.startService(Intent(context, ZigbeeBridgeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ZigbeeBridgeService::class.java))
        }

        /**
         * Interroge le coprocesseur et retourne sa réponse, ou null.
         *
         * Envoie la trame **RST** du protocole ASH et attend la **RSTACK**. Sert au bouton
         * de vérification des réglages : c'est le seul moyen simple de dire à
         * l'utilisateur si son panneau possède bien une radio Zigbee.
         *
         * **Appel bloquant**, et à ne pas faire pendant que le pont sert un client.
         */
        fun probe(context: Context): String? {
            val prefs = Prefs(context)
            val device = prefs.zigbeeDevice
            return try {
                val command = "busybox stty -F $device 115200 raw -echo -crtscts; " +
                    "exec 3<>$device; printf '\\x1a\\xc0\\x38\\xbc\\x7e' >&3; " +
                    "timeout 3 head -c 8 <&3 | xxd; exec 3>&-"
                val process = Runtime.getRuntime().exec(arrayOf("su", "0", "sh", "-c", command))
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                // xxd produit « 00000000: 1ac1 020b 0a52 7e  .....R~ » : on ne garde que
                // les octets, la présence de « 1ac1 » suffisant à identifier une RSTACK.
                output.substringAfter(':', "").substringBefore("  ").trim()
                    .takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                Log.w(TAG, "sonde impossible : ${e.message}")
                null
            }
        }
    }
}
