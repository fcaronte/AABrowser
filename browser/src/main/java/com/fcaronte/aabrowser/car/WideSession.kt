package com.fcaronte.aabrowser.car

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import com.fcaronte.aabrowser.utils.AppLog

/**
 * Tocco sulla scheda del player di Android Auto: il pacchetto ha anche il servizio media, quindi Android Auto apre
 * la Wide come se fosse il suo player (azione MEDIA_SHOW_PLAYBACK_VIEW). Non si può evitare: se la Wide si chiude,
 * Android Auto la riapre subito (log del 20/10/2026, sessione distrutta e ricreata a ogni tocco). Per questo la
 * Wide non chiama navigationStarted() e la navigazione in corso di Maps non si ferma (vedi WideScreen).
 */
class WideSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        AppLog.d(TAG, "onCreateScreen: ${describe(intent)}")
        return WideScreen(carContext)
    }

    override fun onNewIntent(intent: Intent) {
        AppLog.d(TAG, "onNewIntent: ${describe(intent)}")
        // "Portami a …" ridato alla Wide: è lei il navigatore attivo di Android Auto. La pagina di Maps è già aperta
        if (intent.action == CarContext.ACTION_NAVIGATE) {
            AppLog.d(TAG, "Navigation request came back to the Wide: the Maps page stays")
        }
    }

    // Anche i valori degli extra: GH.TargetComponent dice quale componente Android Auto voleva aprire
    @Suppress("DEPRECATION")
    private fun describe(intent: Intent): String {
        val extras = intent.extras?.let { bundle -> bundle.keySet().joinToString { key -> "$key=${bundle.get(key)}" } }
        return "action=${intent.action} categories=${intent.categories} extras=[$extras]"
    }

    companion object {
        private const val TAG = "WideSession"
    }
}
