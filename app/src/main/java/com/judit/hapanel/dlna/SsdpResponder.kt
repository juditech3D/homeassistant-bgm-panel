package com.judit.hapanel.dlna

import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * Annonce le panneau sur le réseau local en SSDP, le mécanisme de découverte d'UPnP.
 *
 * Deux moitiés, toutes deux nécessaires : on **répond** aux recherches `M-SEARCH` des
 * contrôleurs, et on **diffuse** périodiquement des `NOTIFY ssdp:alive` pour ceux qui
 * écoutent en permanence. Music Assistant utilise les deux selon les cas.
 *
 * À l'arrêt on envoie des `ssdp:byebye`, faute de quoi le contrôleur continuerait
 * d'afficher un lecteur injoignable jusqu'à expiration du cache.
 */
class SsdpResponder(
    private val uuid: String,
    private val localIp: String,
    private val httpPort: Int
) {

    @Volatile private var running = false
    private var socket: MulticastSocket? = null

    /** Les cibles auxquelles ce périphérique doit répondre. */
    private val targets = listOf(
        "upnp:rootdevice",
        "uuid:$uuid",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
        "urn:schemas-upnp-org:service:RenderingControl:1",
        "urn:schemas-upnp-org:service:ConnectionManager:1"
    )

    private fun location() = "http://$localIp:$httpPort/description.xml"

    private fun usnFor(target: String) =
        if (target.startsWith("uuid:")) target else "uuid:$uuid::$target"

    fun start() {
        if (running) return
        running = true

        thread(name = "ssdp-listen", isDaemon = true) { listenLoop() }
        thread(name = "ssdp-alive", isDaemon = true) { aliveLoop() }
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            sendByebye()
        } catch (e: Exception) {
            Log.w(TAG, "byebye impossible : ${e.message}")
        }
        try {
            socket?.close()
        } catch (e: Exception) {
            // socket déjà fermée
        }
        socket = null
    }

    // ------------------------------------------------------------- réception

    private fun listenLoop() {
        try {
            val s = MulticastSocket(SSDP_PORT).apply {
                reuseAddress = true
                // Sans interface explicite, le multicast part souvent sur la mauvaise
                // carte : ce panneau a Ethernet et Wi-Fi actifs en même temps.
                multicastInterface()?.let { networkInterface = it }
                joinGroup(InetSocketAddress(InetAddress.getByName(SSDP_ADDR), SSDP_PORT), multicastInterface())
            }
            socket = s

            val buffer = ByteArray(2048)
            while (running) {
                val packet = DatagramPacket(buffer, buffer.size)
                s.receive(packet)
                val message = String(packet.data, 0, packet.length)
                if (message.startsWith("M-SEARCH", ignoreCase = true)) {
                    handleSearch(message, packet.address, packet.port)
                }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "écoute SSDP interrompue : ${e.message}")
        }
    }

    private fun handleSearch(message: String, from: InetAddress, port: Int) {
        val st = message.lineSequence()
            .firstOrNull { it.startsWith("ST:", ignoreCase = true) }
            ?.substringAfter(':')?.trim() ?: return

        val matched = when {
            st == "ssdp:all" -> targets
            targets.contains(st) -> listOf(st)
            else -> return
        }

        // Le délai MX demandé par le contrôleur sert à étaler les réponses ; on reste
        // volontairement bref, le réseau local est petit.
        for (target in matched) {
            val response = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("CACHE-CONTROL: max-age=$MAX_AGE\r\n")
                append("EXT:\r\n")
                append("LOCATION: ${location()}\r\n")
                append("SERVER: Android/8.1 UPnP/1.0 HAPanel/1.0\r\n")
                append("ST: $target\r\n")
                append("USN: ${usnFor(target)}\r\n")
                append("\r\n")
            }.toByteArray()

            try {
                socket?.send(DatagramPacket(response, response.size, from, port))
            } catch (e: Exception) {
                Log.w(TAG, "réponse M-SEARCH impossible : ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------- diffusion

    private fun aliveLoop() {
        while (running) {
            try {
                sendNotify("ssdp:alive")
            } catch (e: Exception) {
                Log.w(TAG, "alive impossible : ${e.message}")
            }
            // On réannonce bien avant l'expiration du cache, pour couvrir les pertes.
            Thread.sleep(ALIVE_PERIOD_MS)
        }
    }

    private fun sendNotify(subtype: String) {
        val group = InetAddress.getByName(SSDP_ADDR)
        for (target in targets) {
            val message = buildString {
                append("NOTIFY * HTTP/1.1\r\n")
                append("HOST: $SSDP_ADDR:$SSDP_PORT\r\n")
                append("CACHE-CONTROL: max-age=$MAX_AGE\r\n")
                append("LOCATION: ${location()}\r\n")
                append("SERVER: Android/8.1 UPnP/1.0 HAPanel/1.0\r\n")
                append("NT: $target\r\n")
                append("NTS: $subtype\r\n")
                append("USN: ${usnFor(target)}\r\n")
                append("\r\n")
            }.toByteArray()

            socket?.send(DatagramPacket(message, message.size, group, SSDP_PORT))
        }
    }

    private fun sendByebye() = sendNotify("ssdp:byebye")

    private fun multicastInterface(): NetworkInterface? = try {
        NetworkInterface.getNetworkInterfaces().toList().firstOrNull { nif ->
            nif.isUp && !nif.isLoopback && nif.supportsMulticast() &&
                nif.inetAddresses.toList().any { it.hostAddress == localIp }
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "SsdpResponder"
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val MAX_AGE = 1800
        private const val ALIVE_PERIOD_MS = 600_000L

        /** Adresse IPv4 du panneau, Ethernet de préférence au Wi-Fi. */
        fun localIpAddress(): String? = try {
            val candidates = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif -> nif.inetAddresses.toList().map { nif.name to it } }
                .filter { (_, addr) -> addr.hostAddress?.contains('.') == true }
            (candidates.firstOrNull { it.first.startsWith("eth") } ?: candidates.firstOrNull())
                ?.second?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
