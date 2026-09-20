package com.judit.hapanel

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * Bluetooth audio, dans les deux sens.
 *
 * Le panneau embarque un amplificateur et un haut-parleur, et son image Android déclare
 * **les deux rôles A2DP** — vérifié sur l'appareil : `com.android.bluetooth` expose
 * `a2dp.A2dpService` (émission) comme `a2dpsink.A2dpSinkService` (réception), avec
 * `avrcpcontroller.AvrcpControllerService` pour les commandes de lecture. D'où le choix
 * explicite d'un sens dans les réglages :
 *
 * - [Mode.ENTREE] — un téléphone diffuse sa musique **vers** le panneau, qui la sort sur
 *   son haut-parleur ou ses bornes d'enceintes. Le panneau se rend visible et attend.
 * - [Mode.SORTIE] — le panneau envoie son audio **vers** une enceinte ou un casque
 *   Bluetooth. C'est lui qui cherche et se connecte.
 *
 * Android sait tenir les deux rôles à la fois, mais pas sur le même flux : les mêler
 * dans une seule interface produirait des situations incompréhensibles — appairer une
 * enceinte pendant qu'un téléphone diffuse coupe le son sans explication. Le sens est
 * donc un réglage, et l'écran ne montre que ce qui s'y rapporte.
 *
 * `connect()` et `disconnect()` des profils A2DP ne sont pas publics : ils sont appelés
 * par réflexion. Android 8.1 est antérieur au filtrage des API masquées (API 28), l'appel
 * passe donc sans contournement. En cas d'échec, Android se connecte de lui-même après
 * l'appairage dans la grande majorité des cas.
 */
class BluetoothController(private val context: Context) {

    enum class Mode {
        /** Recevoir la musique d'un téléphone. */
        ENTREE,

        /** Émettre vers une enceinte ou un casque. */
        SORTIE;

        companion object {
            fun from(value: String): Mode =
                values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ENTREE
        }
    }

    /** Un appareil tel que présenté à l'écran. */
    data class Device(
        val name: String,
        val address: String,
        val paired: Boolean,
        val connected: Boolean,
        val audio: Boolean
    )

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    /**
     * Mandataires des deux profils audio, ouverts une fois pour toutes.
     *
     * Ils ne peuvent pas être obtenus à la demande : Android délivre le mandataire **sur
     * le fil principal**, si bien qu'attendre sa venue depuis ce même fil est un
     * interblocage garanti. L'attente expirait donc toujours, et l'écran annonçait
     * « aucune enceinte connectée » sur une enceinte bel et bien reliée — la machine à
     * états du système disant, elle, `A2dpStateMachine=Connected`. Bug réel, constaté
     * sur le panneau.
     *
     * Ouverts à la construction, ils sont ensuite consultables sans rien bloquer.
     */
    @Volatile
    private var sourceProxy: BluetoothProfile? = null

    @Volatile
    private var sinkProxy: BluetoothProfile? = null

    init {
        openProxy(BluetoothProfile.A2DP) { sourceProxy = it }
        openProxy(PROFILE_A2DP_SINK) { sinkProxy = it }
    }

