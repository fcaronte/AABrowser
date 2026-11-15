package com.fcaronte.aabrowser.utils

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.fcaronte.aabrowser.settings.AppSettings

/**
 * Smista il mouse e la tastiera Bluetooth del telefono verso lo schermo dell'auto che è davanti (la Main o la Wide).
 *
 * Chi riceve è `BtInputActivity`, sul telefono: finché è aperta cattura il puntatore (movimenti relativi, pulsanti,
 * rotellina) e i tasti, e li passa qui. Qui il puntatore ha una posizione sola, nelle coordinate dello schermo che lo riceve,
 * con la velocità scelta nelle Impostazioni; i pulsanti diventano premere e lasciare (il sinistro) o un comando (destro e
 * laterali: indietro e avanti), i tasti diventano tasti rapidi ([BtInput.shortcutFor]) o vanno alla pagina così come sono.
 * Gli schermi dell'auto si registrano ([register]) quando sono davanti all'utente e si tolgono quando non lo sono più.
 *
 * Solo dal thread principale. Nei log solo coordinate e nomi di tasti, mai indirizzi.
 */
object BtInputRouter {

    private const val TAG = "BtInput"

    // Le prime righe di log di ogni apertura bastano a capire cosa è arrivato e dove è andato
    private const val LOG_LIMIT = 40

    enum class PointerAction { DOWN, MOVE, UP }

    /** Uno schermo dell'auto che riceve l'input. */
    interface Target {
        /** Il nome mostrato sul telefono ("CarWeb", "CarWebWide"). */
        val label: String

        /** Larghezza e altezza in pixel della superficie su cui si muove il puntatore; null finché non c'è. */
        fun size(): IntArray?

        /** Davanti all'utente adesso e usabile (non coperto dall'avviso di guida): altrimenti non riceve niente. */
        fun isInFront(): Boolean

        /** Il puntatore va disegnato in ([x], [y]) se [visible], altrimenti nascosto. */
        fun cursor(x: Float, y: Float, visible: Boolean)

        /** Il pulsante sinistro: premuto, trascinato (con il pulsante premuto) e lasciato, nel punto indicato. */
        fun pointer(x: Float, y: Float, action: PointerAction)

        /** La rotellina in ([x], [y]): scatti orizzontali (positivi a destra) e verticali (positivi verso l'alto). */
        fun wheel(x: Float, y: Float, horizontal: Float, vertical: Float)

        /** Un tasto per la pagina o il campo che ha il focus; true se l'ha preso. */
        fun key(event: KeyEvent): Boolean

        /** Un comando di navigazione; false se qui non esiste (le schede nella Wide). */
        fun shortcut(shortcut: BtInput.Shortcut): Boolean
    }

    private val targets = ArrayList<Target>()
    private val handler = Handler(Looper.getMainLooper())
    private var current: Target? = null
    private var x = 0f
    private var y = 0f
    private var buttons = 0
    private var primaryDown = false

    // I tasti rapidi già eseguiti alla pressione: anche le ripetizioni e il rilascio restano fuori dalla pagina
    private val consumedKeys = HashSet<Int>()
    private var logged = 0

    private val hideCursor = Runnable {
        if (!primaryDown) current?.cursor(x, y, false)
    }

    /** La schermata del telefono che riceve è aperta: solo allora il resto del mondo la tiene in conto. */
    var capturing = false
        private set

    fun register(target: Target) {
        if (target !in targets) targets.add(target)
    }

    fun unregister(target: Target) {
        targets.remove(target)
        if (current === target) {
            handler.removeCallbacks(hideCursor)
            target.cursor(x, y, false)
            current = null
            primaryDown = false
            buttons = 0
        }
    }

    /** La schermata del telefono si apre o torna davanti. */
    fun startCapture() {
        capturing = true
        logged = 0
        Log.d(TAG, "Capture started")
    }

    /** La schermata del telefono si chiude o va dietro: un pulsante rimasto premuto si lascia, il puntatore sparisce. */
    fun stopCapture() {
        capturing = false
        handler.removeCallbacks(hideCursor)
        val target = current
        if (target != null) {
            if (primaryDown) target.pointer(x, y, PointerAction.UP)
            target.cursor(x, y, false)
        }
        primaryDown = false
        buttons = 0
        consumedKeys.clear()
        Log.d(TAG, "Capture stopped")
    }

