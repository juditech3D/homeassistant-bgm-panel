package com.judit.hapanel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Partage les fichiers du panneau sur le réseau local, par une page web.
 *
 * Le panneau est encastré dans une boîte électrique : ni carte mémoire accessible, ni
 * port USB hôte. Déposer des photos pour le diaporama ou de la musique demandait donc
 * l'ADB, et donc un PC. Cette page permet de le faire depuis n'importe quel navigateur
 * de la maison, téléphone compris.
 *
 * ### Ce qui est partagé, et rien d'autre
 *
 * Un seul dossier, [ROOT], avec ses sous-dossiers. Tout chemin qui en sortirait — par
 * `..` ou par un lien — est refusé après résolution canonique. Le reste de la mémoire du
 * panneau, ses réglages et son jeton Home Assistant restent hors de portée.
 *
 * ### Sur la protection
 *
 * Le service écrit des fichiers sur l'appareil : ce n'est pas un partage en lecture
 * seule. Un mot de passe peut donc être exigé, et il l'est par authentification HTTP
 * simple. Laissé vide, le partage est ouvert à tout le réseau local — ce qui se défend
 * sur un réseau domestique, mais mérite d'être un choix et non un défaut subi. Le
 * service reste éteint tant qu'on ne l'allume pas dans les réglages.
 *
 * L'échange n'est pas chiffré. À réserver au réseau local ; ne l'exposez pas sur
 * Internet.
 */
class FileShareService : Service() {

    private val prefs by lazy { Prefs(this) }
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        startForeground(NOTIFICATION_ID, notification())
        running = true

