package com.fcaronte.aabrowser.settings

import com.fcaronte.aabrowser.R

/**
 * I tasti a destra della barra delle schede. L'utente sceglie quali mostrare, in che ordine e quanti ne vede insieme: gli
 * altri si raggiungono scorrendo i tasti a destra e a sinistra (Impostazioni > barra delle schede).
 */
enum class TabBarAction(val id: String, val labelRes: Int) {
    VOICE("voice", R.string.voice_command_button),
    ADBLOCK("adblock", R.string.adblock_this_site),
    DESKTOP("desktop", R.string.desktop_mode_this_site),
    BACK("back", R.string.back_button),
    FORWARD("forward", R.string.forward_button);

    companion object {
        /** Quelli di sempre, nell'ordine di sempre. */
        val DEFAULT: List<TabBarAction> = listOf(VOICE, ADBLOCK, DESKTOP, BACK, FORWARD)

        /** Quanti se ne vedono insieme, al massimo: quanti sono in tutto. */
        val MAX_VISIBLE: Int = DEFAULT.size

        /**
         * Dalla preferenza salvata ("voice,back"): null (mai scelta) = [DEFAULT]; vuota = nessun tasto. Un tasto sconosciuto
         * (di una versione futura) o ripetuto si salta.
         */
        fun parse(saved: String?): List<TabBarAction> {
            if (saved == null) return DEFAULT
            return saved.split(',')
                .mapNotNull { id -> values().firstOrNull { it.id == id.trim() } }
                .distinct()
        }

        fun serialize(actions: List<TabBarAction>): String = actions.distinct().joinToString(",") { it.id }

        /** Quanti tasti si vedono: almeno uno, al massimo quelli scelti (con nessun tasto, nessuno). */
        fun visibleCount(wanted: Int, shown: Int): Int = if (shown <= 0) 0 else wanted.coerceIn(1, shown)

        /** L'ordine dopo aver spostato il tasto in [index] di [by] posti (-1 su, +1 giù); fuori dai limiti non cambia. */
        fun moved(actions: List<TabBarAction>, index: Int, by: Int): List<TabBarAction> {
            val target = index + by
            if (index !in actions.indices || target !in actions.indices) return actions
            return actions.toMutableList().also {
                val moving = it.removeAt(index)
                it.add(target, moving)
            }
        }
    }
}
