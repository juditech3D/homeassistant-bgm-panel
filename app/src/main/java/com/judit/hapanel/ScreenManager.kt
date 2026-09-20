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

    /**
     * À appeler à chaque interaction : toucher, bouton rotatif, proximité.
     *
     * Le réveil ne se fie pas au seul état mémorisé : si la dalle est éteinte alors
     * qu'on se croyait éveillé, on rallume quand même. Sur un panneau encastré, un
     * désaccord entre ce que l'application croit et ce que le matériel fait se paie
     * cher — l'écran reste noir et plus rien n'y donne accès. Le matériel fait donc foi.
     */
    fun noteActivity() {
        if (isAsleep || backlightIsOff()) wake()
        hideScreensaver()
        rearm()
    }

    /** Vrai quand le rétroéclairage est à zéro, quoi qu'en pense [isAsleep]. */
    private fun backlightIsOff(): Boolean {
        if (fallbackOnly) return false
        return read(ACTUAL_BRIGHTNESS)?.toIntOrNull() == 0
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
        // L'instance precedente est desarmee avant d'etre remplacee.
        //
        // Le tableau de bord en construit une a chaque creation, et il est recree plus
        // souvent qu'on ne croit : apres une mise a jour, apres un retour depuis le
        // menu du constructeur, apres une reprise par le systeme. Sans cette ligne,
        // l'ancienne gardait sa minuterie armee sur le fil principal : elle eteignait
        // la dalle et notait *chez elle* que l'ecran dormait, pendant que la nouvelle
        // -- celle que les touchers atteignent -- se croyait eveillee et ne rallumait
        // donc rien. Ecran noir, touchers pris en compte, et aucun moyen d'en sortir.
        current?.dispose()
        current = this
    }

    /**
     * Suspend la veille tant qu'une autre application est au premier plan.
     *
     * Les touchers d'une application tierce -- un navigateur ouvert depuis « Mes
     * applications », l'ecran de desinstallation d'Android -- ne nous parviennent pas.
     * Continuer a compter dans ce cas reviendrait a eteindre la dalle sous les doigts de
     * quelqu'un qui s'en sert, et sans moyen de la rallumer puisque ses touchers ne
     * seraient pas davantage entendus.
     *
     * La contrepartie est assumee : laisser une autre application ouverte maintient
     * l'ecran allume. Mieux vaut une dalle allumee pour rien qu'un panneau aveugle.
     */
    fun watchForeground(application: android.app.Application) {
        application.registerActivityLifecycleCallbacks(
            object : android.app.Application.ActivityLifecycleCallbacks {
                /**
                 * Les ecrans de l'application qu'on a vus demarrer.
                 *
                 * Un ensemble et non un compteur : l'observation commence a la
                 * construction du tableau de bord, donc apres le demarrage de l'ecran
                 * qui l'a lance. L'arret de celui-ci arrive alors sans le demarrage
                 * correspondant, et un compteur tomberait a zero alors que le tableau
                 * de bord est bien a l'ecran -- ce qui desarmait la veille pour de bon.
                 *
                 * Les references sont faibles : un ecran detruit sans passer par
                 * onActivityStopped ne doit pas le retenir en memoire.
                 */
                private val demarrees: MutableSet<android.app.Activity> =
                    java.util.Collections.newSetFromMap(
                        java.util.WeakHashMap<android.app.Activity, Boolean>()
                    )

                override fun onActivityStarted(activity: android.app.Activity) {
                    val premier = demarrees.isEmpty()
                    demarrees.add(activity)
                    if (premier) noteActivity()
                }

                override fun onActivityStopped(activity: android.app.Activity) {
                    // Jamais vue demarrer : on n'en conclut rien.
                    if (!demarrees.remove(activity)) return
                    if (demarrees.isNotEmpty()) return
                    // On passe la main : plus de minuterie, et la dalle reste allumee
                    // pour celui qui prend notre place.
                    dispose()
                    if (isAsleep) wake()
                }

                override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) = Unit
                override fun onActivityResumed(a: android.app.Activity) = Unit
                override fun onActivityPaused(a: android.app.Activity) = Unit
                override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) = Unit
                override fun onActivityDestroyed(a: android.app.Activity) = Unit
            }
        )
    }

    /** Desarme tout : plus aucune minuterie de cette instance ne se declenchera. */
    private fun dispose() {
        ui.removeCallbacks(sleepTask)
        ui.removeCallbacks(screensaverTask)
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
