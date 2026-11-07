package com.fcaronte.aabrowser.car

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
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
import androidx.media.MediaBrowserServiceCompat
import com.fcaronte.aabrowser.BuildConfig
import com.fcaronte.aabrowser.CarService
import com.fcaronte.aabrowser.CarServiceUnlisted
import com.fcaronte.aabrowser.MainCarActivity
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.mediaservice.CarMediaService
import com.fcaronte.aabrowser.mediaservice.MediaFocusManager
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.utils.AppLocale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Opzione "CarWeb tra le app media di Android Auto". Android Auto passa i comandi «metti … su <app>» dell'Assistente e
 * di Gemini solo alle app media del suo menu, e con una car app nel pacchetto (la Wide) il servizio media di CarWeb non
 * ci compare (codice di Android Auto 18.1, 07/11/2026). Con l'opzione l'icona "CarWeb" del menu è un'app di categoria
 * media ([CarServiceMediaApp]) che apre la Main. La Main resta, ma senza un'icona sua ([CarServiceUnlisted]): nel menu
 * l'icona della Main viene da CATEGORY_PROJECTION_OEM, mentre Android Auto la avvia da CATEGORY_PROJECTION.
 * Spenta: tutto come prima. Log con tag CarMediaApp, anche nelle build di release.
 */
internal object CarMediaApp {
    const val TAG = "CarMediaApp"

