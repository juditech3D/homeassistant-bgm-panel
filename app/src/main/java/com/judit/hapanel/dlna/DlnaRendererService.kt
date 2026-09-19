package com.judit.hapanel.dlna

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.util.UUID

/**
 * Expose le panneau comme lecteur DLNA sur le réseau local.
 *
 * Home Assistant possède une intégration **DLNA Digital Media Renderer** native : dès
 * que ce service tourne, le panneau est découvert tout seul et apparaît comme une entité
 * `media_player`. On peut alors lui envoyer de la musique, de la synthèse vocale, ou
 * l'intégrer à un groupe de lecteurs — sans rien installer d'autre sur le panneau.
 *
 * Tourne en service de premier plan : la lecture doit survivre au passage du tableau de
 * bord en arrière-plan, et Android tue sans scrupule les services ordinaires.
 */
class DlnaRendererService : Service() {

    private val player = RendererPlayer()
    private var ssdp: SsdpResponder? = null
    private var server: UpnpServer? = null

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var audio: AudioManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        startForeground(NOTIFICATION_ID, buildNotification())
        startRenderer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Redémarré par le système si jamais il nous tue : un lecteur audio doit rester
        // joignable en permanence.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        ssdp?.stop()
        server?.stop()
        player.stop()
        releaseLocks()
    }

    // ------------------------------------------------------------- démarrage

    private fun startRenderer() {
        val ip = SsdpResponder.localIpAddress()
        if (ip == null) {
            Log.w(TAG, "aucune adresse IPv4 : récepteur DLNA non démarré")
            stopSelf()
            return
        }

        acquireLocks()

        val uuid = stableUuid()
        val name = friendlyName()

        val srv = UpnpServer(
            uuid = uuid,
            friendlyName = name,
            player = player,
            // Le matériel compte 21 crans ; UPnP raisonne en pourcentage.
            volumeProvider = {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (max <= 0) 0
                else Math.round(audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max)
            },
            volumeSetter = { percent ->
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val target = Math.round(percent.coerceIn(0, 100) * max / 100f)
                try {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                } catch (e: SecurityException) {
                    Log.w(TAG, "volume refusé : ${e.message}")
                }
            },
            muteProvider = { audio.isStreamMute(AudioManager.STREAM_MUSIC) },
            muteSetter = { muted ->
                @Suppress("DEPRECATION")
                audio.setStreamMute(AudioManager.STREAM_MUSIC, muted)
            }
        )

        if (!srv.start()) {
            Log.w(TAG, "serveur HTTP indisponible : récepteur DLNA non démarré")
            stopSelf()
            return
        }
        server = srv

        ssdp = SsdpResponder(uuid, ip, srv.port).also { it.start() }

        player.onStateChanged = { state ->
            Log.i(TAG, "transport : $state")
            // La lecture doit tenir même écran éteint.
            if (state == RendererPlayer.TransportState.PLAYING) acquireWake() else releaseWake()
        }

        Log.i(TAG, "récepteur DLNA « $name » actif sur http://$ip:${srv.port}/description.xml")
    }

    /**
     * Identifiant stable entre deux démarrages : sans cela, Home Assistant verrait un
     * nouveau lecteur à chaque redémarrage du panneau et accumulerait les doublons.
     */
    private fun stableUuid(): String {
        val prefs = getSharedPreferences("dlna", Context.MODE_PRIVATE)
        prefs.getString(KEY_UUID, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_UUID, generated).apply()
        return generated
    }

    private fun friendlyName(): String {
        val configured = getSharedPreferences("hapanel", Context.MODE_PRIVATE)
            .getString("dlna_name", null)
        return configured?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME
    }

    // ---------------------------------------------------------------- verrous

    private fun acquireLocks() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // Sans ce verrou, Android filtre le multicast et les recherches SSDP
            // n'arrivent jamais jusqu'à nous.
            multicastLock = wifi.createMulticastLock("hapanel-dlna").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "verrou multicast indisponible : ${e.message}")
        }
    }

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hapanel:dlna").apply {
                setReferenceCounted(false)
                acquire(WAKE_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "verrou de veille indisponible : ${e.message}")
        }
    }

    private fun releaseWake() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            // rien à faire
        }
    }

    private fun releaseLocks() {
        releaseWake()
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            // rien à faire
        }
        multicastLock = null
    }

    // ----------------------------------------------------------- notification

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Lecteur réseau", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) }
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Lecteur réseau actif")
            .setContentText("Le panneau est disponible dans Home Assistant")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "DlnaRenderer"
        private const val CHANNEL_ID = "dlna_renderer"
        private const val NOTIFICATION_ID = 42
        private const val KEY_UUID = "uuid"
        private const val DEFAULT_NAME = "Panneau 7 pouces"
        private const val WAKE_TIMEOUT_MS = 4 * 60 * 60 * 1000L

        fun start(context: Context) {
            context.startService(Intent(context, DlnaRendererService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DlnaRendererService::class.java))
        }
    }
}
