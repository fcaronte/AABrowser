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
import kotlin.math.cos
import kotlin.math.sin

class WideScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private companion object {
        const val TAG = "WideScreen"
        const val MAX_FAVORITES = 4          // limite del NavigationTemplate (ActionStrip)
        const val MAX_TITLE = 12
        const val FALLBACK_URL = "https://www.google.com"
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var webView: WebView? = null
    private var surface: Surface? = null

    private var surfaceWidth = 800
    private var surfaceHeight = 480
    private var surfaceDensity = 160

    // Ultimo URL aperto nella WebView del display virtuale (sopravvive al rilascio della surface)
    private var lastUrl: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var downTime = 0L

    private val iconBack: CarIcon = CarIcon.BACK
    private val iconPan: CarIcon = CarIcon.PAN
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
        val favorites = try {
            FavoritesRepository(carContext).loadFavorites().take(MAX_FAVORITES)
        } catch (e: Exception) {
            Log.e(TAG, "Errore lettura preferiti", e)
            emptyList()
        }

        // ActionStrip: almeno 1 azione, max 4, ognuna con titolo OPPURE icona
        val strip = ActionStrip.Builder()
        if (favorites.isEmpty()) {
            strip.addAction(
                Action.Builder()
                    .setTitle("Google")
                    .setOnClickListener { loadUrl(FALLBACK_URL) }
                    .build()
            )
        } else {
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

        // MapActionStrip: solo icone. PAN serve per ricevere scroll/fling/scale
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
                    .setIcon(iconReload)
                    .setOnClickListener { webView?.reload() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(iconClose)
                    .setOnClickListener { closeApp() }
                    .build()
            )
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(strip.build())
            .setMapActionStrip(mapStrip)
            .build()
    }

    private fun loadUrl(url: String) {
        WebStateRepository.currentUrl = url
        lastUrl = url
        webView?.loadUrl(url)
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

    override fun onVisibleAreaChanged(visibleArea: Rect) {}
    override fun onStableAreaChanged(stableArea: Rect) {}

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
            val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
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
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
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
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val scheme = request?.url?.scheme
                                // Blocca schemi non web (intent:, market:, ecc.)
                                return scheme != "http" && scheme != "https"
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (!url.isNullOrEmpty()) {
                                    WebStateRepository.currentUrl = url
                                    lastUrl = url
                                }
                            }
                        }
                    }

                    webView = wv
                    frame.addView(wv)
                    setContentView(frame)

                    val urlToLoad = lastUrl
                        ?: WebStateRepository.currentUrl.takeIf { it.isNotEmpty() }
                        ?: try {
                            FavoritesRepository(carContext).loadFavorites().firstOrNull()?.url
                        } catch (_: Exception) {
                            null
                        }
                        ?: FALLBACK_URL
                    wv.loadUrl(urlToLoad)
                }
            }

            presentation?.show()
        } catch (e: Exception) {
            Log.e(TAG, "Creazione display/presentation fallita", e)
        }
    }

    private fun releaseVirtualDisplay() {
        try {
            webView?.url?.let { if (it.isNotEmpty()) lastUrl = it }
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
}