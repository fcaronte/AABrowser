package com.fcaronte.aabrowser.smartphone

import android.graphics.Bitmap
import android.util.Log
import android.view.TextureView
import androidx.core.graphics.createBitmap

/**
 * Capire se un'app aperta sul display virtuale mostra davvero qualcosa o resta tutta nera.
 *
 * Le app che proteggono la loro finestra (banche, Poste, alcuni video) sul display virtuale vengono disegnate nere, e il
 * sistema non lo dice a nessuno: l'unico modo di saperlo è guardare cosa arriva alla vista. Si campiona la vista in piccolo
 * ([Sampler]) e si giudica il campione ([looksBlack]); l'avviso all'utente parte solo se il nero dura (VirtualAppSession).
 */
object BlackFrame {
    // Misure del campione: duemila punti e rotti bastano a vedere un'interfaccia, anche scura
    const val WIDTH = 64
    const val HEIGHT = 36

    // Un punto è acceso se il suo canale più chiaro supera questo livello (0-255): il nero dei contenuti protetti è 0 esatto
    private const val LIT_LEVEL = 16

    // Quanti punti accesi si tollerano (su 2304) prima di dire che l'app si vede: la vista che ancora si assesta, un pixel rotto
    private const val MAX_LIT = 3

    // Colore con cui si riempie il campione prima di leggere la vista: se resta, la vista non ha dato niente (non è nero)
    private const val NOT_READ = 0xFFFF00FF.toInt()

    /** Il campione (pixel ARGB) è tutto nero, o quasi: nessuna interfaccia, nessun testo, nessun fotogramma di un video. */
    fun looksBlack(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return false
        var lit = 0
        for (pixel in pixels) {
            val brightest = maxOf((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF)
            if (brightest > LIT_LEVEL && ++lit > MAX_LIT) return false
        }
        return true
    }

    /** Legge un campione piccolo di quello che una TextureView mostra ora. Dal thread principale; un solo bitmap, riusato. */
    class Sampler {
        private var bitmap: Bitmap? = null
        private var failureLogged = false

        /** I pixel del campione, o null se la vista non è pronta a darli (non c'è ancora, non si disegna, errore del lettore). */
        fun sample(view: TextureView?): IntArray? {
            if (view == null || !view.isAvailable || !view.isAttachedToWindow) return null
            return try {
                val target = bitmap ?: createBitmap(WIDTH, HEIGHT).also { bitmap = it }
                target.eraseColor(NOT_READ)
                view.getBitmap(target)
                val pixels = IntArray(WIDTH * HEIGHT)
                target.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                if (pixels.all { it == NOT_READ }) null else pixels
            } catch (error: RuntimeException) {
                // Una sola riga: il controllo si ripete ogni secondo
                if (!failureLogged) {
                    failureLogged = true
                    Log.w(TAG, "cannot read the view: $error")
                }
                null
            }
        }
    }

    private const val TAG = "BlackFrame"
}
