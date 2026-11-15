package com.fcaronte.aabrowser.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Il puntatore del mouse Bluetooth sulla superficie della Wide (BtInputRouter): una freccia bianca con il bordo nero, che si
 * vede su qualunque sfondo. Sta sopra a tutto e non prende tocchi (i clic li manda la Wide alla pagina). [unit] = l'altezza
 * della freccia in pixel; la punta, il punto che conta, è l'angolo in alto a sinistra, spostato dello spessore del bordo.
 */
internal class BtCursorView(context: Context, private val unit: Float) : View(context) {

    private val inset = unit * 0.08f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = unit * 0.08f
        strokeJoin = Paint.Join.ROUND
    }
    private val arrow = Path().apply {
        moveTo(inset, inset)
        lineTo(inset, inset + 0.85f * unit)
        lineTo(inset + 0.22f * unit, inset + 0.65f * unit)
        lineTo(inset + 0.38f * unit, inset + 1.0f * unit)
        lineTo(inset + 0.52f * unit, inset + 0.94f * unit)
        lineTo(inset + 0.37f * unit, inset + 0.60f * unit)
        lineTo(inset + 0.66f * unit, inset + 0.60f * unit)
        close()
    }

    /** Larghezza e altezza della vista, con il bordo. */
    val widthPx: Int get() = (unit * 0.66f + inset * 2f).toInt() + 2
    val heightPx: Int get() = (unit + inset * 2f).toInt() + 2

    init {
        isClickable = false
        isFocusable = false
        visibility = GONE
    }

    /** La punta va in ([x], [y]) della superficie; se [visible] è falso il puntatore sparisce. */
    fun moveTip(x: Float, y: Float, visible: Boolean) {
        translationX = x - inset
        translationY = y - inset
        visibility = if (visible) VISIBLE else GONE
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, stroke)
    }
}
