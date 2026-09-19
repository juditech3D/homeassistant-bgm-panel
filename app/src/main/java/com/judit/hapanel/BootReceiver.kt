package com.judit.hapanel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Relance le tableau de bord quand le panneau démarre, et **après chaque mise à jour**.
 *
 * Le second cas est le plus important sur un appareil encastré au mur. `pm install -r`
 * tue le processus de l'application, et avec lui tout ce qui aurait pu la relancer : un
 * shell lancé par l'application meurt en même temps qu'elle — constaté, le shell détaché
 * de la 0.4 était tué avant son `am start`, l'installation ayant déjà été confiée au
 * service système. Le panneau retombait alors sur le lanceur d'origine et y restait.
 *
 * `ACTION_MY_PACKAGE_REPLACED` est le signal prévu pour cela : Android l'envoie à
 * l'application **après** l'avoir réinstallée, donc dans un processus neuf que rien ne
 * va tuer. Il ne dépend ni du root, ni d'un shell, et vaut donc aussi quand la mise à
 * jour est passée par l'installateur du système.
 *
 * On n'ouvre l'écran de configuration que si le panneau n'a jamais été configuré :
 * sinon on va directement au tableau de bord.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val cause = when (action) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON" ->
                "démarrage"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "mise à jour"
            else -> return
        }

        if (!Prefs(context).isConfigured) {
            Log.i(TAG, "$cause : panneau non configuré, on ne lance rien")
            return
        }

        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Log.i(TAG, "$cause : tableau de bord lancé")
        } catch (e: Exception) {
            Log.w(TAG, "$cause : lancement impossible — ${e.message}")
        }
    }

    private companion object {
        const val TAG = "HaPanelBoot"
    }
}
