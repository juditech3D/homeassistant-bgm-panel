package com.judit.hapanel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.StatFs
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * L'historique des coups de sonnette : une capture de la caméra, horodatée.
 *
 * Quand on rentre et qu'on voit une notification de sonnerie, la première question est
 * « qui ? ». La caméra ne s'affiche que quelques secondes au moment où l'on sonne, et
 * personne n'est devant le panneau à ce moment-là — c'est précisément le problème.
 *
 * ### Où c'est rangé, et pourquoi pas sur carte SD
 *
 * Sur la mémoire interne, dans [FOLDER], donc visible dans le partage réseau. La carte
 * SD n'est pas nécessaire : `/data` offre 2,6 Go libres sur ce panneau, là où une capture
 * pèse 150 à 250 ko. Le dossier reste **réglable** pour qui voudrait pointer une carte
 * insérée plus tard ; rien dans ce code ne suppose l'un ou l'autre.
 *
 * Deux garde-fous, parce qu'un dossier qui grossit sans fin sur une partition de 4 Go
 * finirait par empêcher le panneau de se mettre à jour :
 *
 * - un **plafond de captures**, les plus anciennes partant les premières ;
 * - un **seuil d'espace libre** en dessous duquel plus rien n'est écrit, quel que soit
 *   le plafond.
 */
object DoorbellHistory {

    /** Le dossier par défaut, dans l'arborescence partagée sur le réseau. */
    const val FOLDER = "/sdcard/HAPanel/historique"

    /** En dessous, on n'écrit plus : le panneau doit garder de quoi se mettre à jour. */
    private const val MIN_FREE_BYTES = 200L * 1024 * 1024

    private const val QUALITY = 85

    /** Ce qui distingue un essai d'une vraie sonnerie, dans le nom du fichier. */
    private const val SUFFIXE_ESSAI = "-test"
    private const val TAG = "HaPanelHistory"

    /** Ce qu'il est advenu d'une capture. */
    sealed class Result {
        data class Saved(val file: File) : Result()

        /**
         * Plus de place, et le recyclage automatique n'est pas autorisé.
         *
         * Distingué d'un échec quelconque parce que l'écran doit le dire : un historique
         * qui cesse d'enregistrer en silence ne se découvre qu'au pire moment, celui où
         * l'on cherche qui a sonné.
         */
        object StorageFull : Result()

        object Failed : Result()
    }

