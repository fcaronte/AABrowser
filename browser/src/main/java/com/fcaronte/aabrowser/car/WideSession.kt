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
    override fun onCreateScreen(intent: Intent): Screen {
        AppLog.d(TAG, "onCreateScreen: ${describe(intent)}")
        // Tocco sulla scheda del player di Android Auto: il pacchetto ha anche il servizio media, quindi Android
        // Auto apre la Wide come se fosse il suo player. La Wide però è un navigatore: partendo chiamerebbe
        // navigationStarted() e chiuderebbe la navigazione in corso (Maps). Qui si chiude subito e resta l'app di prima
        if (intent.action == ACTION_SHOW_MEDIA_PLAYBACK) return CloseImmediatelyScreen(carContext)
        return WideScreen(carContext)
    }

    override fun onNewIntent(intent: Intent) {
        // Wide già aperta: il tocco sulla scheda del player la lascia com'è
        AppLog.d(TAG, "onNewIntent: ${describe(intent)}")
    }

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

        // Azione con cui Android Auto apre un'app da una superficie del media in riproduzione (scheda del player)
        private const val ACTION_SHOW_MEDIA_PLAYBACK = "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK"
    }
}
