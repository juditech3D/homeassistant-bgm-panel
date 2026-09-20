package com.judit.hapanel

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Choix de l'écran d'accueil du panneau.
 *
 * Par défaut, le lanceur du constructeur s'affiche à chaque démarrage avec toutes ses
 * icônes, et l'application ne vient qu'après — un battement inutile sur un appareil qui
 * n'a qu'un seul usage. En prenant le rôle d'accueil, HA Panel démarre directement.
 *
 * Le changement passe par `cmd package set-home-activity`, donc par `su` : Android
 * réserve normalement ce choix à sa propre boîte de dialogue, que ce panneau n'expose
 * pas — ses réglages système sont remplacés par ceux du constructeur. Sur un appareil
 * sans root, on retombe sur la boîte système, qui sait faire.
 *
 * Le retour au menu du constructeur est toujours possible : c'est l'autre moitié de
 * cette classe, et il n'y a pas de piège — l'application garde son icône dans ce menu,
 * donc on peut toujours revenir.
 */
object LauncherRole {

    /** Le lanceur d'origine du panneau. */
    private const val VENDOR_LAUNCHER = "l.l"

    private const val TAG = "HaPanelLauncher"

    /** Vrai si HA Panel est l'écran d'accueil actuel. */
    fun isDefault(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val actuel = context.packageManager
            .resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return actuel?.activityInfo?.packageName == context.packageName
    }

    /**
     * Fait de HA Panel l'écran d'accueil. Retourne false si le système a refusé, auquel
     * cas l'appelant renvoie l'utilisateur vers la boîte de dialogue d'Android.
     */
    fun take(context: Context): Boolean =
        setHome(context, "${context.packageName}/.SetupActivity")

    /** Rend le rôle au lanceur du constructeur et l'affiche. */
    fun release(context: Context): Boolean {
        val rendu = setHome(context, "$VENDOR_LAUNCHER/$VENDOR_LAUNCHER.MainActivity") ||
            setHome(context, VENDOR_LAUNCHER)
        openVendorLauncher(context)
        return rendu
    }

    /** Affiche le menu du constructeur, sans toucher au rôle d'accueil. */
    fun openVendorLauncher(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(VENDOR_LAUNCHER)
        if (intent != null) {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        // À défaut, on demande au système son écran d'accueil : sur un panneau dont le
        // lanceur porterait un autre nom, cela reste la bonne porte.
        try {
            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w(TAG, "aucun écran d'accueil joignable : ${e.message}")
        }
    }

    /** Propose la boîte de dialogue d'Android, quand `su` n'a pas abouti. */
    fun openSystemChooser(context: Context) {
        try {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_HOME_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w(TAG, "réglages d'accueil indisponibles : ${e.message}")
        }
    }

    private fun setHome(context: Context, composant: String): Boolean = try {
        val process = Runtime.getRuntime().exec(
            arrayOf("su", "0", "cmd", "package", "set-home-activity", composant)
        )
        val sortie = process.inputStream.bufferedReader().readText() +
            process.errorStream.bufferedReader().readText()
        process.waitFor()
        val abouti = sortie.contains("Success", ignoreCase = true) ||
            (sortie.isBlank() && isDefaultAfter(context, composant))
        Log.i(TAG, "set-home-activity $composant : ${sortie.trim().ifEmpty { "sans message" }}")
        abouti
    } catch (e: Exception) {
        Log.w(TAG, "su indisponible : ${e.message}")
        false
    }

    /** Certaines versions ne disent rien : on vérifie alors le résultat. */
    private fun isDefaultAfter(context: Context, composant: String): Boolean =
        composant.startsWith(context.packageName) == isDefault(context)
}
