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
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.smartphone.BlackFrame
import com.fcaronte.aabrowser.smartphone.ShizukuManager
import com.fcaronte.aabrowser.smartphone.VirtualAppSession
import com.fcaronte.aabrowser.smartphone.VirtualApps
import com.fcaronte.aabrowser.smartphone.VirtualScreenPower
import com.fcaronte.aabrowser.smartphone.VirtualSplit
import com.fcaronte.aabrowser.smartphone.VirtualTouch
import kotlin.math.abs

/**
 * Lo schermo virtuale della Wide: un'app del telefono sul suo display, mostrata in una TextureView dentro la Presentation,
 * sopra la pagina web (che resta com'è, nascosta). La Wide ha un'app aperta alla volta; uscendo (home, un'altra pagina)
 * l'app continua a girare in secondo plano finché non la si chiude o non se ne apre un'altra.
 *
 * Android Auto non passa tocchi veri: [VirtualTouch] rifà tocchi, scorrimenti, fling e pizzico da quello che manda l'host.
 * La barra dell'app (schermo diviso, Indietro, Altre App, Tastiera, Chiudi) sta sul bordo della vista, accanto alla sidebar
 * della Wide se c'è, sempre accanto all'app e mai sopra (il display si restringe, niente è coperto), e si mostra o nasconde
 * con il tasto in alto a destra della Wide, che con l'app aperta prende il posto dello scudo dell'AdBlock
 * (WideScreen.onGetTemplate): serve a non restare chiusi nell'app.
 *
 * Schermo diviso ([isSplit], VirtualSplit): la vista occupa solo il pannello dell'app (lo decide la Wide, che gli dà i margini)
 * e la pagina resta visibile e toccabile nell'altro. L'host non dà la posizione del dito per scorrimenti e pizzico: vanno al
 * pannello toccato per ultimo ([receivesGestures]). Il display è sempre in verticale.
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

        /** Lo schermo diviso si accende o si spegne (tasto della barra): la pagina torna visibile accanto all'app, o sparisce. */
        fun onSplitChanged()

        /** Il tasto "Altre App" della barra. */
        fun openAppsPage()

        fun message(text: String)

        /** Lingua scritta da destra a sinistra: la barra sta a destra e l'app comincia prima. */
        fun isRtl(): Boolean

        /** Nello schermo diviso cambia il pannello che riceve scorrimenti e Indietro: il segno sul pannello si sposta. */
        fun onFocusChanged()
    }

    private var session: VirtualAppSession? = null
    private var layer: FrameLayout? = null
    private var textureView: TextureView? = null
    private val blackSampler = BlackFrame.Sampler()
    private var surface: Surface? = null
    private var sidebar: LinearLayout? = null
    private var statusView: TextView? = null
    private var touch: VirtualTouch? = null

    // La barra sta sempre accanto all'app (il display si restringe e niente è coperto)
    private var barWidth = 0
    private var splitButton: ImageButton? = null

    // Dove comincia il display nella vista: dopo la barra (a sinistra) e, nello schermo diviso, dopo la banda nera che centra
    // il display in verticale. Serve a rifare le coordinate dei tocchi
    private var appOffset = 0

    // Schermo diviso: l'host non dà la posizione del dito per scorrimenti e pizzico, quindi vanno al pannello toccato per ultimo
    private var focusOnApp = true

    // Il telefono si spegne o si riaccende (tasto di blocco): lo stato sullo schermo dell'auto si aggiorna
    private val powerListener: () -> Unit = { handler.post { updateStatus() } }

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

    /** Schermo diviso con l'app davanti: la pagina resta visibile accanto, la vista è il pannello dell'app. */
    val isSplit: Boolean get() = isShown && AppSettings.virtualSplitEnabled.value

    /** A chi vanno i gesti senza posizione (scorrimento, fling, pizzico, Indietro): all'app, o nello schermo diviso al pannello toccato per ultimo. */
    fun receivesGestures(): Boolean = isShown && (!isSplit || focusOnApp)

    /** Nello schermo diviso: il pannello che riceve scorrimenti e Indietro è quello dell'app (altrimenti è quello del browser). */
    val focusedOnApp: Boolean get() = focusOnApp

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
        barWidth = sidebarWidthPx
        splitButton = addButton(ctx, bar, R.drawable.ic_vapp_split, s.getString(R.string.vapp_split_on), sidebarWidthPx) { toggleSplit() }
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
        // Il pannello cambia misura (schermo diviso, sidebar, fasce dell'host): il display si rimette in verticale e centrato
        frame.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) frame.post { applyDock() }
        }
        applyDock()
        applySplitButton()
        VirtualScreenPower.addListener(powerListener)
        if (wantShown) show()
        return frame
    }

    private fun addButton(ctx: Context, bar: LinearLayout, icon: Int, description: String, width: Int, action: () -> Unit): ImageButton {
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
        return button
    }

    /** La Presentation sparisce: le viste vanno via, l'app resta aperta (si riattacca alla vista nuova). */
    fun release() {
        session?.detach()
        VirtualScreenPower.removeListener(powerListener)
        textureView?.surfaceTextureListener = null
        surface?.release()
        surface = null
        layer = null
        textureView = null
        sidebar = null
        statusView = null
        splitButton = null
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
                // Un'app che resta tutta nera (banche, finestre protette): si dice perché, invece di lasciare il nero senza parole
                created.frameProbe = { blackSampler.sample(textureView) }
                created.onNotice = { notice ->
                    if (notice == VirtualAppSession.Notice.LOOKS_BLACK) {
                        handler.post { callbacks.message(strings().getString(R.string.vapp_black_notice)) }
                    }
                }
            }
        }
        if (removeShizukuListeners == null) {
            removeShizukuListeners = ShizukuManager.registerListeners(
                onPermissionResult = { _, _ -> handler.post { session?.retry() } },
                onBinderReceived = { handler.post { session?.retry() } }
            )
        }
        wantShown = true
        focusOnApp = true
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

    /**
     * Lo schermo diviso si accende con un'app che gira ma è nascosta (ci si era andati su un'altra pagina quando era spento):
     * torna accanto alla pagina, come un'app che resta (utente, 15/11/2026: lo schermo diviso «dovrebbe essere persistente»).
     * Senza un'app aperta, o se è già davanti, non fa niente.
     */
    fun reshowIfRunning() {
        if (wantShown || layer == null || session == null) return
        wantShown = true
        focusOnApp = true
        show()
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

    /**
     * La barra dell'app si mostra o si nasconde insieme al dock della Wide (il tasto in alto a destra li comanda tutti e due:
     * prima nascondeva solo la barra dell'app e il menu restava), e all'apertura segue lo stato del dock.
     */
    fun setSidebarVisible(visible: Boolean) {
        sidebarVisible = visible
        sidebar?.visibility = if (sidebarVisible) View.VISIBLE else View.GONE
        applyDock()
    }

    /** Il primo tasto della barra: schermo diviso con la pagina, o l'app a schermo intero. Il display cambia misura. */
    private fun toggleSplit() {
        AppSettings.setVirtualSplitEnabled(carContext, !AppSettings.virtualSplitEnabled.value)
        focusOnApp = true
        applySplitButton()
        callbacks.onSplitChanged()
    }

    /** Il tasto dello schermo diviso: azzurro quando è diviso; dice cosa farebbe se lo si tocca. */
    private fun applySplitButton() {
        val on = AppSettings.virtualSplitEnabled.value
        if (on) splitButton?.setColorFilter(0xFF8AB4F8.toInt()) else splitButton?.clearColorFilter()
        splitButton?.contentDescription = strings().getString(if (on) R.string.vapp_split_off else R.string.vapp_split_on)
    }

    /** L'impostazione dello schermo diviso è cambiata da fuori (Impostazioni): il tasto e il display si aggiornano. */
    fun onSettingsChanged() {
        applySplitButton()
        applyDock()
    }

    // La barra sta sempre accanto all'app: il display comincia dopo di lei (a destra della barra; a sinistra in RTL). Nello
    // schermo diviso è in verticale (VirtualSplit.displayWidth): un pannello largo lascia bande nere ai lati e il display sta in mezzo
    private fun applyDock() {
        val texture = textureView ?: return
        val params = texture.layoutParams as? FrameLayout.LayoutParams ?: return
        val offset = if (sidebarVisible) barWidth else 0
        val available = ((layer?.width ?: 0) - offset).coerceAtLeast(0)
        val height = layer?.height ?: 0
        val split = AppSettings.virtualSplitEnabled.value
        val width = if (split && available > 0 && height > 0) VirtualSplit.displayWidth(available, height) else ViewGroup.LayoutParams.MATCH_PARENT
        val gravity = if (width == ViewGroup.LayoutParams.MATCH_PARENT) FrameLayout.LayoutParams.UNSPECIFIED_GRAVITY else Gravity.CENTER_HORIZONTAL
        if (params.marginStart != offset || params.width != width || params.gravity != gravity) {
            params.marginStart = offset
            params.width = width
            params.gravity = gravity
            texture.layoutParams = params
        }
        // Nei tocchi: l'app comincia dopo la barra solo se la barra sta a sinistra, e dopo la banda nera che la centra
        val shift = if (width == ViewGroup.LayoutParams.MATCH_PARENT) 0 else (available - width) / 2
        appOffset = (if (callbacks.isRtl()) 0 else offset) + shift
    }

    /**
     * Nello schermo diviso dice quale pannello riceve scorrimenti e Indietro, dal punto toccato o pizzicato dall'utente
     * (l'host non dà la posizione del dito per il resto). La Wide chiama solo per i punti dentro un pannello: un tocco sulla
     * sidebar o sotto un video a schermo intero non sposta niente.
     */
    fun setFocus(onApp: Boolean) {
        if (!isSplit || focusOnApp == onApp) return
        focusOnApp = onApp
        callbacks.onFocusChanged()
    }

    /** Il punto (coordinate della superficie dell'host) sta sulla vista dell'app: la sidebar fissa della Wide ne è fuori. */
    fun contains(x: Float, y: Float): Boolean {
        val view = layer ?: return false
        return x >= view.left && x < view.right && y >= view.top && y < view.bottom
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
            // Il telefono dorme (tasto di blocco): il display è fermo e l'auto resta nera. Lo si dice, e un tocco lo riaccende
            current != null && VirtualScreenPower.asleep -> s.getString(R.string.vapp_phone_asleep)
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
        VirtualScreenPower.touchOnCar()
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
        touch?.click(localX - appOffset, localY)
    }

    fun onScroll(distanceX: Float, distanceY: Float) {
        VirtualScreenPower.touchOnCar()
        touch?.scroll(distanceX, distanceY)
    }

    fun onFling(velocityX: Float, velocityY: Float) {
        VirtualScreenPower.touchOnCar()
        touch?.fling(velocityX, velocityY)
    }

    fun onScale(focusX: Float, focusY: Float, factor: Float) {
        // Il doppio tocco dell'host arriva come un salto brusco (2.0 o 0.5): un pizzico vero cambia poco a ogni passo
        if (abs(factor - 1f) > 0.35f) return
        touch?.scale(focusX - left - appOffset, focusY - top, factor)
    }
}
