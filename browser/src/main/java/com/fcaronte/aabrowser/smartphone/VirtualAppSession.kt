package com.fcaronte.aabrowser.smartphone

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fcaronte.aabrowser.settings.AppSettings

/**
 * Un'app del telefono aperta sul suo display virtuale, per una scheda della Main o per la schermata della Wide.
 *
 * La vista che la mostra (una TextureView) dà la sua superficie con [attach] e la toglie con [detach]; la sessione crea il
 * display fidato con Shizuku, ci avvia l'app e gli passa tocchi e tasti. Il display nasce alla prima superficie e resta
 * finché la sessione non è chiusa: con la scheda nascosta si toglie solo la superficie (l'app continua a girare, niente
 * si disegna). Sull'auto il telefono deve restare sveglio finché l'app è davanti ([VirtualScreenPower]). Tutto si chiama
 * dal thread principale; i risultati di Shizuku tornano lì.
 */
class VirtualAppSession(
    context: Context,
    val packageName: String,
    // Sullo schermo dell'auto: la tastiera si apre sul display e il telefono deve restare acceso (VirtualScreenPower)
    private val inCar: Boolean
) {
    enum class Phase { IDLE, STARTING, RUNNING, ERROR }

    enum class Kind { SHIZUKU_MISSING, SHIZUKU_STOPPED, SHIZUKU_PERMISSION, NO_APP, FAILED }

    /** Cosa impedisce di mostrare l'app: la vista dice all'utente che cosa fare. */
    data class Problem(val kind: Kind, val detail: String = "")

    private val appContext = context.applicationContext

    var phase by mutableStateOf(Phase.IDLE)
        private set
    var problem by mutableStateOf<Problem?>(null)
        private set

    /** Id del display su cui gira l'app, -1 finché non esiste. */
    var displayId = -1
        private set

    /**
     * A che punto è l'apertura, per capire dove si ferma se non parte: 1 = la vista ha la superficie, 2 = si crea il display
     * (Shizuku), 3 = il display c'è e si avvia l'app. Si mostra accanto a "Apro l'app…".
     */
    var step by mutableIntStateOf(0)
        private set

    private var creating = false
    private var closed = false
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var dpi = 0
    private var recreations = 0
    private var inUse = false

    // Il display divide il gruppo del telefono (se dorme, il display si ferma): finché non lo si sa, si suppone di sì
    private var sharesPhoneGroup = true
    private var powered = false

    /** Chiamato quando l'app comincia o smette di essere davanti sull'auto. */
    var inUseListener: ((Boolean) -> Unit)? = null

    /** Chiamato a ogni cambio di fase o di problema, per le viste che non osservano lo stato di Compose (la Wide). */
    var stateListener: (() -> Unit)? = null

    /** Un avviso per l'utente, una volta per app: ogni vista lo mostra a modo suo. */
    enum class Notice { LOOKS_BLACK }

    var onNotice: ((Notice) -> Unit)? = null

    /**
     * La vista che mostra l'app dà un campione piccolo di quello che disegna (vedi [BlackFrame.Sampler]); null se non può.
     * Serve a capire se l'app resta tutta nera (finestre protette) e a dirlo, invece di lasciare l'utente senza spiegazione.
     */
    var frameProbe: (() -> IntArray?)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val blackWatch = Runnable { checkFrame() }
    private var watching = false
    private var blackChecks = 0
    private var blackRun = 0

    // I tocchi veri della Main si rifanno puliti prima di andare al display (vedi RawTouch)
    private val rawTouch = RawTouch()
    private var gesturesLogged = 0

    val isRunning: Boolean get() = phase == Phase.RUNNING

    /** La vista ha una superficie (o nuove misure): crea il display, o lo ridimensiona e gli ridà la superficie. */
    fun attach(surface: Surface, width: Int, height: Int, dpi: Int) {
        if (closed || !surface.isValid || width <= 0 || height <= 0) return
        this.surface = surface
        this.width = width
        this.height = height
        // La dimensione scelta dall'utente per le app sullo schermo virtuale (Impostazioni): la densità sale o scende
        val scale = AppSettings.virtualAppScale.value
        this.dpi = (dpi * scale / 100).coerceIn(60, 1000)
        Log.i(TAG, "surface ${width}x$height, density $dpi x $scale% = ${this.dpi} (display $displayId, phase $phase)")
        if (displayId <= 0 && phase != Phase.RUNNING) step = 1

        if (displayId > 0) {
            val id = displayId
            ShizukuAppBridge.updateDisplay(appContext, id, surface, width, height, this.dpi) { ok, message ->
                if (ok || closed || displayId != id) return@updateDisplay
                // Il servizio è ripartito e il display non c'è più: si ricrea una volta (poi si mostra l'errore)
                Log.w(TAG, "display $id lost: $message")
                displayId = -1
                if (recreations++ < MAX_RECREATIONS) this.surface?.let { attach(it, this.width, this.height, this.dpi) }
                else fail(Problem(Kind.FAILED, message))
            }
            updateInUse()
            return
        }
        if (creating) return
        if (!shizukuReady()) return
        creating = true
        phase = Phase.STARTING
        step = 2
        problem = null
        stateListener?.invoke()
        // Il telefono si sveglia prima di aprire l'app: su un telefono bloccato il display virtuale non parte
        updateInUse()
        val createdWith = surface
        ShizukuAppBridge.createDisplay(
            appContext, surface, width, height, this.dpi,
            // Sempre un display protetto, se il sistema lo concede (poi senza, in silenzio): così le app con finestre
            // protette si vedono quando il telefono lo permette, senza chiedere niente all'utente
            keyboardOnDisplay = inCar, secure = true
        ) { id, message ->
            creating = false
            if (closed) {
                if (id > 0) ShizukuAppBridge.releaseDisplay(appContext, id)
                return@createDisplay
            }
            if (id <= 0) {
                fail(Problem(Kind.FAILED, message))
                return@createDisplay
            }
            displayId = id
            step = 3
            stateListener?.invoke()
            // Nel frattempo la superficie può essere cambiata o sparita
            if (this.surface !== createdWith) {
                ShizukuAppBridge.updateDisplay(appContext, id, this.surface, this.width, this.height, this.dpi)
            }
            if (inCar) {
                ShizukuAppBridge.phoneGroup(appContext, id) { group ->
                    sharesPhoneGroup = group != 0
                    Log.i(TAG, "display $id phone group=$group")
                    updateInUse()
                }
            }
            launchApp()
        }
    }

    /** La vista non ha più la superficie (scheda nascosta, schermata chiusa): l'app continua, il display non disegna. */
    fun detach() {
        surface = null
        val id = displayId
        if (id > 0) ShizukuAppBridge.updateDisplay(appContext, id, null, width.coerceAtLeast(1), height.coerceAtLeast(1), dpi.coerceAtLeast(60))
        updateInUse()
    }

    /** L'app torna davanti (scheda scelta di nuovo): come toccarne l'icona nel launcher, la riapre se si era chiusa. */
    fun bringToFront() {
        if (!closed && displayId > 0) launchApp()
    }

    /** Dopo che l'utente ha acceso Shizuku o dato il permesso: riprova con l'ultima superficie. */
    fun retry() {
        if (closed || phase == Phase.RUNNING || creating) return
        problem = null
        phase = Phase.IDLE
        stateListener?.invoke()
        surface?.let { attach(it, width, height, dpi) }
    }

    /** Tocco in pixel del display (le coordinate della vista: il display ha le sue stesse misure). */
    fun touch(event: MotionEvent) {
        if (displayId > 0 && phase == Phase.RUNNING) ShizukuAppBridge.touch(displayId, event)
    }

    /**
     * Tocco vero della vista della Main (coordinate della vista: il display ha le sue stesse misure). Si rifa pulito prima di
     * mandarlo (alla Wide i tocchi li fa [VirtualTouch], e i suoi scorrimenti funzionavano; quelli della Main no).
     */
    fun touchRaw(event: MotionEvent) {
        if (displayId <= 0 || phase != Phase.RUNNING) return
        val clean = rawTouch.convert(event) ?: return
        try {
            ShizukuAppBridge.touch(displayId, clean)
        } finally {
            clean.recycle()
        }
        // I primi gesti nel registro: se uno scorrimento non va, si vede se le mosse arrivano
        rawTouch.takeSummary()?.let { if (gesturesLogged++ < MAX_GESTURE_LOGS) Log.i(TAG, it) }
    }

    fun key(keyCode: Int) {
        if (displayId > 0 && phase == Phase.RUNNING) ShizukuAppBridge.key(displayId, keyCode)
    }

    /** Il tasto della tastiera; [onResult] riceve la risposta del servizio ("shown", "hidden", "no field", ...). */
    fun toggleKeyboard(onResult: (String) -> Unit) {
        if (displayId <= 0) {
            onResult("error: no display")
            return
        }
        ShizukuAppBridge.toggleKeyboard(appContext, displayId, onResult)
    }

    /** Chiude l'app (come toglierla dalle recenti) e il suo display. */
    fun close() {
        if (closed) return
        closed = true
        stopBlackWatch()
        val id = displayId
        displayId = -1
        surface = null
        if (id > 0) {
            ShizukuAppBridge.closeApp(appContext, id)
            ShizukuAppBridge.releaseDisplay(appContext, id)
        }
        phase = Phase.IDLE
        updateInUse()
        stateListener?.invoke()
    }

    // ------------------------------------------------------------------ interno

    private fun shizukuReady(): Boolean {
        val kind = when (ShizukuManager.status(appContext)) {
            ShizukuManager.Status.READY -> return true
            ShizukuManager.Status.NOT_INSTALLED -> Kind.SHIZUKU_MISSING
            ShizukuManager.Status.NOT_RUNNING -> Kind.SHIZUKU_STOPPED
            ShizukuManager.Status.NO_PERMISSION -> Kind.SHIZUKU_PERMISSION
        }
        fail(Problem(kind))
        return false
    }

    private fun launchApp() {
        val id = displayId
        val component = VirtualApps.launchComponent(appContext, packageName)
        if (component == null) {
            fail(Problem(Kind.NO_APP))
            return
        }
        ShizukuAppBridge.launch(appContext, id, component) { ok, message ->
            if (closed || displayId != id) return@launch
            if (ok) {
                phase = Phase.RUNNING
                problem = null
                recreations = 0
                updateInUse()
                stateListener?.invoke()
                startBlackWatch()
            } else {
                fail(Problem(Kind.FAILED, message))
            }
        }
    }

    private fun fail(problem: Problem) {
        Log.w(TAG, "${problem.kind} ${problem.detail}")
        this.problem = problem
        phase = Phase.ERROR
        stopBlackWatch()
        updateInUse()
        stateListener?.invoke()
    }

    /**
     * Dopo l'apertura si guarda cosa disegna l'app: se per diversi secondi di seguito è tutto nero, l'app nasconde la sua
     * finestra al display virtuale (banche, Poste, video protetti) e si avvisa l'utente, una volta per app. Un'app che si vede
     * non ha niente da dire: il controllo finisce al primo fotogramma con qualcosa. Il nero di un'app che sta partendo dura
     * poco: per questo si comincia dopo qualche secondo e il nero deve durare.
     */
    private fun startBlackWatch() {
        if (watching || packageName in noticed) return
        watching = true
        blackChecks = 0
        blackRun = 0
        handler.postDelayed(blackWatch, BLACK_FIRST_CHECK_MS)
    }

    private fun stopBlackWatch() {
        watching = false
        handler.removeCallbacks(blackWatch)
    }

    private fun checkFrame() {
        if (!watching || closed || phase != Phase.RUNNING) {
            watching = false
            return
        }
        // Con la vista tolta o il telefono che dorme non c'è niente da giudicare (il display è fermo): si ricomincia a contare
        val pixels = if (surface != null && !VirtualScreenPower.asleep) frameProbe?.invoke() else null
        if (pixels == null) {
            blackRun = 0
        } else if (!BlackFrame.looksBlack(pixels)) {
            // L'app si vede
            watching = false
            return
        } else if (++blackRun >= BLACK_SAMPLES) {
            val listener = onNotice
            if (listener != null && noticed.add(packageName)) {
                Log.i(TAG, "the app has shown only black for $blackRun checks")
                watching = false
                listener(Notice.LOOKS_BLACK)
                return
            }
        }
        if (++blackChecks < MAX_BLACK_CHECKS) {
            handler.postDelayed(blackWatch, BLACK_CHECK_MS)
        } else {
            watching = false
        }
    }

    /**
     * Sull'auto l'app è "davanti" da quando si apre fino a quando la vista la tiene: il telefono resta sveglio (se il display
     * è nel suo gruppo), con la schermata di attesa sopra. Con la vista nascosta o l'app chiusa il telefono torna libero.
     */
    private fun updateInUse() {
        val now = inCar && !closed && (phase == Phase.STARTING || phase == Phase.RUNNING) && surface != null
        if (now != inUse) {
            inUse = now
            inUseListener?.invoke(now)
        }
        val needsPhone = now && sharesPhoneGroup
        if (needsPhone != powered) {
            powered = needsPhone
            VirtualScreenPower.setInUse(appContext, this, needsPhone)
        }
    }

    companion object {
        private const val TAG = "VirtualAppSession"
        private const val MAX_RECREATIONS = 1
        private const val MAX_GESTURE_LOGS = 40

        // Controllo del nero: la prima occhiata dopo l'apertura, poi una al secondo; per dire "tutta nera" servono sei occhiate
        // nere di seguito (circa otto secondi dall'apertura) e si smette dopo quaranta
        private const val BLACK_FIRST_CHECK_MS = 3000L
        private const val BLACK_CHECK_MS = 1000L
        private const val BLACK_SAMPLES = 6
        private const val MAX_BLACK_CHECKS = 40

        // Le app per cui si è già detto che restano nere (thread principale): il display si ricrea dopo uno spegnimento o un
        // riavvio del servizio, e l'avviso non si ripete
        private val noticed = HashSet<String>()
    }
}
