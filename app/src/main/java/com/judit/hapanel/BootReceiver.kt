package com.judit.hapanel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Relance le tableau de bord au démarrage du panneau, pour qu'il se remette tout seul
 * en service après une coupure de courant.
 *
 * On n'ouvre l'écran de configuration que si le panneau n'a jamais été configuré :
 * sinon on va directement au tableau de bord.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        if (!Prefs(context).isConfigured) {
            Log.i(TAG, "démarrage : panneau non configuré, on ne lance rien")
            return
        }

        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Log.i(TAG, "démarrage : tableau de bord lancé")
        } catch (e: Exception) {
            Log.w(TAG, "démarrage : lancement impossible — ${e.message}")
        }
    }

    private companion object {
        const val TAG = "HaPanelBoot"
    }
}
