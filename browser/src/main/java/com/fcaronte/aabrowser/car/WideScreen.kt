package com.fcaronte.aabrowser.car

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.fcaronte.aabrowser.model.FavoritesRepository
import com.fcaronte.aabrowser.model.WebStateRepository
import java.io.ByteArrayInputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * Schermata "wide" (finto navigatore).
 * Richiede WideHomeAndSearch.kt (HomePage e WideSearchScreen) nello stesso package.
 */
class WideScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private companion object {
        const val TAG = "WideScreen"

        // Quanti preferiti tenere come scorciatoie testuali nell'ActionStrip (0-2).
        // Con 0 nell'ActionStrip restano solo Ricarica e Chiudi (i preferiti sono nella home HTML).
        const val SHORTCUT_FAVORITES = 0
        const val MAX_TITLE = 12

        // Marcatore interno per "la pagina corrente è la home HTML"
        const val HOME = "about:home"
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var webView: WebView? = null
    private var surface: Surface? = null

    private var surfaceWidth = 800
    private var surfaceHeight = 480
    private var surfaceDensity = 160

    // Area realmente libera (non coperta dal player affiancato o dalle strisce dei comandi)
    private var visibleArea: Rect? = null

    // Ultimo URL aperto (o HOME). Sopravvive al rilascio della surface.
    private var lastUrl: String? = null

    // HTML della home, servito da shouldInterceptRequest su HomePage.BASE_URL
    @Volatile
    private var homeHtml: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var downTime = 0L

    private val iconBack: CarIcon = CarIcon.BACK
    private val iconPan: CarIcon = CarIcon.PAN
    private val iconHome: CarIcon by lazy { buildHomeIcon() }
    private val iconSearch: CarIcon by lazy { buildSearchIcon() }
    private val iconReload: CarIcon by lazy { buildReloadIcon() }
    private val iconClose: CarIcon by lazy { buildCloseIcon() }

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
            // Tipicamente SecurityException se manca il permesso ACCESS_SURFACE nel manifest
            Log.e(TAG, "Init failed (controlla i permessi NAVIGATION_TEMPLATES e ACCESS_SURFACE)", e)
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
        // ActionStrip: almeno 1 azione, max 4, ognuna con titolo OPPURE icona
        val strip = ActionStrip.Builder()

        if (SHORTCUT_FAVORITES > 0) {
            val favorites = try {
                FavoritesRepository(carContext).loadFavorites().take(SHORTCUT_FAVORITES.coerceAtMost(2))
            } catch (e: Exception) {
                Log.e(TAG, "Errore lettura preferiti", e)
                emptyList()
            }
            favorites.forEach { fav ->
                val title = if (fav.name.length > MAX_TITLE) fav.name.take(MAX_TITLE - 1) + "…" else fav.name
                strip.addAction(
                    Action.Builder()
                        .setTitle(title.ifBlank { "Link" })
                        .setOnClickListener { loadUrl(fav.url) }
                        .build()
                )
            }
        }

        strip.addAction(
            Action.Builder()
                .setIcon(iconReload)
                .setOnClickListener { webView?.reload() }
                .build()
        )
        strip.addAction(
            Action.Builder()
                .setIcon(iconClose)
                .setOnClickListener { closeApp() }
                .build()
        )

