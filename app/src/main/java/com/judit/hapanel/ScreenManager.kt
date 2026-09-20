package com.judit.hapanel

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Mise en veille de l'écran principal et réveil.
 *
 * On pilote le rétroéclairage directement en sysfs plutôt que de s'en remettre à la
 * veille d'Android : le tableau de bord maintient `FLAG_KEEP_SCREEN_ON` — indispensable
 * pour qu'il reste affiché — ce qui neutralise justement la veille du système. En
 * éteignant le rétroéclairage sans endormir Android, l'écran s'éteint vraiment tout en
 * continuant de recevoir les touchers : le réveil au doigt fonctionne sans délai.
 *
 * `bl_power` appartient à root et `brightness` à system : ni l'un ni l'autre n'est
 * accessible à une application ordinaire. On élargit donc leurs droits une fois au
 * démarrage, via `su`. Sans root, on se rabat sur la luminosité de fenêtre, qui ne
 * permet que d'assombrir — [fallbackOnly] le signale à l'appelant.
 */
class ScreenManager(private val prefs: Prefs) {

    private val ui = Handler(Looper.getMainLooper())

    @Volatile var isAsleep: Boolean = false
        private set

    /** Vrai si le root n'a pas pu être obtenu : on ne sait alors qu'assombrir. */
    @Volatile var fallbackOnly: Boolean = false
        private set

    /** Notifié à chaque endormissement ou réveil, pour republier vers Home Assistant. */
    var onSleepChanged: ((asleep: Boolean) -> Unit)? = null

    /**
     * Notifié à l'entrée et à la sortie de l'écran de veille — première étape, où
     * l'écran reste allumé mais affiche photos ou animation.
     */
    var onScreensaverChanged: ((showing: Boolean) -> Unit)? = null

    @Volatile var isScreensaverShowing: Boolean = false
        private set

    private val screensaverTask = Runnable { showScreensaver() }

    /** Appelé quand on ne peut pas éteindre : l'activité assombrit sa fenêtre. */
    var onFallbackBrightness: ((level: Float) -> Unit)? = null

    private val sleepTask = Runnable { sleep() }

    // --------------------------------------------------------------- démarrage

    fun start() {
        ensureWritable()
        applyBrightness(prefs.screenBrightness)
        wake()
    }

    fun stop() {
        ui.removeCallbacks(sleepTask)
        ui.removeCallbacks(screensaverTask)
        hideScreensaver()
        wake()
    }

