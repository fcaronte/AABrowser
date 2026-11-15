package com.fcaronte.aabrowser.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.core.graphics.createBitmap
import com.fcaronte.aabrowser.settings.SidebarAction
import com.fcaronte.aabrowser.ui.dockIcon

/**
 * Le icone dei tasti del dock (la sidebar) della Wide: le stesse della Main (le icone Material di `MainDock`, `dockIcon`),
 * disegnate su un bitmap bianco su fondo trasparente (utente, 15/11/2026: «non puoi usare le stesse icone della sidebar della
 * main?»). La sidebar non ha le icone di sistema: la superficie è un display virtuale dell'app, quindi i tracciati di ogni icona
 * si leggono dal suo `ImageVector` e si disegnano su un `Canvas`.
 */
internal object WideDockIcons {
    private const val SIZE = 96

    /**
     * L'icona di [action]. [state] conta solo per due tasti: AdBlock = il sito è escluso (scudo barrato), desktop = il sito
     * è in modalità desktop (telefono al posto del monitor).
     */
    fun bitmap(action: SidebarAction, state: Boolean = false): Bitmap {
        val vector = action.dockIcon(
            adBlockOff = if (action == SidebarAction.ADBLOCK) state else null,
            desktopModeOn = if (action == SidebarAction.DESKTOP) state else null
        )
        val bitmap = createBitmap(SIZE, SIZE)
        val canvas = Canvas(bitmap)
        canvas.scale(SIZE / vector.viewportWidth, SIZE / vector.viewportHeight)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        draw(canvas, vector.root, paint)
        return bitmap
    }

    private fun draw(canvas: Canvas, group: VectorGroup, paint: Paint) {
        for (node in group) {
            when (node) {
                is VectorPath -> {
                    val path = node.pathData.toPath().asAndroidPath()
                    path.fillType = if (node.pathFillType == PathFillType.EvenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                    canvas.drawPath(path, paint)
                }

                is VectorGroup -> draw(canvas, node, paint)
                else -> Unit
            }
        }
    }
}
