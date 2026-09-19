package com.judit.hapanel

import android.app.Activity
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlin.concurrent.thread

/**
 * Enchaînement d'une mise à jour, côté écran : vérifier, demander, télécharger, installer.
 *
 * Séparé d'[Updater], qui ne connaît que le réseau et les fichiers, pour que la même
 * logique serve depuis les réglages — sur appui d'un bouton, avec un compte rendu écrit —
 * et depuis le tableau de bord au démarrage, en silence tant qu'il n'y a rien à signaler.
 *
 * **Rien ne s'installe sans accord explicite.** Une application qui se remplace toute
 * seule sur un panneau mural, pendant qu'on s'en sert, serait déroutante ; et une version
 * défectueuse installée sans qu'on l'ait voulu obligerait à démonter le panneau.
 */
class UpdateFlow(private val activity: Activity, private val prefs: Prefs) {

    private val updater = Updater(activity)

    /**
     * Vérifie et propose. [state] reçoit le compte rendu quand il est fourni ; sinon la
     * vérification reste muette tant qu'aucune version n'est disponible.
     */
    fun check(state: TextView? = null) {
        val source = prefs.updateSource
        if (source.isBlank()) {
            state?.setText(R.string.update_no_source)
            return
        }

        state?.setText(R.string.update_checking)

        thread(isDaemon = true) {
            val result = updater.check(source)
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                when (result) {
                    is Updater.Result.Available -> {
                        state?.text = activity.getString(
                            R.string.update_available, result.release.versionName
                        )
                        propose(result.release)
                    }

                    is Updater.Result.UpToDate ->
                        state?.text = activity.getString(
                            R.string.update_up_to_date, result.versionName
                        )

                    is Updater.Result.Failed -> state?.text = result.reason
                }
            }
        }
    }

    private fun propose(release: Updater.Release) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_available, release.versionName))
            .setMessage(release.notes.ifBlank { release.url })
            .setPositiveButton(R.string.update_install) { _, _ -> start(release) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun start(release: Updater.Release) {
        // Indéterminé plutôt qu'une barre : certains serveurs ne renvoient pas la taille,
        // et une barre bloquée à zéro laisserait croire à un blocage.
        val dialog = AlertDialog.Builder(activity)
            .setMessage(R.string.update_downloading_unknown)
            .setCancelable(false)
            .show()

        thread(isDaemon = true) {
            var lastShown = -1
            val error = updater.download(release) { percent ->
                // Un rafraîchissement par point de pourcentage suffit : à chaque bloc de
                // 64 ko, on saturerait le fil d'affichage pour rien.
                if (percent >= 0 && percent != lastShown) {
                    lastShown = percent
                    activity.runOnUiThread {
                        dialog.setMessage(
                            activity.getString(R.string.update_downloading, percent)
                        )
                    }
                }
            }

            activity.runOnUiThread {
                if (error == null) {
                    // L'installation par root tue le processus : ce message n'a le temps
                    // de s'afficher que si Android est passé par son propre installateur.
                    dialog.setMessage(activity.getString(R.string.update_installing))
                } else {
                    dialog.dismiss()
                    Toast.makeText(activity, error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
