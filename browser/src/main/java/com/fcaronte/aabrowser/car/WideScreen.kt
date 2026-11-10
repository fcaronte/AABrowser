package com.fcaronte.aabrowser.car

import android.Manifest
import android.annotation.SuppressLint
import android.app.Presentation
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.mediaservice.CarMediaService
import com.fcaronte.aabrowser.mediaservice.MediaFocusManager
import com.fcaronte.aabrowser.mediaservice.MediaSessionManager
import com.fcaronte.aabrowser.model.FavoriteSite
import com.fcaronte.aabrowser.model.FavoritesRepository
import com.fcaronte.aabrowser.settings.AdBlockSettings
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.utils.AdBlockHost
import com.fcaronte.aabrowser.utils.AdBlockJavascript
import com.fcaronte.aabrowser.utils.AppLocale
import com.fcaronte.aabrowser.utils.AppLog
import com.fcaronte.aabrowser.utils.BrowserJavascript
import com.fcaronte.aabrowser.utils.DaznManager
import com.fcaronte.aabrowser.utils.DaznProxy
import com.fcaronte.aabrowser.utils.DrivingState
import com.fcaronte.aabrowser.utils.GoogleLoginManager
import com.fcaronte.aabrowser.utils.IptvSources
import com.fcaronte.aabrowser.utils.JsDialogRequest
import com.fcaronte.aabrowser.utils.LocalPlayer
import com.fcaronte.aabrowser.utils.PopupGuard
import com.fcaronte.aabrowser.utils.SitePermission
import com.fcaronte.aabrowser.utils.SitePermissionPrompts
import com.fcaronte.aabrowser.utils.SponsorBlock
import com.fcaronte.aabrowser.utils.SslErrorPrompts
import com.fcaronte.aabrowser.utils.UpdateManager
import com.fcaronte.aabrowser.utils.UrlUtils
import com.fcaronte.aabrowser.utils.MediaVoice
import com.fcaronte.aabrowser.utils.VoiceCommandRouter
import com.fcaronte.aabrowser.utils.VoiceCommands
import com.fcaronte.aabrowser.utils.WebViewScriptRouter
import com.fcaronte.aabrowser.utils.YouTubeManager
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Schermata "wide" (finto navigatore).
 * Indipendente dall'app principale: condivide solo gli script JavaScript e i fix per i siti
 * (WebViewScriptRouter, AdBlock, GoogleLoginManager, BrowserJavascript).
 * Richiede WideHomeAndSearch.kt (HomePage, SiteSearch, WideSearchScreen) nello stesso package.
 */
class WideScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    companion object {
        const val TAG = "WideScreen"
        const val HOME = "about:home"
        var isWideResumed = false
            internal set
        private var activeInstanceRef: java.lang.ref.WeakReference<WideScreen>? = null
        var activeInstance: WideScreen?
            get() = activeInstanceRef?.get()
            set(value) {
                activeInstanceRef = value?.let { java.lang.ref.WeakReference(it) }
            }

        // Scale passate a WebViewScriptRouter.routeAndInject (1.0 = nessuna modifica).
        // Le scale generali dell'app principale sono pensate per il telefono: qui non valgono.
        // Valgono invece quelle di un preferito, scelte per quel sito.
        const val WIDE_DISPLAY_SCALE = 1.0f
        const val WIDE_DESKTOP_SCALE = 1.0f

        // I preferiti letti restano validi per questo tempo (servono più volte per ogni pagina)
        const val FAVORITES_CACHE_MS = 2000L

        // Ricaricamenti di fila per cambiare user agent (desktop/mobile): oltre, niente più (evita i loop)
        const val MAX_USER_AGENT_RELOADS = 3

        // Scorrimento in attesa del fotogramma da più di così: si riposta (WebView ricreata nel frattempo)
        const val SCROLL_FLUSH_STALE_MS = 500L

        // Fling sulla pagina di un video di YouTube: distanza = velocità per questo tempo (scorrimento morbido)
        const val FLING_SCROLL_SECONDS = 0.35f

        // Debounce per i callback di visible area (i tasti mappa appaiono/scompaiono con animazione)
        const val VISIBLE_AREA_DEBOUNCE_MS = 50L
        // Secondo passaggio, per catturare lo stato finale dell'animazione dei tasti
        const val VISIBLE_AREA_SETTLE_MS = 400L

        // Dopo aver scritto dalla tastiera il campo riprende il focus: per questo tempo il popup non si apre
        const val INPUT_POPUP_SUPPRESS_MS = 1500L

        // Popup tastiera/microfono aperto per così, se non si tocca niente: 3 s, scelta dell'utente (lo vuole
        // poco sullo schermo; provati anche 6 s). Un tocco fuori dalla scheda lo chiude subito
        const val INPUT_POPUP_TIMEOUT_MS = 3000L

        // Come il blocco popup di Chrome, ma più largo (i login aprono il popup qualche secondo dopo il tocco)
        private const val USER_GESTURE_MS = 10_000L

        // Margine extra dei tasti della sidebar fissa, solo dal lato del bordo dello schermo
        const val SIDEBAR_OUTER_TOUCH_TOLERANCE_PX = 20

        // Fascia dei tasti di Android Auto, al massimo: oltre è un pannello (split con il player o con il menu delle app)
        // o altro che l'host mette sopra. Misure del 10/11/2026: 96 px in alto e 104 px a destra su 1447x694
        const val BUTTON_BAND_MAX_WIDTH = 0.15f
        const val BUTTON_BAND_MAX_HEIGHT = 0.2f

        // Sidebar fissa alla dimensione 100% (AppSettings.wideSidebarSize)
        const val SIDEBAR_WIDTH_DP = 52
        const val SIDEBAR_ICON_DP = 42
        const val SIDEBAR_GAP_DP = 20
        const val SIDEBAR_MIN_GAP_PX = 2

        // All'avvio (e quando la surface viene ricreata) l'host sta ancora caricando la sua UI e la
        // visible area dei primi istanti può essere incompleta. L'inset viene applicato SUBITO
        // (la pagina non resta mai sotto il widget) e poi ricalcolato a questi ritardi,
        // così si corregge anche se i primi valori erano transitori.
        val STARTUP_REAPPLY_DELAYS_MS = longArrayOf(300L, 800L, 1500L, 3000L, 6000L)

