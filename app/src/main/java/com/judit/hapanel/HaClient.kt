package com.judit.hapanel

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Client Home Assistant : WebSocket pour les états en temps réel et les appels de service,
 * REST pour la publication des capteurs du panneau.
 *
 * Aucun WebView n'est utilisé — c'est précisément ce qui rend cette app viable sur ce
 * matériel, dont le moteur WebView système est inutilisable.
 */
class HaClient(private val prefs: Prefs) {

    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onStatesLoaded(entities: List<Entity>)
        fun onStateChanged(entity: Entity)
    }

    var listener: Listener? = null

    /**
     * Reçoit les événements du pipeline vocal. Séparé de [Listener] parce que ces
     * événements n'ont rien à voir avec les entités : ils décrivent le déroulement
     * d'une conversation, du micro jusqu'à la réponse parlée.
     */
    var pipelineListener: ((eventType: String, data: JSONObject) -> Unit)? = null

    /** Identifiant du message du pipeline en cours, pour trier les événements reçus. */
    private var pipelineId = -1

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())
    private val nextId = AtomicInteger(1)
    private var ws: WebSocket? = null
    private var authenticated = false
    private var closedByUs = false
    private var retryDelayMs = 2_000L

    private var getStatesId = -1

    fun connect() {
        closedByUs = false
        val request = Request.Builder().url(prefs.wsUrl()).build()
        ws = http.newWebSocket(request, SocketListener())
    }

    fun disconnect() {
        closedByUs = true
        authenticated = false
        ws?.close(1000, "fermeture normale")
        ws = null
    }

    private fun scheduleReconnect(reason: String) {
        if (closedByUs) return
        main.post { listener?.onDisconnected(reason) }
        main.postDelayed({ if (!closedByUs) connect() }, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
    }

    // ---------------------------------------------------------------- services

    fun callService(domain: String, service: String, entityId: String, data: JSONObject? = null) {
        val socket = ws ?: return
        if (!authenticated) return
        val payload = JSONObject().apply {
            put("id", nextId.getAndIncrement())
            put("type", "call_service")
            put("domain", domain)
            put("service", service)
            put("target", JSONObject().put("entity_id", entityId))
            if (data != null) put("service_data", data)
        }
        socket.send(payload.toString())
    }

    /**
     * Bascule l'entité.
     *
     * On s'appuie sur les services `toggle` de Home Assistant plutôt que de choisir
     * nous-mêmes entre `turn_on` et `turn_off`. C'est délibéré : l'état dont nous
     * disposons ici peut être périmé de quelques centaines de millisecondes, et deux
     * appuis rapprochés envoyaient alors deux fois la même commande — la lampe était
     * déjà éteinte, on redemandait « éteins », et rien ne se passait. Le serveur, lui,
     * connaît l'état réel au moment où il traite l'ordre.
     */
    // ------------------------------------------------------------ pipeline vocal

    /**
     * Démarre une conversation : transcription, interprétation, puis réponse parlée.
     * Home Assistant répond par un événement `run-start` contenant l'identifiant du
     * canal binaire sur lequel envoyer l'audio.
     *
     * Retourne false si la connexion n'est pas prête.
     */
    fun startAssistPipeline(sampleRate: Int = 16_000): Boolean {
        val socket = ws ?: return false
        if (!authenticated) return false

        pipelineId = nextId.getAndIncrement()
        val payload = JSONObject().apply {
            put("id", pipelineId)
            put("type", "assist_pipeline/run")
            put("start_stage", "stt")
            put("end_stage", "tts")
            put("input", JSONObject().put("sample_rate", sampleRate))
        }
        return socket.send(payload.toString())
    }

    /**
     * Envoie un fragment audio. Le protocole veut que chaque trame binaire commence par
     * l'octet d'identifiant du canal fourni au démarrage du pipeline.
     */
    fun sendAudioChunk(handlerId: Int, pcm: ByteArray, length: Int): Boolean {
        val socket = ws ?: return false
        val framed = ByteArray(length + 1)
        framed[0] = handlerId.toByte()
        System.arraycopy(pcm, 0, framed, 1, length)
        return socket.send(framed.toByteString())
    }

    /** Signale la fin de la parole : une trame ne contenant que l'identifiant. */
    fun endAudioStream(handlerId: Int): Boolean {
        val socket = ws ?: return false
        return socket.send(byteArrayOf(handlerId.toByte()).toByteString())
    }

    fun toggle(entity: Entity) {
        when (entity.domain) {
            "cover" -> callService("cover", "toggle", entity.entityId)
            // Pas de `toggle` pour un lecteur : play/pause joue ce rôle.
            "media_player" -> callService("media_player", "media_play_pause", entity.entityId)
            "climate" -> callService("climate", "toggle", entity.entityId)
            else -> callService("homeassistant", "toggle", entity.entityId)
        }
    }

    /** Applique une valeur 0..1 à l'entité, selon ce qu'elle sait régler. */
    fun applyValue(entity: Entity, normalised: Float) {
        val v = normalised.coerceIn(0f, 1f)
        when (entity.adjustable) {
            Entity.Adjustable.BRIGHTNESS -> callService(
                "light", "turn_on", entity.entityId,
                JSONObject().put("brightness", (v * 255).toInt().coerceIn(0, 255))
            )
            Entity.Adjustable.VOLUME -> callService(
                "media_player", "volume_set", entity.entityId,
                JSONObject().put("volume_level", v.toDouble())
            )
            Entity.Adjustable.POSITION -> callService(
                "cover", "set_cover_position", entity.entityId,
                JSONObject().put("position", (v * 100).toInt())
            )
            Entity.Adjustable.PERCENTAGE -> callService(
                "fan", "set_percentage", entity.entityId,
                JSONObject().put("percentage", (v * 100).toInt())
            )
            Entity.Adjustable.TEMPERATURE -> {
                val min = entity.attributes.optDouble("min_temp", 7.0)
                val max = entity.attributes.optDouble("max_temp", 35.0)
                val target = min + (max - min) * v
                callService(
                    "climate", "set_temperature", entity.entityId,
                    JSONObject().put("temperature", Math.round(target * 2.0) / 2.0)
                )
            }
            null -> Unit
        }
    }

    // ------------------------------------------------------------------- REST

    /**
     * Publie un capteur du panneau vers Home Assistant.
     * Appelé depuis un thread de fond uniquement.
     */
    fun publishSensor(objectId: String, state: String, unit: String?, friendlyName: String, deviceClass: String?) {
        try {
            val attrs = JSONObject().put("friendly_name", friendlyName)
            if (unit != null) attrs.put("unit_of_measurement", unit)
            if (deviceClass != null) attrs.put("device_class", deviceClass)
            val body = JSONObject()
                .put("state", state)
                .put("attributes", attrs)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val req = Request.Builder()
                .url("${prefs.httpBase()}/api/states/sensor.$objectId")
                .header("Authorization", "Bearer ${prefs.token}")
                .post(body)
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) Log.w(TAG, "publication $objectId refusée : ${resp.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "publication $objectId impossible : ${e.message}")
        }
    }

    // --------------------------------------------------------------- socket

    private inner class SocketListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            retryDelayMs = 2_000L
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = try {
                JSONObject(text)
            } catch (e: Exception) {
                Log.w(TAG, "message illisible"); return
            }

            when (msg.optString("type")) {
                "auth_required" -> webSocket.send(
                    JSONObject().put("type", "auth").put("access_token", prefs.token).toString()
                )

                "auth_invalid" -> {
                    authenticated = false
                    closedByUs = true
                    main.post { listener?.onDisconnected(
                            LocaleHelper.wrap(prefs.appContext)
                                .getString(R.string.ha_token_refused)
                        ) }
                    webSocket.close(1000, "auth_invalid")
                }

                "auth_ok" -> {
                    authenticated = true
                    main.post { listener?.onConnected() }
                    getStatesId = nextId.getAndIncrement()
                    webSocket.send(JSONObject().put("id", getStatesId).put("type", "get_states").toString())
                    webSocket.send(
                        JSONObject()
                            .put("id", nextId.getAndIncrement())
                            .put("type", "subscribe_events")
                            .put("event_type", "state_changed")
                            .toString()
                    )
                }

                "result" -> {
                    if (msg.optInt("id") == getStatesId && msg.optBoolean("success")) {
                        val arr = msg.optJSONArray("result") ?: return
                        val list = ArrayList<Entity>(arr.length())
                        for (i in 0 until arr.length()) {
                            list.add(Entity.fromJson(arr.getJSONObject(i)))
                        }
                        main.post { listener?.onStatesLoaded(list) }
                    }
                }

                "event" -> {
                    val event = msg.optJSONObject("event") ?: return

                    // Les événements du pipeline vocal arrivent sous le même type que
                    // les changements d'état : on les distingue par l'identifiant du
                    // message auquel ils répondent.
                    if (msg.optInt("id") == pipelineId) {
                        val eventType = event.optString("type")
                        val data = event.optJSONObject("data") ?: JSONObject()
                        main.post { pipelineListener?.invoke(eventType, data) }
                        return
                    }

                    val newState = event.optJSONObject("data")
                        ?.optJSONObject("new_state") ?: return
                    val entity = Entity.fromJson(newState)
                    main.post { listener?.onStateChanged(entity) }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            authenticated = false
            scheduleReconnect(t.message ?: "connexion perdue")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            authenticated = false
            scheduleReconnect("fermé ($code)")
        }
    }

    companion object {
        private const val TAG = "HaClient"

        /**
         * Récupère la liste complète des entités en REST, sans ouvrir de WebSocket.
         * Utilisé par le sélecteur d'entités, qui n'a pas besoin du temps réel.
         *
         * Bloquant : à appeler depuis un thread de fond. Lève une exception en cas
         * d'échec, pour que l'appelant puisse afficher la raison.
         */
        /**
         * Associe chaque entité à sa pièce.
         *
         * Les pièces (« areas ») ne sont pas exposées par /api/states : elles vivent
         * dans le registre, accessible en WebSocket. Plutôt que d'ouvrir une seconde
         * connexion temps réel juste pour ça, on interroge le moteur de gabarits de
         * Home Assistant, qui répond en un seul appel REST.
         *
         * Retourne une association entity_id → nom de pièce. Les entités sans pièce
         * sont absentes. En cas d'échec, retourne une association vide : le sélecteur
         * reste utilisable, simplement sans filtre par pièce.
         */
        fun fetchAreas(prefs: Prefs): Map<String, String> {
            return try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                val template =
                    "{% for s in states %}{{ s.entity_id }}|{{ area_name(s.entity_id) or '' }}\n{% endfor %}"
                val body = JSONObject().put("template", template).toString()
                    .toRequestBody("application/json".toMediaType())

                val req = Request.Builder()
                    .url("${prefs.httpBase()}/api/template")
                    .header("Authorization", "Bearer ${prefs.token}")
                    .post(body)
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "pièces indisponibles : réponse ${resp.code}")
                        return emptyMap()
                    }
                    val out = HashMap<String, String>()
                    resp.body?.string().orEmpty().lineSequence().forEach { line ->
                        val sep = line.indexOf('|')
                        if (sep > 0) {
                            val id = line.substring(0, sep).trim()
                            val area = line.substring(sep + 1).trim()
                            if (id.isNotEmpty() && area.isNotEmpty()) out[id] = area
                        }
                    }
                    out
                }
            } catch (e: Exception) {
                Log.w(TAG, "pièces indisponibles : ${e.message}")
                emptyMap()
            }
        }

        /**
         * Liste les flux déclarés dans go2rtc, qui accompagne généralement Frigate.
         *
         * C'est la source à privilégier pour afficher une caméra sur ce panneau :
         * `/api/frame.jpeg?src=<flux>` renvoie un JPEG léger, sans authentification et
         * sans décodage vidéo. Beaucoup de caméras n'exposent aucune image fixe côté
         * Home Assistant, dont l'API répond alors 500.
         *
         * Retourne une liste vide si go2rtc est injoignable : ce n'est pas une erreur,
         * simplement une source indisponible.
         */
        fun fetchGo2rtcStreams(baseUrl: String): List<String> {
            if (baseUrl.isBlank()) return emptyList()
            return try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder().url("$baseUrl/api/streams").get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return emptyList()
                    val json = JSONObject(resp.body?.string().orEmpty())
                    json.keys().asSequence().toList().sorted()
                }
            } catch (e: Exception) {
                Log.w(TAG, "go2rtc injoignable : ${e.message}")
                emptyList()
            }
        }

        @Throws(Exception::class)
        fun fetchStates(prefs: Prefs): List<Entity> {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()

            val req = Request.Builder()
                .url("${prefs.httpBase()}/api/states")
                .header("Authorization", "Bearer ${prefs.token}")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.code == 401) throw IllegalStateException("jeton refusé par Home Assistant")
                if (!resp.isSuccessful) throw IllegalStateException("réponse ${resp.code}")
                val body = resp.body?.string().orEmpty()
                val arr = org.json.JSONArray(body)
                val out = ArrayList<Entity>(arr.length())
                for (i in 0 until arr.length()) out.add(Entity.fromJson(arr.getJSONObject(i)))
                return out
            }
        }
    }
}
