package com.fcaronte.aabrowser.car

import android.content.Intent
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session

/**
 * Tocco sulla scheda del player di Android Auto: il pacchetto ha anche il servizio media, quindi con le due app nel
 * menu Android Auto apre la Wide come se fosse il suo player (azione MEDIA_SHOW_PLAYBACK_VIEW). Nascondendo la Wide dal
 * menu apre la Main (prova dell'utente, 06/11/2026): sceglie lui tra quelle visibili. Non si può evitare da qui: se la
 * Wide si chiude, Android Auto la riapre subito (log del 20/10 e del 06/11/2026). L'unico modo è dargli un'altra app a
 * cui mandare il tocco: l'opzione "Il tocco sul player apre CarWeb" (PlayerCard). Per questo la Wide non chiama
 * navigationStarted() e la navigazione in corso di Maps non si ferma (vedi WideScreen).
 */
class WideSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        Log.d(TAG, "onCreateScreen: ${PlayerCard.describe(intent)}")
        return WideScreen(carContext)
    }

    override fun onNewIntent(intent: Intent) {
        Log.d(TAG, "onNewIntent: ${PlayerCard.describe(intent)}")
        // "Portami a …" ridato alla Wide: è lei il navigatore attivo di Android Auto. La pagina di Maps è già aperta
        if (intent.action == CarContext.ACTION_NAVIGATE) {
            Log.d(TAG, "Navigation request came back to the Wide: the Maps page stays")
        }
    }

    private companion object {
        const val TAG = "WideSession"
    }
}
