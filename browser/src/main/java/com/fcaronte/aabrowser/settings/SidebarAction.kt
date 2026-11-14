package com.fcaronte.aabrowser.settings

/** Chi mostra la sidebar (il dock): la Wide (sempre) e, a scelta dell'utente, la Main. */
enum class SidebarHost { MAIN, WIDE }

/**
 * I tasti della sidebar: quelli del tasto a ventaglio della Main (FAB) più quelli che la sidebar della Wide aveva già.
 * L'utente sceglie quali mostrare e in che ordine (Impostazioni); Home resta sempre, altrimenti non si tornerebbe alla
 * pagina da cui si cambia tutto. Alcuni tasti esistono solo in una delle due app (le schede solo nella Main).
 */
enum class SidebarAction(
    val id: String,
    /** Non si può nascondere: serve a non restare chiusi dentro una pagina. */
    val essential: Boolean = false,
    /** Dove ha senso il tasto. */
    val hosts: Set<SidebarHost> = setOf(SidebarHost.MAIN, SidebarHost.WIDE),
) {
    HOME("home", essential = true),
    BACK("back"),
    FORWARD("forward"),
    RELOAD("reload"),

    /** Gestione delle schede: la Wide ha una pagina sola. */
    TABS("tabs", hosts = setOf(SidebarHost.MAIN)),
    SEARCH("search"),

    /** Aggiunge la pagina aperta ai preferiti. */
    FAVORITE("favorite"),
    ADBLOCK("adblock"),
    DESKTOP("desktop"),
    VOICE("voice"),

    /** Il codice QR della pagina, da leggere con il telefono. */
    SHARE("share"),
    SETTINGS("settings"),

    /** Chiude l'app: di serie nascosto, un tocco sbagliato non deve chiudere tutto. */
    EXIT("exit");

    fun availableIn(host: SidebarHost): Boolean = host in hosts

    companion object {
        /** Tutti, nell'ordine in cui si propongono a chi li vuole aggiungere. */
        val ALL: List<SidebarAction> = values().toList()

        /** Quelli mostrati di serie, nell'ordine di serie: gli altri si accendono nelle Impostazioni. */
        val DEFAULT: List<SidebarAction> = listOf(HOME, BACK, FORWARD, RELOAD, SEARCH, ADBLOCK, DESKTOP, VOICE, FAVORITE, SETTINGS)

        /**
         * Dalla preferenza salvata ("home,back,reload"): null (mai scelta) = [DEFAULT]. Un tasto sconosciuto (di una versione
         * futura) o ripetuto si salta, e Home c'è sempre: se manca in cima. Gli altri tasti nascosti non tornano da soli.
         */
        fun parse(saved: String?): List<SidebarAction> {
            if (saved == null) return DEFAULT
            val chosen = saved.split(',')
                .mapNotNull { id -> ALL.firstOrNull { it.id == id.trim() } }
                .distinct()
            return withEssentials(chosen)
        }

        fun serialize(actions: List<SidebarAction>): String = actions.distinct().joinToString(",") { it.id }

        /** I tasti essenziali mancanti tornano in cima: Home non si nasconde. */
        fun withEssentials(actions: List<SidebarAction>): List<SidebarAction> {
            val missing = ALL.filter { it.essential && it !in actions }
            return missing + actions
        }

        /** I tasti che [host] può mostrare, nell'ordine scelto. */
        fun forHost(actions: List<SidebarAction>, host: SidebarHost): List<SidebarAction> =
            actions.filter { it.availableIn(host) }

        /** Quelli nascosti (in [ALL] ma non scelti), per proporli di nuovo; solo quelli che [host] sa mostrare. */
        fun hidden(actions: List<SidebarAction>, host: SidebarHost): List<SidebarAction> =
            ALL.filter { it !in actions && it.availableIn(host) }

        /** Senza [action]: un tasto essenziale non si toglie (la lista torna com'era). */
        fun without(actions: List<SidebarAction>, action: SidebarAction): List<SidebarAction> =
            if (action.essential) actions else actions - action

        /** Con [action] in fondo, se non c'è già. */
        fun with(actions: List<SidebarAction>, action: SidebarAction): List<SidebarAction> =
            if (action in actions) actions else actions + action

        /** L'ordine dopo aver spostato il tasto in [index] di [by] posti (-1 su, +1 giù); fuori dai limiti non cambia. */
        fun moved(actions: List<SidebarAction>, index: Int, by: Int): List<SidebarAction> {
            val target = index + by
            if (index !in actions.indices || target !in actions.indices) return actions
            return actions.toMutableList().also {
                val moving = it.removeAt(index)
                it.add(target, moving)
            }
        }
    }
}
