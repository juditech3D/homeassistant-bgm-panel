package com.judit.hapanel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Mise à jour de l'application par le réseau.
 *
 * Ce panneau s'encastre dans une boîte électrique : le démonter pour brancher un câble
 * USB à chaque correction n'est pas tenable. ADB par le réseau fonctionne, mais il faut
 * un PC, et le port ne survit pas forcément à un redémarrage. D'où ce module : le panneau
 * va chercher lui-même sa nouvelle version et l'installe.
 *
 * Deux sources sont acceptées, distinguées par la forme de ce qui est saisi :
 *
 * - `proprietaire/depot` — les **publications GitHub** du projet. La version est lue dans
 *   l'étiquette (`v0.3`), l'APK dans les fichiers joints.
 * - une **URL** — un fichier JSON décrivant la version disponible, à déposer où l'on veut,
 *   typiquement dans le dossier `www/` de Home Assistant, qui le sert déjà :
 *   `{"versionName": "0.3", "url": "http://…/hapanel.apk", "notes": "…"}`
 *
 * L'installation se fait **sans interaction** quand le panneau est rooté, ce qui est le
 * cas ici : `pm install -r` conserve les réglages, donc le jeton Home Assistant. Sinon
 * on retombe sur l'installateur d'Android, qui demande confirmation à l'écran — utilisable
 * mais moins commode sur un panneau mural.
 */
class Updater(private val context: Context) {

    /** Ce qu'une vérification a trouvé. */
    data class Release(
        val versionName: String,
        val url: String,
        val notes: String
    )

    sealed class Result {
        /** Une version plus récente existe. */
        data class Available(val release: Release) : Result()

        /** Le panneau est déjà à jour. */
        data class UpToDate(val versionName: String) : Result()

        data class Failed(val reason: String) : Result()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** La version installée, telle que fixée dans `app/build.gradle.kts`. */
    val installedVersion: String
        get() = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }

    /**
     * Interroge la source. **Appel bloquant** : à lancer depuis un fil de fond.
     */
    fun check(source: String): Result {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) {
            return Result.Failed(context.getString(R.string.update_no_source))
        }

