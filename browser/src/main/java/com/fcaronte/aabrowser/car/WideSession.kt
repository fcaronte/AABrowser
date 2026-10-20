package com.fcaronte.aabrowser.car

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.utils.AppLog

class WideSession : Session() {
    private var wideScreen: WideScreen? = null

    override fun onCreateScreen(intent: Intent): Screen {
        AppLog.d(TAG, "onCreateScreen: ${describe(intent)}")
        // Tocco sulla scheda del player di Android Auto: il pacchetto ha anche il servizio media, quindi Android
        // Auto apre la Wide come se fosse il suo player. La Wide però è un navigatore: partendo chiamerebbe
        // navigationStarted() e chiuderebbe la navigazione in corso (Maps). Qui si chiude subito e resta l'app di prima
        if (isMediaCardLaunch(intent)) return CloseImmediatelyScreen(carContext)
        return WideScreen(carContext).also { wideScreen = it }
    }

    override fun onNewIntent(intent: Intent) {
        AppLog.d(TAG, "onNewIntent: ${describe(intent)}")
        // Android Auto può avviare la sessione normalmente e mandare il tocco sul player subito dopo
        if (isMediaCardLaunch(intent)) wideScreen?.onMediaCardLaunch()
    }

    private fun isMediaCardLaunch(intent: Intent): Boolean = intent.action in MEDIA_CARD_ACTIONS

    private fun describe(intent: Intent): String =
        "action=${intent.action} categories=${intent.categories} extras=${intent.extras?.keySet()}"

    /** Chiude l'app appena parte, senza diventare il navigatore attivo. */
    private class CloseImmediatelyScreen(carContext: CarContext) : Screen(carContext) {
        init {
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    carContext.finishCarApp()
                }
            })
        }

        override fun onGetTemplate(): Template =
            MessageTemplate.Builder(carContext.getString(R.string.wide_app_name)).build()
    }

    companion object {
        private const val TAG = "WideSession"

        // Azioni con cui Android Auto apre l'app dalla scheda del player: quella documentata della Car App Library
        // e quella che arriva davvero (vista nei log, Android Auto 2026)
        private val MEDIA_CARD_ACTIONS = setOf(
            "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK",
            "MEDIA_SHOW_PLAYBACK_VIEW"
        )
    }
}
