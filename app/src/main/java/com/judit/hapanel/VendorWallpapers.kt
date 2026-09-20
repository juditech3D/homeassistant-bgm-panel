package com.judit.hapanel

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Les fonds d'écran que le constructeur a laissés dans son application.
 *
 * L'application d'origine embarque une trentaine de fonds — paysages, dégradés,
 * abstractions — répartis entre les gammes de panneaux qu'elle sert. Ils sont bien plus
 * soignés que ce qu'on dessinerait à la main, et **ils sont déjà sur le panneau**.
 *
 * ### Pourquoi les lire, et non les copier dans le dépôt
 *
 * Ces images appartiennent au fabricant, et plusieurs sont manifestement des photos de
 * banque d'images sous licence. Les verser dans un dépôt public les redistribuerait, ce
 * qui n'est pas à faire. Elles sont donc lues **à l'exécution, sur l'appareil**, dans
 * l'APK du constructeur : chaque panneau possède déjà le sien, rien ne circule, et ceux
 * qui reprennent le projet retrouvent les fonds de leur propre modèle.
 *
 * L'APK est en lecture pour tous (`-rw-r--r--` dans `/data/app/`), donc aucun privilège
 * n'est requis. L'image choisie est recopiée une fois dans le stockage privé de
 * l'application, pour qu'elle survive à la désactivation de l'application d'origine —
 * que ce projet recommande par ailleurs.
 */
object VendorWallpapers {

    /** Un fond disponible : son entrée dans l'archive, et son rang pour l'affichage. */
    data class Item(val entry: String, val index: Int) {
        /** Le nom de fichier, sans le chemin ni l'extension. */
        val key: String get() = entry.substringAfterLast('/').substringBeforeLast('.')
    }

    /**
     * Le chemin de l'APK du constructeur, ou null s'il n'est pas installé.
     *
     * `MATCH_DISABLED_COMPONENTS` est indispensable : ce projet recommande de désactiver
     * cette application, et sans ce drapeau elle deviendrait introuvable au moment même
     * où l'on suit son propre conseil.
     */
    fun apkPath(context: Context): String? {
        for (pkg in PACKAGES) {
            try {
                val info = context.packageManager.getApplicationInfo(
                    pkg, PackageManager.MATCH_DISABLED_COMPONENTS
                )
                if (!info.sourceDir.isNullOrBlank()) return info.sourceDir
            } catch (e: PackageManager.NameNotFoundException) {
                // Paquet absent : on essaie le suivant.
            }
        }
        return null
    }

    /**
     * Les fonds utilisables, dans l'ordre où l'archive les présente.
     *
     * Le tri ne se fie pas aux noms : une même application sert plusieurs modèles, avec
     * autant de familles de noms — `bg_01`, `naner_bg_02`, `t71_bg_03`… Sont retenues les
     * images assez grandes, au format paysage et à la bonne échelle, ce qui écarte d'un
     * coup les icônes, les vignettes et les fonds de boîtes de dialogue sans avoir à
     * dresser la liste des noms de chaque modèle.
     *
     * À appeler hors du fil d'affichage : l'archive fait une centaine de mégaoctets.
     */
    fun list(context: Context): List<Item> {
        val chemin = apkPath(context) ?: return emptyList()
        return try {
            ZipFile(chemin).use { zip ->
                zip.entries().toList()
                    .filter { retenue(it) }
                    .mapNotNull { entree ->
                        val taille = dimensions(zip, entree) ?: return@mapNotNull null
                        val (largeur, hauteur) = taille
                        val rapport = largeur.toFloat() / hauteur.toFloat()
                        if (largeur >= 800 && rapport in 1.4f..2.1f) entree.name else null
                    }
                    .sorted()
                    .mapIndexed { i, nom -> Item(nom, i) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "archive du constructeur illisible : ${e.message}")
            emptyList()
        }
    }

    /** Une vignette, décodée à la taille demandée sans charger l'image entière. */
    fun thumbnail(context: Context, item: Item, largeurCible: Int): Bitmap? {
        val chemin = apkPath(context) ?: return null
        return try {
            ZipFile(chemin).use { zip ->
                val entree = zip.getEntry(item.entry) ?: return null
                val (largeur, _) = dimensions(zip, entree) ?: return null
                val options = BitmapFactory.Options().apply {
                    inSampleSize = echelle(largeur, largeurCible)
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                zip.getInputStream(entree).use { BitmapFactory.decodeStream(it, null, options) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "vignette ${item.key} illisible : ${e.message}")
            null
        }
    }

    /**
     * Recopie le fond choisi dans le stockage privé et renvoie le fichier.
     *
     * La copie est ce qui rend le choix durable : l'application d'origine peut être
     * désactivée, mise à jour ou effacée sans que le panneau perde son fond.
     */
    fun copy(context: Context, item: Item, destination: File): File? {
        val chemin = apkPath(context) ?: return null
        return try {
            ZipFile(chemin).use { zip ->
                val entree = zip.getEntry(item.entry) ?: return null
                destination.parentFile?.mkdirs()
                zip.getInputStream(entree).use { source ->
                    destination.outputStream().use { source.copyTo(it) }
                }
            }
            destination
        } catch (e: Exception) {
            Log.w(TAG, "copie de ${item.key} impossible : ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------ interne

    private fun retenue(entree: ZipEntry): Boolean {
        val nom = entree.name
        if (!nom.startsWith("res/")) return false
        if (!nom.endsWith(".png", true) && !nom.endsWith(".jpg", true) &&
            !nom.endsWith(".webp", true)
        ) return false
        // Une photo plein écran pèse forcément lourd ; une icône, jamais.
        if (entree.size < 100_000) return false
        val base = nom.substringAfterLast('/').lowercase()
        if (!base.contains("bg") && !base.contains("screensaver")) return false
        return EXCLUS.none { base.contains(it) }
    }

    /** Les dimensions, lues dans l'en-tête sans décoder les pixels. */
    private fun dimensions(zip: ZipFile, entree: ZipEntry): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zip.getInputStream(entree).use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return options.outWidth to options.outHeight
    }

    /** La puissance de deux qui approche au mieux la largeur voulue, sans passer en dessous. */
    private fun echelle(largeur: Int, cible: Int): Int {
        var facteur = 1
        while (largeur / (facteur * 2) >= cible) facteur *= 2
        return facteur
    }

    /**
     * Les applications d'origine connues. Le nom de paquet change d'un fournisseur à
     * l'autre ; les alternatives évitent que le module ne serve qu'à un seul panneau.
     */
    private val PACKAGES = listOf(
        "com.sznaner.bgmz9",
        "com.sznaner.bgm",
        "com.sznaner.settings"
    )

    /**
     * Ce qui porte « bg » dans son nom sans être un fond d'écran : habillage de lecteur,
     * de boîte de dialogue, de vignette. Le filtre de dimensions en écarte déjà la
     * plupart ; cette liste rattrape les rares images grandes et bien proportionnées qui
     * ne sont pourtant que du décor d'interface.
     */
    private val EXCLUS = listOf(
        "_small", "cover", "dialog", "volume", "item", "icon", "_ui", "player", "top_bg"
    )

    private const val TAG = "HaPanelWallpaper"
}
