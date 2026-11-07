package com.fcaronte.aabrowser.car

import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.fcaronte.aabrowser.CarService
import com.fcaronte.aabrowser.MainCarActivity
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.mediaservice.MediaFocusManager
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.utils.AppLocale

/**
 * "Ok Google, apri CarWeb": Gemini fa aprire ad Android Auto sempre la Wide, anche quando chiede CarWeb (AppKey della
 * Wide nei log del 07/11/2026). Con "Il tocco sul player apre CarWeb" la Wide passa alla Main, come il tocco sul player
 * (tranne quando suona lei), e si chiude se l'aveva portata davanti Gemini: altrimenti resterebbe al posto del
 * navigatore. L'avvio da Gemini non ha azione né i segni degli altri avvii: icona del menu, scheda del player, ripresa
 * all'avvio dell'auto. Log con tag MainHandOver, anche nelle build di release.
 */
internal object MainHandOver {
    const val TAG = "MainHandOver"

    private const val CHECK_MS = 200L
    private const val GIVE_UP_MS = 5_000L

    // Android Auto potrebbe riaprire subito la Wide appena chiusa: allora resta aperta, niente giri a vuoto
    private const val REOPEN_GUARD_MS = 5_000L

    /** Sugli avvii che la Wide fa da sola (comandi vocali con la Wide dietro): non sono di Gemini. */
    const val EXTRA_FROM_WIDE = "com.fcaronte.aabrowser.FROM_WIDE"

    private val LAUNCH_MARKERS = listOf(
        EXTRA_FROM_WIDE,
        "ANDROID_AUTO_APP_LAUNCHER_INTENT",
        "EXTRA_STARTED_BY_DASHBOARD",
        "com.google.android.gms.car.EXTRA_CAR_ACTIVITY_FLAGS"
    )

    private val handler = Handler(Looper.getMainLooper())
    private var closedForMainAt = 0L

    fun isAssistantOpen(intent: Intent): Boolean =
        intent.action == null && intent.hasExtra("AppKey") && LAUNCH_MARKERS.none { intent.hasExtra(it) }

    fun wantsMain(): Boolean = AppSettings.playerCardOpensMain.value &&
            MediaFocusManager.getActiveSource() != MediaFocusManager.Source.WIDE_APP

    fun justClosedForMain(): Boolean = SystemClock.elapsedRealtime() - closedForMainAt < REOPEN_GUARD_MS

    /**
     * Le due strade per aprire la Main da un'app della Car App Library: l'SDK di Android Auto se è già aperta (è quello
     * che usa lei per tornare davanti) e la richiesta con il suo componente, che è dello stesso pacchetto.
     * false se Android Auto ha rifiutato e la Main non era aperta.
     */
    fun openMain(carContext: CarContext): Boolean {
        val wasRunning = MainCarActivity.bringRunningToFront()
        Log.d(TAG, "Opening the Main (already open=$wasRunning)")
        return try {
            carContext.startCarApp(Intent().setComponent(ComponentName(carContext, CarService::class.java)))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Android Auto refused to open the Main: ${e.javaClass.simpleName} ${e.message}")
            wasRunning
        }
    }

    /** Main aperta davanti, poi la Wide chiusa (se [closeWide]). La Main è nello stesso processo: si vede da qui. */
    fun handOver(carContext: CarContext, closeWide: Boolean) {
        if (!openMain(carContext)) {
            if (closeWide) close(carContext)
            return
        }
        waitForMain(SystemClock.elapsedRealtime()) { inFront ->
            if (!inFront) Log.w(TAG, "Android Auto did not open the Main")
            if (closeWide) close(carContext)
        }
    }

    private fun close(carContext: CarContext) {
        Log.d(TAG, "Closing the Wide opened by the assistant")
        closedForMainAt = SystemClock.elapsedRealtime()
        carContext.finishCarApp()
    }

    private fun waitForMain(startedAt: Long, onResult: (Boolean) -> Unit) {
        when {
            MainCarActivity.isInFrontSince(startedAt) -> onResult(true)
            SystemClock.elapsedRealtime() - startedAt > GIVE_UP_MS -> onResult(false)
            else -> handler.postDelayed({ waitForMain(startedAt, onResult) }, CHECK_MS)
        }
    }
}

/** La Wide aperta da Gemini solo per passare alla Main: niente pagine né WebView, poi si chiude. */
internal class HandOverScreen(carContext: CarContext) : Screen(carContext) {
    private val strings = AppLocale.wrap(carContext)
    private var started = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                if (started) return
                started = true
                MainHandOver.handOver(carContext, closeWide = true)
            }
        })
    }

    // setTitle/setHeaderAction sono deprecati, ma il sostituto (setHeader) richiede le API auto 7 e l'app parte dalla 5
    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(strings.getString(R.string.opening_main))
            .setTitle(strings.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .build()
}