    /** Il nome dello schermo che riceverebbe adesso l'input, null se nessuno è davanti. */
    fun frontLabel(): String? = pick()?.label

    private fun pick(): Target? = targets.lastOrNull { it.isInFront() }

    private fun select(target: Target, size: IntArray): Boolean {
        if (size[0] <= 0 || size[1] <= 0) return false
        if (target !== current) {
            current?.cursor(x, y, false)
            current = target
            primaryDown = false
            buttons = 0
            val centre = BtInput.centre(size[0], size[1])
            x = centre.x
            y = centre.y
            Log.d(TAG, "Target ${target.label} ${size[0]}x${size[1]}")
        } else {
            x = x.coerceIn(0f, (size[0] - 1).toFloat())
            y = y.coerceIn(0f, (size[1] - 1).toFloat())
        }
        return true
    }

    /**
     * Un evento del mouse catturato (movimento relativo, pulsanti, rotellina). Restituisce true se è stato preso. Un
     * touchpad catturato dà posizioni assolute del pad, non spostamenti: da quello si prendono solo i pulsanti.
     */
    fun onPointer(event: MotionEvent): Boolean {
        val target = pick() ?: return false
        val size = target.size() ?: return false
        if (!select(target, size)) return false

        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (horizontal != 0f || vertical != 0f) {
                log("wheel h=$horizontal v=$vertical at ${x.toInt()},${y.toInt()}")
                target.wheel(x, y, horizontal, vertical)
                showCursor(target)
            }
            return true
        }

        val relative = (event.source and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE ||
            (event.source and InputDevice.SOURCE_TRACKBALL) == InputDevice.SOURCE_TRACKBALL
        if (relative) {
            // Gli spostamenti raggruppati in un evento solo: quelli vecchi sono nella storia
            var dx = event.x
            var dy = event.y
            for (i in 0 until event.historySize) {
                dx += event.getHistoricalX(i)
                dy += event.getHistoricalY(i)
            }
            if (dx != 0f || dy != 0f) {
                val point = BtInput.moved(x, y, dx, dy, AppSettings.btPointerSpeed.value, size[0], size[1])
                x = point.x
                y = point.y
                if (primaryDown) target.pointer(x, y, PointerAction.MOVE)
                showCursor(target)
            }
        }

        for (change in BtInput.changes(buttons, event.buttonState)) {
            log("button ${change.button} ${if (change.pressed) "down" else "up"} at ${x.toInt()},${y.toInt()}")
            when (change.button) {
                BtInput.Button.PRIMARY ->
                    if (change.pressed) {
                        primaryDown = true
                        target.pointer(x, y, PointerAction.DOWN)
                    } else if (primaryDown) {
                        primaryDown = false
                        target.pointer(x, y, PointerAction.UP)
                    }

                else -> if (change.pressed) BtInput.shortcutFor(change.button)?.let { target.shortcut(it) }
            }
            showCursor(target)
        }
        buttons = event.buttonState
        return true
    }

    /**
     * Un tasto della tastiera, dalla schermata del telefono. true = preso (tasto rapido o consegnato alla pagina), false =
     * resta al telefono (volume, indietro, tasti multimediali) o nessuno schermo dell'auto è davanti.
     */
    fun onKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (!BtInput.forwardable(code)) return false
        if (event.action == KeyEvent.ACTION_UP && consumedKeys.remove(code)) return true
        if (event.action == KeyEvent.ACTION_DOWN && code in consumedKeys) return true
        val target = pick() ?: return false

        if (event.action == KeyEvent.ACTION_DOWN) {
            val shortcut = BtInput.shortcutFor(code, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed)
            if (shortcut != null) {
                log("shortcut $shortcut")
                consumedKeys.add(code)
                target.shortcut(shortcut)
                return true
            }
        }
        val taken = target.key(event)
        log("key ${KeyEvent.keyCodeToString(code)} ${if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"} -> $taken")
        return taken
    }

    private fun showCursor(target: Target) {
        target.cursor(x, y, true)
        handler.removeCallbacks(hideCursor)
        handler.postDelayed(hideCursor, BtInput.CURSOR_HIDE_MS)
    }

    private fun log(text: String) {
        if (logged >= LOG_LIMIT) return
        logged++
        Log.d(TAG, text)
    }
}
