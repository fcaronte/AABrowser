package com.fcaronte.aabrowser.car

import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.CarContext
import com.fcaronte.aabrowser.CarService
import com.fcaronte.aabrowser.MainCarActivity
import com.fcaronte.aabrowser.mediaservice.MediaFocusManager
import com.fcaronte.aabrowser.settings.AppSettings

/**
 * "Ok Google, apri CarWeb": Gemini chiede ad Android Auto di aprire il pacchetto (azione MAIN, nessun componente) e
 * Android Auto apre la prima app del pacchetto nel menu, la Wide (log del 07/11/2026). Con "Il tocco sul player apre
 * CarWeb" la Wide porta davanti la Main, come il tocco sul player (tranne quando suona lei). La Wide resta: chiusa,
 * Android Auto la riapre subito e rimanda indietro la Main (log delle 18:10). L'avvio da Gemini non ha azione né i
 * segni degli altri avvii: icona del menu, scheda del player, ripresa all'avvio dell'auto, avvii della Wide stessa.
 * Log con tag MainHandOver, anche nelle build di release.
 */
internal object MainHandOver {
    const val TAG = "MainHandOver"

    /** Sugli avvii che la Wide fa da sola (comandi vocali con la Wide dietro): non sono di Gemini. */
    const val EXTRA_FROM_WIDE = "com.fcaronte.aabrowser.FROM_WIDE"

    private val LAUNCH_MARKERS = listOf(
        EXTRA_FROM_WIDE,
        "ANDROID_AUTO_APP_LAUNCHER_INTENT",
        "EXTRA_STARTED_BY_DASHBOARD",
        "com.google.android.gms.car.EXTRA_CAR_ACTIVITY_FLAGS"
    )

    private val handler = Handler(Looper.getMainLooper())

    fun isAssistantOpen(intent: Intent): Boolean =
        intent.action == null && intent.hasExtra("AppKey") && LAUNCH_MARKERS.none { intent.hasExtra(it) }

    fun wantsMain(): Boolean = AppSettings.playerCardOpensMain.value &&
            MediaFocusManager.getActiveSource() != MediaFocusManager.Source.WIDE_APP

    /**
     * Dopo che Android Auto ha finito di aprire la Wide: l'SDK di Android Auto se la Main è già aperta (è quello che usa
     * lei per tornare davanti) e la richiesta con il suo componente, che è dello stesso pacchetto.
     */
    fun openMainSoon(carContext: CarContext) {
        handler.post {
            val wasRunning = MainCarActivity.bringRunningToFront()
            Log.d(TAG, "Opening the Main (already open=$wasRunning)")
            try {
                carContext.startCarApp(Intent().setComponent(ComponentName(carContext, CarService::class.java)))
            } catch (e: Exception) {
                Log.w(TAG, "Android Auto refused to open the Main: ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }
}