    /** Demande un mandataire et le range quand il arrive. Ne bloque rien. */
    private fun openProxy(profileId: Int, ranger: (BluetoothProfile?) -> Unit) {
        val a = adapter ?: return
        try {
            a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, service: BluetoothProfile?) {
                    ranger(service)
                    Log.i(TAG, "mandataire du profil $p disponible")
                }

                override fun onServiceDisconnected(p: Int) = ranger(null)
            }, profileId)
        } catch (e: Exception) {
            Log.w(TAG, "profil $profileId indisponible : ${e.message}")
        }
    }

    private fun proxyFor(mode: Mode): BluetoothProfile? =
        if (mode == Mode.SORTIE) sourceProxy else sinkProxy

    /** Faux sur un panneau dépourvu de Bluetooth : l'écran le dit alors clairement. */
    val isSupported: Boolean get() = adapter != null

    var isEnabled: Boolean
        get() = adapter?.isEnabled == true
        set(v) {
            val a = adapter ?: return
            if (v) a.enable() else a.disable()
        }

    /** Le nom sous lequel le panneau apparaît sur le téléphone. */
    var name: String
        get() = adapter?.name ?: ""
        set(v) {
            adapter?.name = v.trim().takeIf { it.isNotEmpty() } ?: return
        }

    val isDiscovering: Boolean get() = adapter?.isDiscovering == true

    /**
     * Rend le panneau visible pour un téléphone, en mode [Mode.ENTREE].
     *
     * Android réserve normalement cette bascule à une confirmation de l'utilisateur via
     * une activité système. Elle est ici demandée directement : sur ce panneau, l'activité
     * de confirmation appartient à l'interface constructeur, qui est désactivée. Si la
     * demande est refusée, on retombe sur l'intention système, qui affiche sa propre
     * demande de confirmation.
     */
    fun makeDiscoverable(seconds: Int = 300): Boolean {
        val a = adapter ?: return false
        if (a.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) return true

        val viaApi = try {
            BluetoothAdapter::class.java
                .getMethod("setScanMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(a, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, seconds) as? Boolean
        } catch (e: Exception) {
            Log.d(TAG, "setScanMode indisponible : ${e.message}")
            null
        }
        if (viaApi == true) return true

        return try {
            context.startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, seconds)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "visibilité impossible : ${e.message}")
            false
        }
    }

    /** Cherche les appareils alentour, en mode [Mode.SORTIE]. */
    fun startDiscovery(): Boolean {
        val a = adapter ?: return false
        if (a.isDiscovering) a.cancelDiscovery()
        return try {
            a.startDiscovery()
        } catch (e: Exception) {
            Log.w(TAG, "recherche refusée : ${e.message}")
            false
        }
    }

    fun cancelDiscovery() {
        try {
            adapter?.cancelDiscovery()
        } catch (e: Exception) {
            // Sans conséquence : la recherche s'arrête d'elle-même au bout de 12 s.
        }
    }

    /**
     * S'abonne aux découvertes, appairages et connexions. Retourne de quoi se désabonner :
     * l'écran de réseau est éphémère, un récepteur laissé en place fuirait l'activité.
     */
    fun onChanges(listener: () -> Unit): AutoCloseable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = listener()
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
            addAction(ACTION_A2DP_STATE)
            addAction(ACTION_A2DP_SINK_STATE)
        }
        context.registerReceiver(receiver, filter)
        return AutoCloseable {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Déjà retiré : sans conséquence.
            }
        }
    }

    /**
     * Les appareils à présenter : ceux déjà appairés, puis ceux trouvés pendant la
     * recherche. En mode [Mode.SORTIE] on écarte le reste — un panneau mural n'a rien à
     * faire d'un clavier ou d'une montre, et la liste brute d'un immeuble est illisible.
     */
    fun devices(mode: Mode, found: Collection<BluetoothDevice>): List<Device> {
        val a = adapter ?: return emptyList()
        val connected = connectedAddresses(mode)

        val bonded = try {
            a.bondedDevices ?: emptySet<BluetoothDevice>()
        } catch (e: Exception) {
            emptySet<BluetoothDevice>()
        }

        val all = LinkedHashMap<String, BluetoothDevice>()
        bonded.forEach { all[it.address] = it }
        found.forEach { all.putIfAbsent(it.address, it) }

        return all.values
            .map { d ->
                Device(
                    name = d.name?.takeIf { it.isNotBlank() } ?: d.address,
                    address = d.address,
                    paired = d.bondState == BluetoothDevice.BOND_BONDED,
                    connected = d.address in connected,
                    audio = isAudioDevice(d)
                )
            }
            // En émission, un appareil non audio ne sert à rien. En réception, on garde
            // tout : c'est le téléphone qui vient, et il s'annonce en « téléphone ».
            .filter { mode == Mode.ENTREE || it.audio || it.paired }
            .sortedWith(
                compareByDescending<Device> { it.connected }
                    .thenByDescending { it.paired }
                    .thenBy { it.name.lowercase() }
            )
    }

    /** Vrai pour une enceinte, un casque ou un autre appareil de rendu audio. */
    private fun isAudioDevice(device: BluetoothDevice): Boolean = try {
        val major = device.bluetoothClass?.majorDeviceClass
        major == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO
    } catch (e: Exception) {
        false
    }

    /** Lance l'appairage, ou la connexion si l'appareil est déjà appairé. */
    fun pairOrConnect(address: String, mode: Mode): Boolean {
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return false

        cancelDiscovery()

        return if (device.bondState != BluetoothDevice.BOND_BONDED) {
            // La suite est automatique : Android affiche la demande de code s'il en faut
            // un, puis connecte le profil audio de lui-même une fois l'appairage acquis.
            try {
                device.createBond()
            } catch (e: Exception) {
                Log.w(TAG, "appairage impossible : ${e.message}")
                false
            }
        } else {
            connectProfile(device, mode)
        }
    }

    /** Coupe la liaison audio sans défaire l'appairage. */
    fun disconnect(address: String, mode: Mode): Boolean {
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return false
        return callProfileMethod(device, mode, "disconnect")
    }

    /** Défait l'appairage. Le téléphone devra recommencer pour revenir. */
    fun forget(address: String): Boolean {
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return false

        // `removeBond` est masquée dans le SDK public, comme tout ce qui défait un
        // appairage : Android réserve le geste à ses propres réglages.
        return try {
            BluetoothDevice::class.java.getMethod("removeBond")
                .invoke(device) as? Boolean ?: false
        } catch (e: Exception) {
            Log.w(TAG, "oubli impossible : ${e.message}")
            false
        }
    }

    private fun connectProfile(device: BluetoothDevice, mode: Mode): Boolean {
        // Le rôle opposé est mis hors course sur cet appareil avant de connecter.
        //
        // Ce panneau tient les deux rôles A2DP à la fois, et sa pile Bluetooth — un
        // Android 8.1 de 2018 — ne le supporte pas sur un même appareil : connecter une
        // enceinte en émission réveille le gestionnaire AVRCP du rôle récepteur, qui
        // déréférence une liste nulle et **fait tomber tout le service Bluetooth**.
        // Constaté en tentant d'appairer une enceinte Google Home :
        //
        //   [FATAL:list.cc(191)] Check failed: list != NULL
        //   handle_avk_rc_metamsg_rsp -> btif_av_state_opened_handler
        //
        // Le défaut est dans `bluetooth.default.so` et n'est pas corrigeable ici. On
        // évite donc d'y entrer, en interdisant au rôle inverse de s'associer.
        setProfilePriorityOff(device, if (mode == Mode.SORTIE) PROFILE_A2DP_SINK else BluetoothProfile.A2DP)
        return callProfileMethod(device, mode, "connect")
    }

    /**
     * Interdit à un profil de s'associer à cet appareil. `setPriority` est masquée dans
     * le SDK public ; un échec n'est pas bloquant, on tente la connexion malgré tout.
     */
    private fun setProfilePriorityOff(device: BluetoothDevice, profileId: Int) {
        val service = if (profileId == BluetoothProfile.A2DP) sourceProxy else sinkProxy
        if (service == null) {
            Log.d(TAG, "profil $profileId pas encore disponible, priorité inchangée")
            return
        }
        try {
            service.javaClass
                .getMethod("setPriority", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                .invoke(service, device, PRIORITY_OFF)
            Log.i(TAG, "profil $profileId écarté sur ${device.address}")
        } catch (e: Exception) {
            Log.d(TAG, "setPriority indisponible pour $profileId : ${e.message}")
        }
    }

    private fun callProfileMethod(
        device: BluetoothDevice,
        mode: Mode,
        method: String
    ): Boolean {
        val service = proxyFor(mode) ?: run {
            Log.w(TAG, "mandataire du sens $mode pas encore disponible")
            return false
        }
        return try {
            service.javaClass
                .getMethod(method, BluetoothDevice::class.java)
                .invoke(service, device) as? Boolean ?: false
        } catch (e: Exception) {
            Log.w(TAG, "$method refusé pour $mode : ${e.message}")
            false
        }
    }

    /** Les adresses actuellement reliées pour le sens demandé. */
    private fun connectedAddresses(mode: Mode): Set<String> {
        val service = proxyFor(mode) ?: return emptySet()
        return try {
            service.connectedDevices.map { it.address }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /** Vrai dès qu'un appareil est relié dans ce sens. */
    fun hasConnection(mode: Mode): Boolean = connectedAddresses(mode).isNotEmpty()

    /** Résumé d'une ligne pour l'écran de réglages : ce qui est relié, et dans quel sens. */
    fun summary(mode: Mode): String {
        if (!isSupported) return context.getString(R.string.bt_unsupported)
        if (!isEnabled) return context.getString(R.string.bt_off)

        val connected = connectedAddresses(mode)
        if (connected.isEmpty()) {
            return context.getString(
                if (mode == Mode.ENTREE) R.string.bt_waiting_phone else R.string.bt_no_speaker
            )
        }
        val names = try {
            adapter?.bondedDevices
                ?.filter { it.address in connected }
                ?.joinToString(", ") { it.name ?: it.address }
                ?: connected.joinToString(", ")
        } catch (e: Exception) {
            connected.joinToString(", ")
        }
        return context.getString(
            if (mode == Mode.ENTREE) R.string.bt_receiving_from else R.string.bt_sending_to,
            names
        )
    }

    private companion object {
        const val TAG = "HaPanelBt"

        /**
         * `BluetoothProfile.A2DP_SINK`, masqué dans le SDK public. La valeur 11 est fixée
         * par la plateforme depuis Android 4.4 et vérifiée sur ce panneau, dont
         * `a2dpsink.A2dpSinkService` répond bien à `android.bluetooth.IBluetoothA2dpSink`.
         */
        const val PROFILE_A2DP_SINK = 11

        /** `BluetoothProfile.PRIORITY_OFF`, masquée dans le SDK public. */
        const val PRIORITY_OFF = 0

        const val ACTION_A2DP_STATE = "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"
        const val ACTION_A2DP_SINK_STATE = "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
    }
}
