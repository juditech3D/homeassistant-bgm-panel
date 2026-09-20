package com.judit.hapanel

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Écran de réseau : Wi-Fi à gauche, Bluetooth à droite.
 *
 * Il existe parce que le panneau n'a plus d'autre moyen de changer de réseau. L'interface
 * d'origine, seule à proposer un choix de Wi-Fi, doit être désactivée pour que le bouton
 * rotatif fonctionne — sans cet écran, il faudrait rebrancher un câble Ethernet et passer
 * par ADB pour toucher au réseau.
 *
 * Il s'ouvre de lui-même au démarrage **si et seulement si** aucune liaison filaire n'est
 * détectée : tant que le RJ45 répond, il n'y a rien à demander.
 */
class NetworkActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var wifi: WifiController
    private lateinit var bt: BluetoothController

    private var wifiWatcher: AutoCloseable? = null
    private var btWatcher: AutoCloseable? = null

    /** Les appareils Bluetooth vus pendant la recherche en cours, effacés à chaque relance. */
    private val discovered = LinkedHashMap<String, BluetoothDevice>()

    private lateinit var linkStatus: TextView
    private lateinit var wifiSwitch: Switch
    private lateinit var wifiStatus: TextView
    private lateinit var wifiList: ListView
    private lateinit var wifiEmpty: TextView
    private lateinit var wifiLocationWarning: View
    private lateinit var btSwitch: Switch
    private lateinit var btModeSpinner: Spinner
    private lateinit var btModeHelp: TextView
    private lateinit var btStatus: TextView
    private lateinit var btList: ListView
    private lateinit var btEmpty: TextView
    private lateinit var btAction: Button
    private lateinit var btColumn: View
    private lateinit var columnGap: View

    private var wifiNetworks: List<WifiController.Network> = emptyList()
    private var btDevices: List<BluetoothController.Device> = emptyList()

    private val audio by lazy { AudioController(this) }

    /**
     * Etat de liaison au rafraichissement precedent, pour ne jouer la confirmation qu'au
     * moment ou elle s'etablit -- et non a chaque passage sur l'ecran.
     */
    private var btWasConnected = false

    /**
     * Vrai une fois le premier releve fait. Sans lui, ouvrir l'ecran alors qu'une enceinte
     * est deja reliee passerait pour une connexion qui vient de s'etablir, et la melodie
     * repartirait a chaque visite.
     */
    private var btStateKnown = false

    private val modeValues = listOf(BluetoothController.Mode.ENTREE, BluetoothController.Mode.SORTIE)

    private val mode: BluetoothController.Mode
        get() = modeValues.getOrElse(btModeSpinner.selectedItemPosition) {
            BluetoothController.Mode.ENTREE
        }

    // La langue choisie dans les reglages s'impose avant que la moindre ressource soit
    // lue : posee plus tard, elle laisserait les textes deja resolus dans l'ancienne.
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    /**
     * Tout geste sur cet ecran reporte la mise en veille.
     *
     * La minuterie appartient au tableau de bord, mais elle court aussi pendant qu'on
     * est ici : sans ce rappel, l'ecran s'eteindrait au milieu d'un reglage.
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        ScreenManager.noteInteraction()
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (ScreenManager.consumeWakeTouch(event)) return true
        return super.dispatchTouchEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network)

        prefs = Prefs(this)
        wifi = WifiController(this)
        bt = BluetoothController(this)

        bind()
        wireWifi()
        wireBluetooth()

        findViewById<Button>(R.id.network_back).setOnClickListener { finish() }

        // La recherche de réseaux et la découverte Bluetooth exigent toutes deux
        // l'autorisation de localisation depuis Android 6 : on la demande une fois, à
        // l'ouverture, plutôt qu'au premier appui sur « Rechercher ».
        ensureLocationPermission()
        refreshAll()
    }

    private fun bind() {
        linkStatus = findViewById(R.id.link_status)
        wifiSwitch = findViewById(R.id.wifi_enable)
        wifiStatus = findViewById(R.id.wifi_status)
        wifiList = findViewById(R.id.wifi_list)
        wifiEmpty = findViewById(R.id.wifi_empty)
        wifiLocationWarning = findViewById(R.id.wifi_location_warning)
        btSwitch = findViewById(R.id.bt_enable)
        btModeSpinner = findViewById(R.id.bt_mode)
        btModeHelp = findViewById(R.id.bt_mode_help)
        btStatus = findViewById(R.id.bt_status)
        btList = findViewById(R.id.bt_list)
        btEmpty = findViewById(R.id.bt_empty)
        btAction = findViewById(R.id.bt_action)
        btColumn = findViewById(R.id.bt_column)
        columnGap = findViewById(R.id.column_gap)
    }

    // ------------------------------------------------------------------------ Wi-Fi

    private fun wireWifi() {
        wifiSwitch.isChecked = wifi.isEnabled
        wifiSwitch.setOnClickListener {
            wifi.isEnabled = wifiSwitch.isChecked
            // La pile Wi-Fi met un instant à s'allumer : on relance la recherche une fois
            // qu'elle a eu le temps de répondre, sinon la liste sortirait vide.
            wifiList.postDelayed({ refreshWifi(scan = wifiSwitch.isChecked) }, 1500)
        }

        findViewById<Button>(R.id.wifi_scan).setOnClickListener { refreshWifi(scan = true) }

        findViewById<Button>(R.id.wifi_location_enable).setOnClickListener {
            if (wifi.tryEnableLocationServices()) {
                refreshWifi(scan = true)
            } else {
                toast(getString(R.string.wifi_location_failed))
            }
        }

        wifiList.adapter = WifiAdapter()
        wifiList.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            wifiNetworks.getOrNull(position)?.let { askPasswordAndConnect(it) }
        }
        // L'appui long oublie le réseau : le seul autre geste utile, et il n'y a pas la
        // place pour un bouton par ligne.
        wifiList.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, position, _ ->
            val network = wifiNetworks.getOrNull(position) ?: return@OnItemLongClickListener false
            if (!network.saved) return@OnItemLongClickListener false
            if (wifi.forget(network.ssid)) {
                toast(getString(R.string.wifi_forgotten, network.ssid))
                refreshWifi(scan = false)
            }
            true
        }
    }

    /**
     * Demande le mot de passe puis lance la connexion.
     *
     * Le champ peut être démasqué : au doigt, sur un clavier tactile, une clé WPA de vingt
     * caractères se saisit mal en aveugle, et une faute ne se manifesterait que par un
     * échec de connexion sans explication.
     */
    private fun askPasswordAndConnect(network: WifiController.Network) {
        if (!network.secured || network.saved) {
            connect(network, null)
            return
        }

        val field = EditText(this).apply {
            hint = getString(R.string.wifi_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val reveal = CheckBox(this).apply {
            text = getString(R.string.wifi_show_password)
            setOnCheckedChangeListener { _, checked ->
                field.inputType = InputType.TYPE_CLASS_TEXT or
                    if (checked) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    else InputType.TYPE_TEXT_VARIATION_PASSWORD
                field.setSelection(field.text.length)
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
            addView(field)
            addView(reveal)
        }

        AlertDialog.Builder(this)
            .setTitle(network.ssid)
            .setView(box)
            .setPositiveButton(R.string.wifi_connect) { _, _ ->
                connect(network, field.text.toString())
            }
            .setNegativeButton(R.string.picker_cancel, null)
            .show()
    }

    private fun connect(network: WifiController.Network, password: String?) {
        toast(getString(R.string.wifi_connecting_to, network.ssid))
        val error = wifi.connect(network.ssid, password, network.secured)
        if (error != null) {
            toast(error)
            return
        }
        // L'association prend deux à trois secondes : on laisse le temps à la pile avant
        // de relire l'état, sinon l'écran afficherait encore « non connecté ».
        wifiList.postDelayed({ refreshWifi(scan = false) }, 3000)
    }

    private fun refreshWifi(scan: Boolean) {
        wifiSwitch.isChecked = wifi.isEnabled

        if (!wifi.isEnabled) {
            wifiStatus.text = getString(R.string.wifi_disabled)
            showWifi(emptyList())
            return
        }

        updateWifiStatusLine()

        wifiLocationWarning.visibility =
            if (wifi.locationServicesOff()) View.VISIBLE else View.GONE

        if (scan) {
            wifiStatus.text = getString(R.string.wifi_scanning)
            wifi.startScan()
        }
        showWifi(wifi.networks())
    }

    /**
     * Réécrit la ligne d'état du Wi-Fi. Appelée aussi à l'arrivée des résultats de
     * balayage : sans cela, « Recherche des réseaux… » resterait affiché alors que la
     * liste est déjà remplie.
     */
    private fun updateWifiStatusLine() {
        if (!wifi.isEnabled) {
            wifiStatus.text = getString(R.string.wifi_disabled)
            return
        }
        val ssid = wifi.currentSsid()
        val ip = wifi.currentIp()
        wifiStatus.text = if (ssid != null && ip != null) {
            getString(R.string.wifi_connected_to, ssid, ip)
        } else {
            getString(R.string.wifi_not_connected)
        }
    }

    private fun showWifi(networks: List<WifiController.Network>) {
        wifiNetworks = networks
        (wifiList.adapter as BaseAdapter).notifyDataSetChanged()
        wifiEmpty.visibility = if (networks.isEmpty()) View.VISIBLE else View.GONE
    }

    // -------------------------------------------------------------------- Bluetooth

    private fun wireBluetooth() {
        // Fonction décochée dans les réglages : la colonne disparaît et le Wi-Fi occupe
        // toute la largeur. Sans cela le réglage n'aurait aucun effet visible, alors que
        // l'application promet qu'une fonction désactivée est entièrement inactive.
        if (!prefs.bluetoothEnabled) {
            btColumn.visibility = View.GONE
            columnGap.visibility = View.GONE
            return
        }

        btModeSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.bt_mode_in), getString(R.string.bt_mode_out))
        )
        btModeSpinner.setSelection(
            modeValues.indexOf(BluetoothController.Mode.from(prefs.bluetoothMode))
                .coerceAtLeast(0)
        )
        btModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.bluetoothMode = mode.name
                bt.cancelDiscovery()
                discovered.clear()
                refreshBluetooth()
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

        btSwitch.isChecked = bt.isEnabled
        btSwitch.isEnabled = bt.isSupported
        // Ce commutateur allume ou éteint la **radio**, rien d'autre. Lui faire écrire
        // aussi le réglage de fonctionnalité mêlerait deux idées distinctes : couper la
        // radio un instant retirerait alors la carte des réglages, sans qu'on l'ait voulu.
        btSwitch.setOnClickListener {
            bt.isEnabled = btSwitch.isChecked
            // L'adaptateur met plusieurs secondes à s'allumer : l'état réel n'est lisible
            // qu'ensuite, et le récepteur d'événements rafraîchira de toute façon.
            btList.postDelayed({ refreshBluetooth() }, 2500)
        }

        // Un seul bouton, dont le rôle suit le sens choisi : se rendre visible pour qu'un
        // téléphone vienne, ou partir en quête d'une enceinte.
        btAction.setOnClickListener {
            if (!bt.isEnabled) {
                toast(getString(R.string.bt_off))
                return@setOnClickListener
            }
            if (mode == BluetoothController.Mode.ENTREE) {
                if (bt.makeDiscoverable()) toast(getString(R.string.bt_discoverable_on))
            } else {
                discovered.clear()
                btStatus.text = getString(R.string.bt_scanning)
                bt.startDiscovery()
            }
        }

        findViewById<Button>(R.id.bt_test_audio).setOnClickListener {
            Toast.makeText(this, R.string.bt_test_audio_playing, Toast.LENGTH_SHORT).show()
            audio.playTestTone()
        }

        btList.adapter = BluetoothAdapter()
        btList.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val device = btDevices.getOrNull(position) ?: return@OnItemClickListener
            toast(
                getString(
                    if (device.paired) R.string.bt_connecting else R.string.bt_pairing,
                    device.name
                )
            )
            if (!bt.pairOrConnect(device.address, mode)) {
                toast(getString(R.string.bt_action_failed, device.name))
            }
            btList.postDelayed({ refreshBluetooth() }, 2000)
        }
        // Appui long : déconnecter si l'appareil est relié, l'oublier sinon.
        btList.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, position, _ ->
            val device = btDevices.getOrNull(position) ?: return@OnItemLongClickListener false
            AlertDialog.Builder(this)
                .setTitle(device.name)
                .setItems(
                    arrayOf(getString(R.string.bt_disconnect), getString(R.string.bt_forget))
                ) { _, which ->
                    if (which == 0) bt.disconnect(device.address, mode)
                    else bt.forget(device.address)
                    btList.postDelayed({ refreshBluetooth() }, 1000)
                }
                .show()
            true
        }
    }

    private fun refreshBluetooth() {
        if (btColumn.visibility == View.GONE) return
        btSwitch.isChecked = bt.isEnabled
        btModeHelp.setText(
            if (mode == BluetoothController.Mode.ENTREE) R.string.bt_mode_in_help
            else R.string.bt_mode_out_help
        )
        btAction.setText(
            if (mode == BluetoothController.Mode.ENTREE) R.string.bt_discoverable
            else R.string.bt_scan
        )
        btStatus.text = bt.summary(mode)

        // Melodie de confirmation au moment ou la liaison s'etablit : sur une enceinte,
        // seule l'oreille prouve que le son sort vraiment, et d'ou.
        val relie = bt.isEnabled && bt.hasConnection(mode)
        if (relie && !btWasConnected && btStateKnown) audio.playTestTone()
        btWasConnected = relie
        btStateKnown = true

        btDevices = if (bt.isEnabled) bt.devices(mode, discovered.values) else emptyList()
        (btList.adapter as BaseAdapter).notifyDataSetChanged()
        btEmpty.visibility = if (btDevices.isEmpty()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------------ commun

    private fun refreshAll() {
        linkStatus.text = ethernetAddress()?.let { getString(R.string.wifi_on_ethernet, it) }
            ?: getString(R.string.wifi_no_ethernet)
        refreshWifi(scan = wifi.isEnabled)
        refreshBluetooth()
    }

    /** L'adresse IPv4 portée par l'interface filaire, ou null. */
    private fun ethernetAddress(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && it.name.startsWith("eth") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLinkLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    override fun onStart() {
        super.onStart()
        wifiWatcher = wifi.onScanResults {
            runOnUiThread {
                updateWifiStatusLine()
                showWifi(wifi.networks())
            }
        }
        // Fonction décochée : aucun récepteur n'est posé. Les enregistrer pour ne rien
        // en faire réveillerait l'activité à chaque événement Bluetooth du système.
        if (prefs.bluetoothEnabled) {
            btWatcher = bt.onChanges {
                runOnUiThread {
                    // Le récepteur reçoit aussi les appareils trouvés un à un : on les
                    // garde, la liste de l'adaptateur ne les conservant pas entre deux
                    // recherches.
                    refreshBluetooth()
                }
            }
            registerDiscoveryReceiver()
        }
    }

    /**
     * Récepteur dédié à `ACTION_FOUND` : il faut extraire l'appareil de l'intention, ce
     * que le rappel générique de [BluetoothController.onChanges] ne transmet pas.
     */
    private var discoveryReceiver: android.content.BroadcastReceiver? = null

    private fun registerDiscoveryReceiver() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_FOUND) return
                val device = intent.getParcelableExtra<BluetoothDevice>(
                    BluetoothDevice.EXTRA_DEVICE
                ) ?: return
                discovered[device.address] = device
                refreshBluetooth()
            }
        }
        registerReceiver(receiver, android.content.IntentFilter(BluetoothDevice.ACTION_FOUND))
        discoveryReceiver = receiver
    }

    override fun onStop() {
        super.onStop()
        wifiWatcher?.close()
        btWatcher?.close()
        discoveryReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Déjà retiré : sans conséquence.
            }
        }
        discoveryReceiver = null
        bt.cancelDiscovery()
    }

    private fun ensureLocationPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) refreshWifi(scan = true)
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- adaptateurs

    /** Les deux listes partagent `item_network` : même gabarit, contenus différents. */
    private abstract inner class RowAdapter : BaseAdapter() {
        override fun getItemId(position: Int) = position.toLong()

        protected fun row(convertView: View?, parent: ViewGroup?): View =
            convertView ?: layoutInflater.inflate(R.layout.item_network, parent, false)
    }

    private inner class WifiAdapter : RowAdapter() {
        override fun getCount() = wifiNetworks.size
        override fun getItem(position: Int) = wifiNetworks[position]

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = row(convertView, parent)
            val network = wifiNetworks[position]

            view.findViewById<TextView>(R.id.row_name).text = network.ssid
            view.findViewById<TextView>(R.id.row_detail).text = buildString {
                append(
                    getString(
                        if (network.secured) R.string.wifi_secured else R.string.wifi_open
                    )
                )
                if (network.saved) append(" · ").append(getString(R.string.wifi_saved))
            }
            // Les barres pleines et vides donnent la réception d'un coup d'œil, sans
            // dépendre d'une ressource graphique.
            view.findViewById<TextView>(R.id.row_badge).text =
                if (network.current) "●" else "▮".repeat(network.bars.coerceAtLeast(1))
            return view
        }
    }

    private inner class BluetoothAdapter : RowAdapter() {
        override fun getCount() = btDevices.size
        override fun getItem(position: Int) = btDevices[position]

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = row(convertView, parent)
            val device = btDevices[position]

            view.findViewById<TextView>(R.id.row_name).text = device.name
            view.findViewById<TextView>(R.id.row_detail).text = buildString {
                append(device.address)
                if (device.paired) append(" · ").append(getString(R.string.bt_paired))
            }
            view.findViewById<TextView>(R.id.row_badge).text =
                if (device.connected) getString(R.string.bt_connected) else ""
            return view
        }
    }

    companion object {
        private const val REQUEST_LOCATION = 41

        /**
         * Ouvre cet écran si le panneau n'a aucune liaison filaire — et seulement alors.
         * Appelé au démarrage du tableau de bord.
         */
        fun openIfNoNetwork(activity: AppCompatActivity, prefs: Prefs): Boolean {
            if (!prefs.wifiFallback) return false
            if (WifiController.hasEthernet()) return false
            if (WifiController(activity).currentSsid() != null) return false
            activity.startActivity(Intent(activity, NetworkActivity::class.java))
            return true
        }
    }
}