    /**
     * Enregistre une capture.
     *
     * [bitmap] n'est pas modifiée : la mention est dessinée sur une copie. L'image
     * d'origine appartient à la vue caméra, qui continue de s'en servir et la recyclera
     * quand bon lui semble.
     */
    fun save(
        context: Context,
        bitmap: Bitmap,
        cameraName: String,
        prefs: Prefs,
        essai: Boolean = false
    ): Result {
        val dossier = File(prefs.historyFolder)
        if (!dossier.exists() && !dossier.mkdirs()) {
            Log.w(TAG, "dossier ${dossier.path} impossible à créer")
            return Result.Failed
        }
        if (!makeRoom(dossier, prefs)) {
            Log.w(TAG, "plus de place, et rien à recycler : capture abandonnée")
            return Result.StorageFull
        }

        val instant = Date()
        val fichier = File(dossier, nomDeFichier(instant, essai))
        return try {
            val marquee = withStamp(context, bitmap, instant, cameraName, essai)
            fichier.outputStream().use { marquee.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            marquee.recycle()
            prune(prefs)
            Log.i(TAG, "capture enregistrée : ${fichier.name}")
            Result.Saved(fichier)
        } catch (e: Exception) {
            Log.w(TAG, "capture impossible : ${e.message}")
            fichier.delete()
            Result.Failed
        }
    }

    /** Vrai si la capture vient du bouton de test et non d'une vraie sonnerie. */
    fun isTest(fichier: File): Boolean = fichier.name.contains(SUFFIXE_ESSAI)

    /**
     * Le jour d'une capture, sous la forme `AAAA-MM-JJ`.
     *
     * Tire du nom du fichier, qui commence precisement par la : aucun decodage d'image
     * ni lecture de metadonnee n'est necessaire pour regrouper par jour.
     */
    fun dayOf(fichier: File): String = fichier.name.take(10)

    /** Le jour courant, dans la meme forme. */
    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Les jours qui portent au moins une capture, du plus recent au plus ancien. */
    fun days(prefs: Prefs): List<String> =
        entries(prefs).map { dayOf(it) }.distinct()

    /**
     * Les captures qui n'ont pas encore ete consultees, de la plus recente a la plus
     * ancienne.
     *
     * La comparaison porte sur les noms, qui se trient chronologiquement : tout ce qui
     * vient apres la derniere capture vue est nouveau. Aucune date de fichier n'entre en
     * jeu, et un dossier recopie ailleurs garde donc le meme decoupage.
     */
    fun unseen(prefs: Prefs): List<File> {
        val marqueur = prefs.historySeenMarker
        if (marqueur.isBlank()) return entries(prefs)
        return entries(prefs).filter { it.name > marqueur }
    }

    /**
     * Marque tout comme consulte.
     *
     * Le marqueur retient la capture la plus recente du moment. Celles qui arriveront
     * ensuite porteront un nom superieur et seront donc a nouveau signalees -- c'est
     * exactement ce qu'on attend d'une notification qu'on vient de lire.
     */
    fun markAllSeen(prefs: Prefs) {
        val derniere = entries(prefs).firstOrNull() ?: return
        prefs.historySeenMarker = derniere.name
    }

    /** Combien de fois on a sonne ce jour-la. */
    fun countOn(prefs: Prefs, jour: String): Int =
        entries(prefs).count { dayOf(it) == jour }

    /** Un jour `AAAA-MM-JJ` tel qu'il s'ecrit dans la langue de l'ecran. */
    fun dayLabel(context: Context, jour: String): String = try {
        val instant = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(jour)
        SimpleDateFormat("EEEE d MMMM yyyy", locale(context)).format(instant!!)
            .replaceFirstChar { it.uppercase() }
    } catch (e: Exception) {
        jour
    }

    /** La place restante, en mégaoctets, pour l'afficher. */
    fun freeMegabytes(prefs: Prefs): Long =
        freeBytes(File(prefs.historyFolder)) / (1024 * 1024)

    /**
     * Les captures, de la plus récente à la plus ancienne.
     *
     * Le tri porte sur le nom et non sur la date du fichier : le nom encode l'instant en
     * `AAAA-MM-JJ_HHMMSS`, donc il se trie tout seul, et il survit à une copie qui
     * remettrait les dates de fichier à neuf.
     */
    fun entries(prefs: Prefs): List<File> =
        File(prefs.historyFolder).listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
            .orEmpty()
            .sortedByDescending { it.name }

    /** Ramène le dossier sous le plafond, en supprimant les plus anciennes. */
    fun prune(prefs: Prefs) {
        val gardees = prefs.historyMax.coerceAtLeast(1)
        entries(prefs).drop(gardees).forEach {
            if (it.delete()) Log.i(TAG, "ancienne capture supprimée : ${it.name}")
        }
    }

    /**
     * La date telle qu'elle s'affiche, tirée du nom du fichier.
     *
     * Passer par le nom plutôt que par la date du système de fichiers garde l'affichage
     * cohérent avec le tri, et juste même après un transfert.
     */
    fun labelOf(context: Context, fichier: File): String {
        val instant = parse(fichier.name) ?: return fichier.name
        return SimpleDateFormat("EEEE d MMMM, HH:mm", locale(context)).format(instant)
            .replaceFirstChar { it.uppercase() }
    }

    // ------------------------------------------------------------------ interne

    /**
     * Le nom d'une capture : `AAAA-MM-JJ_HHMMSS.jpg`, suffixe `-test` pour un essai.
     *
     * Le marqueur tient dans le nom, et non dans un fichier d'accompagnement : une
     * capture recuperee par le partage reseau garde ainsi son statut, et le tri
     * chronologique n'en souffre pas, le suffixe venant apres l'heure.
     */
    private fun nomDeFichier(instant: Date, essai: Boolean): String =
        SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(instant) +
            (if (essai) SUFFIXE_ESSAI else "") + ".jpg"

    private fun parse(nom: String): Date? = try {
        SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
            .parse(nom.removeSuffix(".jpg").removeSuffix(SUFFIXE_ESSAI))
    } catch (e: Exception) {
        null
    }

    private fun locale(context: Context): Locale =
        context.resources.configuration.locales[0]

    /**
     * S'assure qu'il reste de la place, quitte à sacrifier les plus anciennes captures.
     *
     * Le plafond de captures suffit en temps normal ; ceci couvre le cas où c'est autre
     * chose qui a rempli la partition — des photos de diaporama, de la musique, une mise
     * à jour restée en travers. Sans recyclage, l'historique s'arrêterait simplement de
     * fonctionner, en silence, et l'on ne s'en apercevrait que le jour où l'on cherche
     * qui a sonné.
     *
     * La suppression procède une par une et remesure à chaque fois : effacer d'un coup
     * tout l'historique pour loger une seule image serait un remède pire que le mal.
     */
    private fun makeRoom(dossier: File, prefs: Prefs): Boolean {
        if (freeBytes(dossier) > MIN_FREE_BYTES) return true
        if (!prefs.historyRecycle) return false

        // De la plus ancienne à la plus récente.
        for (fichier in entries(prefs).asReversed()) {
            if (!fichier.delete()) continue
            Log.i(TAG, "mémoire pleine, capture recyclée : ${fichier.name}")
            if (freeBytes(dossier) > MIN_FREE_BYTES) return true
        }
        return false
    }

    private fun freeBytes(dossier: File): Long = try {
        StatFs(dossier.path).let { it.availableBlocksLong * it.blockSizeLong }
    } catch (e: Exception) {
        // Impossible de mesurer : on laisse passer, le plafond de captures reste là.
        Long.MAX_VALUE
    }

    /**
     * Copie l'image avec, en bas, le jour, la date, l'heure et le nom de la caméra.
     *
     * La mention est **incrustée dans l'image** et non gardée à côté : une capture qu'on
     * envoie à quelqu'un, ou qu'on retrouve dans le partage réseau, doit porter sa date
     * avec elle. Un bandeau sombre la rend lisible quelle que soit la scène.
     */
    private fun withStamp(
        context: Context,
        source: Bitmap,
        instant: Date,
        cameraName: String,
        essai: Boolean
    ): Bitmap {
        val copie = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: return source.copy(Bitmap.Config.RGB_565, true)
        val canvas = Canvas(copie)

        val hauteur = (copie.height * 0.09f).coerceIn(28f, 64f)
        val texte = hauteur * 0.46f

        canvas.drawRect(
            0f, copie.height - hauteur, copie.width.toFloat(), copie.height.toFloat(),
            Paint().apply { color = Color.argb(170, 0, 0, 0) }
        )

        val encre = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = texte
            isFakeBoldText = true
        }
        val quand = (if (essai) context.getString(R.string.history_test) + "  ·  " else "") +
            SimpleDateFormat("EEEE d MMMM yyyy  ·  HH:mm:ss", locale(context))
                .format(instant)
                .replaceFirstChar { it.uppercase() }
        canvas.drawText(quand, texte * 0.6f, copie.height - hauteur * 0.3f, encre)

        if (cameraName.isNotBlank()) {
            val leger = Paint(encre).apply {
                color = Color.argb(200, 255, 255, 255)
                isFakeBoldText = false
                textSize = texte * 0.85f
            }
            val largeur = leger.measureText(cameraName)
            canvas.drawText(
                cameraName,
                copie.width - largeur - texte * 0.6f,
                copie.height - hauteur * 0.3f,
                leger
            )
        }
        return copie
    }
}
