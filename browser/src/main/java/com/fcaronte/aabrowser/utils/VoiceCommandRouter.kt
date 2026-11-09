package com.fcaronte.aabrowser.utils

import android.os.SystemClock
import com.fcaronte.aabrowser.mediaservice.MediaFocusManager
import com.fcaronte.aabrowser.settings.AppSettings

/**
 * Smista i comandi vocali che non arrivano dal tasto microfono di una schermata: "Ok Google, metti … su AABrowser"
 * (Assistente e Gemini, tramite CarMediaService o MEDIA_PLAY_FROM_SEARCH), i link condivisi dal telefono con
 * "Condividi su CarWeb" e la prova da adb delle build di debug. La Main e la Wide si registrano finché sono aperte; senza
 * nessuna delle due il comando aspetta per un po' quella che si apre. La Main può essere aperta due volte, sul
 * telefono e in auto: vale quella in auto. Tiene anche le ricerche di YouTube da far partire (primo risultato).
 *
 * Solo dal thread principale.
 */
object VoiceCommandRouter {

    private const val TAG = "VoiceCommandRouter"

    /** Diagnosi dei comandi da Gemini e dall'Assistente, dalla sessione media all'esecuzione (solo build di debug). */
    const val DEBUG_TAG = "CarWebVoiceDebug"

    // Un comando senza schermate aperte vale ancora per così (es. la Main che si sta aprendo)
    private const val PENDING_MS = 60_000L

    // La pagina dei risultati deve arrivare entro questo tempo, poi non parte più niente da sola
    private const val PLAY_FIRST_MS = 30_000L

    enum class Target { MAIN, WIDE }

    fun interface Executor {
        fun execute(command: VoiceCommands.Command)
    }

    private class Registration(
        val target: Target,
        val inCar: Boolean,
        val isInForeground: () -> Boolean,
        val executor: Executor,
        val listen: (() -> Unit)?
    )

    // In ordine di apertura
    private val registrations = ArrayList<Registration>()
    private var pending: VoiceCommands.Command? = null
    private var pendingCarOnly = false
    private var pendingAt = 0L
    private val playFirstRequests = HashMap<Target, Long>()
    private val autoplayRequests = HashMap<Target, Long>()

    /**
     * La Main o la Wide è aperta: riceve i comandi (e quello in attesa, se c'è). [inCar] = sullo schermo dell'auto (la
     * Main può essere anche sul telefono). [listen] fa partire il suo microfono dei comandi vocali (doppio tocco su ⏮
     * al volante).
     */
    fun register(
        target: Target,
        inCar: Boolean,
        isInForeground: () -> Boolean,
        executor: Executor,
        listen: (() -> Unit)? = null
    ) {
        registrations.removeAll { it.executor === executor }
        registrations.add(Registration(target, inCar, isInForeground, executor, listen))
        val waiting = pending ?: return
        if (pendingCarOnly && !inCar) return
        pending = null
        if (SystemClock.uptimeMillis() - pendingAt <= PENDING_MS) {
            AppLog.d(TAG, "Pending command delivered to $target")
            executor.execute(waiting)
        }
    }

    fun unregister(target: Target, executor: Executor) {
        registrations.removeAll { it.target == target && it.executor === executor }
    }

    // La schermata [target] aperta: quella in auto se c'è, altrimenti quella del telefono (non con [carOnly])
    private fun registered(target: Target, carOnly: Boolean = false): Registration? =
        registrations.lastOrNull { it.target == target && it.inCar }
            ?: registrations.takeUnless { carOnly }?.lastOrNull { it.target == target }

    /**
     * Link condiviso dal telefono ("Condividi su CarWeb"): si apre nell'app aperta in auto. false = nessuna aperta, il link
     * aspetta quella che si apre.
     */
    fun openInCar(url: String, label: String? = null): Boolean =
        dispatch(VoiceCommands.Command.OpenLink(url, label), carOnly = true)

    /** Link o file condiviso lontano dall'auto: nella Main del telefono, che aspetta se si sta ancora aprendo. */
    fun openOnPhone(url: String, label: String? = null): Boolean =
        dispatch(VoiceCommands.Command.OpenLink(url, label), Target.MAIN)

    /**
     * "Ok Google, metti … su AABrowser". false = nessuna schermata aperta: il comando resta in attesa
     * di quella che si apre e chi chiama può avvisare l'utente.
     */
    fun fromAssistant(query: String?): Boolean {
        val command = VoiceCommands.forAssistant(query)
        AppLog.d(DEBUG_TAG, "fromAssistant: command=$command")
        return dispatch(command)
    }

