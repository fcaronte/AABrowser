package com.fcaronte.aabrowser.utils

import android.os.SystemClock
import com.fcaronte.aabrowser.mediaservice.MediaFocusManager

/**
 * Smista i comandi vocali che non arrivano dal tasto microfono di una schermata: "Ok Google, metti … su AABrowser"
 * (Assistente e Gemini, tramite CarMediaService o MEDIA_PLAY_FROM_SEARCH) e la prova da adb delle build di debug.
 * La Main e la Wide si registrano finché sono aperte; senza nessuna delle due il comando aspetta per un po'
 * quella che si apre. Tiene anche le ricerche di YouTube da far partire (primo risultato).
 *
 * Solo dal thread principale.
 */
object VoiceCommandRouter {

    private const val TAG = "VoiceCommandRouter"

    // Un comando senza schermate aperte vale ancora per così (es. la Main che si sta aprendo)
    private const val PENDING_MS = 60_000L

    // La pagina dei risultati deve arrivare entro questo tempo, poi non parte più niente da sola
    private const val PLAY_FIRST_MS = 30_000L

    enum class Target { MAIN, WIDE }

    fun interface Executor {
        fun execute(command: VoiceCommands.Command)
    }

    private class Registration(val isInForeground: () -> Boolean, val executor: Executor)

    private val registrations = HashMap<Target, Registration>()
    private var pending: VoiceCommands.Command? = null
    private var pendingAt = 0L
    private val playFirstRequests = HashMap<Target, Long>()

    /** La Main o la Wide è aperta: riceve i comandi (e quello in attesa, se c'è). */
    fun register(target: Target, isInForeground: () -> Boolean, executor: Executor) {
        registrations[target] = Registration(isInForeground, executor)
        val waiting = pending ?: return
        pending = null
        if (SystemClock.uptimeMillis() - pendingAt <= PENDING_MS) {
            AppLog.d(TAG, "Pending command delivered to $target")
            executor.execute(waiting)
        }
    }

    fun unregister(target: Target, executor: Executor) {
        if (registrations[target]?.executor === executor) registrations.remove(target)
    }

    /**
     * "Ok Google, metti … su AABrowser". false = nessuna schermata aperta: il comando resta in attesa
     * di quella che si apre e chi chiama può avvisare l'utente.
     */
    fun fromAssistant(query: String?): Boolean = dispatch(VoiceCommands.forAssistant(query))

    /** Esegue [command] sulla schermata indicata, o sulla più adatta. */
    fun dispatch(command: VoiceCommands.Command, target: Target? = null): Boolean {
        val chosen = target?.takeIf { registrations.containsKey(it) } ?: choose()
        val registration = chosen?.let { registrations[it] }
        if (registration == null) {
            AppLog.d(TAG, "No screen open: command kept for ${PENDING_MS / 1000} s")
            pending = command
            pendingAt = SystemClock.uptimeMillis()
            return false
        }
        AppLog.d(TAG, "Command ${command::class.simpleName} to $chosen")
        registration.executor.execute(command)
        return true
    }

    // Quella in primo piano; altrimenti quella che ha il player di Android Auto; altrimenti la Wide (in auto)
    private fun choose(): Target? {
        Target.entries.firstOrNull { registrations[it]?.isInForeground?.invoke() == true }?.let { return it }
        val owner = when (MediaFocusManager.getActiveSource()) {
            MediaFocusManager.Source.WIDE_APP -> Target.WIDE
            MediaFocusManager.Source.MAIN_APP -> Target.MAIN
            null -> null
        }
        if (owner != null && registrations.containsKey(owner)) return owner
        return listOf(Target.WIDE, Target.MAIN).firstOrNull { registrations.containsKey(it) }
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

    /** C'è una ricerca da far partire su [target] (la pagina dei risultati non è ancora arrivata). */
    fun isPlayFirstPending(target: Target): Boolean {
        val requestedAt = playFirstRequests[target] ?: return false
        return SystemClock.uptimeMillis() - requestedAt <= PLAY_FIRST_MS
    }
}