        Thread {
            try {
                root().mkdirs()
                File(root(), "fonds").mkdirs()
                File(root(), "musique").mkdirs()

                val s = ServerSocket(prefs.fileSharePort)
                server = s
                Log.i(TAG, "partage de fichiers sur http://${localAddress()}:${s.localPort}/")
                while (running) {
                    try {
                        val client = s.accept()
                        pool.execute { handle(client) }
                    } catch (e: Exception) {
                        if (running) Log.w(TAG, "accept interrompu : ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "serveur impossible à ouvrir : ${e.message}")
                stopSelf()
            }
        }.start()

        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try {
            server?.close()
        } catch (e: Exception) {
            // rien à faire
        }
        pool.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------ requête HTTP

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 30_000
            val input = BufferedInputStream(client.getInputStream())
            val out = client.getOutputStream()

            val ligne = readLine(input) ?: return
            val morceaux = ligne.split(' ')
            if (morceaux.size < 2) return
            val methode = morceaux[0].uppercase()
            val cible = morceaux[1]
            val chemin = cible.substringBefore('?')
            val requete = cible.substringAfter('?', "")

            var taille = 0L
            var autorisation = ""
            var attendSuite = false
            val entetes = HashMap<String, String>()
            while (true) {
                val entete = readLine(input) ?: break
                if (entete.isEmpty()) break
                val nom = entete.substringBefore(':').trim().lowercase()
                val valeur = entete.substringAfter(':').trim()
                entetes[nom] = valeur
                when (nom) {
                    "content-length" -> taille = valeur.toLongOrNull() ?: 0L
                    "authorization" -> autorisation = valeur
                    "expect" -> attendSuite = valeur.equals("100-continue", ignoreCase = true)
                }
            }

            // curl annonce `Expect: 100-continue` des que le corps depasse le kilooctet,
            // puis attend l'accuse avant d'envoyer quoi que ce soit. Sans reponse, il
            // patiente jusqu'a expiration et l'envoi echoue sans explication. Les
            // navigateurs ne s'en servent pas, mais un script, si.
            if (attendSuite) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                out.flush()
            }

            if (!authorized(autorisation)) {
                // Le corps est absorbé avant de répondre : sans cela, le navigateur
                // écrirait dans une socket déjà close et afficherait une erreur réseau
                // au lieu de la demande de mot de passe.
                skip(input, taille)
                out.write(
                    ("HTTP/1.1 401 Unauthorized\r\n" +
                        "WWW-Authenticate: Basic realm=\"HA Panel\"\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
                )
                return
            }

            when {
                methode == "GET" && chemin == "/" -> sendPage(out)
                methode == "GET" && chemin == "/api/list" -> sendListing(out, param(requete, "d"))
                methode == "GET" && chemin.startsWith("/f/") ->
                    sendFile(out, decode(chemin.removePrefix("/f/")))

                methode == "PUT" && chemin == "/api/file" ->
                    receive(out, input, param(requete, "p"), taille)

                methode == "DELETE" && chemin == "/api/file" ->
                    remove(out, param(requete, "p"))

                // Tout le reste part vers WebDAV, ou le chemin designe directement un
                // fichier du dossier partage.
                else -> dav(out, input, methode, decode(chemin), taille, entetes)
            }
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

    /** Vrai quand aucun mot de passe n'est exigé, ou que celui présenté convient. */
    private fun authorized(entete: String): Boolean {
        val attendu = prefs.fileSharePassword
        if (attendu.isBlank()) return true
        if (!entete.startsWith("Basic ", ignoreCase = true)) return false
        return try {
            val clair = String(Base64.decode(entete.substring(6).trim(), Base64.DEFAULT))
            clair.substringAfter(':') == attendu
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------ les réponses

    private fun sendListing(out: OutputStream, relatif: String) {
        val dossier = resolve(relatif) ?: return status(out, "403 Forbidden")
        val entrees = dossier.listFiles().orEmpty().sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        )
        val json = buildString {
            append("{\"path\":").append(quote(relatif)).append(",\"entries\":[")
            entrees.forEachIndexed { i, f ->
                if (i > 0) append(',')
                append("{\"name\":").append(quote(f.name))
                append(",\"dir\":").append(f.isDirectory)
                append(",\"size\":").append(if (f.isDirectory) 0 else f.length())
                append('}')
            }
            append("]}")
        }
        send(out, "200 OK", "application/json; charset=utf-8", json.toByteArray())
    }

    private fun sendFile(out: OutputStream, relatif: String) {
        val fichier = resolve(relatif) ?: return status(out, "403 Forbidden")
        if (!fichier.isFile) return status(out, "404 Not Found")
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: ${mime(fichier.name)}\r\n" +
                "Content-Length: ${fichier.length()}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        fichier.inputStream().use { it.copyTo(out) }
        out.flush()
    }

    /**
     * Reçoit un fichier, corps brut et non multipart.
     *
     * La page envoie chaque fichier en `PUT`, tel quel : analyser un corps multipart à la
     * main serait long et fragile, alors que la lecture de `Content-Length` octets est
     * directe et ne peut pas se tromper sur les frontières.
     */
    private fun receive(out: OutputStream, input: BufferedInputStream, relatif: String, taille: Long) {
        val fichier = resolve(relatif)
        if (fichier == null || !accepted(fichier.name)) {
            skip(input, taille)
            return status(out, "403 Forbidden")
        }
        fichier.parentFile?.mkdirs()
        // Écrit à côté puis renommé : un envoi interrompu ne laisse pas un fichier
        // tronqué que le diaporama essaierait d'afficher.
        val partiel = File(fichier.parentFile, fichier.name + ".part")
        var recu = 0L
        partiel.outputStream().use { sortie ->
            val tampon = ByteArray(64 * 1024)
            while (recu < taille) {
                val n = input.read(tampon, 0, minOf(tampon.size.toLong(), taille - recu).toInt())
                if (n < 0) break
                sortie.write(tampon, 0, n)
                recu += n
            }
        }
        if (recu < taille) {
            partiel.delete()
            return status(out, "400 Bad Request")
        }
        fichier.delete()
        partiel.renameTo(fichier)
        status(out, "200 OK")
    }

    private fun remove(out: OutputStream, relatif: String) {
        val fichier = resolve(relatif) ?: return status(out, "403 Forbidden")
        if (!fichier.isFile) return status(out, "404 Not Found")
        status(out, if (fichier.delete()) "200 OK" else "500 Internal Server Error")
    }

    private fun sendPage(out: OutputStream) =
        send(out, "200 OK", "text/html; charset=utf-8", page().toByteArray())

    private fun send(out: OutputStream, statut: String, type: String, corps: ByteArray) {
        out.write(
            ("HTTP/1.1 $statut\r\nContent-Type: $type\r\n" +
                "Content-Length: ${corps.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(corps)
        out.flush()
    }

    private fun status(out: OutputStream, statut: String) {
        out.write("HTTP/1.1 $statut\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    // ------------------------------------------------------------------ WebDAV

    /**
     * Le meme dossier, vu comme un lecteur reseau par l'Explorateur Windows.
     *
     * La page web suffit depuis un telephone, mais depuis un PC on attend de pouvoir
     * glisser des fichiers dans une fenetre. Windows ne parle que deux langages pour
     * cela : SMB, qu'on ne reecrit pas a la main, et WebDAV, qui n'est que du HTTP avec
     * quelques verbes de plus. C'est donc WebDAV.
     *
     * Le strict necessaire est implemente : de quoi lister, lire, ecrire, effacer,
     * creer un dossier et renommer. `LOCK` repond un jeton de complaisance -- le
     * verrouillage n'a pas de sens sur un partage a un seul usage, mais l'Explorateur
     * refuse d'ecrire sans lui.
     *
     * **Cote Windows**, un partage sans mot de passe se monte directement. Avec mot de
     * passe, Windows refuse par defaut l'authentification simple hors HTTPS ; mieux vaut
     * alors s'en tenir au navigateur.
     */
    private fun dav(
        out: OutputStream,
        input: BufferedInputStream,
        methode: String,
        chemin: String,
        taille: Long,
        entetes: Map<String, String>
    ) {
        val relatif = chemin.trim('/')
        val cible = resolve(relatif)
        if (cible == null) {
            skip(input, taille)
            return status(out, "403 Forbidden")
        }

        when (methode) {
            "OPTIONS" -> {
                skip(input, taille)
                out.write(
                    ("HTTP/1.1 200 OK\r\n" +
                        "DAV: 1,2\r\n" +
                        "MS-Author-Via: DAV\r\n" +
                        "Allow: OPTIONS,GET,HEAD,PUT,DELETE,PROPFIND,MKCOL,MOVE,COPY," +
                        "LOCK,UNLOCK\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
                )
                out.flush()
            }

            "PROPFIND" -> {
                skip(input, taille)
                if (!cible.exists()) return status(out, "404 Not Found")
                val profond = entetes["depth"] != "0"
                send(
                    out, "207 Multi-Status", "application/xml; charset=utf-8",
                    propfind(cible, relatif, profond).toByteArray()
                )
            }

            "HEAD" -> {
                skip(input, taille)
                if (!cible.isFile) return status(out, "404 Not Found")
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: ${mime(cible.name)}\r\n" +
                        "Content-Length: ${cible.length()}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                out.flush()
            }

            "GET" -> {
                skip(input, taille)
                sendFile(out, relatif)
            }

            "PUT" -> receive(out, input, relatif, taille)

            "DELETE" -> {
                skip(input, taille)
                val efface = if (cible.isDirectory) cible.deleteRecursively() else cible.delete()
                status(out, if (efface) "204 No Content" else "404 Not Found")
            }

            "MKCOL" -> {
                skip(input, taille)
                status(out, if (cible.mkdirs()) "201 Created" else "405 Method Not Allowed")
            }

            "MOVE", "COPY" -> {
                skip(input, taille)
                // La destination arrive en adresse complete : seul son chemin compte.
                val brut = entetes["destination"].orEmpty()
                val versRelatif = decode(brut.substringAfter("://").substringAfter('/')).trim('/')
                val vers = resolve(versRelatif)
                if (vers == null || !cible.exists()) return status(out, "409 Conflict")
                vers.parentFile?.mkdirs()
                val fait = if (methode == "MOVE") {
                    vers.delete()
                    cible.renameTo(vers)
                } else {
                    cible.copyTo(vers, overwrite = true).exists()
                }
                status(out, if (fait) "201 Created" else "500 Internal Server Error")
            }

            "LOCK" -> {
                skip(input, taille)
                // Jeton de complaisance : rien n'est reellement verrouille, mais
                // l'Explorateur n'ecrit pas sans en recevoir un.
                val jeton = "opaquelocktoken:" + java.util.UUID.randomUUID()
                val corps = """<?xml version="1.0" encoding="utf-8"?>
<D:prop xmlns:D="DAV:"><D:lockdiscovery><D:activelock>
<D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope>
<D:depth>infinity</D:depth><D:timeout>Second-3600</D:timeout>
<D:locktoken><D:href>$jeton</D:href></D:locktoken>
</D:activelock></D:lockdiscovery></D:prop>"""
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: application/xml; charset=utf-8\r\n" +
                        "Lock-Token: <$jeton>\r\n" +
                        "Content-Length: ${corps.toByteArray().size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                out.write(corps.toByteArray())
                out.flush()
            }

            "UNLOCK" -> {
                skip(input, taille)
                status(out, "204 No Content")
            }

            else -> {
                skip(input, taille)
                status(out, "405 Method Not Allowed")
            }
        }
    }

    /** La reponse 207 : la ressource demandee, et ses enfants si la profondeur le veut. */
    private fun propfind(cible: File, relatif: String, profond: Boolean): String {
        val elements = StringBuilder()
        elements.append(davEntry(cible, relatif))
        if (profond && cible.isDirectory) {
            for (enfant in cible.listFiles().orEmpty()) {
                val sous = if (relatif.isEmpty()) enfant.name else "$relatif/${enfant.name}"
                elements.append(davEntry(enfant, sous))
            }
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<D:multistatus xmlns:D=\"DAV:\">$elements</D:multistatus>"
    }

    private fun davEntry(fichier: File, relatif: String): String {
        val href = "/" + relatif.split('/').filter { it.isNotEmpty() }
            .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") } +
            if (fichier.isDirectory && relatif.isNotEmpty()) "/" else ""
        val type = if (fichier.isDirectory) "<D:resourcetype><D:collection/></D:resourcetype>"
        else "<D:resourcetype/><D:getcontenttype>${mime(fichier.name)}</D:getcontenttype>" +
            "<D:getcontentlength>${fichier.length()}</D:getcontentlength>"
        val date = java.text.SimpleDateFormat(
            "EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US
        ).apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }.format(fichier.lastModified())
        return "<D:response><D:href>$href</D:href><D:propstat><D:prop>" +
            "<D:displayname>${escapeXml(fichier.name)}</D:displayname>" +
            type +
            "<D:getlastmodified>$date</D:getlastmodified>" +
            "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
    }

    private fun escapeXml(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    // ------------------------------------------------------------------ chemins

    private fun root() = File(ROOT)

    /**
     * Le fichier visé, ou null s'il sort du dossier partagé.
     *
     * La vérification porte sur le chemin **canonique** : `..`, doubles barres et liens
     * symboliques y sont déjà résolus, là où une comparaison de chaînes se laisserait
     * abuser par l'un d'eux.
     */
    private fun resolve(relatif: String): File? {
        val racine = root().canonicalFile
        val vise = File(racine, relatif).canonicalFile
        val dedans = vise == racine || vise.path.startsWith(racine.path + File.separator)
        return if (dedans) vise else null
    }

    /** Seuls des médias sont acceptés : le partage n'a pas à recevoir d'exécutable. */
    private fun accepted(nom: String): Boolean {
        val ext = nom.substringAfterLast('.', "").lowercase()
        return ext in EXTENSIONS
    }

    private fun mime(nom: String) = when (nom.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    // ------------------------------------------------------------------ outils

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun skip(input: BufferedInputStream, taille: Long) {
        var reste = taille
        val tampon = ByteArray(8192)
        while (reste > 0) {
            val n = input.read(tampon, 0, minOf(tampon.size.toLong(), reste).toInt())
            if (n < 0) return
            reste -= n
        }
    }

    private fun param(requete: String, nom: String): String =
        requete.split('&')
            .firstOrNull { it.substringBefore('=') == nom }
            ?.substringAfter('=', "")
            ?.let { decode(it) }
            .orEmpty()

    private fun decode(s: String) = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    private fun quote(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    private fun notification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.share_channel),
                    NotificationManager.IMPORTANCE_MIN
                )
            )
        }
        return Notification.Builder(this).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setChannelId(CHANNEL_ID)
            setContentTitle(getString(R.string.share_running))
            setContentText("http://${localAddress()}:${prefs.fileSharePort}/")
            setSmallIcon(android.R.drawable.stat_notify_sync)
        }.build()
    }

    /**
     * La page du partage : listing, envoi par glisser-déposer, téléchargement,
     * suppression.
     *
     * Tout tient dans une page, sans rien à charger d'Internet — le panneau doit rester
     * utilisable sur un réseau coupé du dehors.
     */
    private fun page(): String = """
<!DOCTYPE html><html lang="fr"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${getString(R.string.share_page_title)}</title>
<style>
:root{color-scheme:dark}
body{margin:0;padding:20px;background:#0b0d12;color:#e8eaf0;
     font:15px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
h1{font-size:20px;margin:0 0 4px}
p.sub{margin:0 0 18px;color:#8b93a7;font-size:13px}
#drop{border:2px dashed #2c3346;border-radius:12px;padding:26px;text-align:center;
      color:#8b93a7;margin-bottom:18px;transition:.15s}
#drop.over{border-color:#4da3ff;color:#e8eaf0;background:#111726}
ul{list-style:none;margin:0;padding:0}
li{display:flex;align-items:center;gap:12px;padding:10px 12px;border-radius:10px;
   background:#131824;margin-bottom:6px}
li .n{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
li .s{color:#8b93a7;font-size:12px;white-space:nowrap}
a{color:#4da3ff;text-decoration:none}
button{background:#1d2534;color:#e8eaf0;border:0;border-radius:8px;padding:6px 12px;
       cursor:pointer;font-size:13px}
button:hover{background:#27314a}
#bar{height:4px;background:#1d2534;border-radius:2px;overflow:hidden;margin-bottom:14px;
     display:none}
#bar i{display:block;height:100%;width:0;background:#4da3ff;transition:width .2s}
</style></head><body>
<h1>${getString(R.string.share_page_title)}</h1>
<p class="sub" id="where"></p>
<div id="drop">${getString(R.string.share_drop)}<br><br>
  <button onclick="document.getElementById('f').click()">${getString(R.string.share_browse)}</button>
  <input id="f" type="file" multiple hidden></div>
<div id="bar"><i></i></div>
<ul id="list"></ul>
<script>
let dir = "";
const el = s => document.querySelector(s);

function human(n){
  if(!n) return "";
  const u = ["o","ko","Mo","Go"]; let i = 0;
  while(n >= 1024 && i < u.length-1){ n /= 1024; i++; }
  return n.toFixed(i ? 1 : 0) + " " + u[i];
}

async function load(){
  const r = await fetch("/api/list?d=" + encodeURIComponent(dir));
  const d = await r.json();
  el("#where").textContent = "/" + (d.path || "");
  const ul = el("#list"); ul.innerHTML = "";
  if(dir){
    const li = document.createElement("li");
    li.innerHTML = '<span class="n"><a href="#">..</a></span>';
    li.querySelector("a").onclick = e => {
      e.preventDefault();
      dir = dir.split("/").slice(0,-1).join("/"); load();
    };
    ul.appendChild(li);
  }
  for(const e of d.entries){
    const li = document.createElement("li");
    const p = (dir ? dir + "/" : "") + e.name;
    if(e.dir){
      li.innerHTML = '<span class="n">&#128193; <a href="#"></a></span>';
      li.querySelector("a").textContent = e.name;
      li.querySelector("a").onclick = ev => { ev.preventDefault(); dir = p; load(); };
    } else {
      li.innerHTML = '<span class="n"><a></a></span><span class="s"></span>' +
                     '<button></button>';
      const a = li.querySelector("a");
      a.textContent = e.name; a.href = "/f/" + encodeURIComponent(p);
      li.querySelector(".s").textContent = human(e.size);
      const b = li.querySelector("button");
      b.textContent = "${getString(R.string.share_delete)}";
      b.onclick = async () => {
        if(!confirm(e.name + " ?")) return;
        await fetch("/api/file?p=" + encodeURIComponent(p), {method:"DELETE"});
        load();
      };
    }
    ul.appendChild(li);
  }
}

async function upload(files){
  const bar = el("#bar"), fill = bar.querySelector("i");
  bar.style.display = "block";
  for(let i = 0; i < files.length; i++){
    const p = (dir ? dir + "/" : "") + files[i].name;
    fill.style.width = (i / files.length * 100) + "%";
    const r = await fetch("/api/file?p=" + encodeURIComponent(p),
                          {method:"PUT", body:files[i]});
    if(!r.ok) alert(files[i].name + " : " + r.status);
  }
  fill.style.width = "100%";
  setTimeout(() => { bar.style.display = "none"; fill.style.width = 0; }, 400);
  load();
}

const drop = el("#drop");
drop.ondragover = e => { e.preventDefault(); drop.classList.add("over"); };
drop.ondragleave = () => drop.classList.remove("over");
drop.ondrop = e => {
  e.preventDefault(); drop.classList.remove("over");
  upload(e.dataTransfer.files);
};
el("#f").onchange = e => upload(e.target.files);
load();
</script></body></html>
""".trimIndent()

    companion object {
        /** Le seul dossier partagé, avec ses sous-dossiers. */
        const val ROOT = "/sdcard/HAPanel"

        /** Ce que le partage accepte de recevoir. */
        private val EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif",
            "mp3", "wav", "ogg", "flac", "m4a",
            "mp4"
        )

        /**
         * L'adresse du panneau sur le reseau, telle qu'on la tapera dans un navigateur.
         *
         * Lue sur les interfaces plutot que par WifiController : le panneau est souvent
         * relie en Ethernet, que celui-ci ne voit pas.
         */
        fun localAddress(): String = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                ?.hostAddress ?: "?"
        } catch (e: Exception) {
            "?"
        }

        private const val CHANNEL_ID = "hapanel_share"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "HaPanelShare"

        fun start(context: Context) {
            context.startService(Intent(context, FileShareService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FileShareService::class.java))
        }
    }
}
