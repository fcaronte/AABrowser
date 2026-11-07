package com.fcaronte.aabrowser.car

import android.content.Intent
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session

/**
 * Tocco sulla scheda del player di Android Auto: se la sessione non dichiara un'app (l'opzione "Il tocco sul player apre
 * CarWeb", vedi CarMediaService) arriva alla prima app del pacchetto nel menu, cioè qui, con l'azione
 * MEDIA_SHOW_PLAYBACK_VIEW. Non si può evitare da qui: se la Wide si chiude, Android Auto la riapre subito (log del 20/10
 * e del 06/11/2026). Per questo la Wide non chiama navigationStarted() e la navigazione in corso di Maps non si ferma
 * (vedi WideScreen).
 */
class WideSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        Log.d(TAG, "onCreateScreen: ${describe(intent)}")
        return WideScreen(carContext)
    }

    override fun onNewIntent(intent: Intent) {
        Log.d(TAG, "onNewIntent: ${describe(intent)}")
        // "Portami a …" ridato alla Wide: è lei il navigatore attivo di Android Auto. La pagina di Maps è già aperta
        if (intent.action == CarContext.ACTION_NAVIGATE) {
            Log.d(TAG, "Navigation request came back to the Wide: the Maps page stays")
        }
    }

    @Suppress("DEPRECATION")
    private fun describe(intent: Intent): String {
        val extras = intent.extras?.let { bundle -> bundle.keySet().joinToString { key -> "$key=${bundle.get(key)}" } }
        return "action=${intent.action} categories=${intent.categories} extras=[$extras]"
    }

    private companion object {
        const val TAG = "WideSession"
    }
}
