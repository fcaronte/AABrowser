package com.fcaronte.aabrowser.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import com.fcaronte.aabrowser.settings.SidebarAction
import kotlin.math.cos
import kotlin.math.sin

/**
 * Le icone dei tasti del dock (la sidebar) della Wide: disegnate a codice, tratti bianchi su fondo trasparente, come le
 * altre icone della Wide (la sidebar non ha le icone di sistema: la superficie è un display virtuale dell'app).
 */
internal object WideDockIcons {
    private const val SIZE = 96

    /**
     * L'icona di [action]. [state] conta solo per due tasti: AdBlock = il sito è escluso (scudo barrato), desktop = il sito
     * è in modalità desktop (telefono al posto del monitor).
     */
    fun bitmap(action: SidebarAction, state: Boolean = false): Bitmap {
        val bitmap = createBitmap(SIZE, SIZE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = SIZE * 0.08f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        draw(action, Canvas(bitmap), paint, SIZE.toFloat(), state)
        return bitmap
    }

    private fun draw(action: SidebarAction, c: Canvas, p: Paint, s: Float, state: Boolean) {
        when (action) {
            SidebarAction.HOME -> home(c, p, s)
            SidebarAction.BACK -> chevron(c, p, s, pointsLeft = true)
            SidebarAction.FORWARD -> chevron(c, p, s, pointsLeft = false)
            SidebarAction.RELOAD -> reload(c, p, s)
            SidebarAction.TABS -> tabs(c, p, s)
            SidebarAction.SEARCH -> search(c, p, s)
            SidebarAction.FAVORITE -> star(c, p, s)
            SidebarAction.ADBLOCK -> shield(c, p, s, crossed = state)
            SidebarAction.DESKTOP -> desktop(c, p, s, phone = state)
            SidebarAction.VOICE -> mic(c, p, s)
            SidebarAction.SHARE -> share(c, p, s)
            SidebarAction.SETTINGS -> gear(c, p, s)
            SidebarAction.EXIT -> power(c, p, s)
        }
    }

    private fun home(c: Canvas, p: Paint, s: Float) {
        val roof = Path().apply {
            moveTo(s * 0.18f, s * 0.48f)
            lineTo(s * 0.50f, s * 0.20f)
            lineTo(s * 0.82f, s * 0.48f)
        }
        c.drawPath(roof, p)
        val body = Path().apply {
            moveTo(s * 0.28f, s * 0.44f)
            lineTo(s * 0.28f, s * 0.78f)
            lineTo(s * 0.72f, s * 0.78f)
            lineTo(s * 0.72f, s * 0.44f)
        }
        c.drawPath(body, p)
    }

    // Indietro e avanti: la stessa freccia, una specchiata
    private fun chevron(c: Canvas, p: Paint, s: Float, pointsLeft: Boolean) {
        val tip = if (pointsLeft) 0.35f else 0.65f
        val tail = if (pointsLeft) 0.65f else 0.35f
        val path = Path().apply {
            moveTo(s * tail, s * 0.25f)
            lineTo(s * tip, s * 0.50f)
            lineTo(s * tail, s * 0.75f)
        }
        c.drawPath(path, p)
    }

    private fun reload(c: Canvas, p: Paint, s: Float) {
        val cx = s / 2f
        val cy = s / 2f
        val r = s * 0.30f
        c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -60f, 290f, false, p)

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

    // Schede: due riquadri sovrapposti
    private fun tabs(c: Canvas, p: Paint, s: Float) {
        c.drawRoundRect(RectF(s * 0.20f, s * 0.34f, s * 0.68f, s * 0.80f), s * 0.07f, s * 0.07f, p)
        c.drawRoundRect(RectF(s * 0.32f, s * 0.20f, s * 0.80f, s * 0.66f), s * 0.07f, s * 0.07f, p)
    }

    private fun search(c: Canvas, p: Paint, s: Float) {
        val cx = s * 0.42f
        val cy = s * 0.42f
        val r = s * 0.22f
        c.drawCircle(cx, cy, r, p)
        val d = r * 0.72f
        c.drawLine(cx + d, cy + d, s * 0.78f, s * 0.78f, p)
    }

    // Preferiti: la stella
    private fun star(c: Canvas, p: Paint, s: Float) {
        val cx = s * 0.50f
        val cy = s * 0.53f
        val outer = s * 0.36f
        val inner = outer * 0.42f
        val path = Path()
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) outer else inner
            val angle = Math.toRadians(-90.0 + i * 36.0)
            val x = cx + radius * cos(angle).toFloat()
            val y = cy + radius * sin(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, p)
    }

