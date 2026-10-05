package com.fcaronte.aabrowser.car

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.content.Intent
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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.car.app.AppManager
import androidx.car.app.CarContext
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
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.fcaronte.aabrowser.mediaservice.MediaSessionManager
import com.fcaronte.aabrowser.model.FavoritesRepository
import com.fcaronte.aabrowser.settings.AdBlockSettings
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.utils.AdBlockHost
import com.fcaronte.aabrowser.utils.AppLog
import com.fcaronte.aabrowser.utils.BrowserJavascript
import com.fcaronte.aabrowser.utils.DaznManager
import com.fcaronte.aabrowser.utils.DaznProxy
import com.fcaronte.aabrowser.utils.GoogleLoginManager
import com.fcaronte.aabrowser.utils.WebViewScriptRouter
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

    private companion object {
        const val TAG = "WideScreen"
        const val HOME = "about:home"

        // Scale passate a WebViewScriptRouter.routeAndInject (1.0 = nessuna modifica).
        // Le scale dell'app principale sono pensate per il telefono: qui si regolano a parte.
        const val WIDE_DISPLAY_SCALE = 1.0f
        const val WIDE_DESKTOP_SCALE = 1.0f

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

    private var popupOverlayReference: FrameLayout? = null
    private var isInputPopupVisible = false
    private val hidePopupRunnable = Runnable {
        popupOverlayReference?.visibility = View.GONE
        isInputPopupVisible = false
    }

    private var surfaceWidth = 800
    private var surfaceHeight = 480
    private var surfaceDensity = 160

    private var visibleArea: Rect? = null
    private var lastUrl: String? = null

    @Volatile
    private var homeHtml: String? = null

    private val mediaSessionManager = MediaSessionManager(carContext)
    private val handler = Handler(Looper.getMainLooper())

    private var downTime = 0L
    private val iconPan: CarIcon = CarIcon.PAN
    private val iconHome: CarIcon by lazy { buildHomeIcon() }
    private val iconSearch: CarIcon by lazy { buildSearchIcon() }
    private val iconReload: CarIcon by lazy { buildReloadIcon() }

    init {
        try {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)

            val navManager = carContext.getCarService(NavigationManager::class.java)
            navManager.setNavigationManagerCallback(object : NavigationManagerCallback {
                override fun onStopNavigation() {
                    closeApp()
                }

                override fun onAutoDriveEnabled() {}
            })
            navManager.navigationStarted()
        } catch (e: Exception) {
            Log.e(TAG, "Init failed", e)
        }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                releaseVirtualDisplay()
                mediaSessionManager.disconnect()
                try {
                    webView?.destroy()
                } catch (_: Exception) {}
                webView = null
            }
        })
    }

    // ------------------------------------------------------------------
    // Template
    // ------------------------------------------------------------------

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(iconHome)
                    .setOnClickListener { showHome() }
                    .build()
            )
            .build()

        val mapStrip = ActionStrip.Builder()
            .addAction(Action.Builder(Action.PAN).setIcon(iconPan).build())
            .addAction(
                Action.Builder()
                    .setIcon(CarIcon.BACK)
                    .setOnClickListener {
                        if (customView != null) {
                            hideCustomView()
                        } else {
                            webView?.let { if (it.canGoBack()) it.goBack() }
                        }
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(iconSearch)
                    .setOnClickListener { openSearch() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(iconReload)
                    .setOnClickListener { webView?.reload() }
                    .build()
            )
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setMapActionStrip(mapStrip)
            .build()
    }

    // ------------------------------------------------------------------
    // Navigazione: home HTML, ricerca, URL
    // ------------------------------------------------------------------

    private fun buildHomeHtml(): String {
        val items = try {
            FavoritesRepository(carContext).loadFavorites().map { HomePage.Item(it.name, it.url) }
        } catch (e: Exception) {
            Log.e(TAG, "Errore lettura preferiti", e)
            emptyList()
        }
        return HomePage.build(carContext, items, reopenEnabled())
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

    /** Lente: cerca sul sito che stai guardando (YouTube, Amazon, ...), altrimenti su Google. */
    private fun openSearch() {
        val from = (webView?.url ?: lastUrl)
            ?.takeIf { it != HOME && !it.startsWith(HomePage.BASE_URL) }
        val target = SiteSearch.targetFor(from)
        screenManager.push(WideSearchScreen(carContext, "Cerca su ${target.label}") { query ->
            loadUrl(SiteSearch.resolve(from, query))
        })
    }

    /** Tastiera dal popup dei campi di testo: scrive nel campo selezionato della pagina. */
    private fun openFieldInput() {
        screenManager.push(WideSearchScreen(carContext, "Scrivi nel campo") { text ->
            webView?.evaluateJavascript(BrowserJavascript.getInjectTextScript(text), null)

            // Forza il focus non subito, ma con un delay per permettere
            // alla WebView di "riprendersi" dalla chiusura della WideSearchScreen
            webView?.postDelayed({
                webView?.requestFocus()
            }, 300)
        })
    }

    private fun loadUrl(url: String) {
        lastUrl = url
        webView?.let {
            applyUserAgent(it, url)
            it.loadUrl(url)
        }
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

    private fun needsDesktopForUrl(url: String?): Boolean =
        WebViewScriptRouter.isDesktopRequired(url) || (daznDesktopForced && isDazn(url))

    private fun applyUserAgent(view: WebView, url: String?) {
        if (url != null && url.startsWith(HomePage.BASE_URL)) return
        if (needsDesktopForUrl(url)) {
            WebViewScriptRouter.setDesktopUserAgent(view, carContext)
        } else {
            WebViewScriptRouter.setMobileUserAgent(view, carContext)
        }
    }

    // ------------------------------------------------------------------
    // Video a schermo intero
    // ------------------------------------------------------------------

    private fun applyMargins(target: View, source: FrameLayout.LayoutParams?) {
        val lp = (target.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        if (source != null) {
            lp.leftMargin = source.leftMargin
            lp.rightMargin = source.rightMargin
            lp.topMargin = source.topMargin
            lp.bottomMargin = source.bottomMargin
        }
        target.layoutParams = lp
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
        applyMargins(view, webView?.layoutParams as? FrameLayout.LayoutParams)
        popupOverlayReference?.bringToFront()
        customView = view
        customViewCallback = callback
    }

    private fun hideCustomView() {
        val view = customView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        customView = null
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
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        try {
            virtualDisplay?.surface = null
        } catch (_: Exception) {}
        surface = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
        handler.post { applyVisibleArea() }
    }

    override fun onStableAreaChanged(stableArea: Rect) {}



    private fun applyVisibleArea() {
        val wv = webView ?: return

        val lp = (wv.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.leftMargin = 0
        lp.rightMargin = 0
        lp.topMargin = 0
        lp.bottomMargin = 0

        wv.layoutParams = lp
        wv.requestLayout()
        customView?.let { applyMargins(it, lp) }
        updatePopupPosition()
    }

    private fun updatePopupPosition() {
        val overlay = popupOverlayReference ?: return
        val r = visibleArea
        if (r == null || r.isEmpty) return

        val card = overlay.getChildAt(0) as? LinearLayout ?: return
        val lp = card.layoutParams as? FrameLayout.LayoutParams ?: return

        val centerX = r.left + r.width() / 2
        val centerY = r.top + r.height() / 2

        lp.gravity = Gravity.TOP or Gravity.START
        card.post {
            lp.leftMargin = centerX - card.width / 2
            lp.topMargin = centerY - card.height / 2
            card.layoutParams = lp
        }
    }

    override fun onClick(x: Float, y: Float) {
        if (isInputPopupVisible) {
            dispatchTouchToPopup(MotionEvent.ACTION_DOWN, x, y)
            dispatchTouchToPopup(MotionEvent.ACTION_UP, x, y)
            return
        }
        dispatchTouch(MotionEvent.ACTION_DOWN, x, y)
        dispatchTouch(MotionEvent.ACTION_UP, x, y)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (isInputPopupVisible || customView != null) return
        webView?.scrollBy(distanceX.toInt(), distanceY.toInt())
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (isInputPopupVisible || customView != null) return
        webView?.flingScroll(-velocityX.toInt(), -velocityY.toInt())
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        // Niente zoom sulla home o zoom improvviso (doppio tap to zoom dell'host Android Auto)
        if (isInputPopupVisible || customView != null || isHome()) return

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

            val area = visibleArea
            val lx = x - (area?.left ?: 0)
            val ly = y

            val event = MotionEvent.obtain(downTime, now, action, lx, ly, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            target.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun dispatchTouchToPopup(action: Int, x: Float, y: Float) {
        handler.post {
            val overlay = popupOverlayReference ?: return@post
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

                    // Popup compatto (tastiera e microfono)
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
                        setPadding(16, 16, 16, 16) // Più compatto
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            setColor("#202124".toColorInt()) // Torniamo al tuo colore originale, più scuro
                            cornerRadius = 20f
                            setStroke(1, "#44464A".toColorInt()) // Bordo più sottile
                        }
                    }

                    val btnStyle = { btn: Button, color: String ->
                        btn.textSize = 18f
                        btn.setTextColor(Color.WHITE)
                        btn.background = GradientDrawable().apply {
                            setColor(color.toColorInt())
                            cornerRadius = 16f
                        }
                        btn.setPadding(32, 16, 32, 16) // Padding ridotto
                    }

/*                  // Disable keyboard for now, i can't manage it correctly
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
*/

                    val btnMic = Button(ctx).apply {
                        text = "🎤"
                        btnStyle(this, "#EF4444")
                        setOnClickListener {
                            handler.removeCallbacks(hidePopupRunnable)
                            // Qui ora passi correttamente il riferimento al bottone
                            startVoiceListening(ctx, webView, this)
                        }
                    }

                    // Disable keyboard for now, i can't manage it correctly
//                    inputCard.addView(btnKeyboard, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 24 })
                    inputCard.addView(btnMic, LinearLayout.LayoutParams(-2, -2))

                    val cardLp = FrameLayout.LayoutParams(-2, -2).apply {
                        gravity = Gravity.CENTER
                    }
                    popupOverlay.addView(inputCard, cardLp)

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
                        }

                        // Script all'inizio di ogni pagina. L'AdBlock di YouTube NON va qui:
                        // lo inietta WebViewScriptRouter solo sui domini YouTube.
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                            WebViewCompat.addDocumentStartJavaScript(this, BrowserJavascript.getLifecycleAndMetadataScript(), setOf("*"))
                            WebViewCompat.addDocumentStartJavaScript(this, GoogleLoginManager.getGoogleOauthFixScript(), setOf("*"))
                            WebViewCompat.addDocumentStartJavaScript(this, GoogleLoginManager.getPopupInterceptorScript(), setOf("*"))
                            WebViewCompat.addDocumentStartJavaScript(this, DaznManager.getAuthProxyScript(), setOf("https://www.dazn.com"))
                            // Aggiungi anche lo script per salvare l'elemento attivo
                            WebViewCompat.addDocumentStartJavaScript(this, BrowserJavascript.getAutoSaveActiveElementScript(), setOf("*"))
                            // Blocca il doppio tap to zoom in tutte le pagine
                            WebViewCompat.addDocumentStartJavaScript(this, BrowserJavascript.getDisableDoubleTapScript(), setOf("*"))
                        }

                        addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                @Suppress("unused")
                                fun onVideoStarted(time: Float) {
                                    @Suppress("DEPRECATION")
                                    mediaSessionManager.updatePlaybackState(
                                        PlaybackStateCompat.STATE_PLAYING,
                                        (time * 1000).toLong(),
                                        1.0f
                                    )
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun onMediaTimeUpdate(time: Float, speed: Float, isPlaying: Boolean) {
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
                                    @Suppress("DEPRECATION")
                                    mediaSessionManager.updatePlaybackState(
                                        if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                                        (time * 1000).toLong(),
                                        speed
                                    )
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun updateMediaMetadata(title: String, artist: String, albumArtUrl: String, duration: Float) {
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
                                    carContext, urlString, method, headersJson, body, requestUserAgent
                                )

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
                                        popupOverlay.visibility = View.VISIBLE
                                        isInputPopupVisible = true
                                        updatePopupPosition()
                                        handler.removeCallbacks(hidePopupRunnable)
                                        handler.postDelayed(hidePopupRunnable, 3000)
                                    }
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun injectText(text: String) {
                                    post {
                                        evaluateJavascript(BrowserJavascript.getInjectTextScript(text), null)
                                    }
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun openInNewTab(url: String) {
                                    post { this@WideScreen.loadUrl(url) }
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun openLinkInNewTab(url: String) {
                                    post { this@WideScreen.loadUrl(url) }
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun openPopup(url: String) {
                                    post { this@WideScreen.loadUrl(url) }
                                }
                            },
                            "AndroidBridge"
                        )

                        webChromeClient = object : WebChromeClient() {
                            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                if (view == null || callback == null) return
                                showCustomView(view, callback)
                            }

                            override fun onHideCustomView() {
                                hideCustomView()
                            }

                            // Solo DRM (Netflix, Spotify, ...). Microfono e fotocamera restano negati.
                            override fun onPermissionRequest(request: PermissionRequest?) {
                                val allowed = request?.resources
                                    ?.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                                    ?.toTypedArray()
                                if (allowed.isNullOrEmpty()) request?.deny() else request.grant(allowed)
                            }
                        }

                        webViewClient = @SuppressLint("MissingOnRenderProcessGone")
                        object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val urlString = request?.url?.toString() ?: ""

                                if (urlString == HomePage.BASE_URL) {
                                    val html = homeHtml ?: buildHomeHtml().also { homeHtml = it }
                                    return WebResourceResponse(
                                        "text/html",
                                        "UTF-8",
                                        ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
                                    )
                                }

                                if (AdBlockHost.shouldBlock(urlString)) {
                                    return WebResourceResponse(
                                        "text/plain",
                                        "utf-8",
                                        ByteArrayInputStream("".toByteArray())
                                    )
                                }
                                // DAZN: risposta alle richieste CORS "OPTIONS" (solo per i domini DAZN)
                                if (request != null && request.method.equals("OPTIONS", ignoreCase = true)) {
                                    val origin = request.requestHeaders["Origin"] ?: ""
                                    val isDaznRequest = isDazn(urlString) || isDazn(origin)
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

                                if (url.startsWith("about:toggle_reopen")) {
                                    toggleReopen()
                                    homeHtml = null
                                    showHome()
                                    return true
                                }

                                // Link "intent://": usa l'indirizzo di ripiego invece di aprire un'app
                                if (url.startsWith("intent://")) {
                                    try {
                                        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                                        val fallback = intent.getStringExtra("browser_fallback_url")
                                        val target = if (!fallback.isNullOrEmpty()) {
                                            fallback
                                        } else {
                                            intent.dataString?.takeIf { it.startsWith("https://") }
                                        }
                                        if (!target.isNullOrEmpty()) {
                                            if (isDazn(target)) daznDesktopForced = true
                                            this@WideScreen.loadUrl(target)
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Intent parse error", e)
                                    }
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

                                // Blocca market:// e ogni altro schema non web
                                val scheme = request?.url?.scheme
                                return scheme != "http" && scheme != "https"
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                val isHomePage = url?.startsWith(HomePage.BASE_URL) == true

                                // Zoom disattivato solo sulla home
                                view?.settings?.setSupportZoom(!isHomePage)
                                view?.settings?.builtInZoomControls = true

                                // Autoplay: su DAZN sempre consentito
                                view?.settings?.mediaPlaybackRequiresUserGesture =
                                    !AppSettings.autoplayMedia.value && !isDazn(url)

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

                                view?.evaluateJavascript(BrowserJavascript.getDisableDoubleTapScript(), null)

                                if (url.startsWith(HomePage.BASE_URL)) {
                                    lastUrl = HOME
                                } else {
                                    lastUrl = url
                                    saveUrl(url)

                                    // Fix e script per sito (Spotify, YouTube, chat, viewport, ...)
                                    view?.let {
                                        if (isDazn(url)) {
                                            it.evaluateJavascript(DaznManager.getClickInterceptorScript(), null)
                                        }
                                        WebViewScriptRouter.routeAndInject(
                                            webView = it,
                                            urlString = url,
                                            isYouTubeAdBlockEnabled = AdBlockSettings.isYouTubeEnabled.value,
                                            autoplayMedia = AppSettings.autoplayMedia.value,
                                            displayScale = WIDE_DISPLAY_SCALE,
                                            desktopScale = WIDE_DESKTOP_SCALE,
                                            isDesktopMode = needsDesktopForUrl(url),
                                            isTabActive = true
                                        )
                                    }
                                }
                                sampleBackdrop(view)
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
                    frame.addView(
                        wv,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )

                    frame.addView(popupOverlay, FrameLayout.LayoutParams(-1, -1))

                    setContentView(frame)
                    applyVisibleArea()
                    loadInitial()
                }
            }

            presentation?.show()
        } catch (e: Exception) {
            Log.e(TAG, "Creazione display/presentation fallita", e)
        }
    }

    private fun startVoiceListening(context: Context, targetWebView: WebView?, btnMic: Button) {
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
                    if (!spokenText.isNullOrEmpty() && targetWebView != null) {
                        targetWebView.evaluateJavascript(BrowserJavascript.getInjectTextScript(spokenText), null)
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
        handler.removeCallbacks(hidePopupRunnable)
        popupOverlayReference = null
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

    private fun buildReloadIcon(): CarIcon = maskIcon { c, p, s ->
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

    private fun buildSearchIcon(): CarIcon = maskIcon { c, p, s ->
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