        return try {
            val release = (
                if (trimmed.startsWith("http")) fromManifest(trimmed) else fromGitHub(trimmed)
                ) ?: return Result.Failed(context.getString(R.string.update_no_release))

            if (isNewer(release.versionName, installedVersion)) {
                Result.Available(release)
            } else {
                Result.UpToDate(installedVersion)
            }
        } catch (e: Exception) {
            Log.w(TAG, "vérification impossible : ${e.message}")
            Result.Failed(e.message ?: context.getString(R.string.update_failed))
        }
    }

    /** Dernière publication d'un dépôt GitHub. Aucune authentification : dépôt public. */
    private fun fromGitHub(repo: String): Release? {
        val clean = repo.trim('/').removePrefix("https://github.com/")
        val body = get("https://api.github.com/repos/$clean/releases/latest") ?: return null
        val json = JSONObject(body)

        val apk = json.optJSONArray("assets")?.let { assets ->
            (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?.optString("browser_download_url")
        } ?: return null

        return Release(
            versionName = json.optString("tag_name").removePrefix("v"),
            url = apk,
            notes = plainText(json.optString("body"))
        )
    }

    /** Fichier JSON déposé à une adresse quelconque. */
    private fun fromManifest(url: String): Release? {
        val json = JSONObject(get(url) ?: return null)
        val apk = json.optString("url").takeIf { it.isNotEmpty() } ?: return null
        return Release(
            versionName = json.optString("versionName").removePrefix("v"),
            url = apk,
            notes = plainText(json.optString("notes"))
        )
    }

    /**
     * Débarrasse les notes de publication de leur balisage.
     *
     * GitHub les rend en Markdown ; la boîte de dialogue du panneau, elle, affiche du
     * texte brut, et les `###` et `**` s'y retrouvaient tels quels.
     */
    private fun plainText(markdown: String): String = markdown
        .lineSequence()
        .map { line ->
            line.trimStart().removePrefix("#").removePrefix("#").removePrefix("#")
                .removePrefix("#").trimStart()
                .replace("**", "")
                .replace("`", "")
        }
        .joinToString("\n")
        .trim()
        .let { text ->
            // Couper net tombe au milieu d'un mot — « le réglage de fonctionnalit ».
            // On recule jusqu'à la dernière césure et on annonce la suite.
            if (text.length <= NOTES_LIMIT) text
            else text.take(NOTES_LIMIT)
                .substringBeforeLast(' ')
                .trimEnd(',', ';', ':', '.') + " […]"
        }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "$url a répondu ${response.code}")
                return null
            }
            return response.body?.string()
        }
    }

    /**
     * Télécharge puis installe. **Appel bloquant.**
     *
     * [onProgress] reçoit un pourcentage, ou -1 tant que la taille est inconnue — certains
     * serveurs ne renvoient pas `Content-Length`.
     *
     * Retourne null si l'installation est partie, un message d'erreur sinon.
     */
    fun download(release: Release, onProgress: (Int) -> Unit): String? {
        val target = File(context.cacheDir, "hapanel-${release.versionName}.apk")

        try {
            val request = Request.Builder().url(release.url).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return context.getString(R.string.update_http_error, response.code)
                }
                val body = response.body ?: return context.getString(R.string.update_failed)
                val total = body.contentLength()

                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            done += read
                            onProgress(if (total > 0) (done * 100 / total).toInt() else -1)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "téléchargement échoué : ${e.message}")
            return e.message ?: context.getString(R.string.update_failed)
        }

        // Un fichier tronqué s'installerait mal et laisserait le panneau sans application.
        if (target.length() < MIN_APK_BYTES) {
            target.delete()
            return context.getString(R.string.update_truncated)
        }

        return install(target)
    }

    /**
     * Installe l'APK. Par root si possible — c'est le seul chemin réellement utilisable
     * sur un panneau encastré, l'installateur d'Android exigeant sinon qu'on aille toucher
     * l'écran.
     */
    private fun install(apk: File): String? {
        // L'installateur système lit le fichier sous une autre identité : il lui faut
        // l'accès, et le cache de l'application est privé.
        apk.setReadable(true, false)

        // Le retour au tableau de bord n'est **pas** géré ici : l'installation tue le
        // processus de l'application, et emporte avec lui tout shell qu'elle aurait
        // lancé — y compris détaché, essayé et constaté en 0.4. C'est `BootReceiver`,
        // sur `MY_PACKAGE_REPLACED`, qui relance l'écran depuis un processus neuf.
        val silent = runAsRoot("pm install -r -d ${apk.absolutePath}")
        if (silent != null && silent.contains("Success", ignoreCase = true)) return null

        Log.i(TAG, "installation silencieuse indisponible ($silent), passage par le système")

        return try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.updates", apk
            )
            context.startActivity(
                Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            )
            null
        } catch (e: Exception) {
            Log.w(TAG, "installateur système refusé : ${e.message}")
            e.message ?: context.getString(R.string.update_failed)
        }
    }

    private fun runAsRoot(command: String): String? = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "0", "sh", "-c", command))
        val output = process.inputStream.bufferedReader().readText() +
            process.errorStream.bufferedReader().readText()
        process.waitFor()
        output
    } catch (e: Exception) {
        Log.d(TAG, "su indisponible : ${e.message}")
        null
    }

    companion object {
        private const val TAG = "HaPanelUpdate"
        private const val NOTES_LIMIT = 500
        private const val MIN_APK_BYTES = 100_000L

        /**
         * Compare deux versions composant par composant : `0.10` est postérieure à `0.9`,
         * ce qu'une comparaison de chaînes affirmerait à l'envers.
         */
        fun isNewer(candidate: String, installed: String): Boolean {
            fun parts(v: String) = v.trim().removePrefix("v")
                .split('.', '-', '_')
                .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

            val a = parts(candidate)
            val b = parts(installed)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
