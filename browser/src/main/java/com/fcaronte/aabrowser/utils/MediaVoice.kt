package com.fcaronte.aabrowser.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import com.fcaronte.aabrowser.R
import org.json.JSONObject
import java.util.Locale

/**
 * Porta i comandi vocali alle pagine di CarWeb Media (Media, TV, Radio, Podcast). La richiesta resta qui, una alla volta: la
 * pagina la chiede all'app quando si apre (/api/voice, con il suo token) o quando l'app la avvisa ([CHECK_SCRIPT]) se è
 * già aperta. Così una pagina qualsiasi non può far partire niente con un indirizzo, e ricaricare non ripete il comando.
 */
object MediaVoice {
    /** Avvisa la pagina già aperta; su una pagina che si sta ancora aprendo non fa niente: chiederà da sola. */
    const val CHECK_SCRIPT = "window.aabVoice && window.aabVoice();"

    // La pagina che si sta aprendo deve arrivare entro questo tempo, poi la richiesta non vale più
    private const val MAX_WAIT_MS = 30_000L

    /** Cambia a ogni richiesta: la scheda attiva della Main la annuncia alla sua pagina (BrowserScreen). */
    val version = mutableIntStateOf(0)

    private class Pending(val plan: VoiceCommands.Plan.Media, val target: VoiceCommandRouter.Target, val at: Long)

    private var pending: Pending? = null
    private var pendingFallback: Pending? = null

    /** Solo dal thread principale. */
    fun submit(plan: VoiceCommands.Plan.Media, target: VoiceCommandRouter.Target) {
        synchronized(this) { pending = Pending(plan, target, SystemClock.uptimeMillis()) }
        version.intValue++
    }

    /** C'è una richiesta che la pagina dovrebbe venire a prendere. */
    fun hasPending(): Boolean = synchronized(this) { pending != null }

    /** Risposta a /api/voice: la richiesta per quella pagina, una volta sola; {} se non ce ne sono. */
    fun take(section: String?): JSONObject = synchronized(this) {
        val current = pending ?: return JSONObject()
        val isExpired = SystemClock.uptimeMillis() - current.at > MAX_WAIT_MS
        if (isExpired) pending = null
        if (isExpired || current.plan.section.name != section?.uppercase(Locale.ROOT)) return JSONObject()
        pending = null
        pendingFallback = current.takeIf { it.plan.fallback != null }
        val plan = current.plan
        JSONObject()
            .put("query", plan.query ?: JSONObject.NULL)
            .put("play", plan.play)
            .put("action", plan.action?.name?.lowercase(Locale.ROOT) ?: JSONObject.NULL)
            .put("fallback", plan.fallback != null)
    }

    /** La pagina non ha trovato niente e la frase poteva voler dire altro ("metti radio ga ga"): si fa quello. */
    fun runFallback() {
        val current = synchronized(this) { pendingFallback.also { pendingFallback = null } } ?: return
        val command = current.plan.fallback ?: return
        Handler(Looper.getMainLooper()).post { VoiceCommandRouter.dispatch(command, current.target) }
    }

    fun title(context: Context, section: VoiceCommands.MediaSection): String = context.getString(
        when (section) {
            VoiceCommands.MediaSection.MEDIA -> R.string.favorite_default_name_0
            VoiceCommands.MediaSection.TV -> R.string.favorite_default_name_1
            VoiceCommands.MediaSection.RADIO -> R.string.favorite_default_name_2
            VoiceCommands.MediaSection.PODCAST -> R.string.favorite_default_name_12
        }
    )

    /** Il messaggio che conferma il comando; null per i tasti del player, che si vedono da soli. */
    fun message(context: Context, plan: VoiceCommands.Plan.Media): String? {
        if (plan.action != null) return null
        val query = plan.query ?: return context.getString(R.string.voice_command_open, title(context, plan.section))
        return context.getString(
            when (plan.section) {
                VoiceCommands.MediaSection.MEDIA -> R.string.voice_command_media
                VoiceCommands.MediaSection.TV -> R.string.voice_command_tv
                VoiceCommands.MediaSection.RADIO -> R.string.voice_command_radio
                VoiceCommands.MediaSection.PODCAST -> R.string.voice_command_podcast
            },
            query
        )
    }
}
