package com.fcaronte.aabrowser.ui

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Offset
import com.fcaronte.aabrowser.utils.BtInput
import com.fcaronte.aabrowser.utils.BtInputRouter
import com.fcaronte.aabrowser.utils.DrivingState

/**
 * La Main in auto come destinazione del mouse e della tastiera Bluetooth del telefono ([BtInputRouter]). Il pulsante sinistro
 * diventa un dito sulla finestra dell'auto (gli stessi tocchi che manda l'auto: premere, trascinare, lasciare), la rotellina
 * uno scorrimento del mouse e i tasti vanno a quello che ha il focus nella finestra (la pagina o un campo di testo). Gli
 * eventi entrano dalla radice della finestra come quelli veri, quindi li ricevono anche le schede delle app del telefono
 * sullo schermo virtuale. Il puntatore si disegna in [BtCursorOverlay]; i tasti rapidi li esegue `MainScreen`.
 *
 * [decor] = la radice della finestra dell'auto, [isResumed] = la Main è davanti sull'auto.
 */
class MainBtTarget(
    private val decor: () -> View?,
    private val isResumed: () -> Boolean,
) : BtInputRouter.Target {

    override val label = "CarWeb"

    private var downTime = 0L

    override fun size(): IntArray? = decor()?.takeIf { it.width > 0 && it.height > 0 }?.let { intArrayOf(it.width, it.height) }

    // In movimento, senza la Modalità passeggero, la Main è coperta: il mouse e la tastiera non passano sotto la copertura
    override fun isInFront(): Boolean = isResumed() && !DrivingState.isLocked

    override fun cursor(x: Float, y: Float, visible: Boolean) {
        BtCursor.position.value = if (visible) Offset(x, y) else null
    }

    override fun pointer(x: Float, y: Float, action: BtInputRouter.PointerAction) {
        val view = decor() ?: return
        val now = SystemClock.uptimeMillis()
        val motion = when (action) {
            BtInputRouter.PointerAction.DOWN -> {
                downTime = now
                MotionEvent.ACTION_DOWN
            }

            BtInputRouter.PointerAction.MOVE -> MotionEvent.ACTION_MOVE
            BtInputRouter.PointerAction.UP -> MotionEvent.ACTION_UP
        }
        // Un dito vero sul touchscreen, come i tocchi dell'auto: pagine, schede di Compose e viste delle app li prendono tutti
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        val event = MotionEvent.obtain(
            downTime, now, motion, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            view.dispatchTouchEvent(event)
        } catch (e: Exception) {
            Log.w(TAG, "pointer $action: ${e.javaClass.simpleName}")
        } finally {
            event.recycle()
        }
    }

    override fun wheel(x: Float, y: Float, horizontal: Float, vertical: Float) {
        val view = decor() ?: return
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal)
                setAxisValue(MotionEvent.AXIS_VSCROLL, vertical)
            }
        )
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        try {
            val handled = view.dispatchGenericMotionEvent(event)
            if (!handled) Log.d(TAG, "wheel not taken at ${x.toInt()},${y.toInt()}")
        } catch (e: Exception) {
            Log.w(TAG, "wheel: ${e.javaClass.simpleName}")
        } finally {
            event.recycle()
        }
    }

    override fun key(event: KeyEvent): Boolean {
        val view = decor() ?: return false
        return try {
            // Senza nessuna vista con il focus i tasti non andrebbero da nessuna parte: si dà il focus alla prima che lo prende
            // (di solito la schermata di Compose, e Tab, frecce e Invio muovono e usano i suoi tasti). Una pagina ha il suo
            // dopo un clic
            if (event.action == KeyEvent.ACTION_DOWN && view.findFocus() == null) view.requestFocus()
            view.dispatchKeyEvent(event)
        } catch (e: Exception) {
            Log.w(TAG, "key: ${e.javaClass.simpleName}")
            false
        }
    }

    override fun shortcut(shortcut: BtInput.Shortcut): Boolean = BtShortcuts.send(shortcut)

    /** La Main si ferma: il puntatore non resta disegnato. */
    fun release() {
        BtCursor.position.value = null
    }

    private companion object {
        const val TAG = "BtInput"
    }
}
