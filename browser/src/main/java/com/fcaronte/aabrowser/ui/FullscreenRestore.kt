package com.fcaronte.aabrowser.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.fcaronte.aabrowser.utils.BrowserJavascript
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Di nuovo a schermo intero al ritorno dal background (BrowserJavascript.getRestoreFullscreenScript): il browser lo
 * concede solo dentro un tocco, quindi lo script mette uno strato invisibile sulla pagina e qui si tocca la WebView.
 * Solo a WebView davvero tornata visibile (finestra col focus, un fotogramma disegnato): toccata subito dopo il
 * ritorno, la pagina poteva restare ferma come nascosta e lo strato prendeva i tocchi per minuti.
 * [syntheticTap]: acceso durante il tocco dell'app, che non conta come tocco dell'utente. [isFullscreen]: lo schermo
 * intero è arrivato (la WebView ha passato la sua vista).
 */
internal class FullscreenRestore(
    private val webView: WebView,
    private val syntheticTap: AtomicBoolean,
    private val isFullscreen: () -> Boolean,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val startedAt = SystemClock.uptimeMillis()

    fun start() {
        if (webView.hasWindowFocus()) {
            whenDrawn(::arm)
            return
        }
        val observer = webView.viewTreeObserver
        val listener = object : ViewTreeObserver.OnWindowFocusChangeListener {
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (!hasFocus) return
                if (observer.isAlive) observer.removeOnWindowFocusChangeListener(this)
                whenDrawn(::arm)
            }
        }
        observer.addOnWindowFocusChangeListener(listener)
        // Il focus non arriva (finestra coperta da altro): si lascia stare
        handler.postDelayed({
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
        }, READY_TIMEOUT_MS)
    }

    private fun isReady(): Boolean = webView.isAttachedToWindow && webView.isShown && webView.hasWindowFocus()

    // Al prossimo fotogramma della pagina: arriva solo con la WebView visibile e la pagina che disegna
    private fun whenDrawn(block: () -> Unit) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.VISUAL_STATE_CALLBACK)) {
            WebViewCompat.postVisualStateCallback(webView, startedAt) { block() }
        } else {
            handler.postDelayed(block, FALLBACK_DELAY_MS)
        }
    }

    private fun arm() {
        if (!isReady()) {
            log("not visible, skipped")
            return
        }
        webView.evaluateJavascript(BrowserJavascript.getRestoreFullscreenScript()) { result ->
            log("layer $result")
            // Il tocco quando anche lo strato è disegnato
            if (result == "\"armed\"") whenDrawn(::tap)
        }
    }

    private fun tap() {
        if (!isReady()) {
            webView.evaluateJavascript(BrowserJavascript.getCancelFullscreenRestoreScript(), null)
            log("not visible, layer removed")
            return
        }
        // Il tocco solo se lo strato c'è ancora: senza arriverebbe alla pagina (pausa, link)
        webView.evaluateJavascript(BrowserJavascript.getFullscreenRestoreArmedScript()) { armed ->
            if (armed == "true") dispatchTap() else log("layer gone, no tap")
        }
    }

    private fun dispatchTap() {
        val x = webView.width / 2f
        val y = webView.height / 2f
        val downTime = SystemClock.uptimeMillis()
        syntheticTap.set(true)
        try {
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                webView.dispatchTouchEvent(event)
                event.recycle()
            }
        } finally {
            syntheticTap.set(false)
        }
        log("tap sent")
        // Senza schermo intero lo strato non deve restare a prendere i tocchi: lo toglie l'app, senza contare sui
        // timer della pagina
        handler.postDelayed({
            if (isFullscreen()) {
                log("fullscreen back")
            } else {
                webView.evaluateJavascript(BrowserJavascript.getCancelFullscreenRestoreScript(), null)
                log("no fullscreen, layer removed")
            }
        }, LAYER_CHECK_MS)
    }

    // Anche nelle build di rilascio, con le righe di PageResumeCheck: dicono dove si ferma il ritorno davanti
    private fun log(message: String) {
        Log.d(PageResumeCheck.TAG, "Fullscreen restore: $message (+${SystemClock.uptimeMillis() - startedAt} ms)")
    }

    private companion object {
        const val READY_TIMEOUT_MS = 5000L
        const val FALLBACK_DELAY_MS = 500L
        const val LAYER_CHECK_MS = 1500L
    }
}
