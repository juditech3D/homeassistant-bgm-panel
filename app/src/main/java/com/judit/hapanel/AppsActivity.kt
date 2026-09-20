package com.judit.hapanel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import kotlin.concurrent.thread

/**
 * Mes applications : lancer, installer, désinstaller, sans quitter le panneau.
 *
 * Le panneau est encastré et n'a pas de lanceur utilisable une fois que HA Panel est
 * l'écran d'accueil. Or on a parfois besoin d'autre chose — un navigateur pour une
 * page, une prise en main à distance, l'installateur d'un outil. Cet écran rend ces
 * applications accessibles sans avoir à rendre l'écran d'accueil au constructeur.
 *
 * ### Ce que cet écran ne fait pas tout seul
 *
 * **Il ne désinstalle rien en silence.** Le panneau est rooté, donc `pm uninstall`
 * passerait sans un mot — raison de plus pour ne pas s'en servir. La désinstallation
 * passe par l'écran d'Android, qui nomme l'application et demande confirmation : sur un
 * écran tactile encastré, un doigt s'égare vite, et une application retirée par erreur
 * peut demander un démontage pour être remise.
 *
 * **Les applications du système ne sont pas proposées à la désinstallation.** Android
 * la refuserait de toute façon, et surtout ce panneau en dépend : son lanceur de secours
 * et son installateur en font partie. Elles restent lançables, et leur fiche Android
 * reste accessible pour qui veut les désactiver en connaissance de cause.
 */
class AppsActivity : AppCompatActivity() {

    private data class App(
        val label: String,
        val packageName: String,
        val icon: Drawable?,
        val system: Boolean
    )

    private lateinit var grid: RecyclerView
    private lateinit var subtitle: TextView
    private var apps: List<App> = emptyList()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    /**
     * Tout geste sur cet écran reporte la mise en veille.
     *
     * La minuterie appartient au tableau de bord, mais elle court aussi pendant qu'on
     * est ici : sans ce rappel, l'écran s'éteindrait au milieu d'un réglage.
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
        MdiIcons.load(this)
        setContentView(R.layout.activity_apps)

        subtitle = findViewById(R.id.apps_subtitle)
        grid = findViewById(R.id.apps_grid)
        grid.layoutManager = GridLayoutManager(this, COLUMNS)
        grid.adapter = Adapter()

        findViewById<View>(R.id.back_button).setOnClickListener { finish() }
        findViewById<View>(R.id.apps_install).setOnClickListener { chooseApk() }
    }

    override fun onResume() {
        super.onResume()
        // Relu a chaque retour : on revient sur cet ecran juste apres avoir installe ou
        // desinstalle quelque chose, et une liste figee montrerait l'etat d'avant.
        load()
    }

    /**
     * Les applications qui se lancent, classées par nom.
     *
     * L'inventaire passe par les activités qui répondent à `LAUNCHER` plutôt que par la
     * liste des paquets : une bibliothèque ou un service n'a rien à faire dans une
     * grille où l'on vient pour ouvrir quelque chose.
     */
    private fun load() {
        subtitle.setText(R.string.apps_loading)
        thread(isDaemon = true) {
            val pm = packageManager
            val intention = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val trouvees = try {
                pm.queryIntentActivities(intention, 0)
                    .mapNotNull { resolution ->
                        val info = resolution.activityInfo?.applicationInfo ?: return@mapNotNull null
                        if (info.packageName == packageName) return@mapNotNull null
                        App(
                            label = resolution.loadLabel(pm).toString(),
                            packageName = info.packageName,
                            icon = resolution.loadIcon(pm),
                            system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    }
                    .distinctBy { it.packageName }
                    .sortedBy { it.label.lowercase() }
            } catch (e: Exception) {
                emptyList()
            }

            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                apps = trouvees
                subtitle.text = getString(
                    R.string.apps_count, trouvees.size, trouvees.count { !it.system }
                )
                grid.adapter?.notifyDataSetChanged()
            }
        }
    }

    // ------------------------------------------------------------------ actions

    private fun launch(app: App) {
        val intention = packageManager.getLaunchIntentForPackage(app.packageName)
        if (intention == null) {
            Toast.makeText(this, R.string.apps_cannot_launch, Toast.LENGTH_LONG).show()
            return
        }
        startActivity(intention.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Le menu d'un appui long : ouvrir, la fiche Android, et la désinstallation. */
    private fun showMenu(app: App) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions.add(getString(R.string.apps_open) to { launch(app) })
        actions.add(getString(R.string.apps_info) to { showInfo(app) })
        if (!app.system) {
            actions.add(getString(R.string.apps_uninstall) to { uninstall(app) })
        }

        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .setNegativeButton(R.string.picker_cancel, null)
            .show()
    }

    /** La fiche Android de l'application : permissions, stockage, désactivation. */
    private fun showInfo(app: App) {
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(this, R.string.apps_no_settings, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Désinstalle, par l'écran d'Android.
     *
     * Volontairement pas par `pm uninstall`, que le root rendrait pourtant possible et
     * silencieux : retirer une application ne se rattrape pas, et la confirmation
     * d'Android nomme ce qui va partir.
     */
    private fun uninstall(app: App) {
        try {
            startActivity(
                Intent(Intent.ACTION_UNINSTALL_PACKAGE)
                    .setData(Uri.parse("package:${app.packageName}"))
                    .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(this, R.string.apps_no_uninstall, Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ installer

    /**
     * Propose les APK déposés dans le dossier partagé.
     *
     * C'est le chemin complet, et sans PC : on dépose le fichier depuis l'Explorateur
     * Windows par le partage réseau, puis on l'installe d'ici.
     */
    private fun chooseApk() {
        val dossier = File(FileShareService.ROOT, APK_FOLDER)
        dossier.mkdirs()
        val apks = dossier.listFiles { f -> f.isFile && f.name.endsWith(".apk", true) }
            .orEmpty()
            .sortedByDescending { it.lastModified() }

        if (apks.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.apps_install)
                .setMessage(getString(R.string.apps_no_apk, dossier.path))
                .setPositiveButton(R.string.back, null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.apps_install)
            .setItems(apks.map { "${it.name}  (${it.length() / 1024} ko)" }.toTypedArray()) { _, i ->
                install(apks[i])
            }
            .setNegativeButton(R.string.picker_cancel, null)
            .show()
    }

    /**
     * Installe un APK par l'installateur d'Android.
     *
     * Là encore, pas de `pm install` silencieux : une application inconnue qui
     * s'installerait sans un mot sur un panneau mural serait exactement ce qu'on ne
     * veut pas, fût-ce par confort.
     */
    private fun install(apk: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.updates", apk
            )
            startActivity(
                Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            )
        } catch (e: Exception) {
            Toast.makeText(
                this, getString(R.string.apps_install_failed, e.message.orEmpty()),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ------------------------------------------------------------------ grille

    private inner class Adapter : RecyclerView.Adapter<Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        )

        override fun getItemCount() = apps.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = apps[position]
            holder.label.text = app.label
            holder.icon.setImageDrawable(app.icon)
            holder.system.visibility = if (app.system) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener { launch(app) }
            holder.itemView.setOnLongClickListener { showMenu(app); true }
        }
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val label: TextView = view.findViewById(R.id.app_label)
        val system: TextView = view.findViewById(R.id.app_system)
    }

    companion object {
        private const val COLUMNS = 6

        /** Où déposer les APK à installer, dans l'arborescence partagée sur le réseau. */
        const val APK_FOLDER = "applications"

        fun open(activity: Activity) {
            activity.startActivity(Intent(activity, AppsActivity::class.java))
        }
    }
}