        // MapActionStrip: solo icone. PAN serve per ricevere scroll/fling/scale.
        // Verifica sul DHU se accetta più di 4 azioni; per ora sono 4.
        val mapStrip = ActionStrip.Builder()
            .addAction(Action.Builder(Action.PAN).setIcon(iconPan).build())
            .addAction(
                Action.Builder()
                    .setIcon(iconBack)
                    .setOnClickListener {
                        webView?.let { if (it.canGoBack()) it.goBack() }
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(iconHome)
                    .setOnClickListener { showHome() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(iconSearch)
                    .setOnClickListener { openSearch() }
                    .build()
            )
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(strip.build())
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
        return HomePage.build(items)
    }

    private fun showHome() {
        homeHtml = buildHomeHtml()
        lastUrl = HOME
        val wv = webView ?: return
        if (wv.url == HomePage.BASE_URL) wv.reload() else wv.loadUrl(HomePage.BASE_URL)
    }

    private fun openSearch() {
        screenManager.push(WideSearchScreen(carContext) { query ->
            loadUrl(HomePage.queryToUrl(query))
        })
    }

    private fun loadUrl(url: String) {
        WebStateRepository.currentUrl = url
        lastUrl = url
        webView?.loadUrl(url)
    }

    private fun loadInitial(wv: WebView) {
        val target = lastUrl
            ?: WebStateRepository.currentUrl.takeIf { it.isNotEmpty() && !it.startsWith(HomePage.BASE_URL) }
            ?: HOME
        if (target == HOME) showHome() else wv.loadUrl(target)
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
    // SurfaceCallback
    // ------------------------------------------------------------------

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
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

    // Zona non coperta dal player affiancato / dalle strisce dei comandi
    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
        handler.post { applyVisibleArea() }
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    private fun applyVisibleArea() {
        val wv = webView ?: return
        val r = visibleArea ?: return
        if (r.isEmpty) return

        val lp = (wv.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        lp.leftMargin = r.left.coerceAtLeast(0)
        lp.topMargin = r.top.coerceAtLeast(0)
        lp.rightMargin = (surfaceWidth - r.right).coerceAtLeast(0)
        lp.bottomMargin = (surfaceHeight - r.bottom).coerceAtLeast(0)
        wv.layoutParams = lp
    }

    override fun onClick(x: Float, y: Float) {
        dispatchTouch(MotionEvent.ACTION_DOWN, x, y)
        dispatchTouch(MotionEvent.ACTION_UP, x, y)
    }

    // Arrivano solo se nella MapActionStrip c'è Action.PAN e l'auto li supporta
    override fun onScroll(distanceX: Float, distanceY: Float) {
        webView?.scrollBy(distanceX.toInt(), distanceY.toInt())
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        webView?.flingScroll(-velocityX.toInt(), -velocityY.toInt())
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
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

            // Le coordinate arrivano relative all'intera surface: togli lo scostamento della WebView
            val area = visibleArea
            val lx = x - (area?.left ?: 0)
            val ly = y - (area?.top ?: 0)

            val event = MotionEvent.obtain(downTime, now, action, lx, ly, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            wv.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    // ------------------------------------------------------------------
    // VirtualDisplay + Presentation + WebView
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
                        setBackgroundColor(Color.BLACK)
                    }

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
                            mediaPlaybackRequiresUserGesture = false
                        }
                        webViewClient = object : WebViewClient() {

                            // La home HTML è servita in locale: nessuna rete e la cronologia (Indietro) funziona
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val url = request?.url?.toString()
                                if (url == HomePage.BASE_URL) {
                                    val html = homeHtml ?: buildHomeHtml().also { homeHtml = it }
                                    return WebResourceResponse(
                                        "text/html",
                                        "UTF-8",
                                        ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
                                    )
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val scheme = request?.url?.scheme
                                // Blocca schemi non web (intent:, market:, ecc.)
                                return scheme != "http" && scheme != "https"
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (url.isNullOrEmpty()) return
                                if (url.startsWith(HomePage.BASE_URL)) {
                                    lastUrl = HOME
                                } else {
                                    WebStateRepository.currentUrl = url
                                    lastUrl = url
                                }
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
                    setContentView(frame)
                    applyVisibleArea()
                    loadInitial(wv)
                }
            }

            presentation?.show()
        } catch (e: Exception) {
            Log.e(TAG, "Creazione display/presentation fallita", e)
        }
    }

    private fun releaseVirtualDisplay() {
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
    // Icone disegnate a codice (nessun drawable da aggiungere).
    // Maschere nere con tint CarColor.DEFAULT: colore scelto dall'host.
    // ------------------------------------------------------------------

    private fun maskIcon(draw: (Canvas, Paint, Float) -> Unit): CarIcon {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
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

    private fun buildCloseIcon(): CarIcon = maskIcon { c, p, s ->
        val m = s * 0.25f
        c.drawLine(m, m, s - m, s - m, p)
        c.drawLine(s - m, m, m, s - m, p)
    }

    private fun buildReloadIcon(): CarIcon = maskIcon { c, p, s ->
        val cx = s / 2f
        val cy = s / 2f
        val r = s * 0.30f
        c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -60f, 290f, false, p)

        // punta della freccia all'inizio dell'arco
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
        // tetto
        val roof = Path().apply {
            moveTo(s * 0.18f, s * 0.48f)
            lineTo(s * 0.50f, s * 0.20f)
            lineTo(s * 0.82f, s * 0.48f)
        }
        c.drawPath(roof, p)
        // corpo
        val body = Path().apply {
            moveTo(s * 0.28f, s * 0.44f)
            lineTo(s * 0.28f, s * 0.78f)
            lineTo(s * 0.72f, s * 0.78f)
            lineTo(s * 0.72f, s * 0.44f)
        }
        c.drawPath(body, p)
    }
}