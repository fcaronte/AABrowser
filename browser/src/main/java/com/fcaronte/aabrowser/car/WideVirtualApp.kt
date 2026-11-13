package com.fcaronte.aabrowser.car

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Handler
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.car.app.CarContext
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.smartphone.ShizukuManager
import com.fcaronte.aabrowser.smartphone.VirtualAppSession
import com.fcaronte.aabrowser.smartphone.VirtualApps
import com.fcaronte.aabrowser.smartphone.VirtualTouch
import kotlin.math.abs

/**
 * Lo schermo virtuale della Wide: un'app del telefono sul suo display, mostrata in una TextureView dentro la Presentation,
 * sopra la pagina web (che resta com'è, nascosta). La Wide ha un'app aperta alla volta; uscendo (home, un'altra pagina)
 * l'app continua a girare in secondo plano finché non la si chiude o non se ne apre un'altra.
 *
 * Android Auto non passa tocchi veri: [VirtualTouch] rifà tocchi, scorrimenti, fling e pizzico da quello che manda l'host.
 * La barra dell'app (Indietro, Altre App, Tastiera, Chiudi) sta sul bordo della vista, accanto al menu fisso della Wide se
 * c'è, e si mostra o nasconde con il tasto in alto a destra della Wide, che con l'app aperta prende il posto dello scudo
 * dell'AdBlock (WideScreen.onGetTemplate): serve a non restare chiusi nell'app.
 */
