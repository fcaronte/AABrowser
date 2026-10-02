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
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.model.FavoritesRepository
import com.fcaronte.aabrowser.settings.AdBlockSettings
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.utils.AdBlockHost
import com.fcaronte.aabrowser.utils.AdBlockJavascript
import com.fcaronte.aabrowser.utils.AppLog
import com.fcaronte.aabrowser.utils.BrowserJavascript
import com.fcaronte.aabrowser.utils.GoogleLoginManager
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Schermata "wide" (finto navigatore).
 * Indipendente dall'app principale: condivide solo gli script JavaScript (adblock, login, ecc.).
 * Richiede WideHomeAndSearch.kt (HomePage, SiteSearch, WideSearchScreen) nello stesso package.
 */
class WideScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private companion object {
        const val TAG = "WideScreen"
        const val HOME = "about:home"

        // Legge il colore di sfondo della pagina per colorare le fasce libere attorno alla WebView
        const val BACKDROP_JS =
            "(function(){var b=document.body,d=document.documentElement;" +
                    "var c=b?getComputedStyle(b).backgroundColor:'';" +
                    "if(!c||c==='transparent'||c==='rgba(0, 0, 0, 0)'){c=getComputedStyle(d).backgroundColor;}" +
                    "return c;})()"
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var backdrop: FrameLayout? = null
    private var webView: WebView? = null
    private var surface: Surface? = null

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
                        webView?.let { if (it.canGoBack()) it.goBack() }
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
        return HomePage.build(carContext, items, AppSettings.wideReopenLastPage.value)
    }

    private fun showHome() {
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
        })
    }

    private fun loadUrl(url: String) {
        lastUrl = url
        webView?.loadUrl(url)
    }

    private fun loadInitial() {
        val lastSavedUrl = AppSettings.lastUrl.value
        val shouldReopen = AppSettings.wideReopenLastPage.value

        val targetUrl = if (shouldReopen && !lastSavedUrl.isNullOrEmpty()) lastSavedUrl else null

        if (targetUrl != null) loadUrl(targetUrl) else showHome()
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

        createVirtualDisplayAndPresentation()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        releaseVirtualDisplay()
        surface = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
        handler.post { applyVisibleArea() }
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    private var lockedRightMargin = 0

    private fun applyVisibleArea() {
        val wv = webView ?: return
        val r = visibleArea ?: return
        if (r.isEmpty) return

        val currentRight = (surfaceWidth - r.right).coerceAtLeast(0)

        if (currentRight > 50) {
            if (lockedRightMargin == 0 || abs(currentRight - lockedRightMargin) > 25) {
                lockedRightMargin = currentRight
            }
        } else {
            lockedRightMargin = 0
        }

        val lp = (wv.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

        lp.leftMargin = r.left.coerceAtLeast(0)
        lp.rightMargin = lockedRightMargin
        lp.topMargin = 0
        lp.bottomMargin = 0

        wv.layoutParams = lp
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
        if (isInputPopupVisible) return
        webView?.scrollBy(distanceX.toInt(), distanceY.toInt())
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (isInputPopupVisible) return
        webView?.flingScroll(-velocityX.toInt(), -velocityY.toInt())
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        // Niente zoom sulla home (zoomBy ignora il meta viewport, va bloccato qui)
        if (isInputPopupVisible || isHome()) return
        if (scaleFactor in 0.5f..2.0f) {
            try {
                webView?.zoomBy(scaleFactor)
            } catch (_: Exception) {
            }
        }
    }

    private fun dispatchTouch(action: Int, x: Float, y: Float) {
        handler.post {
            val wv = webView ?: return@post
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now

            val area = visibleArea
            val lx = x - (area?.left ?: 0)
            val ly = y

            val event = MotionEvent.obtain(downTime, now, action, lx, ly, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            wv.dispatchTouchEvent(event)
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
                        setBackgroundColor(Color.parseColor("#101010"))
                    }
                    backdrop = frame

                    // Popup compatto (tastiera e microfono) con timeout e chiusura al tocco esterno
                    val popupOverlay = FrameLayout(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(-1, -1)
                        visibility = View.GONE
                        setBackgroundColor(Color.parseColor("#99000000"))
                        setOnClickListener {
                            handler.removeCallbacks(hidePopupRunnable)
                            visibility = View.GONE
                            isInputPopupVisible = false
                        }
                    }
                    popupOverlayReference = popupOverlay

                    val inputCard = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(24, 16, 24, 16)
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#202124"))
                            cornerRadius = 20f
                        }
                        setOnClickListener { /* consuma click sulla card */ }
                    }

                    val btnKeyboard = Button(ctx).apply {
                        text = "⌨️"
                        textSize = 20f
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#3b82f6"))
                        setPadding(20, 12, 20, 12)
                        setOnClickListener {
                            handler.removeCallbacks(hidePopupRunnable)
                            popupOverlay.visibility = View.GONE
                            isInputPopupVisible = false
                            openFieldInput()
                        }
                    }

                    val btnMic = Button(ctx).apply {
                        text = "🎤"
                        textSize = 20f
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#ef4444"))
                        setPadding(20, 12, 20, 12)
                        setOnClickListener {
                            handler.removeCallbacks(hidePopupRunnable)
                            popupOverlay.visibility = View.GONE
                            isInputPopupVisible = false
                            startVoiceListening(ctx, webView)
                        }
                    }

                    inputCard.addView(btnKeyboard, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 16 })
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

                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                            val isYouTubeAdBlockEnabled = AdBlockSettings.isYouTubeEnabled.value

                            WebViewCompat.addDocumentStartJavaScript(this, BrowserJavascript.getLifecycleAndMetadataScript(), setOf("*"))
                            WebViewCompat.addDocumentStartJavaScript(this, GoogleLoginManager.getGoogleOauthFixScript(), setOf("*"))
                            WebViewCompat.addDocumentStartJavaScript(this, GoogleLoginManager.getPopupInterceptorScript(), setOf("*"))

                            if (isYouTubeAdBlockEnabled) {
                                WebViewCompat.addDocumentStartJavaScript(this, AdBlockJavascript.getYouTubeAdBlockScript(), setOf("*"))
                            }
                        }

                        addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                @Suppress("unused")
                                fun onVideoStarted(time: Float) {}

                                @JavascriptInterface
                                @Suppress("unused")
                                fun onMediaTimeUpdate(time: Float, speed: Float, isPlaying: Boolean) {}

                                @JavascriptInterface
                                @Suppress("unused")
                                fun onMediaStatusChanged(isPlaying: Boolean, time: Float, speed: Float) {}

                                @JavascriptInterface
                                @Suppress("unused")
                                fun updateMediaMetadata(title: String, artist: String, albumArtUrl: String, duration: Float) {}

                                @JavascriptInterface
                                @Suppress("unused")
                                fun recMediaStatus(jsonStr: String) {}

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
                                    post { loadUrl(url) }
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun openLinkInNewTab(url: String) {
                                    post { loadUrl(url) }
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun openPopup(url: String) {
                                    post { loadUrl(url) }
                                }
                            },
                            "AndroidBridge"
                        )

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
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString() ?: ""
                                if (url.startsWith("about:toggle_reopen")) {
                                    AppSettings.setWideReopenLastPage(carContext, !AppSettings.wideReopenLastPage.value)
                                    homeHtml = null
                                    showHome()
                                    return true
                                }
                                val scheme = request?.url?.scheme
                                return scheme != "http" && scheme != "https"
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                // Zoom disattivato solo sulla home
                                view?.settings?.setSupportZoom(url?.startsWith(HomePage.BASE_URL) != true)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (url.isNullOrEmpty()) return
                                if (url.startsWith(HomePage.BASE_URL)) {
                                    lastUrl = HOME
                                } else {
                                    lastUrl = url
                                    AppSettings.setLastUrl(carContext, url)
                                }
                                sampleBackdrop(view)
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

    private fun startVoiceListening(context: Context, targetWebView: WebView?) {
        try {
            val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.voice_prompt))
            }
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    try { speechRecognizer.destroy() } catch (_: Exception) {}
                }
                override fun onResults(results: Bundle?) {
                    val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!spokenText.isNullOrEmpty() && targetWebView != null) {
                        targetWebView.evaluateJavascript(BrowserJavascript.getInjectTextScript(spokenText), null)
                    }
                    try { speechRecognizer.destroy() } catch (_: Exception) {}
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            AppLog.e(TAG, "SpeechRecognizer error", e)
        }
    }

    private fun releaseVirtualDisplay() {
        handler.removeCallbacks(hidePopupRunnable)
        popupOverlayReference = null
        isInputPopupVisible = false
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