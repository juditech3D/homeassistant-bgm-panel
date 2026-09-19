package com.judit.hapanel.dlna

import android.util.Log
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Serveur HTTP minimal qui expose le panneau comme MediaRenderer UPnP.
 *
 * Il sert la description du périphérique, les descriptions de services, et traite les
 * commandes SOAP des trois services qu'un contrôleur attend d'un lecteur : AVTransport
 * pour le transport, RenderingControl pour le volume, ConnectionManager pour la
 * négociation des formats.
 *
 * Écrit à la main plutôt qu'avec une bibliothèque UPnP : celles-ci sont volumineuses,
 * souvent incompatibles avec API 27, et on n'a besoin que d'une douzaine d'actions.
 *
 * Les abonnements GENA (`SUBSCRIBE`) sont acceptés mais aucun événement n'est émis :
 * les contrôleurs interrogent l'état en secours, ce qui suffit ici et évite d'écrire
 * toute la machinerie d'événements.
 */
class UpnpServer(
    private val uuid: String,
    private val friendlyName: String,
    private val player: RendererPlayer,
    private val volumeProvider: () -> Int,
    private val volumeSetter: (Int) -> Unit,
    private val muteProvider: () -> Boolean,
    private val muteSetter: (Boolean) -> Unit
) {

    @Volatile private var running = false
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    private val subscriptionCounter = AtomicInteger(1)

    var port: Int = 0
        private set

    fun start(): Boolean {
        if (running) return true
        return try {
            val s = ServerSocket(0)
            server = s
            port = s.localPort
            running = true
            thread(name = "upnp-http", isDaemon = true) { acceptLoop(s) }
            Log.i(TAG, "serveur UPnP en écoute sur le port $port")
            true
        } catch (e: Exception) {
            Log.w(TAG, "impossible d'ouvrir le serveur : ${e.message}")
            false
        }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (e: Exception) {
            // déjà fermé
        }
        server = null
        pool.shutdownNow()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            try {
                val client = s.accept()
                pool.execute { handle(client) }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept interrompu : ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------ requête HTTP

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 10_000
            val input = BufferedInputStream(client.getInputStream())

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')

            var contentLength = 0
            var soapAction = ""
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
                val name = header.substringBefore(':').trim().lowercase()
                val value = header.substringAfter(':').trim()
                when (name) {
                    "content-length" -> contentLength = value.toIntOrNull() ?: 0
                    "soapaction" -> soapAction = value.trim('"')
                }
            }

            val body = if (contentLength > 0) {
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            route(client.getOutputStream(), method, path, soapAction, body)
        } catch (e: Exception) {
            Log.w(TAG, "requête abandonnée : ${e.message}")
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
                // rien à faire
            }
        }
    }

    private fun route(out: OutputStream, method: String, path: String, action: String, body: String) {
        when {
            method == "GET" && path == "/description.xml" ->
                sendXml(out, UpnpXml.deviceDescription(uuid, friendlyName))

            method == "GET" && path == "/AVTransport.xml" ->
                sendXml(out, UpnpXml.AV_TRANSPORT_SCPD)

            method == "GET" && path == "/RenderingControl.xml" ->
                sendXml(out, UpnpXml.RENDERING_CONTROL_SCPD)

            method == "GET" && path == "/ConnectionManager.xml" ->
                sendXml(out, UpnpXml.CONNECTION_MANAGER_SCPD)

            method == "SUBSCRIBE" -> sendSubscription(out)
            method == "UNSUBSCRIBE" -> sendStatus(out, "200 OK")

            method == "POST" && path.endsWith("/control") -> handleSoap(out, action, body)

            else -> sendStatus(out, "404 Not Found")
        }
    }

    // ------------------------------------------------------------------- SOAP

    private fun handleSoap(out: OutputStream, soapAction: String, body: String) {
        val action = soapAction.substringAfterLast('#')
        val service = soapAction.substringBeforeLast('#').trim('"')

        val response: String? = when (action) {
            // --- AVTransport ---
            "SetAVTransportURI" -> {
                val uri = arg(body, "CurrentURI")
                val meta = arg(body, "CurrentURIMetaData")
                player.setUri(uri, meta)
                empty(action, service)
            }
            "SetNextAVTransportURI" -> empty(action, service)
            "Play" -> { player.play(); empty(action, service) }
            "Pause" -> { player.pause(); empty(action, service) }
            "Stop" -> { player.stop(); empty(action, service) }
            "Seek" -> {
                player.seekTo(RendererPlayer.parseUpnpTime(arg(body, "Target")))
                empty(action, service)
            }

            "GetTransportInfo" -> soap(action, service, mapOf(
                "CurrentTransportState" to player.state.name,
                "CurrentTransportStatus" to "OK",
                "CurrentSpeed" to "1"
            ))

            "GetPositionInfo" -> soap(action, service, mapOf(
                "Track" to "1",
                "TrackDuration" to RendererPlayer.upnpTime(player.durationSeconds()),
                "TrackMetaData" to player.currentMetadata,
                "TrackURI" to player.currentUri,
                "RelTime" to RendererPlayer.upnpTime(player.positionSeconds()),
                "AbsTime" to RendererPlayer.upnpTime(player.positionSeconds()),
                "RelCount" to "2147483647",
                "AbsCount" to "2147483647"
            ))

            "GetMediaInfo" -> soap(action, service, mapOf(
                "NrTracks" to if (player.currentUri.isEmpty()) "0" else "1",
                "MediaDuration" to RendererPlayer.upnpTime(player.durationSeconds()),
                "CurrentURI" to player.currentUri,
                "CurrentURIMetaData" to player.currentMetadata,
                "NextURI" to "",
                "NextURIMetaData" to "",
                "PlayMedium" to "NETWORK",
                "RecordMedium" to "NOT_IMPLEMENTED",
                "WriteStatus" to "NOT_IMPLEMENTED"
            ))

            "GetTransportSettings" -> soap(action, service, mapOf(
                "PlayMode" to "NORMAL",
                "RecQualityMode" to "NOT_IMPLEMENTED"
            ))

            "GetDeviceCapabilities" -> soap(action, service, mapOf(
                "PlayMedia" to "NETWORK",
                "RecMedia" to "NOT_IMPLEMENTED",
                "RecQualityModes" to "NOT_IMPLEMENTED"
            ))

            // --- RenderingControl ---
            "GetVolume" -> soap(action, service, mapOf("CurrentVolume" to volumeProvider().toString()))
            "SetVolume" -> {
                arg(body, "DesiredVolume").toIntOrNull()?.let { volumeSetter(it) }
                empty(action, service)
            }
            "GetMute" -> soap(action, service, mapOf("CurrentMute" to if (muteProvider()) "1" else "0"))
            "SetMute" -> {
                val desired = arg(body, "DesiredMute")
                muteSetter(desired == "1" || desired.equals("true", ignoreCase = true))
                empty(action, service)
            }

            // --- ConnectionManager ---
            "GetProtocolInfo" -> soap(action, service, mapOf(
                "Source" to "",
                "Sink" to UpnpXml.SINK_PROTOCOLS
            ))
            "GetCurrentConnectionIDs" -> soap(action, service, mapOf("ConnectionIDs" to "0"))
            "GetCurrentConnectionInfo" -> soap(action, service, mapOf(
                "RcsID" to "0",
                "AVTransportID" to "0",
                "ProtocolInfo" to "",
                "PeerConnectionManager" to "",
                "PeerConnectionID" to "-1",
                "Direction" to "Input",
                "Status" to "OK"
            ))

            else -> null
        }

        if (response == null) {
            Log.w(TAG, "action non gérée : $soapAction")
            sendStatus(out, "501 Not Implemented")
        } else {
            sendXml(out, response)
        }
    }

    /** Récupère la valeur d'un argument SOAP, sans dépendre d'un analyseur XML complet. */
    private fun arg(body: String, name: String): String {
        val regex = Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
        return unescape(regex.find(body)?.groupValues?.get(1)?.trim().orEmpty())
    }

    private fun empty(action: String, service: String) = soap(action, service, emptyMap())

    private fun soap(action: String, service: String, values: Map<String, String>): String {
        val inner = values.entries.joinToString("") { (k, v) -> "<$k>${escape(v)}</$k>" }
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:${action}Response xmlns:u="$service">$inner</u:${action}Response></s:Body>
</s:Envelope>"""
    }

    // -------------------------------------------------------------- réponses

    private fun sendXml(out: OutputStream, xml: String) {
        val bytes = xml.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/xml; charset=\"utf-8\"\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun sendSubscription(out: OutputStream) {
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("SID: uuid:sub-${subscriptionCounter.getAndIncrement()}\r\n")
            append("TIMEOUT: Second-1800\r\n")
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray())
        out.flush()
    }

    private fun sendStatus(out: OutputStream, status: String) {
        out.write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun unescape(s: String) = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    private companion object {
        const val TAG = "UpnpServer"
    }
}
