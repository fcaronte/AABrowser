package com.fcaronte.aabrowser.smartphone

import android.os.Handler
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Trasforma i gesti di Android Auto in flussi di tocchi veri per il display di un'app.
 *
 * Alla Wide l'host non passa i tocchi: gira un suo riconoscitore di gesti e chiama la schermata con un tocco (un punto),
 * scorrimenti (le distanze, come `GestureDetector`: posizione prima meno posizione dopo), un fling (la velocità) e
 * passi di pizzico, ma **mai la posizione del dito durante un trascinamento**. Allora:
 *  - **tocco**: giù e su nel punto, a [TAP_MS] di distanza;
 *  - **scorrimento**: un dito messo al centro dell'area, spostato prima oltre la soglia di tocco nella direzione dello
 *    scorrimento (le viste la tolgono, così nessuna distanza va persa), poi di ogni distanza; alzato [DRAG_END_MS] dopo
 *    l'ultimo passo o al bordo del display (il passo dopo riparte dal centro);
 *  - **fling**: poche mosse veloci alla velocità data e poi su, così il VelocityTracker dell'app vede un fling;
 *  - **pizzico**: due dita attorno al punto, a distanza moltiplicata per ogni fattore, alzate [PINCH_END_MS] dopo.
 *
 * Tutto gira sul thread di [handler], in ordine; ogni gesto nuovo chiude prima quello di prima. [sink] riceve l'evento e
 * lo copia se lo tiene (qui viene riciclato appena dopo).
 */
class VirtualTouch(private val handler: Handler, private val sink: (MotionEvent) -> Unit) {

    private var width = 1
    private var height = 1
    private var slop = 10f
    private var centerX = 0f
    private var centerY = 0f
    private var mode = IDLE
    private var generation = 0
    private var downTime = 0L
    private var x = 0f // il dito (tocco e scorrimento) o il centro del pizzico
    private var y = 0f
    private var span = 0f // metà della distanza fra le dita del pizzico

    private val end = Runnable { endNow() }
    private val properties = arrayOf(MotionEvent.PointerProperties(), MotionEvent.PointerProperties())
    private val coords = arrayOf(MotionEvent.PointerCoords(), MotionEvent.PointerCoords())

    init {
        for (i in 0..1) {
            properties[i].id = i
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER
            coords[i].pressure = 1f
            coords[i].size = 1f
        }
    }

    /** Misure del display e densità: da qui la soglia di tocco e il punto da cui parte lo scorrimento (il centro). */
    fun area(width: Int, height: Int, dpi: Int) {
        handler.post {
            endNow()
            this.width = max(1, width)
            this.height = max(1, height)
            slop = 10f * dpi / 160f // un po' più degli 8 dp di soglia di ViewConfiguration
            centerX = clampX(this.width / 2f)
            centerY = clampY(this.height / 2f)
        }
    }

    fun click(px: Float, py: Float) {
        handler.post {
            endNow()
            x = clampX(px)
            y = clampY(py)
            begin(TAP)
            handler.postDelayed(end, TAP_MS)
        }
    }

    /** Distanze di uno scorrimento di Android Auto (posizione prima meno posizione dopo). */
    fun scroll(dx: Float, dy: Float) {
        handler.post {
            if (mode != DRAG) startDrag(-dx, -dy)
            handler.removeCallbacks(end)
            if (moveBy(-dx, -dy)) endNow() else handler.postDelayed(end, DRAG_END_MS)
        }
    }

    /** Velocità del dito in pixel al secondo. */
    fun fling(vx: Float, vy: Float) {
        handler.post {
            if (mode != DRAG) startDrag(vx, vy)
            handler.removeCallbacks(end)
            flingStep(generation, vx, vy, 0)
        }
    }

    fun scale(focusX: Float, focusY: Float, factor: Float) {
        handler.post {
            if (factor <= 0f || factor.isNaN()) return@post
            if (mode != PINCH) {
                endNow()
                x = clampX(focusX)
                y = clampY(focusY)
                span = max(slop * 4, min(width, height) / 8f)
                begin(PINCH)
            }
            x = clampX(focusX)
            y = clampY(focusY)
            span = max(slop, min(span * factor, max(width, height) / 2f))
            emitTwo(MotionEvent.ACTION_MOVE)
            handler.removeCallbacks(end)
            handler.postDelayed(end, PINCH_END_MS)
        }
    }

    /** Alza ogni dito subito (la superficie sparisce, l'app cambia). */
    fun cancel() {
        handler.post { endNow() }
    }

    // ------------------------------------------------------------------ solo sul thread del gestore

    private fun startDrag(dirX: Float, dirY: Float) {
        endNow()
        x = centerX
        y = centerY
        begin(DRAG)
        val length = hypot(dirX, dirY)
        if (length > 0f) moveBy(dirX / length * slop, dirY / length * slop)
    }

    private fun flingStep(gen: Int, vx: Float, vy: Float, index: Int) {
        if (gen != generation || mode != DRAG) return
        val dt = FLING_STEP_MS / 1000f
        if (moveBy(vx * dt, vy * dt) || index + 1 >= FLING_STEPS) {
            endNow()
        } else {
            handler.postDelayed({ flingStep(gen, vx, vy, index + 1) }, FLING_STEP_MS)
        }
    }

    /** Sposta il dito del trascinamento; true se ha toccato il bordo del display. */
    private fun moveBy(dx: Float, dy: Float): Boolean {
        val nextX = x + dx
        val nextY = y + dy
        x = clampX(nextX)
        y = clampY(nextY)
        emitOne(MotionEvent.ACTION_MOVE, x, y)
        return x != nextX || y != nextY
    }

    private fun begin(newMode: Int) {
        generation++
        mode = newMode
        downTime = SystemClock.uptimeMillis()
        if (newMode == PINCH) {
            emitOne(MotionEvent.ACTION_DOWN, x - span, y)
            emitTwo(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        } else {
            emitOne(MotionEvent.ACTION_DOWN, x, y)
        }
    }

    private fun endNow() {
        handler.removeCallbacks(end)
        if (mode == PINCH) {
            emitTwo(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
            emitOne(MotionEvent.ACTION_UP, clampX(x - span), y)
        } else if (mode != IDLE) {
            emitOne(MotionEvent.ACTION_UP, x, y)
        }
        mode = IDLE
        generation++
    }

    private fun emitOne(action: Int, px: Float, py: Float) {
        coords[0].x = clampX(px)
        coords[0].y = clampY(py)
        send(action, 1)
    }

    private fun emitTwo(action: Int) {
        coords[0].x = clampX(x - span)
        coords[0].y = y
        coords[1].x = clampX(x + span)
        coords[1].y = y
        send(action, 2)
    }

    private fun send(action: Int, pointers: Int) {
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, pointers, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            sink(event)
        } finally {
            event.recycle()
        }
    }

    private fun clampX(value: Float) = max(0f, min(width - 1f, value))
    private fun clampY(value: Float) = max(0f, min(height - 1f, value))

    companion object {
        const val TAP_MS = 40L
        const val DRAG_END_MS = 150L
        const val PINCH_END_MS = 200L
        private const val FLING_STEP_MS = 8L
        private const val FLING_STEPS = 4
        private const val IDLE = 0
        private const val TAP = 1
        private const val DRAG = 2
        private const val PINCH = 3
    }
}