    // Scudo dell'AdBlock: con la spunta attivo sul sito, barrato se il sito è in whitelist
    private fun shield(c: Canvas, p: Paint, s: Float, crossed: Boolean) {
        val shield = Path().apply {
            moveTo(s * 0.50f, s * 0.16f)
            lineTo(s * 0.78f, s * 0.27f)
            lineTo(s * 0.78f, s * 0.48f)
            quadTo(s * 0.78f, s * 0.72f, s * 0.50f, s * 0.84f)
            quadTo(s * 0.22f, s * 0.72f, s * 0.22f, s * 0.48f)
            lineTo(s * 0.22f, s * 0.27f)
            close()
        }
        c.drawPath(shield, p)
        if (crossed) {
            c.drawLine(s * 0.16f, s * 0.84f, s * 0.84f, s * 0.16f, p)
        } else {
            val check = Path().apply {
                moveTo(s * 0.37f, s * 0.50f)
                lineTo(s * 0.47f, s * 0.60f)
                lineTo(s * 0.64f, s * 0.41f)
            }
            c.drawPath(check, p)
        }
    }

    // Monitor se il sito è in versione mobile (si può passare al desktop), telefono se è già in desktop
    private fun desktop(c: Canvas, p: Paint, s: Float, phone: Boolean) {
        if (phone) {
            c.drawRoundRect(RectF(s * 0.32f, s * 0.16f, s * 0.68f, s * 0.84f), s * 0.12f, s * 0.12f, p)
            c.drawLine(s * 0.44f, s * 0.74f, s * 0.56f, s * 0.74f, p)
        } else {
            c.drawRoundRect(RectF(s * 0.15f, s * 0.20f, s * 0.85f, s * 0.65f), s * 0.08f, s * 0.08f, p)
            c.drawLine(s * 0.50f, s * 0.65f, s * 0.50f, s * 0.80f, p)
            c.drawLine(s * 0.35f, s * 0.80f, s * 0.65f, s * 0.80f, p)
        }
    }

    private fun mic(c: Canvas, p: Paint, s: Float) {
        c.drawRoundRect(RectF(s * 0.38f, s * 0.14f, s * 0.62f, s * 0.56f), s * 0.12f, s * 0.12f, p)
        c.drawArc(RectF(s * 0.26f, s * 0.30f, s * 0.74f, s * 0.70f), 0f, 180f, false, p)
        c.drawLine(s * 0.50f, s * 0.70f, s * 0.50f, s * 0.84f, p)
        c.drawLine(s * 0.36f, s * 0.84f, s * 0.64f, s * 0.84f, p)
    }

    // Condividi: tre punti uniti da due linee
    private fun share(c: Canvas, p: Paint, s: Float) {
        val a = floatArrayOf(s * 0.28f, s * 0.50f)
        val b = floatArrayOf(s * 0.72f, s * 0.26f)
        val d = floatArrayOf(s * 0.72f, s * 0.74f)
        c.drawLine(a[0], a[1], b[0], b[1], p)
        c.drawLine(a[0], a[1], d[0], d[1], p)
        val fill = Paint(p).apply { style = Paint.Style.FILL }
        for (point in listOf(a, b, d)) c.drawCircle(point[0], point[1], s * 0.095f, fill)
    }

    // Impostazioni: un ingranaggio, cerchio con i denti
    private fun gear(c: Canvas, p: Paint, s: Float) {
        val cx = s / 2f
        val cy = s / 2f
        c.drawCircle(cx, cy, s * 0.24f, p)
        c.drawCircle(cx, cy, s * 0.09f, p)
        for (i in 0 until 8) {
            val angle = Math.toRadians(i * 45.0)
            val inner = s * 0.24f
            val outer = s * 0.37f
            c.drawLine(
                cx + inner * cos(angle).toFloat(), cy + inner * sin(angle).toFloat(),
                cx + outer * cos(angle).toFloat(), cy + outer * sin(angle).toFloat(), p
            )
        }
    }

    // Esci: il simbolo dell'accensione, cerchio aperto in alto con la lineetta
    private fun power(c: Canvas, p: Paint, s: Float) {
        val cx = s / 2f
        val cy = s * 0.54f
        val r = s * 0.29f
        c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -60f, 300f, false, p)
        c.drawLine(cx, s * 0.14f, cx, s * 0.50f, p)
    }
}