    /**
     * Doppio tocco su ⏮ al volante: il microfono dei comandi vocali sulla schermata più adatta (come per
     * l'Assistente), a prescindere da chi ha il player. false = nessuna schermata aperta.
     */
    fun startListening(): Boolean {
        val target = choose() ?: return false
        val listen = registered(target)?.listen ?: return false
        AppLog.d(TAG, "Voice command listening on $target")
        listen()
        return true
    }

    /** Esegue [command] sulla schermata indicata, o sulla più adatta. [carOnly]: solo su quelle dell'auto. */
    fun dispatch(command: VoiceCommands.Command, target: Target? = null, carOnly: Boolean = false): Boolean {
        val chosen = target?.takeIf { registered(it, carOnly) != null } ?: choose(carOnly)
        val registration = chosen?.let { registered(it, carOnly) }
        AppLog.d(DEBUG_TAG, "dispatch: asked=$target chosen=$chosen open=${registrations.map { "${it.target}${if (it.inCar) "" else " (phone)"}" }}")
        if (registration == null) {
            AppLog.d(TAG, "No screen open: command kept for ${PENDING_MS / 1000} s")
            AppLog.d(DEBUG_TAG, "dispatch: no screen open, command pending")
            pending = command
            pendingCarOnly = carOnly
            pendingAt = SystemClock.uptimeMillis()
            return false
        }
        AppLog.d(TAG, "Command ${command::class.simpleName} to $chosen")
        registration.executor.execute(command)
        return true
    }

    // Quella in primo piano; altrimenti quella che ha il player di Android Auto; altrimenti la Wide (in auto). Con
    // "Il tocco sul player apre CarWeb" prima di tutto quella che apre il tocco sul player: la Main, tranne quando suona
    // la Wide. La Wide risulta in primo piano anche quando sta solo nella mappa della schermata principale di Android
    // Auto, e il microfono del player e il doppio tocco al volante andavano sempre a lei
    private fun choose(carOnly: Boolean = false): Target? {
        val owner = when (MediaFocusManager.getActiveSource()) {
            MediaFocusManager.Source.WIDE_APP -> Target.WIDE
            MediaFocusManager.Source.MAIN_APP -> Target.MAIN
            null -> null
        }
        if (AppSettings.playerCardOpensMain.value) {
            val card = if (owner == Target.WIDE) Target.WIDE else Target.MAIN
            if (registered(card, carOnly) != null) return card
        }
        Target.entries.firstOrNull { registered(it, carOnly)?.isInForeground?.invoke() == true }?.let { return it }
        if (owner != null && registered(owner, carOnly) != null) return owner
        return listOf(Target.WIDE, Target.MAIN).firstOrNull { registered(it, carOnly) != null }
    }

    /** Il prossimo caricamento di una ricerca di YouTube o YouTube Music su [target] fa partire il primo risultato. */
    fun requestPlayFirstResult(target: Target) {
        playFirstRequests[target] = SystemClock.uptimeMillis()
    }

    /** Pagina finita su [target]: true, una volta sola, se è la ricerca da far partire. */
    fun consumePlayFirstResult(target: Target, url: String?): Boolean {
        val requestedAt = playFirstRequests[target] ?: return false
        if (SystemClock.uptimeMillis() - requestedAt > PLAY_FIRST_MS) {
            playFirstRequests.remove(target)
            return false
        }
        if (!VoiceCommands.isSearchResultsPage(url)) return false
        playFirstRequests.remove(target)
        return true
    }

    /** Il prossimo video di YouTube che si apre su [target] parte da solo (link condiviso dal telefono). */
    fun requestAutoplay(target: Target) {
        autoplayRequests[target] = SystemClock.uptimeMillis()
    }

    /** Pagina iniziata su [target]: true, una volta sola, se è il video da far partire. */
    fun consumeAutoplay(target: Target, url: String?): Boolean {
        val requestedAt = autoplayRequests[target] ?: return false
        if (SystemClock.uptimeMillis() - requestedAt > PLAY_FIRST_MS) {
            autoplayRequests.remove(target)
            return false
        }
        if (!VoiceCommands.isYouTubeUrl(url)) return false
        autoplayRequests.remove(target)
        return true
    }

    /** C'è una ricerca da far partire su [target] (la pagina dei risultati non è ancora arrivata). */
    fun isPlayFirstPending(target: Target): Boolean {
        val requestedAt = playFirstRequests[target] ?: return false
        return SystemClock.uptimeMillis() - requestedAt <= PLAY_FIRST_MS
    }
}