        // Legge il colore di sfondo della pagina per colorare le fasce libere attorno alla WebView
        const val BACKDROP_JS =
            "(function(){var b=document.body,d=document.documentElement;" +
                    "var c=b?getComputedStyle(b).backgroundColor:'';" +
                    "if(!c||c==='transparent'||c==='rgba(0, 0, 0, 0)'){c=getComputedStyle(d).backgroundColor;}" +
                    "return c;})()"
    }

    // ------------------------------------------------------------------
    // Impostazioni della wide (se hai nomi diversi in AppSettings, cambia solo queste 4 righe)
    // ------------------------------------------------------------------
    private fun reopenEnabled(): Boolean = AppSettings.wideReopenLastPage.value
    private fun toggleReopen() = AppSettings.setWideReopenLastPage(carContext, !reopenEnabled())
    private fun sidebarFixedEnabled(): Boolean = AppSettings.wideSidebarFixed.value
    private fun toggleSidebarFixed() = AppSettings.setWideSidebarFixed(carContext, !sidebarFixedEnabled())
    private val isRtl: Boolean
        get() = carContext.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    // Testi nella lingua dell'app: Android Auto passa alla Wide la configurazione dell'auto, con la lingua del telefono
    private var stringsCache: Pair<Configuration, Context>? = null
    private val strings: Context
        get() {
            val config = carContext.resources.configuration
            stringsCache?.takeIf { it.first == config }?.let { return it.second }
            return AppLocale.wrap(carContext).also { stringsCache = Configuration(config) to it }
        }
    private fun savedUrl(): String = AppSettings.wideLastUrl.value
    private fun saveUrl(url: String) = AppSettings.setWideLastUrl(carContext, url)

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var backdrop: FrameLayout? = null      // contenitore radice nella Presentation
    private var webView: WebView? = null
    private var surface: Surface? = null

    private var customView: View? = null            // video a schermo intero
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var lastRenderCrash = 0L
    private var daznDesktopForced = false       // DAZN: dopo il clic su un evento serve la modalità desktop
    private var favoritesCache: List<FavoriteSite>? = null
    private var favoritesReadAt = 0L
    private var userAgentReloads = 0            // ricaricamenti per cambiare user agent dall'ultima pagina finita
    private var pendingScrollX = 0f
    private var pendingScrollY = 0f
    private var isScrollFlushPosted = false
    private var scrollFlushPostedAt = 0L
    private val flushScrollRunnable = Runnable { flushScroll() }

    private var popupOverlayReference: FrameLayout? = null
    private var sidebarContainerReference: LinearLayout? = null

    // Tastiera passeggero (al posto di quella di Android Auto) e copertura dell'auto in movimento, sulla superficie
    private var passengerKeyboard: WidePassengerKeyboard? = null
    private var driveLockReference: FrameLayout? = null
    private var driveLockCardReference: LinearLayout? = null
    private val driveStateListener: () -> Unit = { applyDriveLock() }

    // Indietro dell'auto con la tastiera passeggero aperta: la chiude, come quella di Android Auto
    private val closeKeyboardOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            passengerKeyboard?.hide()
        }
    }

    // Comandi vocali: scheda "In ascolto…" sulla superficie, tasto della sidebar (rosso mentre ascolta)
    private var voiceCardReference: LinearLayout? = null
    private var voiceDetailReference: TextView? = null
    private var sidebarVoiceButton: ImageButton? = null
    private var voiceRecognizer: SpeechRecognizer? = null
    private var isVoiceListening = false
    // "Metti …": su YouTube il video parte anche con l'autoplay spento, finché si resta su YouTube
    private var voiceAutoplay = false
    private val voiceExecutor = VoiceCommandRouter.Executor { command -> runVoiceCommand(command) }
    private var isInputPopupVisible = false
    private var suppressInputPopupUntil = 0L
    private val hidePopupRunnable = Runnable {
        popupOverlayReference?.visibility = View.GONE
        isInputPopupVisible = false
    }

    // Dimensioni REALI della surface: arrivano solo da onSurfaceAvailable, mai da onVisibleAreaChanged
    private var surfaceWidth = 800
    private var surfaceHeight = 480
    private var surfaceDensity = 160

    private var visibleArea: Rect? = null
    private var stableArea: Rect? = null

    // Video di CarWeb Media a schermo intero con "Menu al tocco" (barra del video, video-zoom.js): il menu fisso resta
    // sotto il video e torna con i comandi; all'uscita dallo schermo intero torna sempre
    private var sidebarHiddenForVideo = false

    // Margine in alto e in basso che Android Auto tiene di solito in ogni disposizione (superficie e bordi laterali della
    // visible area): la fascia dei suoi tasti. Quello che toglie in più, come la chiamata in corso, si riconosce da qui
    private val usualAreaTop = HashMap<String, Int>()
    private val usualAreaBottom = HashMap<String, Int>()

    // Fascia riservata ai tasti dell'host su ciascun lato, imparata per ogni disposizione (stable area)
    private data class ButtonsReserve(val left: Int, val right: Int)
    private val learnedReserves = HashMap<Rect, ButtonsReserve>()
    private var lastUrl: String? = null

    @Volatile
    private var homeHtml: String? = null

    // Versione nuova trovata all'apertura: l'avviso in cima alla home (la home si costruisce anche dal thread di rete)
    @Volatile
    private var updateInfo: UpdateManager.UpdateInfo? = null

    private val mediaSessionManager = MediaSessionManager(carContext, MediaFocusManager.Source.WIDE_APP)
    private val handler = Handler(Looper.getMainLooper())

    // Sito della pagina principale, letto dal thread del ponte JavaScript (proxy DAZN)
    @Volatile
    private var pageHost: String? = null
    // Finestre aperte dagli script della pagina: le pubblicità a ogni tocco (PopupGuard)
    private val popupAttempts = PopupGuard.Attempts()

    // Certificato non valido (es. server di casa autofirmato): conferma con una schermata di Android Auto
    private val sslPrompts = SslErrorPrompts(carContext) { handler.post { refreshSslWarning() } }
    private var sslWarningScreen: WideSslWarningScreen? = null

    // Microfono, posizione e file del telefono: il sito chiede con una schermata di Android Auto
    private val sitePrompts = SitePermissionPrompts(carContext, inCar = true) { handler.post { refreshSitePermission() } }.apply {
        // In auto la finestra dei permessi di Android non compare: il permesso dell'app si dà dal telefono
        onPhonePermissionMissing = { permission ->
            val message = if (permission == SitePermission.LOCATION) R.string.web_location_phone_toast
            else R.string.site_permission_mic_phone
            CarToast.makeText(carContext, strings.getString(message), CarToast.LENGTH_LONG).show()
            false
        }
    }
    private var sitePermissionScreen: WideSitePermissionScreen? = null

    // alert e confirm della pagina, uno alla volta
    private var jsDialogScreen: WideJsDialogScreen? = null

    // Ultimo tocco sulla surface (letto dal thread del JavaBridge)
    @Volatile
    private var lastTapAt = 0L

    // Applicazione della visible area: debounce + passaggio finale a animazione conclusa
    private val applyAreaRunnable = Runnable { applyVisibleArea() }
    private val applyAreaSettledRunnable = Runnable {
        noteUsualArea()
        applyVisibleArea()
    }

    // Ricalcoli ritardati all'avvio, per correggere i valori transitori della UI dell'host
    private val startupReapplyRunnable = Runnable { applyVisibleArea() }

    private var downTime = 0L
    private val iconPan: CarIcon = CarIcon.PAN
    private val iconHome: CarIcon by lazy { buildHomeIcon() }
    private val iconSearch: CarIcon by lazy { buildSearchIcon() }
    private val iconReload: CarIcon by lazy { buildReloadIcon() }
    private val iconAdBlockOn: CarIcon by lazy { buildShieldIcon(crossed = false) }
    private val iconAdBlockOff: CarIcon by lazy { buildShieldIcon(crossed = true) }
    private val iconMic: CarIcon by lazy { maskIcon(::drawMic) }
    private var shownAdBlockState: String? = null   // sito e stato mostrati dallo scudo nell'ultimo template

    init {
        activeInstance = this
        carContext.onBackPressedDispatcher.addCallback(this, closeKeyboardOnBack)
        try {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)

            val navManager = carContext.getCarService(NavigationManager::class.java)
            navManager.setNavigationManagerCallback(object : NavigationManagerCallback {
                override fun onStopNavigation() {
                    closeApp()
                }

                override fun onAutoDriveEnabled() {}
            })
            // Niente navigationStarted(): la Wide non guida, e Android Auto ammette un solo navigatore attivo, quindi
            // quella chiamata fermava la navigazione in corso di Maps. Serviva a poco: la superficie arriva lo
            // stesso. Così aprire la Wide (anche dal tocco sul player, che Android Auto fa comunque) non la ferma
        } catch (e: Exception) {
            Log.e(TAG, "Init failed", e)
        }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                checkForUpdate()
                watchMediaLists()
                // Auto ferma o in movimento: senza la Modalità passeggero, in movimento la pagina resta coperta
                DrivingState.acquire(carContext)
                DrivingState.addListener(driveStateListener)
                // "Ok Google, metti … su CarWeb Media" e il doppio tocco su ⏮ al volante arrivano qui finché la Wide è aperta
                VoiceCommandRouter.register(
                    VoiceCommandRouter.Target.WIDE,
                    inCar = true,
                    { isWideResumed },
                    voiceExecutor,
                    openMain = { MainHandOver.openMainSoon(carContext) }
                ) {
                    if (!isVoiceListening) toggleVoiceCommand()
                }
            }

            override fun onResume(owner: LifecycleOwner) {
                isWideResumed = true
            }

            override fun onPause(owner: LifecycleOwner) {
                isWideResumed = false
            }

            override fun onDestroy(owner: LifecycleOwner) {
                isWideResumed = false
                VoiceCommandRouter.unregister(VoiceCommandRouter.Target.WIDE, voiceExecutor)
                DrivingState.removeListener(driveStateListener)
                DrivingState.release()
                stopVoiceCommand()
                try {
                    voiceRecognizer?.destroy()
                } catch (_: Exception) {}
                voiceRecognizer = null
                if (activeInstance == this@WideScreen) {
                    activeInstance = null
                }
                handler.removeCallbacks(applyAreaRunnable)
                handler.removeCallbacks(applyAreaSettledRunnable)
                handler.removeCallbacks(startupReapplyRunnable)
                releaseVirtualDisplay()
                mediaSessionManager.disconnect()
                try {
                    webView?.destroy()
                } catch (_: Exception) {}
                webView = null
            }
        })
    }

    fun navigateToHome() {
        handler.post {
            loadUrl(HomePage.BASE_URL)
        }
    }

    // ------------------------------------------------------------------
    // Template
    // ------------------------------------------------------------------

    override fun onGetTemplate(): Template {
        val fixed = sidebarFixedEnabled()

        // AdBlock su questo sito: scudo con la spunta = attivo, barrato = sito in whitelist. Sempre presente
        // (spento sulla home), così la barra non cambia larghezza e la pagina non si ridimensiona
        val site = currentAdBlockSite()
        val siteWhitelisted = site != null && AdBlockHost.isWhitelisted(site)
        shownAdBlockState = adBlockState(site)

        val searchAction = Action.Builder()
            .setIcon(iconSearch)
            .setOnClickListener { openSearch() }
            .build()
        val voiceAction = Action.Builder()
            .setIcon(iconMic)
            .setOnClickListener { toggleVoiceCommand() }
            .build()

        // In alto a destra un tasto solo, lo scudo (Android Auto ne vuole almeno uno nell'angolo). Senza sidebar
        // anche cerca e microfono, alla sua sinistra: in basso non ci stanno
        val stripBuilder = ActionStrip.Builder()
        if (!fixed) stripBuilder.addAction(searchAction).addAction(voiceAction)
        val strip = stripBuilder
            .addAction(
                Action.Builder()
                    .setIcon(if (siteWhitelisted) iconAdBlockOff else iconAdBlockOn)
                    .setEnabled(site != null)
                    .setOnClickListener { toggleAdBlockForSite() }
                    .build()
            )
            .build()

        // In basso al massimo 4 tasti, e PAN serve: senza, l'host non manda lo scorrimento (onScroll)
        val mapStripBuilder = ActionStrip.Builder()
            .addAction(Action.Builder(Action.PAN).setIcon(iconPan).build())

        if (!fixed) {
            // Dal basso: home, indietro, ricarica
            mapStripBuilder
                .addAction(
                    Action.Builder()
                        .setIcon(iconReload)
                        .setOnClickListener { webView?.reload() }
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setIcon(CarIcon.BACK)
                        .setOnClickListener { goBack() }
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setIcon(iconHome)
                        .setOnClickListener { showHome() }
                        .build()
                )
        }

        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setMapActionStrip(mapStripBuilder.build())
            .build()
    }

    /** Indietro: chiude il video a schermo intero, altrimenti la pagina precedente. */
    private fun goBack() {
        if (customView != null) {
            hideCustomView()
        } else {
            webView?.let { if (it.canGoBack()) it.goBack() }
        }
    }

    /** Sito della pagina aperta per lo scudo dell'AdBlock; null sulla home e su CarWeb Media. */
    private fun currentAdBlockSite(): String? {
        val url = (webView?.url ?: lastUrl)?.takeUnless { it == HOME } ?: return null
        if (url.startsWith(HomePage.BASE_URL) || LocalPlayer.isPlayerUrl(url) || !UrlUtils.isWebUrl(url)) return null
        return AdBlockHost.siteKey(Uri.parse(url).host)
    }

    private fun adBlockState(site: String?): String = "$site|${site != null && AdBlockHost.isWhitelisted(site)}"

    /** Aggiunge o toglie il sito dalla whitelist dell'AdBlock e ricarica la pagina. */
    private fun toggleAdBlockForSite() {
        val site = currentAdBlockSite() ?: return
        val disable = !AdBlockHost.isWhitelisted(site)
        if (disable) AdBlockHost.addToWhitelist(site) else AdBlockHost.removeFromWhitelist(site)
        val message = strings.getString(if (disable) R.string.adblock_site_off else R.string.adblock_site_on, site)
        CarToast.makeText(carContext, message, CarToast.LENGTH_SHORT).show()
        invalidate()
        webView?.reload()
    }

    /** Cambiato sito (o stato): lo scudo va ridisegnato. */
    private fun refreshAdBlockAction() {
        if (adBlockState(currentAdBlockSite()) != shownAdBlockState) invalidate()
    }

    // ------------------------------------------------------------------
    // Navigazione: home HTML, ricerca, URL
    // ------------------------------------------------------------------

    private fun buildHomeHtml(): String {
        favoritesCache = null
        val items = favorites().map { HomePage.Item(it.name, it.url) }
        val updateText = updateInfo?.takeIf { it.isAvailable && !UpdateManager.isBannerDismissed }
            ?.let { strings.getString(R.string.update_available, it.latestVersion) }
        return HomePage.build(strings, items, reopenEnabled(), sidebarFixedEnabled(), updateText, AppSettings.wideSidebarSize.value)
    }

    // Come la home della Main: all'apertura controlla se c'è una versione nuova e la mostra in cima alla home
    private fun checkForUpdate() {
        lifecycleScope.launch {
            val info = UpdateManager.checkForUpdates(carContext)
            if (!info.isAvailable) return@launch
            updateInfo = info
            UpdateManager.showUpdateNotification(carContext, info)
            homeHtml = null
            if (isHome()) showHome()
        }
    }

    // Liste di TV e Radio aggiunte dal telefono mentre la pagina è aperta qui: aggiorna il suo menu
    private fun watchMediaLists() {
        lifecycleScope.launch {
            snapshotFlow { IptvSources.version.intValue }.drop(1).collect {
                webView?.takeIf { LocalPlayer.isPlayerUrl(it.url) }
                    ?.evaluateJavascript(LocalPlayer.SOURCES_CHANGED_SCRIPT, null)
            }
        }
    }

    // Tocco sull'avviso: scarica e installa con KingInstaller, come dalla home della Main
    private fun startUpdateDownload() {
        val info = updateInfo ?: return
        val started = UpdateManager.startDownload(carContext, info)
        val message = strings.getString(if (started) R.string.update_download_started else R.string.update_download_failed)
        CarToast.makeText(carContext, message, CarToast.LENGTH_LONG).show()
    }

    private fun showHome() {
        hideCustomView()
        homeHtml = buildHomeHtml()
        lastUrl = HOME
        val wv = webView ?: return
        if (wv.url == HomePage.BASE_URL) wv.reload() else wv.loadUrl(HomePage.BASE_URL)
    }

    private fun isHome(): Boolean =
        lastUrl == HOME || webView?.url?.startsWith(HomePage.BASE_URL) == true

    /** Lente: cerca sul sito che stai guardando (YouTube, Amazon, ...), altrimenti sul motore di ricerca impostato. */
    private fun openSearch() {
        val from = (webView?.url ?: lastUrl)
            ?.takeIf { it != HOME && !it.startsWith(HomePage.BASE_URL) }
        val target = SiteSearch.targetFor(from)
        val title = strings.getString(R.string.wide_search_on, target.label)
        val search: (String) -> Unit = { query -> loadUrl(SiteSearch.resolve(strings, from, query)) }
        // Tastiera passeggero sulla superficie; sotto la copertura in movimento resta quella di Android Auto
        val keyboard = passengerKeyboard
        if (AppSettings.passengerKeyboard.value && keyboard != null && !isDriveLocked()) {
            keyboard.show("", title) { query -> if (query.isNotBlank()) search(query) }
        } else {
            screenManager.push(WideSearchScreen(carContext, title, onSubmit = search))
        }
    }

    /**
     * Tastiera dal popup dei campi di testo. Alle app con template Android Auto dà solo la tastiera a schermo
     * intero (SearchTemplate) e mentre è aperta la pagina perde il focus: il campo viene memorizzato prima
     * di aprirla (precompilandola col suo testo) e al ritorno il testo viene scritto lì.
     */
    /** Risultato di [BrowserJavascript.getCaptureInputTargetScript]: valore e suggerimento del campo, o null. */
    private fun parseCapturedField(result: String?): JSONObject? =
        result?.takeIf { it.startsWith("{") }?.let {
            try {
                JSONObject(it)
            } catch (_: Exception) {
                null
            }
        }

    private fun openFieldInput() {
        val wv = webView ?: return
        wv.evaluateJavascript(BrowserJavascript.getCaptureInputTargetScript()) { result ->
            val field = parseCapturedField(result)
            AppLog.d(TAG, "Keyboard: field captured=${field != null}")
            val hint = field?.optString("hint")?.takeIf { it.isNotBlank() }
                ?: strings.getString(R.string.wide_field_input_hint)
            val initialText = field?.optString("value").orEmpty()

            val writeText: (String) -> Unit = { text ->
                // Lo script rimette il focus sul campo: senza questo il popup si riaprirebbe subito
                suppressInputPopupUntil = SystemClock.uptimeMillis() + INPUT_POPUP_SUPPRESS_MS
                // Il risultato dice come è stato scritto (typed:exec, typed:paste, no-target, ...): solo nei log
                webView?.evaluateJavascript(BrowserJavascript.getSetInputTextScript(text)) { written ->
                    AppLog.d(TAG, "Keyboard text written: $written")
                }

                // Forza il focus non subito, ma con un delay per permettere
                // alla WebView di "riprendersi" dalla chiusura della WideSearchScreen
                webView?.postDelayed({
                    webView?.requestFocus()
                }, 300)
            }

            // Tastiera passeggero sulla superficie, anche in movimento; altrimenti quella di Android Auto
            val keyboard = passengerKeyboard
            if (AppSettings.passengerKeyboard.value && keyboard != null) {
                keyboard.show(initialText, hint, writeText)
            } else {
                screenManager.push(WideSearchScreen(carContext, hint, initialText = initialText, allowEmpty = true, onSubmit = writeText))
            }
        }
    }

    private fun loadUrl(url: String) {
        lastUrl = url
        webView?.let {
            applyUserAgent(it, url)
            it.loadUrl(url)
        }
    }

    /** Mostra l'avviso di certificato in attesa, oppure chiude quello che non serve più (nuova pagina). */
    private fun refreshSslWarning() {
        if (lifecycle.currentState == Lifecycle.State.DESTROYED) return
        val prompt = sslPrompts.current
        val shown = sslWarningScreen
        if (shown?.prompt === prompt) return

        sslWarningScreen = null
        shown?.finish()
        if (prompt == null) return
        sslWarningScreen = WideSslWarningScreen(carContext, prompt) { proceed ->
            sslPrompts.answer(prompt, proceed)
        }.also { screenManager.push(it) }
    }

    /** alert e confirm della pagina con una schermata di Android Auto, uno alla volta: la pagina aspetta la risposta. */
    private fun showJsDialog(request: JsDialogRequest): Boolean {
        if (jsDialogScreen != null || lifecycle.currentState == Lifecycle.State.DESTROYED) {
            request.cancel()
            return true
        }
        lateinit var screen: WideJsDialogScreen
        screen = WideJsDialogScreen(carContext, request) { if (jsDialogScreen === screen) jsDialogScreen = null }
        jsDialogScreen = screen
        screenManager.push(screen)
        return true
    }

    private fun hasRecentTap(action: String): Boolean {
        val recent = SystemClock.uptimeMillis() - lastTapAt <= USER_GESTURE_MS
        if (!recent) AppLog.w(TAG, "$action ignored: no recent tap on the page")
        return recent
    }

    /** Mostra la richiesta di permesso in attesa, oppure chiude quella che non serve più (nuova pagina). */
    private fun refreshSitePermission() {
        if (lifecycle.currentState == Lifecycle.State.DESTROYED) return
        val prompt = sitePrompts.current
        val shown = sitePermissionScreen
        if (shown?.prompt === prompt) return

        sitePermissionScreen = null
        shown?.finish()
        if (prompt == null) return
        sitePermissionScreen = WideSitePermissionScreen(carContext, prompt) { choice ->
            sitePrompts.answer(prompt, choice)
        }.also { screenManager.push(it) }
    }

    /**
     * All'apertura della WebView:
     * 1) se la surface viene ricreata (ricerca, crash, ecc.) riapre dov'eri;
     * 2) altrimenti, se "riapri ultima pagina" è ON, l'ultima pagina salvata;
     * 3) altrimenti la home.
     */
    private fun loadInitial() {
        val resume = lastUrl
        if (resume != null) {
            if (resume == HOME) showHome() else loadUrl(resume)
            return
        }

        val saved = savedUrl()
        if (reopenEnabled() && saved.isNotEmpty()) loadUrl(saved) else showHome()
    }

    private fun closeApp() {
        try {
            carContext.getCarService(NavigationManager::class.java).navigationEnded()
        } catch (e: Exception) {
            Log.w(TAG, "navigationEnded failed", e)
        }
        carContext.finishCarApp()
    }

    // ------------------------------------------------------------------
    // Fix per i siti (condivisi con l'app principale tramite WebViewScriptRouter)
    // ------------------------------------------------------------------

    /** Desktop o mobile in base al sito (Google, WhatsApp, login, ...). Non tocca la home locale. */
    private fun isDazn(url: String?): Boolean =
        url?.contains("dazn.com", ignoreCase = true) == true

    private fun needsDesktopForUrl(url: String?): Boolean {
        if (url != null && url.startsWith(HomePage.BASE_URL)) return false
        // Come nel Main: la scelta del preferito sostituisce quella generale, i siti "solo desktop" restano desktop
        val desktopPreferred = favoriteFor(url)?.isDesktopMode ?: AppSettings.desktopMode.value
        return desktopPreferred || WebViewScriptRouter.isDesktopRequired(url) || (daznDesktopForced && isDazn(url))
    }

    /** Host senza "www.": "https://www.dazn.com/it" e "https://dazn.com" sono lo stesso sito. */
    private fun siteHost(url: String?): String? =
        url?.let { Uri.parse(it).host }?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }

    // I preferiti si leggono più volte per ogni pagina: basta rileggerli ogni tanto (si modificano dal telefono)
    private fun favorites(): List<FavoriteSite> {
        val now = SystemClock.uptimeMillis()
        favoritesCache?.takeIf { now - favoritesReadAt < FAVORITES_CACHE_MS }?.let { return it }
        val list = try {
            FavoritesRepository(carContext).loadFavorites()
        } catch (e: Exception) {
            Log.e(TAG, "Errore lettura preferiti", e)
            emptyList()
        }
        favoritesCache = list
        favoritesReadAt = now
        return list
    }

    /**
     * Preferito del sito della pagina: le sue impostazioni (desktop, zoom) valgono anche nella Wide, che a
     * differenza del Main non apre una scheda per preferito. Stesso host o un suo sottodominio
     * (preferito facebook.com, pagina m.facebook.com); vince il più specifico.
     */
    private fun favoriteFor(url: String?): FavoriteSite? {
        val host = siteHost(url) ?: return null
        return favorites()
            .mapNotNull { fav -> siteHost(fav.url)?.let { fav to it } }
            .filter { (_, favHost) -> host == favHost || host.endsWith(".$favHost") }
            .maxByOrNull { (_, favHost) -> favHost.length }
            ?.first
    }

    private fun applyUserAgent(view: WebView, url: String?) {
        if (url != null && url.startsWith(HomePage.BASE_URL)) return
        if (needsDesktopForUrl(url)) {
            WebViewScriptRouter.setDesktopUserAgent(view, carContext)
        } else {
            WebViewScriptRouter.setMobileUserAgent(view, carContext)
        }
    }

    private fun applyDarkMode(settings: WebSettings) {
        val darkPages = AppSettings.darkPages.value
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, darkPages)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
            WebSettingsCompat.setForceDarkStrategy(
                settings,
                WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
            )
        }
    }

    // ------------------------------------------------------------------
    // Video a schermo intero
    // ------------------------------------------------------------------

    /**
     * Video a schermo intero: margini laterali della pagina (sidebar, colonne dell'host) e anche quelli in alto e in
     * basso dell'area visibile, che la pagina invece ignora (es. una chiamata di Android Auto: il video restava sotto).
     * Prima entrava sempre a tutta superficie, anche sotto la UI dell'host, fino al cambio d'area successivo.
     */
    private fun layoutCustomView(view: View, pageLp: FrameLayout.LayoutParams?) {
        val area = visibleArea?.takeUnless { it.isEmpty }
        val lp = (view.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (area != null && LocalPlayer.isPlayerUrl(webView?.url)) {
            val margins = playerFullscreenMargins(area)
            lp.leftMargin = margins[0]
            lp.topMargin = margins[1]
            lp.rightMargin = margins[2]
            lp.bottomMargin = margins[3]
        } else {
            // Gli altri siti hanno i loro comandi negli angoli del video: restano fuori dai tasti di Android Auto
            lp.leftMargin = pageLp?.leftMargin ?: 0
            lp.rightMargin = pageLp?.rightMargin ?: 0
            lp.topMargin = (area?.top ?: 0).coerceIn(0, surfaceHeight / 2)
            lp.bottomMargin = (area?.let { surfaceHeight - it.bottom } ?: 0).coerceIn(0, surfaceHeight / 2)
        }
        view.layoutParams = lp
        Log.d(TAG, "Fullscreen video margins l=${lp.leftMargin} t=${lp.topMargin} r=${lp.rightMargin} b=${lp.bottomMargin}")
    }

    /**
     * CarWeb Media a schermo intero: il video va sotto le fasce dei tasti di Android Auto ed è centrato sullo schermo
     * (utente, 10/11/2026: la fascia del tasto in alto a destra lo spingeva in basso); la barra dello zoom sta in basso
     * al centro, fuori dai tasti, e la sidebar fissa resta sopra il video. Restano fuori i pannelli (split) e quello che
     * Android Auto toglie in più del solito in questa disposizione, come la chiamata in corso. [sinistra, alto, destra, basso]
     */
    private fun playerFullscreenMargins(area: Rect): IntArray {
        val key = usualAreaKey(area)
        val sideBand = surfaceWidth * BUTTON_BAND_MAX_WIDTH
        val edgeBand = surfaceHeight * BUTTON_BAND_MAX_HEIGHT
        val left = area.left.coerceAtLeast(0)
        val right = (surfaceWidth - area.right).coerceAtLeast(0)
        val top = area.top.coerceAtLeast(0)
        val bottom = (surfaceHeight - area.bottom).coerceAtLeast(0)
        val usualTop = usualAreaTop[key] ?: area.top
        val usualBottom = usualAreaBottom[key] ?: area.bottom
        return intArrayOf(
            if (left <= sideBand) 0 else left,
            if (top <= edgeBand && area.top <= usualTop) 0 else top,
            if (right <= sideBand) 0 else right,
            if (bottom <= edgeBand && area.bottom >= usualBottom) 0 else bottom,
        )
    }

    private fun setSidebarOverVideo(visible: Boolean) {
        sidebarHiddenForVideo = !visible
        applySidebarOverVideo()
    }

    private fun applySidebarOverVideo() {
        val sb = sidebarContainerReference ?: return
        if (!sidebarFixedEnabled()) return
        sb.visibility = if (sidebarHiddenForVideo && customView != null) View.INVISIBLE else View.VISIBLE
    }

    private fun usualAreaKey(area: Rect): String = "${surfaceWidth}x$surfaceHeight ${area.left}-${area.right}"

    // Solo aree assestate (dopo l'animazione dei tasti), così un passaggio intermedio non conta come il solito
    private fun noteUsualArea() {
        val area = visibleArea?.takeUnless { it.isEmpty } ?: return
        val key = usualAreaKey(area)
        usualAreaTop[key] = minOf(usualAreaTop[key] ?: area.top, area.top)
        usualAreaBottom[key] = maxOf(usualAreaBottom[key] ?: area.bottom, area.bottom)
    }

    private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        val frame = backdrop
        if (frame == null || customView != null) {
            callback.onCustomViewHidden()
            return
        }
        (view.parent as? ViewGroup)?.removeView(view)
        view.setBackgroundColor(Color.BLACK)
        frame.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        layoutCustomView(view, webView?.layoutParams as? FrameLayout.LayoutParams)
        // Sidebar fissa (il video di CarWeb Media ci va sotto), popup, tastiera passeggero, copertura in movimento e
        // ascolto restano sopra il video
        if (sidebarFixedEnabled()) sidebarContainerReference?.bringToFront()
        popupOverlayReference?.bringToFront()
        passengerKeyboard?.view?.bringToFront()
        driveLockReference?.bringToFront()
        voiceCardReference?.bringToFront()
        customView = view
        customViewCallback = callback
        applySidebarOverVideo()
    }

    private fun hideCustomView() {
        val view = customView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        customView = null
        sidebarHiddenForVideo = false
        applySidebarOverVideo()
        try {
            customViewCallback?.onCustomViewHidden()
        } catch (_: Exception) {
        }
        customViewCallback = null
    }

    // ------------------------------------------------------------------
    // Sfondo che segue il colore della pagina
    // ------------------------------------------------------------------

    private fun sampleBackdrop(view: WebView?) {
        view?.evaluateJavascript(BACKDROP_JS) { raw ->
            parseCssColor(raw)?.let { color -> backdrop?.setBackgroundColor(color) }
        }
    }

    private fun parseCssColor(raw: String?): Int? {
        val m = Regex("""rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)""")
            .find(raw ?: return null) ?: return null
        val alpha = m.groupValues[4].toFloatOrNull() ?: 1f
        if (alpha < 0.1f) return Color.WHITE
        return Color.rgb(
            m.groupValues[1].toInt().coerceIn(0, 255),
            m.groupValues[2].toInt().coerceIn(0, 255),
            m.groupValues[3].toInt().coerceIn(0, 255)
        )
    }

    // ------------------------------------------------------------------
    // Misure della visible area (sidebar e inset)
    // ------------------------------------------------------------------

    private fun surfaceScale(): Float = (if (surfaceDensity > 0) surfaceDensity.toFloat() else 160f) / 160f

    private fun sidebarSizeScale(): Float = AppSettings.wideSidebarSize.value / 100f

    private fun sidebarWidthPx(): Int = (SIDEBAR_WIDTH_DP * sidebarSizeScale() * surfaceScale()).toInt()

    /**
     * Larghezza del menu fisso ([width]) più lo spazio tra il bordo dello schermo e l'area libera ([edgeInset], dove può
     * esserci un pannello dell'host): anche quello è colorato come il menu. Sotto un pannello non si vede, senza pannello
     * (il margine dell'host accanto alla sua barra, un pannello più stretto dell'area riservata) resterebbe un vuoto tra
     * il menu e il bordo. I tasti stanno centrati nell'area libera; su schermi bassi si stringono, mai fuori.
     */
    private fun layoutSidebar(sb: LinearLayout, width: Int, edgeInset: Int, rtl: Boolean) {
        sb.layoutParams = FrameLayout.LayoutParams(edgeInset + width, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.TOP or (if (rtl) Gravity.END else Gravity.START)
        }
        if (rtl) sb.setPadding(0, 0, edgeInset, 0) else sb.setPadding(edgeInset, 0, 0, 0)

        val scale = sidebarSizeScale() * surfaceScale()
        val top = stableArea?.top ?: 0
        val height = (stableArea?.bottom ?: surfaceHeight) - top
        val count = sb.childCount
        if (count == 0) return
        var icon = minOf((SIDEBAR_ICON_DP * scale).toInt(), width)
        var gap = (SIDEBAR_GAP_DP * scale).toInt()
        if (count * icon + (count - 1) * gap > height) {
            gap = ((height - count * icon) / (count - 1)).coerceAtLeast(SIDEBAR_MIN_GAP_PX)
            icon = minOf(icon, (height - (count - 1) * gap) / count).coerceAtLeast(1)
        }
        val firstTop = (top + (height - count * icon - (count - 1) * gap) / 2).coerceAtLeast(0)
        val iconPadding = icon / 10
        for (i in 0 until count) {
            val button = sb.getChildAt(i)
            button.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            button.layoutParams = LinearLayout.LayoutParams(icon, icon).apply {
                topMargin = if (i == 0) firstTop else 0
                bottomMargin = if (i == count - 1) 0 else gap
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
    }

    /**
     * L'host può riservare una fascia ai suoi tasti (action strip e tasti mappa):
     * - visible area = area libera in questo momento (si restringe quando i tasti compaiono);
     * - stable area  = area libera ANCHE a tasti visibili: è identica con tasti nascosti o visibili.
     * Quindi: inset reale = inset della stable area - fascia dei tasti, così i tasti (che si sovrappongono
     * alla pagina) non cambiano la dimensione della WebView, mentre il player o il menu in split sì.
     *
     * La fascia si impara quando i tasti sono nascosti (visible più grande della stable in alto) e vale solo per
     * quella disposizione dell'host (stable area): a schermo intero può esserci, in split col player o col menu
     * no. Prima valeva per tutte e partiva da 76 px: in split la pagina finiva 76 px sotto il player.
     * Disposizione mai vista: nessuna fascia, la pagina non va mai sotto la UI dell'host.
     */
    private fun learnButtonsReserve() {
        val v = visibleArea ?: return
        val st = stableArea ?: return
        if (v.top < st.top) {
            learnedReserves[Rect(st)] = ButtonsReserve(
                left = (st.left - v.left).coerceAtLeast(0),
                right = (v.right - st.right).coerceAtLeast(0)
            )
        }
    }

    private fun buttonsReserve(): ButtonsReserve =
        stableArea?.let { learnedReserves[it] } ?: ButtonsReserve(0, 0)

    private fun buttonsReserveRight(): Int = buttonsReserve().right

    private fun buttonsReserveLeft(): Int = buttonsReserve().left

    /** Inset sinistro reale (player a sinistra incluso), senza la fascia dei tasti. */
    private fun leftInsetPx(): Int {
        val st = stableArea ?: return (visibleArea?.left ?: 0).coerceAtLeast(0)
        return (st.left - buttonsReserveLeft()).coerceAtLeast(0)
    }

    /** Inset destro grezzo della visible area (solo per il log e come ripiego). */
    private fun rawRightInsetPx(): Int {
        val area = visibleArea ?: return 0
        return if (area.right in 1 until surfaceWidth) surfaceWidth - area.right else 0
    }

    /** Inset destro reale (player a destra incluso), senza la fascia dei tasti. */
    private fun rightInsetPx(): Int {
        val st = stableArea ?: return rawRightInsetPx()
        val fromStable = if (st.right in 1 until surfaceWidth) surfaceWidth - st.right else 0
        return (fromStable - buttonsReserveRight()).coerceAtLeast(0)
    }

    /** All'avvio ricalcola più volte il layout, finché la UI dell'host non si è stabilizzata. */
    private fun scheduleStartupReapply() {
        handler.removeCallbacks(startupReapplyRunnable)
        for (delay in STARTUP_REAPPLY_DELAYS_MS) {
            handler.postDelayed(startupReapplyRunnable, delay)
        }
    }

    // ------------------------------------------------------------------
    // SurfaceCallback
    // ------------------------------------------------------------------

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        try {
            AppSettings.init(carContext)
        } catch (e: Exception) {
            Log.e(TAG, "AppSettings init failed", e)
        }
        surface = surfaceContainer.surface
        surfaceWidth = if (surfaceContainer.width > 0) surfaceContainer.width else 800
        surfaceHeight = if (surfaceContainer.height > 0) surfaceContainer.height else 480
        surfaceDensity = if (surfaceContainer.dpi > 0) surfaceContainer.dpi else 160

        Log.d(TAG, "CALLBACK onSurfaceAvailable ${surfaceWidth}x$surfaceHeight dpi=$surfaceDensity")

        // La UI dell'host sta ancora caricando: ricalcolo il resize più volte all'avvio
        scheduleStartupReapply()

        if (virtualDisplay != null && presentation != null && webView != null) {
            try {
                virtualDisplay?.surface = surface
                virtualDisplay?.resize(surfaceWidth, surfaceHeight, surfaceDensity)
                applyVisibleArea()
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to swap surface on VirtualDisplay, recreating", e)
            }
        }

        createVirtualDisplayAndPresentation()
        handler.post { applyVisibleArea() }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        try {
            virtualDisplay?.surface = null
        } catch (_: Exception) {}
        surface = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Log.d(
            TAG,
            "CALLBACK onVisibleAreaChanged $visibleArea (w=${visibleArea.width()} h=${visibleArea.height()}) " +
                    "surface=${surfaceWidth}x$surfaceHeight rightInset=${surfaceWidth - visibleArea.right}"
        )
        this.visibleArea = Rect(visibleArea)

        // NON modificare surfaceWidth/surfaceHeight qui: sono le dimensioni reali della surface
        // e arrivano solo da onSurfaceAvailable. Cambiarle in base alla visible area le "sporca"
        // (crescono ma non tornano mai indietro) e falsa il calcolo degli inset.

        // Debounce: l'host manda più callback intermedi mentre i tasti appaiono/scompaiono.
        handler.removeCallbacks(applyAreaRunnable)
        handler.postDelayed(applyAreaRunnable, VISIBLE_AREA_DEBOUNCE_MS)

        // Secondo passaggio a animazione conclusa, per catturare lo stato finale.
        handler.removeCallbacks(applyAreaSettledRunnable)
        handler.postDelayed(applyAreaSettledRunnable, VISIBLE_AREA_SETTLE_MS)
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        Log.d(
            TAG,
            "CALLBACK onStableAreaChanged $stableArea (w=${stableArea.width()} h=${stableArea.height()}) " +
                    "surface=${surfaceWidth}x$surfaceHeight rightInset=${surfaceWidth - stableArea.right}"
        )
        this.stableArea = Rect(stableArea)

        // L'inset reale dipende anche dalla stable area: ricalcolo con lo stesso debounce
        handler.removeCallbacks(applyAreaRunnable)
        handler.postDelayed(applyAreaRunnable, VISIBLE_AREA_DEBOUNCE_MS)
        handler.removeCallbacks(applyAreaSettledRunnable)
        handler.postDelayed(applyAreaSettledRunnable, VISIBLE_AREA_SETTLE_MS)
    }

    private fun applyVisibleArea() {
        layoutVisibleArea()
    }

    private fun layoutVisibleArea() {
        val wv = webView ?: return
        val sb = sidebarContainerReference ?: return

        // Impara la fascia dei tasti Google (a tasti nascosti), poi calcola gli inset reali
        learnButtonsReserve()

        val leftAreaM = leftInsetPx()
        val rightAreaM = rightInsetPx()
        val sidebarWidthPx = sidebarWidthPx()

        val fixed = sidebarFixedEnabled()
        val rtl = isRtl

        // La fascia stretta sul bordo (angoli arrotondati dell'host) resta alla sidebar fissa; un pannello
        // dell'host da quel lato (es. il menu delle app in split, con la Wide a destra) la sposta accanto a sé.
        // Prima veniva ignorato: sidebar e pagina restavano sotto il menu. Il confine non cambia con la dimensione
        // scelta per il menu: la fascia stretta è sempre la stessa
        val panelThreshold = (SIDEBAR_WIDTH_DP * surfaceScale()).toInt()
        val sidebarEdge = (if (rtl) rightAreaM else leftAreaM).let { if (it >= panelThreshold) it else 0 }

        Log.d(
            TAG,
            "layout raw=$visibleArea surface=${surfaceWidth}x$surfaceHeight " +
                    "stable=$stableArea rawRight=${rawRightInsetPx()} reserveL=${buttonsReserveLeft()} reserveR=${buttonsReserveRight()} -> left=$leftAreaM right=$rightAreaM " +
                    "fixed=$fixed rtl=$rtl"
        )

        if (fixed) {
            sb.visibility = if (sidebarHiddenForVideo && customView != null) View.INVISIBLE else View.VISIBLE
            sb.alpha = 1f
            layoutSidebar(sb, sidebarWidthPx, sidebarEdge, rtl)
        } else {
            sb.visibility = View.GONE
        }

        val lp = (wv.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        if (fixed) {
            if (rtl) {
                lp.leftMargin = leftAreaM
                lp.rightMargin = sidebarEdge + sidebarWidthPx
            } else {
                lp.leftMargin = sidebarEdge + sidebarWidthPx
                lp.rightMargin = rightAreaM
            }
        } else {
            lp.leftMargin = leftAreaM
            lp.rightMargin = rightAreaM
        }
        lp.topMargin = 0
        lp.bottomMargin = 0
        wv.layoutParams = lp
        wv.requestLayout()
        wv.post {
            Log.d(
                TAG,
                "WEBVIEW real left=${wv.left} right=${wv.right} width=${wv.width} " +
                        "backdropWidth=${backdrop?.width} marginL=${lp.leftMargin} marginR=${lp.rightMargin}"
            )
        }
        customView?.let { layoutCustomView(it, lp) }

        updatePopupPosition()
        updateVoiceCardPosition()
        passengerKeyboard?.layout(overlayArea())
        layoutDriveLock()
    }

    /**
     * Popup tastiera/microfono al centro dell'area libera dall'interfaccia di Android Auto, tutto dentro.
     * La scheda si misura subito: alla prima apertura era GONE e la sua larghezza valeva 0, quindi finiva
     * spostata di mezza scheda, e su schermi grandi in parte sotto i tasti dell'host (non toccabile).
     */
    private fun updatePopupPosition() {
        val overlay = popupOverlayReference ?: return
        val card = overlay.getChildAt(0) as? LinearLayout ?: return
        val lp = card.layoutParams as? FrameLayout.LayoutParams ?: return
        val r = visibleArea?.takeUnless { it.isEmpty } ?: Rect(0, 0, surfaceWidth, surfaceHeight)

        card.measure(
            View.MeasureSpec.makeMeasureSpec(r.width(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(r.height(), View.MeasureSpec.AT_MOST)
        )
        val w = card.measuredWidth
        val h = card.measuredHeight
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = (r.centerX() - w / 2).coerceIn(r.left, maxOf(r.left, r.right - w))
        lp.topMargin = (r.centerY() - h / 2).coerceIn(r.top, maxOf(r.top, r.bottom - h))
        card.layoutParams = lp
    }

    /**
     * Area della tastiera passeggero e dell'avviso in movimento: quella libera anche coi tasti di Android Auto
     * visibili (stable area), così la tastiera non si sposta sotto il dito quando i tasti compaiono o spariscono.
     */
    private fun overlayArea(): Rect =
        (stableArea ?: visibleArea)?.takeUnless { it.isEmpty } ?: Rect(0, 0, surfaceWidth, surfaceHeight)

    private fun isDriveLocked(): Boolean = driveLockReference?.isVisible == true

    /** Pagina sotto la copertura in movimento o sotto la tastiera passeggero: niente scorrimento né zoom. */
    private fun isPageCovered(): Boolean = isDriveLocked() || passengerKeyboard?.isVisible == true

    /**
     * Auto in movimento (lo dice Android Auto) e Modalità passeggero spenta: la pagina resta coperta da un avviso che
     * spiega come sbloccarla e i tocchi si fermano. Audio, player di Android Auto e comandi vocali vanno avanti.
     */
    private fun applyDriveLock() {
        val overlay = driveLockReference ?: return
        val locked = DrivingState.isLocked
        if (locked == overlay.isVisible) return
        if (locked) {
            handler.removeCallbacks(hidePopupRunnable)
            popupOverlayReference?.visibility = View.GONE
            isInputPopupVisible = false
        }
        overlay.isVisible = locked
        layoutDriveLock()
        Log.d(TAG, "Drive lock ${if (locked) "on" else "off"}")
    }

    // Avviso al centro dell'area libera, largo al massimo quanto lei
    private fun layoutDriveLock() {
        val card = driveLockCardReference ?: return
        val lp = card.layoutParams as? FrameLayout.LayoutParams ?: return
        val r = overlayArea()
        val margin = (24 * card.resources.displayMetrics.density).toInt()
        card.measure(
            View.MeasureSpec.makeMeasureSpec((r.width() - 2 * margin).coerceAtLeast(0), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(r.height(), View.MeasureSpec.AT_MOST)
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.width = card.measuredWidth
        lp.leftMargin = (r.centerX() - card.measuredWidth / 2).coerceAtLeast(r.left)
        lp.topMargin = (r.centerY() - card.measuredHeight / 2).coerceAtLeast(r.top)
        card.layoutParams = lp
    }

    override fun onClick(x: Float, y: Float) {
        // In movimento senza la Modalità passeggero la pagina è coperta: i tocchi si fermano
        if (isDriveLocked()) return

        passengerKeyboard?.takeIf { it.isVisible }?.let { keyboard ->
            dispatchTouchToOverlay(keyboard.view, MotionEvent.ACTION_DOWN, x, y)
            dispatchTouchToOverlay(keyboard.view, MotionEvent.ACTION_UP, x, y)
            return
        }

        if (isInputPopupVisible) {
            dispatchTouchToOverlay(popupOverlayReference, MotionEvent.ACTION_DOWN, x, y)
            dispatchTouchToOverlay(popupOverlayReference, MotionEvent.ACTION_UP, x, y)
            return
        }

        lastTapAt = SystemClock.uptimeMillis()
        webView?.evaluateJavascript("window.__lastTouchX = $x; window.__lastTouchY = $y;", null)

        val fixed = sidebarFixedEnabled()
        val rtl = isRtl
        val sb = sidebarContainerReference

        if (fixed && sb != null && sb.isVisible && sb.width > 0) {
            // Stessi limiti della sidebar disegnata (la WebView parte esattamente dal suo bordo):
            // tolleranza solo verso il bordo dello schermo, mai verso la pagina, altrimenti
            // i tocchi sul sito vicino alla sidebar finiscono su home/indietro/ricerca
            val hitSidebar = if (rtl) {
                x >= sb.left && x <= sb.right + SIDEBAR_OUTER_TOUCH_TOLERANCE_PX
            } else {
                x >= sb.left - SIDEBAR_OUTER_TOUCH_TOLERANCE_PX && x < sb.right
            }

            if (hitSidebar) {
                var bestButton: View? = null
                var minDistance = Float.MAX_VALUE

                // Trova sempre il pulsante più vicino al punto toccato verticalmente
                for (i in 0 until sb.childCount) {
                    val child = sb.getChildAt(i)
                    val childCenterY = (child.top + child.bottom) / 2f
                    val dist = abs(y - childCenterY)
                    if (dist < minDistance) {
                        minDistance = dist
                        bestButton = child
                    }
                }
                bestButton?.performClick()
                return
            }
        }

        dispatchTouch(MotionEvent.ACTION_DOWN, x, y)
        dispatchTouch(MotionEvent.ACTION_UP, x, y)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (isInputPopupVisible || customView != null || isPageCovered()) return
        val wv = webView ?: return
        // Pagina di un video di YouTube: niente scorrimento nativo, decide lo script (prima le colonne, video in alto)
        if (!isYouTubeWatchPage(wv.url)) wv.scrollBy(distanceX.toInt(), distanceY.toInt())
        // La parte JavaScript una volta per fotogramma con le distanze sommate: Android Auto manda molti
        // onScroll piccoli e ognuno era uno script nella pagina
        pendingScrollX += distanceX
        pendingScrollY += distanceY
        // Riposta anche se l'ultimo è rimasto in coda su una WebView ricreata
        val now = SystemClock.uptimeMillis()
        if (!isScrollFlushPosted || now - scrollFlushPostedAt > SCROLL_FLUSH_STALE_MS) {
            isScrollFlushPosted = true
            scrollFlushPostedAt = now
            wv.postOnAnimation(flushScrollRunnable)
        }
    }

    private fun flushScroll() {
        isScrollFlushPosted = false
        val dx = pendingScrollX.toInt()
        val dy = pendingScrollY.toInt()
        // Il resto sotto il pixel resta per il prossimo fotogramma
        pendingScrollX -= dx
        pendingScrollY -= dy
        if (dx == 0 && dy == 0) return
        webView?.evaluateJavascript(BrowserJavascript.getWideScrollScript(dx, dy), null)
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (isInputPopupVisible || customView != null || isPageCovered()) return
        val wv = webView ?: return
        if (isYouTubeWatchPage(wv.url)) {
            // Come lo scorrimento: il fling nativo sposterebbe la pagina e il video
            val dx = (-velocityX * FLING_SCROLL_SECONDS).toInt()
            val dy = (-velocityY * FLING_SCROLL_SECONDS).toInt()
            wv.evaluateJavascript(BrowserJavascript.getWideScrollScript(dx, dy, smooth = true), null)
        } else {
            wv.flingScroll(-velocityX.toInt(), -velocityY.toInt())
        }
    }

    private fun isYouTubeWatchPage(url: String?): Boolean {
        val uri = url?.let { Uri.parse(it) } ?: return false
        val host = uri.host?.lowercase() ?: return false
        return (host == "youtube.com" || host.endsWith(".youtube.com")) && uri.path == "/watch"
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        // Niente zoom sulla home o zoom improvviso (doppio tap to zoom dell'host Android Auto)
        if (isInputPopupVisible || customView != null || isHome() || isPageCovered()) return

        // I doppi tap interpretati dall'host Android Auto inviano scaleFactor fissi o ampi (es. 2.0 o 0.5).
        // Il pinch-to-zoom usa variazioni graduali vicine a 1.0 (es. 0.95 - 1.05).
        if (abs(scaleFactor - 1.0f) > 0.35f) {
            return
        }

        if (scaleFactor in 0.65f..1.35f) {
            try {
                webView?.zoomBy(scaleFactor)
            } catch (_: Exception) {
            }
        }
    }

    private fun dispatchTouch(action: Int, x: Float, y: Float) {
        handler.post {
            // Con un video a schermo intero i tocchi vanno ai suoi controlli
            val target: View = customView ?: webView ?: return@post
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now

            // Coordinate locali alla view: sottraggo la sua posizione reale (margini inclusi),
            // così il tocco resta corretto con sidebar, RTL e inset a destra/sinistra.
            val lx = x - target.left
            val ly = y - target.top

            val event = MotionEvent.obtain(downTime, now, action, lx, ly, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            target.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    /** Tocco a una scheda sopra la pagina (popup, tastiera passeggero), che copre tutta la superficie. */
    private fun dispatchTouchToOverlay(overlay: View?, action: Int, x: Float, y: Float) {
        handler.post {
            if (overlay == null) return@post
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now

            val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            overlay.dispatchTouchEvent(event)
            event.recycle()
        }
    }



    // ------------------------------------------------------------------
    // VirtualDisplay + Presentation + WebView + Popup
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createVirtualDisplayAndPresentation() {
        val surf = surface ?: return
        if (!surf.isValid) {
            Log.w(TAG, "Surface non valida")
            return
        }
        releaseVirtualDisplay()

        try {
            val displayManager = carContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val vd = displayManager.createVirtualDisplay(
                "AABrowserWideDisplay",
                surfaceWidth,
                surfaceHeight,
                surfaceDensity,
                surf,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            )
            virtualDisplay = vd

            val display = vd.display
            if (display == null) {
                Log.e(TAG, "VirtualDisplay senza display")
                return
            }

            presentation = object : Presentation(carContext, display) {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    val ctx = context

                    val frame = FrameLayout(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setBackgroundColor("#101010".toColorInt())
                    }
                    backdrop = frame

                    // Popup compatto (tastiera e microfono). Misure in dp: in pixel fissi sugli schermi grandi e
                    // ad alta densità (14") i tasti venivano piccoli. Un tocco fuori dalla scheda lo chiude subito
                    val dp = ctx.resources.displayMetrics.density
                    val popupOverlay = FrameLayout(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(-1, -1)
                        visibility = View.GONE
                        setBackgroundColor("#CC000000".toColorInt()) // Sfondo più scuro per contrasto
                        setOnClickListener {
                            handler.removeCallbacks(hidePopupRunnable)
                            visibility = View.GONE
                            isInputPopupVisible = false
                        }
                    }
                    popupOverlayReference = popupOverlay

                    val inputCard = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        val pad = (12 * dp).toInt()
                        setPadding(pad, pad, pad, pad)
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            setColor("#202124".toColorInt())
                            cornerRadius = 16 * dp
                            setStroke(1, "#44464A".toColorInt())
                        }
                    }

                    val btnStyle = { btn: Button, color: String ->
                        btn.textSize = 22f
                        btn.setTextColor(Color.WHITE)
                        btn.background = GradientDrawable().apply {
                            setColor(color.toColorInt())
                            cornerRadius = 12 * dp
                        }
                        btn.minWidth = (72 * dp).toInt()
                        btn.minHeight = (56 * dp).toInt()
                        btn.setPadding((20 * dp).toInt(), (10 * dp).toInt(), (20 * dp).toInt(), (10 * dp).toInt())
                    }

                    val btnKeyboard = Button(ctx).apply {
                        text = "⌨️"
                        btnStyle(this, "#3B82F6") // Blue Material
                        setOnClickListener {
                            handler.removeCallbacks(hidePopupRunnable)
                            popupOverlay.visibility = View.GONE
                            isInputPopupVisible = false
                            openFieldInput()
                        }
                    }

                    val btnMic = Button(ctx).apply {
                        text = "🎤"
                        btnStyle(this, "#EF4444")
                        setOnClickListener {
                            handler.removeCallbacks(hidePopupRunnable)
                            // Qui ora passi correttamente il riferimento al bottone
                            startVoiceListening(ctx, webView, this)
                        }
                    }

                    inputCard.addView(btnKeyboard, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = (16 * dp).toInt() })
                    inputCard.addView(btnMic, LinearLayout.LayoutParams(-2, -2))

                    val cardLp = FrameLayout.LayoutParams(-2, -2).apply {
                        gravity = Gravity.CENTER
                    }
                    popupOverlay.addView(inputCard, cardLp)

                    // Comando vocale in ascolto: in alto nell'area libera, con quello che si sta capendo.
                    // Non riceve tocchi (vanno sempre alla pagina); il microfono lo chiude di nuovo
                    val voiceCard = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        visibility = View.GONE
                        val pad = (12 * dp).toInt()
                        setPadding(pad * 2, pad, pad * 2, pad)
                        background = GradientDrawable().apply {
                            setColor("#E6202124".toColorInt())
                            cornerRadius = 16 * dp
                            setStroke(1, "#44464A".toColorInt())
                        }
                    }
                    val voiceTitle = TextView(ctx).apply {
                        text = "🎤 ${strings.getString(R.string.voice_listening)}"
                        textSize = 20f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                    }
                    val voiceDetail = TextView(ctx).apply {
                        textSize = 16f
                        setTextColor("#BDC1C6".toColorInt())
                        gravity = Gravity.CENTER
                    }
                    voiceCard.addView(voiceTitle, LinearLayout.LayoutParams(-1, -2))
                    voiceCard.addView(voiceDetail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4 * dp).toInt() })
                    voiceCardReference = voiceCard
                    voiceDetailReference = voiceDetail

                    val keyboard = WidePassengerKeyboard(ctx, strings)
                    keyboard.onVisibilityChanged = { visible -> closeKeyboardOnBack.isEnabled = visible }
                    passengerKeyboard = keyboard

                    // Auto in movimento senza la Modalità passeggero: copre pagina e sidebar, spiega come sbloccare
                    val driveLock = FrameLayout(ctx).apply {
                        visibility = View.GONE
                        setBackgroundColor("#101010".toColorInt())
                        isClickable = true
                    }
                    val driveLockCard = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                    driveLockCard.addView(TextView(ctx).apply {
                        text = "🚗 ${strings.getString(R.string.drive_lock_title)}"
                        textSize = 24f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                    }, LinearLayout.LayoutParams(-1, -2))
                    driveLockCard.addView(TextView(ctx).apply {
                        text = strings.getString(R.string.drive_lock_message)
                        textSize = 18f
                        setTextColor("#BDC1C6".toColorInt())
                        gravity = Gravity.CENTER
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * dp).toInt() })
                    driveLock.addView(driveLockCard, FrameLayout.LayoutParams(-2, -2))
                    driveLockReference = driveLock
                    driveLockCardReference = driveLockCard

                    val wv = WebView(ctx).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            mediaPlaybackRequiresUserGesture = !AppSettings.autoplayMedia.value
                            applyDarkMode(this)
                        }

                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        mediaSessionManager.connect()
                        mediaSessionManager.apply {
                            onPlay = { evaluateJavascript(BrowserJavascript.PLAY_SCRIPT.trimIndent(), null) }
                            onPause = { evaluateJavascript(BrowserJavascript.PAUSE_SCRIPT.trimIndent(), null) }
                            onStop = { evaluateJavascript(BrowserJavascript.STOP_SCRIPT.trimIndent(), null) }
                            onSkipToNext = { evaluateJavascript(BrowserJavascript.NEXT_SCRIPT.trimIndent(), null) }
                            onSkipToPrevious = { evaluateJavascript(BrowserJavascript.PREVIOUS_SCRIPT.trimIndent(), null) }
                            onSeekTo = { pos -> evaluateJavascript(BrowserJavascript.getSeekScript(pos), null) }
                            singleVideoAsTracks = {
                                AppSettings.youtubeVideoSkip.value && VoiceCommands.isYouTubeUrl("https://$pageHost/")
                            }
                        }
                        // Titolo della pagina finché il sito non invia i metadati del media
                        mediaSessionManager.fallbackTitle = { title }

                        // Script all'inizio di ogni pagina. L'AdBlock di YouTube NON va qui:
                        // lo inietta WebViewScriptRouter solo sui domini YouTube.
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                            WebViewCompat.addDocumentStartJavaScript(this, BrowserJavascript.getLifecycleAndMetadataScript(), setOf("*"))
                            WebViewCompat.addDocumentStartJavaScript(this, GoogleLoginManager.getGoogleOauthFixScript(), setOf("*"))
                            WebViewCompat.addDocumentStartJavaScript(this, GoogleLoginManager.getPopupInterceptorScript(), setOf("*"))
                            // Prima del player di YouTube: toglie i dati delle pubblicità (riserva: lo script a pagina finita)
                            WebViewCompat.addDocumentStartJavaScript(
                                this,
                                AdBlockJavascript.getYouTubeAdPruneScript(),
                                AdBlockJavascript.YOUTUBE_ORIGINS
                            )
                            // Previene lo scroll/focus automatico verso la playlist in basso su piccolo schermo
                            WebViewCompat.addDocumentStartJavaScript(
                                this,
                                YouTubeManager.getPreventPlaylistScrollScript(),
                                AdBlockJavascript.YOUTUBE_ORIGINS
                            )
                            // SponsorBlock: salta sponsor e le altre parti scelte (chiede le categorie al ponte)
                            WebViewCompat.addDocumentStartJavaScript(
                                this,
                                SponsorBlock.script(carContext),
                                AdBlockJavascript.YOUTUBE_ORIGINS
                            )
                            WebViewCompat.addDocumentStartJavaScript(this, DaznManager.getAuthProxyScript(), setOf("https://www.dazn.com"))
                            // Aggiungi anche lo script per salvare l'elemento attivo
                            WebViewCompat.addDocumentStartJavaScript(this, BrowserJavascript.getAutoSaveActiveElementScript(), setOf("*"))
                            // Blocca il doppio tap to zoom in tutte le pagine
                            WebViewCompat.addDocumentStartJavaScript(this, BrowserJavascript.getDisableDoubleTapScript(), setOf("*"))
                            // Identità desktop completa (platform, userAgentData, ...) prima degli script del sito,
                            // solo sulle pagine chieste con lo user agent desktop: la Wide cambia sito nella stessa
                            // WebView e da onPageStarted lo script arriva tardi (WhatsApp Web lo controlla subito)
                            WebViewScriptRouter.installedChromeVersion(ctx)?.let { chromeVersion ->
                                WebViewCompat.addDocumentStartJavaScript(
                                    this,
                                    BrowserJavascript.getDesktopSpoofScriptForDesktopUa(chromeVersion),
                                    setOf("*")
                                )
                            }
                        }

                        addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                @Suppress("unused")
                                fun onVideoStarted(time: Float) {
                                    AppLog.d("WideScreenBridge", "onVideoStarted time=$time")
                                    @Suppress("DEPRECATION")
                                    mediaSessionManager.updatePlaybackState(
                                        PlaybackStateCompat.STATE_PLAYING,
                                        (time * 1000).toLong(),
                                        1.0f,
                                        isPlaybackStart = true
                                    )
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun onMediaTimeUpdate(time: Float, speed: Float, isPlaying: Boolean) {
                                    AppLog.d("WideScreenBridge", "onMediaTimeUpdate time=$time, speed=$speed, isPlaying=$isPlaying")
                                    @Suppress("DEPRECATION")
                                    mediaSessionManager.updatePlaybackState(
                                        if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                                        (time * 1000).toLong(),
                                        speed
                                    )
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun onMediaStatusChanged(isPlaying: Boolean, time: Float, speed: Float) {
                                    AppLog.d("WideScreenBridge", "onMediaStatusChanged isPlaying=$isPlaying, time=$time, speed=$speed")
                                    @Suppress("DEPRECATION")
                                    mediaSessionManager.updatePlaybackState(
                                        if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                                        (time * 1000).toLong(),
                                        speed,
                                        isPlaybackStart = isPlaying
                                    )
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun updateMediaMetadata(title: String, artist: String, albumArtUrl: String, duration: Float) {
                                    AppLog.d("WideScreenBridge", "updateMediaMetadata title='$title', artist='$artist', duration=$duration")
                                    mediaSessionManager.updateMetadata(
                                        title,
                                        artist,
                                        albumArtUrl,
                                        (duration * 1000).toLong()
                                    )
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun recMediaStatus(jsonStr: String) {
                                    try {
                                        val json = JSONObject(jsonStr)
                                        val title = json.optString("track", "")
                                        val artist = json.optString("artist", "")
                                        val position = json.optLong("position", 0L)
                                        val duration = json.optLong("duration", 0L)
                                        val isPlaying = json.optBoolean("playing", false)

                                        var coverUrl = json.optString("cover", "")
                                        coverUrl = coverUrl.replace("00004851", "0000b273")

                                        val lowerTitle = title.lowercase()
                                        val ignoredTitles = listOf("buonasera", "buongiorno", "buon pomeriggio", "good evening", "good morning", "spotify", "home", "search", "cerca")
                                        if (title.isNotEmpty() && !ignoredTitles.any { lowerTitle.contains(it) }) {
                                            mediaSessionManager.updateMetadata(title, artist, coverUrl, duration)
                                            mediaSessionManager.updatePlaybackState(
                                                if (isPlaying) PlaybackStateCompat.STATE_PLAYING
                                                else PlaybackStateCompat.STATE_PAUSED,
                                                position,
                                                1.0f
                                            )
                                        }
                                    } catch (e: Exception) {
                                        AppLog.e("WideScreenBridge", "Error parsing media status", e)
                                    }
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun updateMediaCapabilities(canSeek: Boolean, canSkipNext: Boolean, canSkipPrevious: Boolean) {
                                    mediaSessionManager.setPlaybackCapabilities(canSeek, canSkipNext, canSkipPrevious)
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun onMetadataUpdated(title: String, faviconUrl: String, currentUrl: String) {}

                                // DAZN: chiamate di autenticazione/playback fatte lato app (thread del ponte, bloccante)
                                @JavascriptInterface
                                @Suppress("unused")
                                fun proxyFetch(
                                    urlString: String,
                                    method: String,
                                    headersJson: String,
                                    body: String?,
                                    requestUserAgent: String
                                ): String = DaznProxy.proxyFetch(
                                    carContext, pageHost, urlString, method, headersJson, body, requestUserAgent
                                )

                                // Velocità di YouTube scelta dall'utente: la stessa della Main (che a ogni pagina
                                // YouTube la rimette nel localStorage, condiviso tra le due WebView)
                                @JavascriptInterface
                                @Suppress("unused")
                                fun saveYouTubeSpeed(speed: Float) {
                                    AppSettings.setYouTubeSpeed(carContext, speed)
                                }

                                // Lo chiede lo script dei dati del player di YouTube all'inizio della pagina
                                @JavascriptInterface
                                @Suppress("unused")
                                fun isYouTubeAdPruneOn(): Boolean = AdBlockSettings.isYouTubePruneOn(pageHost)

                                // DAZN: al clic su un evento passa alla modalità desktop
                                @JavascriptInterface
                                @Suppress("unused")
                                fun onDaznEventClicked(eventUrl: String) {
                                    post {
                                        if (!daznDesktopForced) {
                                            daznDesktopForced = true
                                            WebViewScriptRouter.setDesktopUserAgent(this@apply, carContext)
                                            if (eventUrl.startsWith("http")) {
                                                this@WideScreen.loadUrl(eventUrl)
                                            } else {
                                                this@apply.reload()
                                            }
                                        }
                                    }
                                }

                                // Chiamato dagli script quando serve l'AdBlock di YouTube
                                @JavascriptInterface
                                @Suppress("unused")
                                fun onStartAdBlock() {
                                    post {
                                        WebViewScriptRouter.injectYouTubeAdBlockIfNeeded(
                                            this@apply,
                                            this@apply.url,
                                            AdBlockSettings.isYouTubeEnabled.value
                                        )
                                    }
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun onStartInput() {
                                    post {
                                        if (SystemClock.uptimeMillis() < suppressInputPopupUntil || isPageCovered()) return@post
                                        popupOverlay.visibility = View.VISIBLE
                                        isInputPopupVisible = true
                                        updatePopupPosition()
                                        handler.removeCallbacks(hidePopupRunnable)
                                        handler.postDelayed(hidePopupRunnable, INPUT_POPUP_TIMEOUT_MS)
                                    }
                                }

                                // window.open delle pagine (GoogleLoginManager): [tappedLink] = il link toccato, se c'era
                                @JavascriptInterface
                                @Suppress("unused")
                                fun openInNewTab(url: String, tappedLink: String) {
                                    // Solo pagine web: dalle pagine non devono arrivare file://, content://, intent://...
                                    if (UrlUtils.isWebUrl(url) && hasRecentTap("openInNewTab") &&
                                        PopupGuard.allowsPopup(url, pageHost, tappedLink, popupAttempts, isWindow = true)
                                    ) {
                                        post { this@WideScreen.loadUrl(url) }
                                    }
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun openLinkInNewTab(url: String) {
                                    if (UrlUtils.isWebUrl(url) && hasRecentTap("openLinkInNewTab")) post { this@WideScreen.loadUrl(url) }
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun openPopup(url: String, tappedLink: String) {
                                    if (UrlUtils.isWebUrl(url) && hasRecentTap("openPopup") &&
                                        PopupGuard.allowsPopup(url, pageHost, tappedLink, popupAttempts, isWindow = true)
                                    ) {
                                        post { this@WideScreen.loadUrl(url) }
                                    }
                                }

                                // Window.open e link cliccati da uno script: si aprono? (PopupGuard)
                                @JavascriptInterface
                                @Suppress("unused")
                                fun allowsPopup(url: String, tappedLink: String): Boolean =
                                    PopupGuard.allowsPopup(url, pageHost, tappedLink, popupAttempts, isWindow = false)

                                // Lo script di SponsorBlock all'inizio delle pagine di YouTube: cosa saltare e il messaggio
                                @JavascriptInterface
                                @Suppress("unused")
                                fun sponsorBlockCategories(): String = SponsorBlock.categoriesJson()

                                @JavascriptInterface
                                @Suppress("unused")
                                fun sponsorBlockMessage(): String = strings.getString(R.string.sponsorblock_skipped)

                                // Barra dei video di CarWeb Media a schermo intero: con il menu fisso sceglie se resta
                                // sopra il video o compare solo con i comandi (video-zoom.js)
                                @JavascriptInterface
                                @Suppress("unused")
                                fun hasFixedSidebar(): Boolean = sidebarFixedEnabled()

                                @JavascriptInterface
                                @Suppress("unused")
                                fun showSidebarOverVideo(visible: Boolean) {
                                    if (LocalPlayer.isPlayerUrl("https://$pageHost/")) post { setSidebarOverVideo(visible) }
                                }
                            },
                            "AndroidBridge"
                        )

                        webChromeClient = object : WebChromeClient() {
                            // Sull'auto il selettore non si apre: la pagina riceve l'ultima scelta fatta sul telefono,
                            // se il sito ha il permesso
                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallback: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?
                            ): Boolean = filePathCallback != null && sitePrompts.onCarFileRequest(
                                webView?.url,
                                filePathCallback,
                                fileChooserParams?.mode ?: FileChooserParams.MODE_OPEN
                            )

                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message?
                            ): Boolean {
                                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                val dummyWebView = WebView(view?.context ?: return false).apply {
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                            request?.url?.toString()?.let { url ->
                                                loadUrl(url)
                                            }
                                            return true
                                        }
                                    }
                                }
                                transport.webView = dummyWebView
                                resultMsg.sendToTarget()
                                return true
                            }

                            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                if (view == null || callback == null) return
                                showCustomView(view, callback)
                            }

                            override fun onHideCustomView() {
                                hideCustomView()
                            }

                            // DRM sempre, microfono come scelto per il sito, fotocamera mai
                            override fun onPermissionRequest(request: PermissionRequest?) {
                                request?.let(sitePrompts::onPermissionRequest)
                            }

                            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                                request?.let(sitePrompts::onPermissionRequestCanceled)
                            }

                            override fun onGeolocationPermissionsShowPrompt(
                                origin: String?,
                                callback: GeolocationPermissions.Callback?
                            ) {
                                sitePrompts.onGeolocationPrompt(origin, callback)
                            }

                            override fun onGeolocationPermissionsHidePrompt() {
                                sitePrompts.onGeolocationHidePrompt()
                            }

                            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean =
                                result != null && showJsDialog(JsDialogRequest(JsDialogRequest.Kind.ALERT, url, message, null, result))

                            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean =
                                result != null && showJsDialog(JsDialogRequest(JsDialogRequest.Kind.CONFIRM, url, message, null, result))

                            // "Vuoi lasciare la pagina?": senza risposta la WebView, qui senza Activity, annulla e il
                            // tasto indietro non fa niente
                            override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean =
                                result != null && showJsDialog(JsDialogRequest(JsDialogRequest.Kind.CONFIRM, url, message, null, result))

                            // I messaggi di Android Auto non hanno un campo di testo: la pagina riceve Annulla
                            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?): Boolean {
                                result?.cancel()
                                return true
                            }
                        }

                        webViewClient = @SuppressLint("MissingOnRenderProcessGone")
                        object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                // Prima che la pagina nuova esegua i suoi script (onPageStarted arriva dopo)
                                if (request?.isForMainFrame == true) pageHost = request.url?.host

                                // Lettore locale dell'app (https://player.local/), lo stesso della Main
                                LocalPlayer.intercept(carContext, request)?.let { return it }

                                val urlString = request?.url?.toString() ?: ""

                                if (urlString == HomePage.BASE_URL) {
                                    val html = homeHtml ?: buildHomeHtml().also { homeHtml = it }
                                    return WebResourceResponse(
                                        "text/html",
                                        "UTF-8",
                                        ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
                                    )
                                }

                                if (AdBlockHost.shouldBlock(urlString, pageHost)) {
                                    return AdBlockHost.blockedResponse(request)
                                }
                                // DAZN: risposta alle richieste CORS "OPTIONS" (solo per i domini DAZN, col dominio esatto: con "contiene
                                // dazn.com" valeva anche per siti come notdazn.com)
                                if (request != null && request.method.equals("OPTIONS", ignoreCase = true)) {
                                    val origin = request.requestHeaders["Origin"] ?: ""
                                    val isDaznRequest = DaznProxy.isDaznHost(request.url.host) ||
                                            DaznProxy.isDaznHost(Uri.parse(origin).host)
                                    val isPlaybackPreflight =
                                        request.url.host.equals("api.playback.indazn.com", ignoreCase = true) &&
                                                request.url.path.equals("/v5/Playback", ignoreCase = true)
                                    if (isDaznRequest && !isPlaybackPreflight) {
                                        val reqOrigin = origin.ifEmpty { "https://www.dazn.com" }
                                        val reqHeaders = request.requestHeaders["Access-Control-Request-Headers"] ?: "*"
                                        val corsHeaders = mapOf(
                                            "Access-Control-Allow-Origin" to reqOrigin,
                                            "Access-Control-Allow-Credentials" to "true",
                                            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, PUT, DELETE",
                                            "Access-Control-Allow-Headers" to reqHeaders,
                                            "Access-Control-Max-Age" to "86400"
                                        )
                                        return WebResourceResponse(
                                            "text/plain",
                                            "UTF-8",
                                            200,
                                            "OK",
                                            corsHeaders,
                                            ByteArrayInputStream(ByteArray(0))
                                        )
                                    }
                                }

                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString() ?: ""

                                // Interruttori e avviso di aggiornamento valgono solo dalla home: qualunque pagina
                                // poteva aprire "about:toggle_…" e chiudere la Wide
                                if (url.startsWith("about:toggle_") || url.startsWith(HomePage.UPDATE_DOWNLOAD) ||
                                    url.startsWith(HomePage.UPDATE_DISMISS)
                                ) {
                                    val fromHome = request?.isForMainFrame == true &&
                                            view?.url?.startsWith(HomePage.BASE_URL) == true
                                    if (!fromHome) return true
                                }

                                if (url.startsWith("about:toggle_reopen")) {
                                    toggleReopen()
                                    homeHtml = null
                                    carContext.finishCarApp()
                                    return true
                                }

                                if (url.startsWith(HomePage.UPDATE_DOWNLOAD)) {
                                    startUpdateDownload()
                                    return true
                                }

                                if (url.startsWith(HomePage.OPEN_MAIN)) {
                                    MainHandOver.openMainSoon(carContext)
                                    return true
                                }

                                if (url.startsWith(HomePage.UPDATE_DISMISS)) {
                                    UpdateManager.isBannerDismissed = true
                                    showHome()
                                    return true
                                }

                                if (url.startsWith(HomePage.SIDEBAR_SMALLER) || url.startsWith(HomePage.SIDEBAR_LARGER)) {
                                    AppSettings.stepWideSidebarSize(carContext, if (url.startsWith(HomePage.SIDEBAR_LARGER)) 1 else -1)
                                    applyVisibleArea()
                                    showHome()
                                    return true
                                }

                                if (url.startsWith("about:toggle_sidebar_fixed")) {
                                    toggleSidebarFixed()
                                    homeHtml = null
                                    carContext.finishCarApp()
                                    return true
                                }

                                // Link "intent://": usa l'indirizzo di ripiego invece di aprire un'app. Solo dalla
                                // pagina principale e solo verso indirizzi web: un iframe (es. una pubblicità) con
                                // ripiego "javascript:" faceva eseguire il suo codice nella pagina
                                if (url.startsWith("intent://")) {
                                    if (request?.isForMainFrame != true) return true
                                    try {
                                        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                                        val target = intent.getStringExtra("browser_fallback_url")
                                            ?.takeIf { UrlUtils.isWebUrl(it) }
                                            ?: intent.dataString?.takeIf { it.startsWith("https://") }
                                        if (target != null) {
                                            if (isDazn(target)) daznDesktopForced = true
                                            this@WideScreen.loadUrl(target)
                                        }
                                    } catch (e: Exception) {
                                        AppLog.w(TAG, "Intent parse error: ${e.javaClass.simpleName}")
                                    }
                                    return true
                                }

                                // Pagine pubblicitarie, e la pagina che va altrove subito dopo un pop-up: si resta qui
                                if (request?.isForMainFrame == true && !PopupGuard.allowsNavigation(
                                        url, pageHost, request.hasGesture(), request.isRedirect, popupAttempts
                                    )
                                ) {
                                    return true
                                }

                                // DAZN: le pagine evento funzionano solo in modalità desktop
                                if (isDazn(url) &&
                                    (url.contains("/watch/") || url.contains("/event/") || url.contains("/video/")) &&
                                    !daznDesktopForced
                                ) {
                                    daznDesktopForced = true
                                    this@WideScreen.loadUrl(url)
                                    return true
                                }

                                val scheme = request?.url?.scheme
                                val isWeb = scheme == "http" || scheme == "https"

                                // Il sito vuole l'altra identità (desktop o mobile): la richiesta deve partire già con lo
                                // user agent giusto, altrimenti il server manda la versione sbagliata (es. WhatsApp Web
                                // aperto dalla home). Qui arrivano solo le GET: ricaricarle non perde dati
                                if (isWeb && request?.isForMainFrame == true && view != null &&
                                    !url.startsWith(HomePage.BASE_URL) &&
                                    userAgentReloads < MAX_USER_AGENT_RELOADS &&
                                    needsDesktopForUrl(url) != WebViewScriptRouter.hasDesktopUserAgent(view)
                                ) {
                                    userAgentReloads++
                                    this@WideScreen.loadUrl(url)
                                    return true
                                }

                                // Blocca market:// e ogni altro schema non web
                                return !isWeb
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                pageHost = url?.let { Uri.parse(it).host }
                                val isHomePage = url?.startsWith(HomePage.BASE_URL) == true

                                // Nuova pagina (anche la home): il media della precedente non esiste più,
                                // e neanche le sue richieste in attesa di un avviso di certificato o di un permesso
                                mediaSessionManager.releaseMediaFocus()
                                sslPrompts.cancelAll()
                                sitePrompts.onPageStarted(url)
                                jsDialogScreen?.finish()

                                // Zoom disattivato solo sulla home
                                view?.settings?.setSupportZoom(!isHomePage)
                                view?.settings?.builtInZoomControls = true

                                // Applica modalità scura in base alle impostazioni
                                view?.settings?.let { applyDarkMode(it) }

                                // Autoplay: su DAZN sempre consentito, e su YouTube dopo il comando vocale "metti …"
                                if (!VoiceCommands.isYouTubeUrl(url)) voiceAutoplay = false
                                // Le pagine di CarWeb Media partono solo se glielo si chiede (tocco o comando vocale)
                                view?.settings?.mediaPlaybackRequiresUserGesture =
                                    !AppSettings.autoplayMedia.value && !isDazn(url) && !voiceAutoplay &&
                                            !LocalPlayer.isPlayerUrl(url)

                                // Uscendo da DAZN si torna alla modalità normale
                                if (!isHomePage && !isDazn(url)) daznDesktopForced = false

                                if (view != null && !isHomePage) {
                                    applyUserAgent(view, url)
                                    if (needsDesktopForUrl(url)) {
                                        val ua = view.settings.userAgentString.orEmpty()
                                        val chromeVersion = Regex("Chrome/([0-9.]+)")
                                            .find(ua)?.groups?.get(1)?.value ?: "152.0.0.0"
                                        view.evaluateJavascript(
                                            BrowserJavascript.getDesktopSpoofScript(chromeVersion),
                                            null
                                        )
                                    }
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (url.isNullOrEmpty()) return
                                userAgentReloads = 0
                                refreshAdBlockAction()

                                view?.evaluateJavascript(BrowserJavascript.getDisableDoubleTapScript(), null)

                                if (url.startsWith(HomePage.BASE_URL)) {
                                    lastUrl = HOME
                                } else {
                                    // Arriva anche per una navigazione annullata, con il suo indirizzo: quella non è la
                                    // pagina aperta
                                    if (view?.url == url) {
                                        lastUrl = url
                                        saveUrl(url)
                                    }

                                    // Fix e script per sito (Spotify, YouTube, chat, viewport, ...)
                                    view?.let {
                                        if (isDazn(url)) {
                                            it.evaluateJavascript(DaznManager.getClickInterceptorScript(), null)
                                        }
                                        // Velocità YouTube salvata lato Android, come nella Main
                                        if (url.contains("youtube.com", ignoreCase = true) &&
                                            !url.contains("music.youtube.com", ignoreCase = true)
                                        ) {
                                            val savedSpeed = AppSettings.getYouTubeSpeed(carContext)
                                            it.evaluateJavascript("localStorage.setItem('yt-custom-speed', '$savedSpeed');", null)
                                        }
                                        // Ricerca del comando vocale "metti …": parte il primo risultato
                                        if (VoiceCommandRouter.consumePlayFirstResult(VoiceCommandRouter.Target.WIDE, url)) {
                                            voiceAutoplay = true
                                            it.settings.mediaPlaybackRequiresUserGesture = false
                                            it.evaluateJavascript(YouTubeManager.getPlayFirstResultScript(), null)
                                        }
                                        val favorite = favoriteFor(url)
                                        WebViewScriptRouter.routeAndInject(
                                            webView = it,
                                            urlString = url,
                                            isYouTubeAdBlockEnabled = AdBlockSettings.isYouTubeEnabled.value,
                                            forceMaxYouTubeQuality = AppSettings.forceMaxYouTubeQuality.value,
                                            youtubeQuality = AppSettings.youtubeQuality.value,
                                            autoplayMedia = AppSettings.autoplayMedia.value || voiceAutoplay,
                                            displayScale = favorite?.mobileZoom ?: WIDE_DISPLAY_SCALE,
                                            desktopScale = favorite?.desktopZoom ?: WIDE_DESKTOP_SCALE,
                                            isDesktopMode = needsDesktopForUrl(url),
                                            isTabActive = true
                                        )
                                    }
                                }
                                sampleBackdrop(view)
                            }

                            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                if (handler == null || error == null) {
                                    handler?.cancel()
                                    return
                                }
                                sslPrompts.onSslError(handler, error)
                            }

                            // Se il processo di rendering muore, ricrea la WebView sulla stessa pagina
                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?
                            ): Boolean {
                                AppLog.e(TAG, "Render process gone (crash=${detail?.didCrash()})")
                                val now = SystemClock.uptimeMillis()
                                val recent = now - lastRenderCrash < 5000
                                lastRenderCrash = now
                                handler.post {
                                    releaseVirtualDisplay()
                                    if (!recent && surface?.isValid == true) {
                                        createVirtualDisplayAndPresentation()
                                    }
                                }
                                return true
                            }
                        }
                    }

                    webView = wv

                    // Misure, posizione e dimensione dei tasti: layoutSidebar
                    val sidebarContainer = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor("#1e1e1e".toColorInt())
                        gravity = Gravity.TOP
                    }
                    sidebarContainerReference = sidebarContainer

                    val btnSidebarStyle = { btn: ImageButton ->
                        btn.background = null
                        btn.scaleType = ImageView.ScaleType.FIT_CENTER
                    }

                    val btnHome = ImageButton(ctx).apply {
                        setImageBitmap(buildHomeBitmap())
                        btnSidebarStyle(this)
                        setOnClickListener { showHome() }
                    }
                    val btnBack = ImageButton(ctx).apply {
                        setImageBitmap(buildBackBitmap())
                        btnSidebarStyle(this)
                        setOnClickListener { goBack() }
                    }
                    // Il ricarica era in alto a destra: lì ora c'è lo scudo dell'AdBlock
                    val btnReload = ImageButton(ctx).apply {
                        setImageBitmap(buildReloadBitmap())
                        btnSidebarStyle(this)
                        setOnClickListener { webView?.reload() }
                    }
                    val btnSearch = ImageButton(ctx).apply {
                        setImageBitmap(buildSearchBitmap())
                        btnSidebarStyle(this)
                        setOnClickListener { openSearch() }
                    }
                    val btnVoice = ImageButton(ctx).apply {
                        setImageBitmap(buildMicBitmap())
                        btnSidebarStyle(this)
                        setOnClickListener { toggleVoiceCommand() }
                    }
                    sidebarVoiceButton = btnVoice

                    sidebarContainer.addView(btnHome)
                    sidebarContainer.addView(btnBack)
                    sidebarContainer.addView(btnReload)
                    sidebarContainer.addView(btnSearch)
                    sidebarContainer.addView(btnVoice)

                    frame.addView(
                        wv,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    frame.addView(sidebarContainer, FrameLayout.LayoutParams(sidebarWidthPx(), ViewGroup.LayoutParams.MATCH_PARENT))
                    frame.addView(popupOverlay, FrameLayout.LayoutParams(-1, -1))
                    frame.addView(keyboard.view, FrameLayout.LayoutParams(-1, -1))
                    frame.addView(driveLock, FrameLayout.LayoutParams(-1, -1))
                    // L'ascolto dei comandi vocali resta visibile anche sopra la copertura in movimento
                    frame.addView(voiceCard, FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.TOP })

                    setContentView(frame)
                    applyVisibleArea()
                    applyDriveLock()
                    loadInitial()
                }
            }

            presentation?.show()
        } catch (e: Exception) {
            Log.e(TAG, "Creazione display/presentation fallita", e)
        }
    }

    // ------------------------------------------------------------------
    // Comandi vocali (tasto microfono, "Ok Google, metti … su CarWeb")
    // ------------------------------------------------------------------

    /** Tasto microfono (sidebar o in alto): ascolta una frase e la esegue come comando (VoiceCommands). */
    private fun toggleVoiceCommand() {
        if (isVoiceListening) {
            stopVoiceCommand()
            return
        }
        val granted = ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            showVoiceToast(strings.getString(R.string.voice_command_mic_permission))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(carContext)) {
            showVoiceToast(strings.getString(R.string.voice_command_unavailable))
            return
        }
        val recognizer = voiceRecognizer ?: SpeechRecognizer.createSpeechRecognizer(carContext).also { voiceRecognizer = it }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        isVoiceListening = true
        showVoiceCard(voiceHintText())
        try {
            recognizer.cancel()
            recognizer.setRecognitionListener(voiceListener)
            recognizer.startListening(intent)
        } catch (e: Exception) {
            AppLog.e(TAG, "Voice command: startListening failed", e)
            isVoiceListening = false
            hideVoiceCard()
            showVoiceToast(strings.getString(R.string.voice_command_unavailable))
        }
    }

    private fun stopVoiceCommand() {
        isVoiceListening = false
        hideVoiceCard()
        try {
            voiceRecognizer?.cancel()
        } catch (_: Exception) {}
    }

    private val voiceListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            // Annullato da noi (secondo tocco sul microfono, Wide chiusa): niente avvisi
            if (!isVoiceListening) return
            isVoiceListening = false
            hideVoiceCard()
            val message = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_command_mic_permission
                SpeechRecognizer.ERROR_CLIENT -> R.string.voice_command_unavailable
                else -> R.string.voice_command_not_heard
            }
            showVoiceToast(strings.getString(message))
        }

        override fun onResults(results: Bundle?) {
            if (!isVoiceListening) return
            isVoiceListening = false
            hideVoiceCard()
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                .filter { it.isNotBlank() }
            if (texts.isEmpty()) {
                showVoiceToast(strings.getString(R.string.voice_command_not_heard))
            } else {
                runVoiceCommand(VoiceCommands.parse(texts))
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull { it.isNotBlank() }
                ?.let {
                    voiceDetailReference?.text = it
                    CarMediaService.showVoiceListening(true, it)
                }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * Esegue un comando vocale (anche quelli di "Ok Google, metti … su CarWeb"). Il piano è lo stesso della
     * Main; qui la ricerca segue il sito aperto come la lente (SiteSearch) e "portami a …" usa il navigatore.
     */
    private fun runVoiceCommand(command: VoiceCommands.Command) {
        val from = (webView?.url ?: lastUrl)?.takeIf { it != HOME && !it.startsWith(HomePage.BASE_URL) }
        val plan = VoiceCommands.plan(
            command,
            from,
            favorites(),
            webSearchUrl = { query ->
                strings.getString(AppSettings.effectiveSearchEngine.baseUrlRes) + Uri.encode(query)
            },
            siteSearchUrl = { query -> SiteSearch.resolve(strings, from, query) },
            playerOwned = MediaFocusManager.getActiveSource() != null
        )
        AppLog.d(VoiceCommandRouter.DEBUG_TAG, "Wide executes $command -> ${plan::class.simpleName}")
        // Con la Wide dietro (tasto 🎤 del player, doppio tocco su ⏮, assistente) si riporta davanti: si vede quello
        // che è stato chiesto e la pagina può far partire il video (una pagina nascosta non avvia l'audio da sola).
        // I comandi del player vanno anche così
        if (plan !is VoiceCommands.Plan.Player && !isWideResumed) bringToFront()
        when (plan) {
            is VoiceCommands.Plan.Load -> {
                // "metti …" e i video condivisi dal telefono partono da soli anche con la riproduzione automatica spenta
                if (plan.playFirst || plan.autoplay) {
                    voiceAutoplay = true
                    webView?.settings?.mediaPlaybackRequiresUserGesture = false
                }
                if (plan.playFirst) VoiceCommandRouter.requestPlayFirstResult(VoiceCommandRouter.Target.WIDE)
                hideCustomView()
                loadUrl(plan.url)
                val message = when (plan.kind) {
                    VoiceCommands.LoadKind.YOUTUBE -> strings.getString(R.string.voice_command_youtube, plan.label)
                    VoiceCommands.LoadKind.YOUTUBE_MUSIC -> strings.getString(R.string.voice_command_youtube_music, plan.label)
                    VoiceCommands.LoadKind.SEARCH -> strings.getString(R.string.voice_command_search, plan.label)
                    VoiceCommands.LoadKind.SITE ->
                        strings.getString(R.string.voice_command_site, plan.siteName.orEmpty(), plan.label)
                    VoiceCommands.LoadKind.PAGE -> strings.getString(R.string.voice_command_open, plan.label)
                    VoiceCommands.LoadKind.LINK -> strings.getString(R.string.share_to_car_opened, plan.label)
                }
                showVoiceToast(message)
            }
            is VoiceCommands.Plan.Navigate -> startNavigation(plan.destination)
            // Media, TV e Radio: la richiesta la prende la pagina, quando si apre o avvisata qui se è già aperta (MediaVoice)
            is VoiceCommands.Plan.Media -> {
                MediaVoice.submit(plan, VoiceCommandRouter.Target.WIDE)
                val view = webView
                if (view != null && VoiceCommands.mediaSectionOf(view.url) == plan.section) {
                    view.evaluateJavascript(MediaVoice.CHECK_SCRIPT, null)
                } else {
                    hideCustomView()
                    loadUrl(VoiceCommands.mediaSectionUrl(plan.section))
                }
                MediaVoice.message(strings, plan)?.let { showVoiceToast(it) }
            }
            VoiceCommands.Plan.Back -> goBack()
            VoiceCommands.Plan.Reload -> webView?.reload()
            VoiceCommands.Plan.Home -> showHome()
            is VoiceCommands.Plan.Player -> CarMediaService.sendPlayerCommand(plan.action)
        }
    }

    /**
     * "Portami a …": le indicazioni di Google Maps nella pagina, come nella Main, e la destinazione al navigatore di
     * Android Auto. Da sola la richiesta non bastava (prova dell'utente): la Wide è un'app di navigazione, quindi
     * Android Auto può ridarla alla Wide stessa (WideSession.onNewIntent) invece che a Maps o Waze. Se invece la
     * passa al navigatore, la navigazione vera prende il posto della Wide.
     */
    private fun startNavigation(destination: String) {
        hideCustomView()
        loadUrl(VoiceCommands.mapsDirectionsUrl(destination))
        showVoiceToast(strings.getString(R.string.voice_command_navigate, destination))
        try {
            carContext.startCarApp(
                Intent(CarContext.ACTION_NAVIGATE, Uri.parse(VoiceCommands.navigationGeoUri(destination)))
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "Voice command: Android Auto navigation not started: ${e.message}")
        }
    }

    private fun bringToFront() {
        try {
            carContext.startCarApp(
                Intent().setComponent(ComponentName(carContext, CarServiceWide::class.java))
                    .putExtra(MainHandOver.EXTRA_FROM_WIDE, true)
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "Voice command: Wide not brought to front: ${e.message}")
        }
    }

    private fun showVoiceToast(message: String) {
        try {
            CarToast.makeText(carContext, message, CarToast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            AppLog.w(TAG, "Voice command toast not shown: ${e.message}")
        }
    }

    // Esempi; col player di Android Auto attivo anche il doppio tocco su ⏮ al volante, che apre il microfono
    private fun voiceHintText(): String {
        val hint = strings.getString(R.string.voice_command_hint)
        if (!AppSettings.mediaPlayerEnabled.value) return hint
        return hint + "\n" + strings.getString(R.string.voice_command_hint_wheel)
    }

    // Anche il player di Android Auto mostra "In ascolto…": si vede pure con la Wide dietro (doppio tocco su ⏮)
    private fun showVoiceCard(detail: String) {
        voiceDetailReference?.text = detail
        updateVoiceCardPosition()
        voiceCardReference?.visibility = View.VISIBLE
        sidebarVoiceButton?.setColorFilter("#EF4444".toColorInt())
        CarMediaService.showVoiceListening(true)
    }

    private fun hideVoiceCard() {
        voiceCardReference?.visibility = View.GONE
        sidebarVoiceButton?.clearColorFilter()
        CarMediaService.showVoiceListening(false)
    }

    // In alto nell'area libera dall'interfaccia di Android Auto, larga quanto l'area meno i margini
    private fun updateVoiceCardPosition() {
        val card = voiceCardReference ?: return
        val lp = card.layoutParams as? FrameLayout.LayoutParams ?: return
        val r = visibleArea?.takeUnless { it.isEmpty } ?: Rect(0, 0, surfaceWidth, surfaceHeight)
        val margin = (16 * card.resources.displayMetrics.density).toInt()
        lp.gravity = Gravity.TOP or Gravity.START
        lp.width = (r.width() - 2 * margin).coerceAtLeast(0)
        lp.leftMargin = r.left + margin
        lp.topMargin = r.top + margin
        card.layoutParams = lp
    }

    /**
     * Dettatura nel campo della pagina, con lo stesso script della tastiera: il campo si memorizza prima di
     * ascoltare, poi il testo dettato va in fondo a quello che c'è (vedi getSetInputTextScript, che negli editor
     * come quello di WhatsApp Web scrive come la tastiera del browser: textContent e un Event finto li ignorano).
     */
    private fun startVoiceListening(context: Context, targetWebView: WebView?, btnMic: Button) {
        val wv = targetWebView ?: return
        wv.evaluateJavascript(BrowserJavascript.getCaptureInputTargetScript()) { result ->
            AppLog.d(TAG, "Voice input: field captured=${parseCapturedField(result) != null}")
            listenForSpeech(context, wv, btnMic)
        }
    }

    private fun listenForSpeech(context: Context, targetWebView: WebView, btnMic: Button) {
        try {
            val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            }

            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    val bg = btnMic.background
                    if (bg is GradientDrawable) {
                        bg.setColor("#34D399".toColorInt())
                    }
                }


                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    val bg = btnMic.background
                    if (bg is GradientDrawable) {
                        bg.setColor("#EF4444".toColorInt())
                    }
                }
                override fun onError(error: Int) {
                    val bg = btnMic.background
                    if (bg is GradientDrawable) {
                        bg.setColor("#EF4444".toColorInt())
                    }
                    try { speechRecognizer.destroy() } catch (_: Exception) {}
                }
                override fun onResults(results: Bundle?) {
                    val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!spokenText.isNullOrEmpty()) {
                        // Lo script rimette il focus sul campo: senza questo il popup si riaprirebbe subito
                        suppressInputPopupUntil = SystemClock.uptimeMillis() + INPUT_POPUP_SUPPRESS_MS
                        targetWebView.evaluateJavascript(BrowserJavascript.getSetInputTextScript(spokenText, append = true)) { result ->
                            AppLog.d(TAG, "Voice input written: $result")
                        }
                    }
                    try { speechRecognizer.destroy() } catch (_: Exception) {}
                    handler.post {
                        popupOverlayReference?.visibility = View.GONE
                        isInputPopupVisible = false
                    }
                }
                // Metodi mancanti aggiunti qui
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            AppLog.e(TAG, "SpeechRecognizer error", e)
        }
    }

    private fun releaseVirtualDisplay() {
        mediaSessionManager.disconnect()
        sslPrompts.cancelAll()
        sitePrompts.cancelAll()
        jsDialogScreen?.finish()
        handler.removeCallbacks(hidePopupRunnable)
        handler.removeCallbacks(applyAreaRunnable)
        handler.removeCallbacks(applyAreaSettledRunnable)
        popupOverlayReference = null
        sidebarContainerReference = null
        passengerKeyboard = null
        closeKeyboardOnBack.isEnabled = false
        driveLockReference = null
        driveLockCardReference = null
        voiceCardReference = null
        voiceDetailReference = null
        sidebarVoiceButton = null
        isInputPopupVisible = false

        // Esce dal video a schermo intero prima di distruggere la WebView
        try {
            customViewCallback?.onCustomViewHidden()
        } catch (_: Exception) {
        }
        customView = null
        customViewCallback = null
        backdrop = null

        try {
            webView?.url?.let {
                if (it.isNotEmpty()) lastUrl = if (it.startsWith(HomePage.BASE_URL)) HOME else it
            }
            webView?.stopLoading()
            webView?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "release webView", e)
        }
        webView = null

        try {
            presentation?.dismiss()
        } catch (e: Exception) {
            Log.w(TAG, "release presentation", e)
        }
        presentation = null

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "release virtualDisplay", e)
        }
        virtualDisplay = null
    }

    // ------------------------------------------------------------------
    // Icone disegnate a codice (maschere nere con tint scelto dall'host)
    // ------------------------------------------------------------------

    private fun maskIcon(draw: (Canvas, Paint, Float) -> Unit): CarIcon {
        val size = 96
        val bmp = createBitmap(size, size)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = size * 0.09f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        draw(canvas, paint, size.toFloat())
        return CarIcon.Builder(IconCompat.createWithBitmap(bmp))
            .setTint(CarColor.DEFAULT)
            .build()
    }

    // Ricarica: tasto in basso (senza sidebar) e nella sidebar fissa, stesso disegno
    private fun buildReloadIcon(): CarIcon = maskIcon(::drawReload)

    private fun buildReloadBitmap(): Bitmap = maskBitmap(::drawReload)

    private fun drawReload(c: Canvas, p: Paint, s: Float) {
        val cx = s / 2f
        val cy = s / 2f
        val r = s * 0.30f
        c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -60f, 290f, false, p)

        val a = Math.toRadians(-60.0)
        val px = cx + r * cos(a).toFloat()
        val py = cy + r * sin(a).toFloat()
        val fill = Paint(p).apply { style = Paint.Style.FILL }
        val head = Path().apply {
            moveTo(px + s * 0.14f, py - s * 0.02f)
            lineTo(px - s * 0.02f, py - s * 0.14f)
            lineTo(px - s * 0.04f, py + s * 0.10f)
            close()
        }
        c.drawPath(head, fill)
    }

    // Scudo dell'AdBlock: con la spunta attivo sul sito, barrato se il sito è in whitelist
    private fun buildShieldIcon(crossed: Boolean): CarIcon = maskIcon { c, p, s ->
        val shield = Path().apply {
            moveTo(s * 0.50f, s * 0.16f)
            lineTo(s * 0.78f, s * 0.27f)
            lineTo(s * 0.78f, s * 0.48f)
            quadTo(s * 0.78f, s * 0.72f, s * 0.50f, s * 0.84f)
            quadTo(s * 0.22f, s * 0.72f, s * 0.22f, s * 0.48f)
            lineTo(s * 0.22f, s * 0.27f)
            close()
        }
        c.drawPath(shield, p)
        if (crossed) {
            c.drawLine(s * 0.16f, s * 0.84f, s * 0.84f, s * 0.16f, p)
        } else {
            val check = Path().apply {
                moveTo(s * 0.37f, s * 0.50f)
                lineTo(s * 0.47f, s * 0.60f)
                lineTo(s * 0.64f, s * 0.41f)
            }
            c.drawPath(check, p)
        }
    }

    // Microfono dei comandi vocali: tasto in alto (senza sidebar) e nella sidebar fissa, stesso disegno
    private fun buildMicBitmap(): Bitmap = maskBitmap(::drawMic)

    private fun drawMic(c: Canvas, p: Paint, s: Float) {
        c.drawRoundRect(RectF(s * 0.38f, s * 0.14f, s * 0.62f, s * 0.56f), s * 0.12f, s * 0.12f, p)
        c.drawArc(RectF(s * 0.26f, s * 0.30f, s * 0.74f, s * 0.70f), 0f, 180f, false, p)
        c.drawLine(s * 0.50f, s * 0.70f, s * 0.50f, s * 0.84f, p)
        c.drawLine(s * 0.36f, s * 0.84f, s * 0.64f, s * 0.84f, p)
    }

    private fun buildSearchIcon(): CarIcon = maskIcon { c, p, s ->
        val cx = s * 0.42f
        val cy = s * 0.42f
        val r = s * 0.22f
        c.drawCircle(cx, cy, r, p)
        val d = r * 0.72f
        c.drawLine(cx + d, cy + d, s * 0.78f, s * 0.78f, p)
    }

    private fun maskBitmap(draw: (Canvas, Paint, Float) -> Unit): Bitmap {
        val size = 96
        val bmp = createBitmap(size, size)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = size * 0.08f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        draw(canvas, paint, size.toFloat())
        return bmp
    }

    private fun buildHomeBitmap(): Bitmap = maskBitmap { c, p, s ->
        val roof = Path().apply {
            moveTo(s * 0.18f, s * 0.48f)
            lineTo(s * 0.50f, s * 0.20f)
            lineTo(s * 0.82f, s * 0.48f)
        }
        c.drawPath(roof, p)
        val body = Path().apply {
            moveTo(s * 0.28f, s * 0.44f)
            lineTo(s * 0.28f, s * 0.78f)
            lineTo(s * 0.72f, s * 0.78f)
            lineTo(s * 0.72f, s * 0.44f)
        }
        c.drawPath(body, p)
    }

    private fun buildBackBitmap(): Bitmap = maskBitmap { c, p, s ->
        val path = Path().apply {
            moveTo(s * 0.65f, s * 0.25f)
            lineTo(s * 0.35f, s * 0.50f)
            lineTo(s * 0.65f, s * 0.75f)
        }
        c.drawPath(path, p)
    }

    private fun buildSearchBitmap(): Bitmap = maskBitmap { c, p, s ->
        val cx = s * 0.42f
        val cy = s * 0.42f
        val r = s * 0.22f
        c.drawCircle(cx, cy, r, p)
        val d = r * 0.72f
        c.drawLine(cx + d, cy + d, s * 0.78f, s * 0.78f, p)
    }

    private fun buildHomeIcon(): CarIcon = maskIcon { c, p, s ->
        val roof = Path().apply {
            moveTo(s * 0.18f, s * 0.48f)
            lineTo(s * 0.50f, s * 0.20f)
            lineTo(s * 0.82f, s * 0.48f)
        }
        c.drawPath(roof, p)
        val body = Path().apply {
            moveTo(s * 0.28f, s * 0.44f)
            lineTo(s * 0.28f, s * 0.78f)
            lineTo(s * 0.72f, s * 0.78f)
            lineTo(s * 0.72f, s * 0.44f)
        }
        c.drawPath(body, p)
    }
}