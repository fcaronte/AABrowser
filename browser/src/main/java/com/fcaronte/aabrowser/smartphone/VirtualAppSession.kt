package com.fcaronte.aabrowser.smartphone

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Un'app del telefono aperta sul suo display virtuale, per una scheda della Main o per la schermata della Wide.
 *
 * La vista che la mostra (una SurfaceView) dà la sua superficie con [attach] e la toglie con [detach]; la sessione crea il
 * display fidato con Shizuku, ci avvia l'app e gli passa tocchi e tasti. Il display nasce alla prima superficie e resta
 * finché la sessione non è chiusa: con la scheda nascosta si toglie solo la superficie (l'app continua a girare, niente
 * si disegna). Tutto si chiama dal thread principale; i risultati di Shizuku tornano lì.
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

    private var creating = false
    private var closed = false
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var dpi = 0
    private var recreations = 0
    private var inUse = false

    /** Chiamato quando l'app comincia o smette di essere davanti sull'auto: tiene sveglio il telefono (VirtualScreenPower). */
    var inUseListener: ((Boolean) -> Unit)? = null

    val isRunning: Boolean get() = phase == Phase.RUNNING

    /** La vista ha una superficie (o nuove misure): crea il display, o lo ridimensiona e gli ridà la superficie. */
    fun attach(surface: Surface, width: Int, height: Int, dpi: Int) {
        if (closed || !surface.isValid || width <= 0 || height <= 0) return
        this.surface = surface
        this.width = width
        this.height = height
        this.dpi = dpi.coerceAtLeast(60)

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
        problem = null
        val createdWith = surface
        ShizukuAppBridge.createDisplay(appContext, surface, width, height, this.dpi, inCar) { id, message ->
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
            // Nel frattempo la superficie può essere cambiata o sparita
            if (this.surface !== createdWith) {
                ShizukuAppBridge.updateDisplay(appContext, id, this.surface, this.width, this.height, this.dpi)
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
        surface?.let { attach(it, width, height, dpi) }
    }

    /** Tocco in pixel del display (le coordinate della vista: il display ha le sue stesse misure). */
    fun touch(event: MotionEvent) {
        if (displayId > 0 && phase == Phase.RUNNING) ShizukuAppBridge.touch(displayId, event)
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
        val id = displayId
        displayId = -1
        surface = null
        if (id > 0) {
            ShizukuAppBridge.closeApp(appContext, id)
            ShizukuAppBridge.releaseDisplay(appContext, id)
        }
        phase = Phase.IDLE
        updateInUse()
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
            } else {
                fail(Problem(Kind.FAILED, message))
            }
        }
    }

    private fun fail(problem: Problem) {
        Log.w(TAG, "$packageName: ${problem.kind} ${problem.detail}")
        this.problem = problem
        phase = Phase.ERROR
        updateInUse()
    }

    private fun updateInUse() {
        val now = inCar && !closed && phase == Phase.RUNNING && surface != null
        if (now == inUse) return
        inUse = now
        inUseListener?.invoke(now)
    }

    companion object {
        private const val TAG = "VirtualAppSession"
        private const val MAX_RECREATIONS = 1
    }
}
