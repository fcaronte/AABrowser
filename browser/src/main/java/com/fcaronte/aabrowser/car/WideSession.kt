package com.fcaronte.aabrowser.car

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import com.fcaronte.aabrowser.settings.AppSettings

/**
 * Tocco sulla scheda del player di Android Auto: il pacchetto ha anche il servizio media, quindi con le due app nel
 * menu Android Auto apre la Wide come se fosse il suo player (azione MEDIA_SHOW_PLAYBACK_VIEW). Nascondendo la Wide dal
 * menu apre la Main (prova dell'utente, 06/11/2026): sceglie lui tra quelle visibili. Se la Wide si chiude, Android Auto
 * la riapre subito (log del 20/10/2026, sessione distrutta e ricreata a ogni tocco). Per questo la Wide non chiama
 * navigationStarted() e la navigazione in corso di Maps non si ferma (vedi WideScreen).
 *
 * Con l'opzione "Il tocco sul player apre CarWeb" il tocco dovrebbe andare a un'altra app (PlayerCard). Se arriva
 * comunque qui si passa alla Main e, una volta davanti, si prova a lasciare lo spazio del navigatore chiudendo la Wide,
 * una volta sola: se Android Auto la riapre subito resta aperta. Niente passaggio se sta suonando proprio la Wide.
 */
class WideSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        Log.d(TAG, "onCreateScreen: ${PlayerCard.describe(intent)}")
        if (SystemClock.elapsedRealtime() - closedForPlayerCardAt < REOPENED_MS) {
            Log.w(PlayerCard.TAG, "Android Auto reopened the Wide after it closed for the Main")
            return WideScreen(carContext)
        }
        return if (shouldOpenMain(intent)) PassageScreen(carContext, ::onPassageFinished) else WideScreen(carContext)
    }

    override fun onNewIntent(intent: Intent) {
        Log.d(TAG, "onNewIntent: ${PlayerCard.describe(intent)}")
        // "Portami a …" ridato alla Wide: è lei il navigatore attivo di Android Auto. La pagina di Maps è già aperta
        if (intent.action == CarContext.ACTION_NAVIGATE) {
            Log.d(TAG, "Navigation request came back to the Wide: the Maps page stays")
        }
        val screens = carContext.getCarService(ScreenManager::class.java)
        val top = screens.top
        when {
            shouldOpenMain(intent) -> {
                // Si stava usando la Wide: resta dov'è e si apre anche la Main
                val wideInUse = WideScreen.isInUse()
                PlayerCard.openMain(carContext)
                if (!wideInUse) PlayerCard.waitForMain(onResult = ::onPassageFinished)
            }
            // Aperta dal menu dopo un passaggio alla Main: stavolta si vuole proprio la Wide
            top is PassageScreen -> showWide()
        }
    }

    private fun shouldOpenMain(intent: Intent): Boolean =
        AppSettings.playerCardOpensMain.value && intent.action in MEDIA_CARD_ACTIONS && !PlayerCard.isWidePlaying()

    private fun onPassageFinished(mainInFront: Boolean) {
        if (mainInFront) {
            Log.d(PlayerCard.TAG, "The Main is in front: closing the Wide to give back the navigation space")
            closedForPlayerCardAt = SystemClock.elapsedRealtime()
            carContext.finishCarApp()
        } else if (carContext.getCarService(ScreenManager::class.java).top is PassageScreen) {
            showWide()
        }
    }

    private fun showWide() {
        val screens = carContext.getCarService(ScreenManager::class.java)
        val passage = screens.top
        screens.push(WideScreen(carContext))
        screens.remove(passage)
    }

    private companion object {
        const val TAG = "WideSession"

        // Una sessione nuova entro questo tempo dalla chiusura è Android Auto che riapre la Wide
        const val REOPENED_MS = 4_000L

        val MEDIA_CARD_ACTIONS = setOf(
            "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK",
            "MEDIA_SHOW_PLAYBACK_VIEW"
        )

        var closedForPlayerCardAt = -REOPENED_MS
    }
}