class WideVirtualApp(
    private val carContext: CarContext,
    private val handler: Handler,
    private val strings: () -> Context,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        /** L'app si mostra o si nasconde: il template (tasto in alto a destra) e la pagina sotto vanno aggiornati. */
        fun onShownChanged(shown: Boolean)

        /** Il tasto "Altre App" della barra. */
        fun openAppsPage()

        fun message(text: String)
    }

    private var session: VirtualAppSession? = null
    private var layer: FrameLayout? = null
    private var textureView: TextureView? = null
    private var surface: Surface? = null
    private var sidebar: LinearLayout? = null
    private var statusView: TextView? = null
    private var touch: VirtualTouch? = null

    // Shizuku che si accende o dà il permesso mentre si guarda l'errore: l'apertura riprova da sola
    private var removeShizukuListeners: (() -> Unit)? = null

    // L'app è quella davanti (anche se le viste non ci sono ancora: la Presentation si può ricreare)
    private var wantShown = false
    private var sidebarVisible = true
    private var dpi = 160
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var left = 0
    private var top = 0
    private var right = 0
    private var bottom = 0

    val isShown: Boolean get() = wantShown && layer != null
    val packageName: String? get() = session?.packageName

    // ------------------------------------------------------------------ viste

    /** Crea le viste (nascoste) nella Presentation: la Wide le aggiunge a [FrameLayout] sopra pagina e menu fisso. */
    fun install(ctx: Context, sidebarWidthPx: Int): FrameLayout {
        dpi = ctx.resources.displayMetrics.densityDpi
        val frame = FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        val texture = TextureView(ctx)
        texture.surfaceTextureListener = textureListener
        frame.addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val pad = (24 * ctx.resources.displayMetrics.density).toInt()
        val status = TextView(ctx).apply {
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(pad + sidebarWidthPx, pad, pad, pad)
            visibility = View.GONE
        }
        frame.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE61E1E1E.toInt())
            // I tocchi sulla barra non passano all'app sotto (quelli di Android Auto li instrada onClick)
            isClickable = true
            visibility = if (sidebarVisible) View.VISIBLE else View.GONE
        }
        val s = strings()
        addButton(ctx, bar, R.drawable.ic_vapp_back, s.getString(R.string.vapp_back), sidebarWidthPx) { back() }
        addButton(ctx, bar, R.drawable.ic_apps, s.getString(R.string.apps_title), sidebarWidthPx) { callbacks.openAppsPage() }
        addButton(ctx, bar, R.drawable.ic_vapp_keyboard, s.getString(R.string.vapp_keyboard), sidebarWidthPx) { toggleKeyboard() }
        addButton(ctx, bar, R.drawable.ic_vapp_close, s.getString(R.string.vapp_close), sidebarWidthPx) { close() }
        frame.addView(bar, FrameLayout.LayoutParams(sidebarWidthPx, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))

        layer = frame
        textureView = texture
        sidebar = bar
        statusView = status
        touch = VirtualTouch(handler) { event -> session?.touch(event) }
        if (wantShown) show()
        return frame
    }

    private fun addButton(ctx: Context, bar: LinearLayout, icon: Int, description: String, width: Int, action: () -> Unit) {
        val button = ImageButton(ctx).apply {
            setImageResource(icon)
            background = null
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = description
            val padding = width / 5
            setPadding(padding, padding, padding, padding)
            setOnClickListener { action() }
        }
        bar.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** La Presentation sparisce: le viste vanno via, l'app resta aperta (si riattacca alla vista nuova). */
    fun release() {
        session?.detach()
        textureView?.surfaceTextureListener = null
        surface?.release()
        surface = null
        layer = null
        textureView = null
        sidebar = null
        statusView = null
        touch = null
    }

    /** La Wide si chiude: chiude anche l'app e il suo display. */
    fun shutdown() {
        release()
        session?.close()
        session = null
        wantShown = false
        removeShizukuListeners?.invoke()
        removeShizukuListeners = null
    }

    /** Dove sta la vista nella superficie dell'host: l'area libera, senza le fasce dei tasti di Android Auto. */
    fun layout(leftPx: Int, topPx: Int, rightPx: Int, bottomPx: Int) {
        if (leftPx == left && topPx == top && rightPx == right && bottomPx == bottom && layer?.layoutParams is FrameLayout.LayoutParams) return
        left = leftPx
        top = topPx
        right = rightPx
        bottom = bottomPx
        val view = layer ?: return
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        params.leftMargin = left
        params.topMargin = top
        params.rightMargin = right
        params.bottomMargin = bottom
        view.layoutParams = params
    }

    // ------------------------------------------------------------------ apertura e chiusura

    /** Apre l'app davanti (o la riporta davanti se è già quella): la vista si mostra e il display ci disegna. */
    fun open(packageName: String) {
        val current = session
        if (current == null || current.packageName != packageName) {
            session?.close()
            session = VirtualAppSession(carContext, packageName, inCar = true).also { created ->
                created.stateListener = { handler.post { updateStatus() } }
            }
        }
        if (removeShizukuListeners == null) {
            removeShizukuListeners = ShizukuManager.registerListeners(
                onPermissionResult = { _, _ -> handler.post { session?.retry() } },
                onBinderReceived = { handler.post { session?.retry() } }
            )
        }
        wantShown = true
        show()
    }

    private fun show() {
        val view = layer ?: return
        view.visibility = View.VISIBLE
        updateStatus()
        // Con la vista già pronta (app riaperta) il display si riattacca subito; altrimenti ci pensa la texture quando arriva
        val ready = surface
        if (ready != null && ready.isValid && surfaceWidth > 0) {
            session?.attach(ready, surfaceWidth, surfaceHeight, dpi)
            session?.bringToFront()
        }
        callbacks.onShownChanged(true)
    }

    /** L'app esce di scena (home, un'altra pagina): la vista sparisce, il display si ferma e l'app resta aperta. */
    fun hide() {
        if (!wantShown && layer?.visibility != View.VISIBLE) return
        wantShown = false
        layer?.visibility = View.GONE
        touch?.cancel()
        session?.detach()
        callbacks.onShownChanged(false)
    }

    /** Chiude l'app e il suo display (il tasto Chiudi della barra). */
    fun close() {
        session?.close()
        session = null
        hide()
    }

    fun toggleSidebar() {
        sidebarVisible = !sidebarVisible
        sidebar?.visibility = if (sidebarVisible) View.VISIBLE else View.GONE
    }

    /** Indietro dentro l'app (tasto della barra, tasto Indietro di Wide e dell'auto). */
    fun back() {
        session?.key(KeyEvent.KEYCODE_BACK)
    }

    private fun toggleKeyboard() {
        val current = session ?: return
        current.toggleKeyboard { result ->
            val s = strings()
            when {
                result == "no field" -> callbacks.message(s.getString(R.string.vapp_keyboard_no_field))
                result == "old android" -> callbacks.message(s.getString(R.string.vapp_keyboard_old))
                result.startsWith("error") -> callbacks.message(s.getString(R.string.vapp_keyboard_error, result.removePrefix("error: ")))
            }
        }
    }

    private fun updateStatus() {
        val view = statusView ?: return
        val current = session
        val s = strings()
        val problem = current?.problem
        val text = when {
            current == null || current.phase == VirtualAppSession.Phase.RUNNING -> null
            problem != null -> VirtualApps.problemMessage(s, problem)
            // Il passo (n/3) dice dove si ferma l'apertura, se non parte: 1 vista pronta, 2 display, 3 app
            else -> s.getString(R.string.vapp_starting) + if (current.step > 0) " (${current.step}/3)" else ""
        }
        view.text = text
        view.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ superficie

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
            surface?.release()
            surface = Surface(surfaceTexture)
            surfaceWidth = width
            surfaceHeight = height
            touch?.area(width, height, dpi)
            if (wantShown) surface?.let { session?.attach(it, width, height, dpi) }
        }

        override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
            touch?.area(width, height, dpi)
            if (wantShown) surface?.let { session?.attach(it, width, height, dpi) }
        }

        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
            session?.detach()
            surface?.release()
            surface = null
            return true
        }

        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
    }

    // ------------------------------------------------------------------ gesti di Android Auto

    /** Tocco di Android Auto (coordinate della superficie dell'host): la barra, oppure l'app. */
    fun onClick(x: Float, y: Float) {
        val localX = x - left
        val localY = y - top
        val bar = sidebar
        if (sidebarVisible && bar != null && bar.visibility == View.VISIBLE && localX >= bar.left && localX < bar.right) {
            // Sempre il tasto più vicino in verticale: sull'auto il dito non centra i tasti
            var best: View? = null
            var distance = Float.MAX_VALUE
            for (i in 0 until bar.childCount) {
                val child = bar.getChildAt(i)
                val d = abs(localY - (child.top + child.bottom) / 2f)
                if (d < distance) {
                    distance = d
                    best = child
                }
            }
            best?.performClick()
            return
        }
        touch?.click(localX, localY)
    }

    fun onScroll(distanceX: Float, distanceY: Float) {
        touch?.scroll(distanceX, distanceY)
    }

    fun onFling(velocityX: Float, velocityY: Float) {
        touch?.fling(velocityX, velocityY)
    }

    fun onScale(focusX: Float, focusY: Float, factor: Float) {
        // Il doppio tocco dell'host arriva come un salto brusco (2.0 o 0.5): un pizzico vero cambia poco a ogni passo
        if (abs(factor - 1f) > 0.35f) return
        touch?.scale(focusX - left, focusY - top, factor)
    }
}
