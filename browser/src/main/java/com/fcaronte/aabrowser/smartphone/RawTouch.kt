package com.fcaronte.aabrowser.smartphone

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.hypot
import kotlin.math.max

/**
 * I tocchi veri della Main (la vista di un'app del telefono) rifatti puliti per il display virtuale.
 *
 * Alla Wide i tocchi li costruisce [VirtualTouch] da zero (tempi nostri, un dito vero, sorgente touchscreen) e funzionano
 * tutti, scorrimenti compresi. Quelli della Main, passati così come arrivano dall'auto, facevano il tocco ma non lo
 * scorrimento: qui si rifanno con le stesse regole: posizioni e dita sono quelle dell'evento, ma tempi (quelli dell'evento
 * se sono plausibili, altrimenti l'istante in cui arriva), tipo di dito, sorgente, dispositivo, pressione e dimensione sono
 * quelli che il sistema si aspetta da un touchscreen. Un evento rifatto va riciclato da chi lo riceve.
 */
class RawTouch {
    private var downTime = 0L

    // Per il registro: com'è andato l'ultimo gesto (quante mosse, di quanto si è spostato, quanto è durato)
    private var moves = 0
    private var startX = 0f
    private var startY = 0f
    private var startTime = 0L
    private var summary: String? = null

    /** Una copia pulita di [event]; null per gli eventi che non sono tocchi (passaggi del mouse e simili). */
    fun convert(event: MotionEvent): MotionEvent? {
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> Unit

            else -> return null
        }
        val now = SystemClock.uptimeMillis()
        // I tempi dell'evento valgono se sono di adesso (stesso orologio, non più vecchi di mezzo secondo né nel futuro)
        val time = if (event.eventTime in (now - SANE_PAST_MS)..(now + SANE_FUTURE_MS)) event.eventTime else now
        if (action == MotionEvent.ACTION_DOWN || downTime == 0L) {
            downTime = if (event.downTime in (now - SANE_PAST_MS)..time) event.downTime else time
        }

        val count = event.pointerCount.coerceIn(1, MAX_POINTERS)
        val properties = Array(count) { MotionEvent.PointerProperties() }
        val coords = Array(count) { MotionEvent.PointerCoords() }
        for (i in 0 until count) {
            event.getPointerProperties(i, properties[i])
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER
            event.getPointerCoords(i, coords[i])
            coords[i].pressure = 1f
            coords[i].size = 1f
        }
        val clean = MotionEvent.obtain(
            downTime, max(time, downTime), event.action, count, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )

        track(action, coords[0].x, coords[0].y, time)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) downTime = 0L
        return clean
    }

    /** Com'è andato l'ultimo gesto finito, una volta sola (per il registro); null se non ce n'è uno nuovo. */
    fun takeSummary(): String? = summary.also { summary = null }

    private fun track(action: Int, x: Float, y: Float, time: Long) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                moves = 0
                startX = x
                startY = y
                startTime = time
            }

            MotionEvent.ACTION_MOVE -> moves++

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val moved = hypot(x - startX, y - startY).toInt()
                summary = (if (action == MotionEvent.ACTION_UP) "touch gesture: " else "touch gesture cancelled: ") +
                        "$moves moves, ${moved}px, ${time - startTime}ms"
            }
        }
    }

    private companion object {
        const val SANE_PAST_MS = 500L
        const val SANE_FUTURE_MS = 5L
        const val MAX_POINTERS = 10
    }
}
