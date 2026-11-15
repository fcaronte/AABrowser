package com.fcaronte.aabrowser.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import com.fcaronte.aabrowser.utils.BtInput
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Il lato della Main del mouse e della tastiera Bluetooth del telefono ([com.fcaronte.aabrowser.utils.BtInputRouter]): dove
 * sta il puntatore da disegnare e i tasti rapidi che la schermata deve eseguire (indietro, avanti, schede, ricerca).
 */
object BtCursor {
    /** La punta del puntatore nelle coordinate della finestra dell'auto; null = nascosto. */
    val position = mutableStateOf<Offset?>(null)
}

object BtShortcuts {
    val events = MutableSharedFlow<BtInput.Shortcut>(extraBufferCapacity = 8)

    /** Manda il comando alla schermata; false se non c'è nessuno ad ascoltare (la Main non è composta). */
    fun send(shortcut: BtInput.Shortcut): Boolean = events.subscriptionCount.value > 0 && events.tryEmit(shortcut)
}

/**
 * Il puntatore del mouse Bluetooth: una freccia bianca con il bordo nero, che si vede su qualunque sfondo, sopra a tutto. Non
 * riceve né ferma tocchi (non ha niente che li prenda): i clic li manda la Main alla finestra come tocchi.
 */
@Composable
fun BtCursorOverlay(modifier: Modifier = Modifier) {
    // La finestra dell'auto può avere i margini di sistema: le coordinate del puntatore sono della finestra, quelle del disegno
    // di questa vista
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier = modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val tip = BtCursor.position.value
        if (tip != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val unit = 24.dp.toPx()
                val at = tip - origin
                val arrow = Path().apply {
                    moveTo(at.x, at.y)
                    lineTo(at.x, at.y + 0.85f * unit)
                    lineTo(at.x + 0.22f * unit, at.y + 0.65f * unit)
                    lineTo(at.x + 0.38f * unit, at.y + 1.0f * unit)
                    lineTo(at.x + 0.52f * unit, at.y + 0.94f * unit)
                    lineTo(at.x + 0.37f * unit, at.y + 0.60f * unit)
                    lineTo(at.x + 0.66f * unit, at.y + 0.60f * unit)
                    close()
                }
                drawPath(arrow, Color.White)
                drawPath(arrow, Color.Black, style = Stroke(width = 0.08f * unit, join = StrokeJoin.Round))
            }
        }
    }
}