    private const val CHECK_MS = 200L
    private const val GIVE_UP_MS = 5_000L

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Prima si accendono i componenti nuovi, poi si spegne la Main con l'icona: c'è sempre una Main da aprire. Il
     * menu di Android Auto si aggiorna alla connessione successiva.
     */
    fun applySetting(context: Context, enabled: Boolean) {
        val default = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        val changes = listOf(
            CarServiceUnlisted::class.java to if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else default,
            CarServiceMediaApp::class.java to if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else default,
            CarService::class.java to if (enabled) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else default
        )
        val manager = context.packageManager
        for ((type, state) in changes) {
            val component = ComponentName(context, type)
            try {
                if (manager.getComponentEnabledSetting(component) == state) continue
                manager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                Log.d(TAG, "${type.simpleName} state=$state")
            } catch (e: Exception) {
                Log.w(TAG, "${type.simpleName} not changed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Il componente con cui Android Auto apre la Main: quello senza icona quando c'è l'app media. */
    fun mainComponent(context: Context): ComponentName = ComponentName(
        context,
        if (AppSettings.mediaAppInMenu.value) CarServiceUnlisted::class.java else CarService::class.java
    )

    /**
     * Le due strade per aprire la Main da un'app della Car App Library: l'SDK di Android Auto se è già aperta (è quello
     * che usa lei per tornare davanti) e la richiesta con il suo componente, che è dello stesso pacchetto.
     * false se Android Auto ha rifiutato e la Main non era aperta.
     */
    fun openMain(carContext: CarContext): Boolean {
        val wasRunning = MainCarActivity.bringRunningToFront()
        Log.d(TAG, "Opening the Main (already open=$wasRunning)")
        return try {
            carContext.startCarApp(Intent().setComponent(mainComponent(carContext)))
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

    /** Tocco sulla scheda del player (non sull'icona del menu): segue "Il tocco sul player apre CarWeb". */
    fun isPlayerCardTap(intent: Intent): Boolean = intent.action in PLAYER_CARD_ACTIONS

    @Suppress("DEPRECATION")
    fun describe(intent: Intent): String {
        val extras = intent.extras?.let { bundle -> bundle.keySet().joinToString { key -> "$key=${bundle.get(key)}" } }
        return "action=${intent.action} categories=${intent.categories} extras=[$extras]"
    }

    // Quella vista nei log di Android Auto e quella dei documenti della Car App Library
    private val PLAYER_CARD_ACTIONS = setOf(
        "MEDIA_SHOW_PLAYBACK_VIEW",
        "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK"
    )
}

/**
 * L'app media "CarWeb", attiva solo con l'opzione: vedi [CarMediaApp]. Android Auto si collega a questo componente in
 * due modi e CarAppService.onBind è final: un servizio semplice che smista per azione, tra un CarAppService che vive
 * qui dentro (icona del menu e tocco sulla scheda) e [CarMediaService] (player, comandi di Gemini e dell'Assistente).
 */
class CarServiceMediaApp : Service() {
    private class LauncherApp : CarAppService() {
        fun attachTo(base: Context) = attachBaseContext(base)

        override fun createHostValidator(): HostValidator =
            if (BuildConfig.DEBUG) {
                HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
            } else {
                // Come per la Wide: solo Android Auto e i sistemi auto Google
                HostValidator.Builder(applicationContext)
                    .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                    .build()
            }

        override fun onCreateSession(): Session = object : Session() {
            override fun onCreateScreen(intent: Intent): Screen {
                Log.d(CarMediaApp.TAG, "onCreateScreen: ${CarMediaApp.describe(intent)}")
                return MediaAppScreen(carContext, CarMediaApp.isPlayerCardTap(intent)) { carContext.finishCarApp() }
            }

            override fun onNewIntent(intent: Intent) {
                Log.d(CarMediaApp.TAG, "onNewIntent: ${CarMediaApp.describe(intent)}")
                (carContext.getCarService(ScreenManager::class.java).top as? MediaAppScreen)
                    ?.start(CarMediaApp.isPlayerCardTap(intent))
            }
        }
    }

    private val carApp = LauncherApp()

    private val mediaReady = CountDownLatch(1)

    @Volatile
    private var mediaBinder: IBinder? = null
    private var mediaBound = false

    private val mediaConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            mediaBinder = service
            mediaReady.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mediaBinder = null
        }
    }

    // Le richieste del MediaBrowser di Android Auto passano al CarMediaService vero. Stesso processo: la chiamata resta
    // nello stesso thread e il servizio vede ancora Android Auto come chiamante, come quando si collega direttamente
    private val mediaForward = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            mediaReady.await(MEDIA_WAIT_MS, TimeUnit.MILLISECONDS)
            val target = mediaBinder
            if (target == null) {
                Log.w(CarMediaApp.TAG, "Media service not available")
                return false
            }
            return target.transact(code, data, reply, flags)
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        carApp.attachTo(this)
    }

    override fun onBind(intent: Intent): IBinder? {
        if (intent.action != MediaBrowserServiceCompat.SERVICE_INTERFACE) return carApp.onBind(intent)
        if (!mediaBound) {
            Log.d(CarMediaApp.TAG, "Android Auto connects its player")
            val media = Intent(MediaBrowserServiceCompat.SERVICE_INTERFACE).setComponent(ComponentName(this, CarMediaService::class.java))
            mediaBound = bindService(media, mediaConnection, BIND_AUTO_CREATE)
        }
        return mediaForward
    }

    override fun onUnbind(intent: Intent): Boolean =
        if (intent.action != MediaBrowserServiceCompat.SERVICE_INTERFACE) carApp.onUnbind(intent) else false

    override fun onDestroy() {
        carApp.onDestroy()
        if (mediaBound) unbindService(mediaConnection)
        mediaBound = false
        super.onDestroy()
    }

    private companion object {
        const val MEDIA_WAIT_MS = 5_000L
    }
}

/**
 * Passaggio dall'app media: niente pagine né WebView. Dall'icona del menu apre la Main; dal tocco sulla scheda del
 * player segue l'opzione "Il tocco sul player apre CarWeb" (la Wide se è spenta o se sta suonando lei). Quando l'app
 * scelta è davanti chiama [onFinished] con true (false se non ci è arrivata). Una volta sola per ogni [start]: passando
 * alla Main questa schermata può andare in pausa e riprendere e non deve ripartire da sola.
 */
internal class MediaAppScreen(
    carContext: CarContext,
    private var fromPlayerCard: Boolean,
    private val onFinished: (Boolean) -> Unit
) : Screen(carContext) {
    private val strings = AppLocale.wrap(carContext)
    private var started = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                if (!started) start(fromPlayerCard)
            }
        })
    }

    fun start(playerCard: Boolean) {
        started = true
        fromPlayerCard = playerCard
        val wideOwnsPlayer = MediaFocusManager.getActiveSource() == MediaFocusManager.Source.WIDE_APP
        if (playerCard && (!AppSettings.playerCardOpensMain.value || wideOwnsPlayer)) {
            Log.d(CarMediaApp.TAG, "Player card tap: opening the Wide (Wide playing=$wideOwnsPlayer)")
            try {
                carContext.startCarApp(Intent().setComponent(ComponentName(carContext, CarServiceWide::class.java)))
            } catch (e: Exception) {
                Log.w(CarMediaApp.TAG, "Wide not opened: ${e.javaClass.simpleName}")
            }
            onFinished(true)
            return
        }
        if (!CarMediaApp.openMain(carContext)) {
            onFinished(false)
            return
        }
        CarMediaApp.waitForMain { inFront ->
            if (!inFront) Log.w(CarMediaApp.TAG, "Android Auto did not open the Main")
            onFinished(inFront)
        }
    }

    // setTitle/setHeaderAction sono deprecati, ma il sostituto (setHeader) richiede le API auto 7 e l'app parte dalla 5
    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(strings.getString(R.string.media_app_opening))
            .setTitle(strings.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .build()
}
