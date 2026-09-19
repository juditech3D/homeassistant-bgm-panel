package com.judit.hapanel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log

/**
 * Connexion au Wi-Fi depuis l'application.
 *
 * Le panneau est livré câblé en Ethernet, et l'interface d'origine — la seule à proposer
 * un choix de réseau — est désactivée pour que le bouton rotatif fonctionne. Sans cette
 * classe, passer le panneau en Wi-Fi obligerait à repasser par ADB.
 *
 * L'API employée (`WifiConfiguration`, `addNetwork`, `enableNetwork`) est dépréciée
 * depuis Android 10, mais c'est la seule qui existe sur Android 8.1 : le remplaçant,
 * `WifiNetworkSuggestion`, n'apparaît qu'en API 29. Le `targetSdk` du projet étant 27,
 * elle reste pleinement fonctionnelle.
 */
class WifiController(private val context: Context) {

    private val wifi =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** Un réseau visible, ou déjà enregistré, tel que présenté à l'écran. */
    data class Network(
        val ssid: String,
        val level: Int,
        val secured: Boolean,
        val saved: Boolean,
        val current: Boolean
    ) {
        /** 0 à 4 barres, comme l'indicateur d'Android. */
        val bars: Int get() = WifiManager.calculateSignalLevel(level, 5)
    }

    var isEnabled: Boolean
        get() = wifi.isWifiEnabled
        @Suppress("DEPRECATION")
        set(v) {
            wifi.isWifiEnabled = v
        }

    /**
     * Le SSID auquel le panneau est relié, ou null. Android encadre le nom de guillemets
     * et renvoie `<unknown ssid>` quand il n'a pas encore l'information.
     */
    fun currentSsid(): String? {
        val raw = wifi.connectionInfo?.ssid?.trim('"') ?: return null
        return raw.takeIf { it.isNotEmpty() && it != UNKNOWN }
    }

    /** L'adresse IPv4 obtenue en Wi-Fi, ou null si l'interface n'est pas active. */
    fun currentIp(): String? {
        val ip = wifi.connectionInfo?.ipAddress ?: return null
        if (ip == 0) return null
        return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
    }

    /**
     * Android 8.1 ne rend les résultats de balayage qu'aux applications qui détiennent
     * l'autorisation de localisation **et** quand la localisation est activée dans les
     * réglages du système. Sur un panneau mural sans GPS, elle est souvent éteinte : la
     * recherche renverrait alors une liste vide, sans la moindre erreur.
     */
    fun locationServicesOff(): Boolean = try {
        Settings.Secure.getInt(
            context.contentResolver, Settings.Secure.LOCATION_MODE
        ) == Settings.Secure.LOCATION_MODE_OFF
    } catch (e: Settings.SettingNotFoundException) {
        false
    }

    /**
     * Active la localisation par le service `su`. Le panneau est rooté et en SELinux
     * permissif ; sur un appareil qui ne le serait pas, l'appel échoue sans conséquence
     * et l'écran invite à le faire à la main.
     */
    fun tryEnableLocationServices(): Boolean = try {
        val p = Runtime.getRuntime().exec(
            arrayOf(
                "su", "0", "settings", "put", "secure",
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_HIGH_ACCURACY.toString()
            )
        )
        p.waitFor() == 0 && !locationServicesOff()
    } catch (e: Exception) {
        Log.w(TAG, "impossible d'activer la localisation : ${e.message}")
        false
    }

    /** Lance un balayage. Les résultats arrivent par [onScanResults]. */
    @Suppress("DEPRECATION")
    fun startScan(): Boolean = try {
        wifi.startScan()
    } catch (e: Exception) {
        Log.w(TAG, "balayage refusé : ${e.message}")
        false
    }

