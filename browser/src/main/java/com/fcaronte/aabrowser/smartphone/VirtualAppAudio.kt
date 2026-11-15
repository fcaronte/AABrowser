package com.fcaronte.aabrowser.smartphone

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.fcaronte.aabrowser.mediaservice.CarMediaService
import com.fcaronte.aabrowser.utils.VoiceCommands

/**
 * L'audio tra il browser e le app del telefono sullo schermo virtuale. Un'app che comincia a suonare prende il focus al browser da
 * sola (Android lo toglie alla WebView, che si ferma); qui si guarda soltanto, senza chiedere mai il focus a nome dell'app:
 * quando l'app smette, il browser che suonava riprende ([AudioHandoff] decide quando, con le sue cautele).
 *
 * Si guarda finché c'è almeno una sessione di app aperta ([acquire] / [release] dalla sessione): i lettori di media attivi nel
 * telefono (AudioManager, senza permessi: delle altre app si vede solo che suonano) e, ogni mezzo secondo, lo stato del player
 * di Android Auto. Tutto sul thread principale.
 */
object VirtualAppAudio {
    private const val TAG = "VirtualAppAudio"
    private const val TICK_MS = 500L

    private val handler = Handler(Looper.getMainLooper())
    private val handoff = AudioHandoff()
    private var users = 0
    private var audioManager: AudioManager? = null
    private var callback: AudioManager.AudioPlaybackCallback? = null

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            handoff.onBrowser(CarMediaService.isBrowserPlaying(), now)
            if (handoff.tick(now) == AudioHandoff.Action.RESUME_BROWSER) {
                Log.i(TAG, "the app stopped: the browser that was playing resumes")
                CarMediaService.sendPlayerCommand(VoiceCommands.PlayerAction.PLAY)
            }
            if (users > 0) handler.postDelayed(this, TICK_MS)
        }
    }

    /**
     * Il telefono ha l'audio occupato da altro: una chiamata o una comunicazione in corso (Android non darebbe il focus a nessun
     * lettore di media, quindi riprovare ogni pochi secondi non serve), oppure qualcosa suona adesso (musica, video, giochi).
     * Lo chiede la pagina della TV quando il suo video è fermo senza che l'utente l'abbia chiesto: con il suo lettore fermo,
     * quello che suona è di un'altra app, e non le si toglie l'audio per riprendere (finché dura la pagina aspetta, poi riprende).
     */
    fun otherAudioPlaying(context: Context): Boolean {
        val manager = context.getSystemService(AudioManager::class.java) ?: return false
        if (manager.mode != AudioManager.MODE_NORMAL) return true
        return mediaPlayers(manager.activePlaybackConfigurations) > 0
    }

    /** Una sessione di app si apre: la prima accende l'osservazione. */
    fun acquire(context: Context) {
        if (users++ == 0) start(context.applicationContext)
    }

    /** Una sessione si chiude: l'ultima spegne l'osservazione. */
    fun release() {
        if (users > 0 && --users == 0) stop()
    }

    private fun start(context: Context) {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        audioManager = manager
        handoff.reset(mediaPlayers(manager.activePlaybackConfigurations), CarMediaService.isBrowserPlaying())
        val observer = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                handoff.onPlayers(mediaPlayers(configs), SystemClock.elapsedRealtime())
            }
        }
        callback = observer
        try {
            manager.registerAudioPlaybackCallback(observer, handler)
        } catch (e: Exception) {
            Log.w(TAG, "playback callback not registered: ${e.javaClass.simpleName}")
        }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
    }

    private fun stop() {
        handler.removeCallbacks(tick)
        callback?.let { audioManager?.unregisterAudioPlaybackCallback(it) }
        callback = null
        audioManager = null
    }

    /** I lettori che suonano musica, video o giochi (non le notifiche, la navigazione, le chiamate: il browser non riprende per loro). */
    private fun mediaPlayers(configs: List<AudioPlaybackConfiguration>): Int = configs.count { config ->
        when (config.audioAttributes.usage) {
            AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN -> true
            else -> false
        }
    }
}
