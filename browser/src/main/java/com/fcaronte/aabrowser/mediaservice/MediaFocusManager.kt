package com.fcaronte.aabrowser.mediaservice

import android.os.SystemClock
import com.fcaronte.aabrowser.utils.AppLog
import java.lang.ref.WeakReference

/**
 * Decide quale client controlla il player di Android Auto.
 * Ogni WebView è un client a sé (ogni scheda della Main e la Wide): vince sempre l'ultima
 * riproduzione avviata, mentre quella precedente viene messa in pausa e sganciata dal player
 * senza chiudere né ricaricare la sua pagina.
 */
object MediaFocusManager {

    enum class Source {
        MAIN_APP,
        WIDE_APP
    }

    interface MediaClient {
        val source: Source
        fun pauseMedia()
        fun onFocusLost()
        fun onFocusGained()
    }

    private const val TAG = "MediaFocusManager"

    // Un client appena sganciato non può riprendersi subito il player: gli eventi "play" che la sua
    // pagina può ancora inviare mentre viene messa in pausa causerebbero un ping-pong tra le due riproduzioni
    private const val RECLAIM_GUARD_MS = 1000L

    private var activeClient: MediaClient? = null
    private var previousClientRef: WeakReference<MediaClient>? = null
    private var previousClientLostAt = 0L

    /**
     * Restituisce true se dopo la chiamata il player appartiene a [client].
     */
    @Synchronized
    fun requestMediaFocus(client: MediaClient): Boolean {
        val prevClient = activeClient
        if (prevClient === client) {
            AppLog.d(TAG, "Media focus RETAINED by ${client.source}")
            return true
        }

        val justLost = previousClientRef?.get() === client &&
                SystemClock.elapsedRealtime() - previousClientLostAt < RECLAIM_GUARD_MS
        if (justLost) {
            AppLog.d(TAG, "Media focus DENIED to ${client.source}: lost it less than ${RECLAIM_GUARD_MS}ms ago")
            return false
        }

        AppLog.d(TAG, "Media focus REQUEST: switching from ${prevClient?.source} to ${client.source}")
        activeClient = client

        if (prevClient != null) {
            previousClientRef = WeakReference(prevClient)
            previousClientLostAt = SystemClock.elapsedRealtime()
            // Sgancia la riproduzione precedente: niente più aggiornamenti verso il player e media in pausa
            prevClient.onFocusLost()
            prevClient.pauseMedia()
        }

        AppLog.d(TAG, "Media focus GRANTED to ${client.source}")
        client.onFocusGained()
        return true
    }

    @Synchronized
    fun releaseMediaFocus(client: MediaClient) {
        if (activeClient === client) {
            AppLog.d(TAG, "Media focus RELEASED by ${client.source}")
            activeClient = null
        }
        if (previousClientRef?.get() === client) {
            previousClientRef = null
        }
    }

    @Synchronized
    fun isFocusOwner(client: MediaClient): Boolean = activeClient === client

    @Synchronized
    fun getActiveSource(): Source? = activeClient?.source
}
