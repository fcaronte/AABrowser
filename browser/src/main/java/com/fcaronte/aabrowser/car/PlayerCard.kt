package com.fcaronte.aabrowser.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.fcaronte.aabrowser.CarService
import com.fcaronte.aabrowser.MainCarActivity
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.mediaservice.MediaFocusManager
import com.fcaronte.aabrowser.utils.AppLocale

/**
 * Opzione "Il tocco sul player apre CarWeb". Android Auto manda il tocco sulla scheda del player a un'app del pacchetto
 * tra quelle nel suo menu: con Wide e Main visibili sceglie la Wide, che però è un'app di navigazione e prende il posto
 * del navigatore aperto, anche se poi si passa alla Main (prova dell'utente, 06/11/2026).
 *
 * Con l'opzione attiva c'è un'altra app del pacchetto ([CarServicePlayerCard]): non è di navigazione, quindi non tocca
 * lo spazio del navigatore, apre la Main e si chiude. Non può essere di categoria media: Android Auto la prendeva per il
 * servizio del suo player, invece di CarMediaService, e il player restava vuoto (prova dell'utente, log GH.Media*). Se
 * Android Auto manda comunque il tocco alla Wide, ci pensa WideSession. Log con tag PlayerCard, anche nelle release.
 */
internal object PlayerCard {
    const val TAG = "PlayerCard"

    // Android Auto può chiudere la sessione della Wide un attimo prima di riaprirla, e con lei il suo player
    private const val WIDE_WAS_PLAYING_MS = 3_000L
    private const val CHECK_MS = 200L
    private const val GIVE_UP_MS = 5_000L

    private val handler = Handler(Looper.getMainLooper())

    /** L'app di passaggio esiste solo con l'opzione attiva: spenta, per Android Auto non cambia niente. */
    fun applySetting(context: Context, opensMain: Boolean) {
        val component = ComponentName(context, CarServicePlayerCard::class.java)
        val state = if (opensMain) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        try {
            val manager = context.packageManager
            val current = manager.getComponentEnabledSetting(component)
            // Nel manifest è spenta: se non si è mai cambiata, spenta è già
            if (current == state || (!opensMain && current == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)) return
            manager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            Log.d(TAG, "Player card app enabled=$opensMain")
        } catch (e: Exception) {
            Log.w(TAG, "Player card app not changed: ${e.javaClass.simpleName}")
        }
    }

    /** Sta suonando la Wide: il tocco sul player è per lei (chiudendola si fermerebbe il suo audio). */
    fun isWidePlaying(): Boolean = MediaFocusManager.hasOrJustHadPlayer(MediaFocusManager.Source.WIDE_APP, WIDE_WAS_PLAYING_MS)

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

    /** [onResult] true quando la Main è davanti, false se non ci arriva. La Main è nello stesso processo: si vede da qui. */
    fun waitForMain(startedAt: Long = SystemClock.elapsedRealtime(), onResult: (Boolean) -> Unit) {
        when {
            MainCarActivity.isInFrontSince(startedAt) -> onResult(true)
            SystemClock.elapsedRealtime() - startedAt > GIVE_UP_MS -> onResult(false)
            else -> handler.postDelayed({ waitForMain(startedAt, onResult) }, CHECK_MS)
        }
    }

    @Suppress("DEPRECATION")
    fun describe(intent: Intent): String {
        val extras = intent.extras?.let { bundle -> bundle.keySet().joinToString { key -> "$key=${bundle.get(key)}" } }
        return "action=${intent.action} categories=${intent.categories} extras=[$extras]"
    }
}

/** L'app di passaggio, attiva solo con l'opzione: vedi [PlayerCard]. */
class CarServicePlayerCard : CarAppService() {
    override fun createHostValidator(): HostValidator = carHostValidator(applicationContext)

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            Log.d(PlayerCard.TAG, "Player card app, onCreateScreen: ${PlayerCard.describe(intent)}")
            return PassageScreen(carContext) {
                carContext.finishCarApp()
            }
        }

        override fun onNewIntent(intent: Intent) {
            Log.d(PlayerCard.TAG, "Player card app, onNewIntent: ${PlayerCard.describe(intent)}")
            (carContext.getCarService(ScreenManager::class.java).top as? PassageScreen)?.start()
        }
    }
}

/**
 * Passaggio dopo un tocco sul player: niente pagine né WebView. Apre la Main, o la Wide se è lei che sta suonando, e
 * quando è davanti chiama [onFinished] con true (false se non ci è arrivata). Una volta sola per ogni [start]: passando
 * alla Main questa schermata può andare in pausa e riprendere (due app affiancate) e non deve ripartire da sola.
 */
internal class PassageScreen(
    carContext: CarContext,
    private val onFinished: (Boolean) -> Unit
) : Screen(carContext) {
    private val strings = AppLocale.wrap(carContext)
    private var started = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                if (!started) start()
            }
        })
    }

    fun start() {
        started = true
        if (PlayerCard.isWidePlaying()) {
            Log.d(PlayerCard.TAG, "The Wide is playing: opening it instead of the Main")
            try {
                carContext.startCarApp(Intent().setComponent(ComponentName(carContext, CarServiceWide::class.java)))
            } catch (e: Exception) {
                Log.w(PlayerCard.TAG, "Wide not opened: ${e.javaClass.simpleName}")
            }
            onFinished(true)
            return
        }
        if (!PlayerCard.openMain(carContext)) {
            onFinished(false)
            return
        }
        PlayerCard.waitForMain { inFront ->
            if (!inFront) Log.w(PlayerCard.TAG, "Android Auto did not open the Main")
            onFinished(inFront)
        }
    }

    // setTitle/setHeaderAction sono deprecati, ma il sostituto (setHeader) richiede le API auto 7 e l'app parte dalla 5
    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(strings.getString(R.string.player_card_opening_main))
            .setTitle(strings.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .build()
}