    /**
     * S'abonne aux résultats de balayage. Retourne de quoi se désabonner : l'écran de
     * réseau est éphémère, un récepteur laissé en place fuirait l'activité.
     */
    fun onScanResults(listener: (List<Network>) -> Unit): AutoCloseable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = listener(networks())
        }
        context.registerReceiver(
            receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )
        return AutoCloseable {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Déjà retiré : sans conséquence.
            }
        }
    }

    /**
     * Les réseaux visibles, du plus fort au plus faible.
     *
     * Un même SSID apparaît autant de fois qu'il y a de bornes — un réseau maillé en
     * produit trois ou quatre. On ne garde que la meilleure réception de chaque nom,
     * sans quoi la liste serait illisible.
     */
    @Suppress("DEPRECATION")
    fun networks(): List<Network> {
        val saved = savedSsids()
        val connected = currentSsid()
        val results = try {
            wifi.scanResults ?: emptyList()
        } catch (e: SecurityException) {
            Log.w(TAG, "résultats de balayage refusés : ${e.message}")
            emptyList()
        }

        return results
            .filter { it.SSID.isNotBlank() }
            .groupBy { it.SSID }
            .map { (ssid, group) ->
                val best = group.maxByOrNull { it.level }!!
                Network(
                    ssid = ssid,
                    level = best.level,
                    secured = isSecured(best.capabilities),
                    saved = ssid in saved,
                    current = ssid == connected
                )
            }
            .sortedWith(compareByDescending<Network> { it.current }.thenByDescending { it.level })
    }

    /** Les réseaux déjà enregistrés sur le panneau, visibles ou non. */
    @Suppress("DEPRECATION")
    fun savedSsids(): Set<String> = try {
        wifi.configuredNetworks?.mapNotNull { it.SSID?.trim('"') }?.toSet() ?: emptySet()
    } catch (e: SecurityException) {
        emptySet()
    }

    private fun isSecured(capabilities: String): Boolean =
        listOf("WEP", "PSK", "EAP", "SAE").any { it in capabilities }

    /**
     * Enregistre le réseau et s'y connecte. [password] est ignoré pour un réseau ouvert,
     * et peut être vide pour un réseau déjà enregistré.
     *
     * Retourne un message d'erreur, ou null si la demande est partie. La connexion
     * elle-même est asynchrone : c'est l'état affiché qui dira si elle a abouti.
     */
    @Suppress("DEPRECATION")
    fun connect(ssid: String, password: String?, secured: Boolean): String? {
        if (!isEnabled) isEnabled = true

        val quoted = "\"$ssid\""
        val existing = try {
            wifi.configuredNetworks?.firstOrNull { it.SSID == quoted }
        } catch (e: SecurityException) {
            null
        }

        // Un réseau déjà enregistré et sans mot de passe fourni : on le réutilise tel
        // quel. C'est le cas quand on revient sur un réseau connu.
        val netId = if (existing != null && password.isNullOrEmpty()) {
            existing.networkId
        } else {
            val config = WifiConfiguration().apply {
                SSID = quoted
                // Le réseau créé par une autre application ne peut pas être modifié :
                // on réutilise son identifiant pour que la pile Wi-Fi remplace la
                // configuration au lieu d'en ajouter une seconde, en double.
                if (existing != null) networkId = existing.networkId
                if (!secured) {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                } else {
                    preSharedKey = "\"$password\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                }
                status = WifiConfiguration.Status.ENABLED
            }
            val id = wifi.addNetwork(config)
            if (id == -1 && existing != null) existing.networkId else id
        }

        if (netId == -1) {
            return context.getString(R.string.wifi_add_failed)
        }

        wifi.disconnect()
        val enabled = wifi.enableNetwork(netId, true)
        wifi.saveConfiguration()
        wifi.reconnect()

        return if (enabled) null else context.getString(R.string.wifi_connect_failed)
    }

    /** Oublie un réseau enregistré. Sans effet s'il a été créé par une autre application. */
    @Suppress("DEPRECATION")
    fun forget(ssid: String): Boolean = try {
        val id = wifi.configuredNetworks?.firstOrNull { it.SSID == "\"$ssid\"" }?.networkId
        id != null && wifi.removeNetwork(id) && wifi.saveConfiguration()
    } catch (e: SecurityException) {
        false
    }

    companion object {
        private const val TAG = "HaPanelWifi"
        private const val UNKNOWN = "<unknown ssid>"

        /**
         * Vrai si une interface filaire porte une adresse IPv4 utilisable.
         *
         * Le panneau est livré avec un port RJ45 et c'est la liaison normale : tant
         * qu'elle fonctionne, il n'y a aucune raison d'importuner l'utilisateur avec le
         * Wi-Fi. L'écran de réseau ne s'ouvre de lui-même que si ce test échoue.
         *
         * On interroge directement les interfaces plutôt que `ConnectivityManager` :
         * `TRANSPORT_ETHERNET` n'est pas toujours renseigné sur ces images Rockchip,
         * alors que `eth0` porte bel et bien son adresse.
         */
        fun hasEthernet(): Boolean = try {
            java.net.NetworkInterface.getNetworkInterfaces().toList().any { itf ->
                itf.isUp && !itf.isLoopback &&
                    (itf.name.startsWith("eth") || itf.name.startsWith("usbnet")) &&
                    itf.inetAddresses.toList().any { addr ->
                        addr is java.net.Inet4Address && !addr.isLinkLocalAddress
                    }
            }
        } catch (e: Exception) {
            Log.w(TAG, "interfaces illisibles : ${e.message}")
            // Dans le doute, on suppose le filaire présent : mieux vaut ne pas ouvrir
            // un écran de réglage par surprise sur un panneau qui fonctionne.
            true
        }
    }
}
