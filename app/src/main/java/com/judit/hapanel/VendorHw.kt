package com.judit.hapanel

import android.util.Log
import java.io.File

/**
 * Accès à l'API constructeur du panneau, exposée en /proc/vendor/.
 *
 * Ces fichiers sont en rw-rw-rw-, donc utilisables sans root. Les noms et la
 * sémantique ont été relevés sur l'appareil et recoupés avec l'app de test usine
 * du fabricant (spc_testdemo).
 */
object VendorHw {

    private const val TAG = "VendorHw"
    private const val DIR = "/proc/vendor"

    /**
     * Alimentation de l'écran rond du bouton rotatif (GPIO 110, `lcd_ctrl_en`).
     *
     * **À appeler au démarrage de l'application.** Cette ligne est remise à l'arrêt à
     * chaque redémarrage du panneau : c'était l'application constructeur `bgmz9` qui
     * l'activait au boot, et elle a été désactivée parce qu'elle accaparait le focus
     * clavier. Sans cet appel, l'écran rond reste noir alors même que les écritures
     * dans le framebuffer réussissent — symptôme trompeur.
     */
    fun setKnobScreenPower(on: Boolean) = write("lcd_ctrl", if (on) "on" else "off")

    fun isKnobScreenPowered(): Boolean = read("lcd_ctrl") == "on"

    /**
     * Anneau lumineux du bouton rotatif.
     *
     * Attention : ce n'est pas un RGB adressable. Un unique GPIO (led_ctrl_en, gpio-13)
     * bascule entre deux états câblés — `off` donne du rouge, `on` donne du blanc.
     * Aucune couleur intermédiaire n'est possible.
     */
    fun setRingWhite(white: Boolean) = write("led_ctrl", if (white) "on" else "off")

    fun isRingWhite(): Boolean = read("led_ctrl") == "on"

    /** Avertisseur sonore intégré. */
    fun setHorn(on: Boolean) = write("horn_mode", if (on) "on" else "off")

    fun isHornOn(): Boolean = read("horn_mode") == "on"

    /** Sourdine manuelle de l'amplificateur audio intégré. */
    fun setMute(muted: Boolean) = write("mute_manual", if (muted) "on" else "off")

    fun isMuted(): Boolean = read("mute_manual") == "on"

    /** Dalle tactile de l'écran principal. */
    fun setTouch(enabled: Boolean) = write("touch_ctrl", if (enabled) "1" else "0")

    /** Sens du bus RS485 : émission ou réception. */
    fun setRs485Transmit(transmit: Boolean) =
        write("485_tx_mode", if (transmit) "send" else "receive")

    fun rs485Mode(): String = read("485_tx_mode")

    /** Entrée auxiliaire : source locale ou externe. */
    fun auxMode(): String = read("aux_mode")

    /**
     * Les quatre relais du pilote constructeur.
     *
     * Sur cet exemplaire le bornier externe ne comporte **aucune borne de relais** :
     * ils proviennent vraisemblablement d'un pilote partagé avec d'autres modèles de la
     * gamme. Ils restent accessibles, mais ne les activer qu'en connaissance de cause —
     * s'ils commandaient du 230 V réel, un essai à l'aveugle aurait des conséquences
     * physiques.
     */
    fun setRelay(index: Int, on: Boolean) {
        val name = relayName(index) ?: return
        write(name, if (on) "on" else "off")
    }

    fun isRelayOn(index: Int): Boolean {
        val name = relayName(index) ?: return false
        return read(name) == "on"
    }

    private fun relayName(index: Int): String? = when (index) {
        1 -> "relay_first"
        2 -> "relay_second"
        3 -> "relay_third"
        4 -> "relay_forth"
        else -> null
    }

    val isAvailable: Boolean
        get() = File(DIR, "led_ctrl").exists()

    private fun write(name: String, value: String) {
        try {
            File(DIR, name).writeText(value)
        } catch (e: Exception) {
            Log.w(TAG, "écriture $name impossible : ${e.message}")
        }
    }

    private fun read(name: String): String = try {
        File(DIR, name).readText().trim()
    } catch (e: Exception) {
        ""
    }
}
