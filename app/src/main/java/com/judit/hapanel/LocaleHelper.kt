package com.judit.hapanel

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Applique la langue choisie dans les réglages à un écran donné.
 *
 * Android 8.1 ne connaît pas la liste de langues par application apparue en Android 13 :
 * il n'existe aucun réglage système auquel se raccrocher. Le choix est donc conservé par
 * [Prefs] et réimposé ici, écran par écran, en fabriquant un contexte dont la
 * configuration porte la locale voulue.
 *
 * Chaque activité appelle [wrap] depuis `attachBaseContext`, c'est-à-dire **avant** que
 * la moindre ressource soit lue : un appel plus tardif laisserait les textes déjà
 * résolus dans l'ancienne langue.
 */
object LocaleHelper {

    /** Valeur de [Prefs.language] qui laisse la main à la langue du panneau. */
    const val SYSTEM = "systeme"

    /** Les choix proposés dans les réglages, dans l'ordre de la liste déroulante. */
    val CHOICES = listOf(SYSTEM, "fr", "en")

    /**
     * Renvoie un contexte dans la langue réglée, ou [base] tel quel quand on suit le
     * système.
     */
    fun wrap(base: Context): Context {
        val choice = Prefs(base).language
        if (choice == SYSTEM || choice.isBlank()) return base

        val locale = Locale(choice)
        Locale.setDefault(locale)

        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        // setLayoutDirection suit la locale : inutile ici, les deux langues s'écrivent
        // de gauche à droite, mais c'est ce que fait la plateforme et le laisser de côté
        // surprendrait quiconque ajouterait l'arabe ou l'hébreu plus tard.
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * Le nom de la langue tel qu'il s'affiche dans la liste : toujours dans sa propre
     * langue, pour rester lisible à quelqu'un qui ne comprend pas celle en cours.
     */
    fun label(context: Context, choice: String): String = when (choice) {
        "fr" -> "Français"
        "en" -> "English"
        else -> context.getString(R.string.language_system)
    }
}