    /**
     * Élargit les droits des fichiers du rétroéclairage. À refaire à chaque démarrage :
     * les permissions sysfs sont réinitialisées au redémarrage du panneau.
     */
    private fun ensureWritable() {
        if (File(BL_POWER).canWrite() && File(BRIGHTNESS).canWrite()) {
            fallbackOnly = false
            return
        }
        try {
            Runtime.getRuntime()
                .exec(arrayOf("su", "0", "sh", "-c", "chmod 666 $BRIGHTNESS $BL_POWER"))
                .waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "su indisponible : ${e.message}")
        }
        fallbackOnly = !(File(BL_POWER).canWrite() && File(BRIGHTNESS).canWrite())
        if (fallbackOnly) {
            Log.w(TAG, "rétroéclairage non pilotable : on se limitera à assombrir")
        }
    }

    // ------------------------------------------------------------ veille active

    /** À appeler à chaque interaction : toucher, bouton rotatif, proximité. */
    fun noteActivity() {
        if (isAsleep) wake()
        hideScreensaver()
        rearm()
    }

    /**
     * Réarme les deux étapes. Les deux délais partent de la dernière interaction : le
     * plus court amène l'écran de veille, le plus long éteint la dalle.
     */
    private fun rearm() {
        ui.removeCallbacks(sleepTask)
        ui.removeCallbacks(screensaverTask)

        val saver = prefs.screensaverSeconds
        if (saver > 0) ui.postDelayed(screensaverTask, saver * 1000L)

        val off = prefs.screenTimeoutSeconds
        if (off > 0) ui.postDelayed(sleepTask, off * 1000L)
    }

    private fun showScreensaver() {
        if (isScreensaverShowing || isAsleep) return
        isScreensaverShowing = true
        onScreensaverChanged?.invoke(true)
    }

    private fun hideScreensaver() {
        if (!isScreensaverShowing) return
        isScreensaverShowing = false
        onScreensaverChanged?.invoke(false)
    }

    /**
     * Eteint l'ecran.
     *
     * `bl_power` seul ne suffit pas : le pilote de ce panneau accepte la consigne
     * `FB_BLANK_POWERDOWN` sans rien en faire, et `brightness` reste a sa valeur --
     * mesure sur l'appareil, `bl_power` a 4 pendant que `brightness` tenait 255. La
     * dalle restait donc eclairee a fond derriere une image noire, ce qui se voit dans
     * une piece sombre et use l'ecran pour rien.
     *
     * La luminosite est donc ramenee a zero par-dessus, et c'est elle qui eteint
     * reellement. `bl_power` est conserve : il ne coute rien, et sur un panneau dont le
     * pilote l'honore, il coupe l'alimentation du retroeclairage plutot que d'en mettre
     * la modulation a zero.
     */
    fun sleep() {
        if (isAsleep) return
        isAsleep = true
        if (fallbackOnly) {
            onFallbackBrightness?.invoke(0f)
        } else {
            write(BL_POWER, FB_BLANK_POWERDOWN)
            write(BRIGHTNESS, 0)
        }
        onSleepChanged?.invoke(true)
    }

    fun wake() {
        val was = isAsleep
        isAsleep = false
        if (fallbackOnly) {
            onFallbackBrightness?.invoke(prefs.screenBrightness / 100f)
        } else {
            // Dans cet ordre : rallumer l'alimentation avant de reposer la luminosite,
            // sinon la valeur ecrite serait perdue par le rallumage.
            write(BL_POWER, FB_BLANK_UNBLANK)
            applyBrightness(prefs.screenBrightness)
        }
        rearm()
        if (was) onSleepChanged?.invoke(false)
    }

    // -------------------------------------------------------------- luminosité

    /** Luminosité en pourcentage. Un minimum est imposé pour ne jamais tout noircir. */
    fun applyBrightness(percent: Int) {
        // Ecran eteint, on ne rallume pas : un reglage venu de Home Assistant ou de
        // l'ecran de configuration ne doit pas reveiller le panneau par surprise. La
        // valeur sera posee au reveil, qui lit prefs.screenBrightness.
        if (isAsleep) return
        val clamped = percent.coerceIn(MIN_PERCENT, 100)
        if (fallbackOnly) {
            onFallbackBrightness?.invoke(clamped / 100f)
            return
        }
        val max = read(MAX_BRIGHTNESS)?.toIntOrNull() ?: 255
        write(BRIGHTNESS, Math.round(clamped * max / 100f))
    }

    /**
     * La luminosite reellement appliquee, en pourcentage.
     *
     * Ecran eteint, la mesure vaudrait zero et serait publiee telle quelle vers Home
     * Assistant. Ce n'est pas faux, mais ce n'est pas ce qu'on veut y lire : une
     * automatisation qui relit cette valeur pour la reposer plus tard eteindrait l'ecran
     * definitivement. On renvoie donc le reglage, c'est-a-dire ce que l'ecran retrouvera
     * au reveil, et l'extinction se lit sur son propre capteur.
     */
    fun brightnessPercent(): Int {
        if (isAsleep) return prefs.screenBrightness
        val max = read(MAX_BRIGHTNESS)?.toIntOrNull() ?: 255
        val current = read(ACTUAL_BRIGHTNESS)?.toIntOrNull() ?: return prefs.screenBrightness
        if (max <= 0) return prefs.screenBrightness
        return Math.round(current * 100f / max)
    }

    // ------------------------------------------------------------------ sysfs

    private fun write(path: String, value: Int) {
        try {
            File(path).writeText(value.toString())
        } catch (e: Exception) {
            Log.w(TAG, "écriture $path impossible : ${e.message}")
        }
    }

    private fun read(path: String): String? = try {
        // Le pilote renvoie parfois des caractères parasites avant la valeur.
        File(path).readText().filter { it.isDigit() }.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    init {
        // Le gestionnaire se declare des sa construction : les autres ecrans n'ont pas
        // de raison de connaitre le tableau de bord pour pouvoir lui signaler un
        // toucher.
        current = this
    }

    companion object {
        /**
         * Le gestionnaire en service, s'il y en a un.
         *
         * La minuterie de veille appartient au tableau de bord, mais **tous** les ecrans
         * doivent la rearmer. Sans cela, l'ecran s'eteint pendant qu'on regle quelque
         * chose dans la configuration -- et comme les reglages ne signalaient aucun
         * toucher, plus rien ne le rallumait : le panneau devenait aveugle jusqu'a ce
         * qu'on revienne au tableau de bord a l'aveuglette.
         *
         * Le defaut existait depuis toujours ; il ne se voyait pas tant que `bl_power`
         * n'eteignait rien reellement.
         */
        @Volatile
        var current: ScreenManager? = null
            private set

        /** Un ecran signale qu'on vient de s'en servir. */
        fun noteInteraction() {
            current?.noteActivity()
        }

        /**
         * Le premier toucher sur un ecran endormi ne sert qu'a reveiller.
         *
         * Renvoie vrai quand l'evenement a ete absorbe. Sans cela, le doigt qui rallume
         * cocherait au passage la case qui se trouve dessous, ce qu'on ne verrait meme
         * pas puisque l'ecran etait noir au moment du geste.
         */
        fun consumeWakeTouch(event: android.view.MotionEvent): Boolean {
            val manager = current ?: return false
            if (!manager.isAsleep) return false
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                manager.noteActivity()
            }
            return true
        }

        private const val TAG = "ScreenManager"
        private const val DIR = "/sys/class/backlight/backlight"
        private const val BRIGHTNESS = "$DIR/brightness"
        private const val ACTUAL_BRIGHTNESS = "$DIR/actual_brightness"
        private const val MAX_BRIGHTNESS = "$DIR/max_brightness"
        private const val BL_POWER = "$DIR/bl_power"

        /** Constantes du sous-système fbdev. */
        private const val FB_BLANK_UNBLANK = 0
        private const val FB_BLANK_POWERDOWN = 4

        /** En dessous, l'écran est illisible et on croirait l'appareil éteint. */
        private const val MIN_PERCENT = 5
    }
}
