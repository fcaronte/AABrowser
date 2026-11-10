package com.fcaronte.aabrowser.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Al ritorno davanti la pagina della scheda visibile deve ridisegnarsi. Se entro [DRAW_TIMEOUT_MS] non arriva un
 * fotogramma la WebView viene nascosta e rimostrata, come uscire e rientrare nell'app (utente, 10/11/2026: al primo
 * avvio in auto, dopo un video e la home di Android Auto, la pagina restava ferma fino a un cambio di pagina).
 * Le righe [TAG] del registro, anche nelle build di rilascio, dicono cosa è successo.
 */
internal class PageResumeCheck(private val webView: WebView) {
    private val handler = Handler(Looper.getMainLooper())
    private val startedAt = SystemClock.uptimeMillis()
    private var drawn = false

    fun start() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.VISUAL_STATE_CALLBACK)) return
        WebViewCompat.postVisualStateCallback(webView, startedAt) { drawn = true }
        handler.postDelayed(::check, DRAW_TIMEOUT_MS)
    }

    private fun check() {
        if (drawn) return
        Log.w(TAG, "No frame ${elapsed()} ms after resume (${state()}): hiding and showing the page")
        webView.visibility = View.INVISIBLE
        handler.postDelayed({
            webView.visibility = View.VISIBLE
            WebViewCompat.postVisualStateCallback(webView, startedAt) {
                Log.d(TAG, "Page drawn again ${elapsed()} ms after resume")
            }
        }, SHOW_AGAIN_MS)
    }

    private fun state(): String = "attached=${webView.isAttachedToWindow} shown=${webView.isShown} " +
            "focus=${webView.hasWindowFocus()} size=${webView.width}x${webView.height}"

    private fun elapsed(): Long = SystemClock.uptimeMillis() - startedAt

    companion object {
        const val TAG = "PageResume"
        private const val DRAW_TIMEOUT_MS = 2000L
        private const val SHOW_AGAIN_MS = 100L
    }
}
