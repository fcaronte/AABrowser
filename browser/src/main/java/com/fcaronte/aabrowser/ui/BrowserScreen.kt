package com.fcaronte.aabrowser.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.support.v4.media.session.PlaybackStateCompat
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.fcaronte.aabrowser.CarFrameLayout
import com.fcaronte.aabrowser.CarInputManager
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.mediaservice.MediaSessionManager
import com.fcaronte.aabrowser.model.TabManager
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.utils.AdBlockHost
import com.fcaronte.aabrowser.utils.AdBlockJavascript
import com.fcaronte.aabrowser.utils.AppLog
import com.fcaronte.aabrowser.utils.BrowserJavascript
import com.fcaronte.aabrowser.utils.DaznManager
import com.fcaronte.aabrowser.utils.GoogleLoginManager
import com.fcaronte.aabrowser.utils.InactivityTracker
import com.fcaronte.aabrowser.utils.SpotifyManager
import com.fcaronte.aabrowser.utils.WebViewScriptRouter
import java.io.ByteArrayInputStream
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private fun setDesktopUserAgent(webView: WebView, context: Context): String =
    WebViewScriptRouter.setDesktopUserAgent(webView, context)

private fun setMobileUserAgent(webView: WebView, context: Context) =
    WebViewScriptRouter.setMobileUserAgent(webView, context)

private fun summarizeDaznErrorBody(body: String): String {
    val errorFields = setOf(
        "code", "error_code", "errorcode", "error_description", "message",
        "description", "reason", "type", "status", "request_id", "requestid",
        "correlation_id", "correlationid"
    )
    val containerFields = setOf("error", "errors", "details", "data", "cause", "metadata")

    fun sanitizeValue(value: String): String = value
        .replace(Regex("""(?is)<(script|style)\b[^>]*>.*?</\1>"""), " ")
        .replace(Regex("""(?s)<!--.*?-->"""), " ")
        .replace(Regex("""<[^>]*>"""), " ")
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace(
            Regex("""(?i)(bearer\s+)[A-Za-z0-9._~+/-]+=*"""),
            "$1[REDACTED]"
        )
        .replace(
            Regex("""(?i)("?(?:access[_-]?token|refresh[_-]?token|id[_-]?token|authorization|cookie|password|secret)"?\s*[:=]\s*"?)[^",}\s]+"""),
            "$1[REDACTED]"
        )
        .replace(Regex("""[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}"""), "[REDACTED_EMAIL]")
        .replace(Regex("""\s+"""), " ")
        .take(800)

    fun filter(value: Any?, depth: Int): Any? {
        if (depth > 6) return null
        return when (value) {
            is JSONObject -> JSONObject().apply {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val normalizedKey = key.lowercase()
                    val child = value.opt(key)
                    when {
                        normalizedKey in errorFields && child !is JSONObject && child !is JSONArray ->
                            put(key, sanitizeValue(child.toString()))
                        normalizedKey in containerFields ->
                            filter(child, depth + 1)?.let { put(key, it) }
                    }
                }
            }
            is JSONArray -> JSONArray().apply {
                for (index in 0 until minOf(value.length(), 10)) {
                    filter(value.opt(index), depth + 1)?.let { put(it) }
                }
            }
            else -> null
        }
    }

    return try {
        val filtered = filter(JSONObject(body), 0)?.toString().orEmpty()
        filtered.ifBlank { "No allowlisted error fields" }.take(1000)
    } catch (_: JSONException) {
        sanitizeValue(body).ifBlank { "Empty error body" }
    }
}

private fun isDesktopRequired(url: String?): Boolean = WebViewScriptRouter.isDesktopRequired(url)

private fun safeUrlForLog(url: String?): String =
    AppLog.safeUrlForLog(url)

private fun createPopupWebView(
    context: Context,
    initialUrl: String? = null,
    userAgent: String?,
    onPopupDismiss: () -> Unit
): WebView {
    return WebView(context).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowContentAccess = true
            allowFileAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                WebSettingsCompat.setSafeBrowsingEnabled(this, false)
            }
            if (!userAgent.isNullOrBlank()) {
                userAgentString = userAgent
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        if (!userAgent.isNullOrBlank() && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val chromeVersion = Regex("Chrome/([0-9.]+)")
                .find(userAgent)
                ?.groups?.get(1)?.value
            if (chromeVersion != null) {
                WebViewCompat.addDocumentStartJavaScript(
                    this,
                    BrowserJavascript.getDesktopSpoofScript(chromeVersion),
                    setOf("*")
                )
            }
            WebViewCompat.addDocumentStartJavaScript(
                this,
                GoogleLoginManager.getGoogleOauthFixScript(),
                setOf("*")
            )
            WebViewCompat.addDocumentStartJavaScript(
                this,
                GoogleLoginManager.getPopupInterceptorScript(),
                setOf("*")
            )
        }

        webViewClient = @SuppressLint("MissingOnRenderProcessGone")
        object : WebViewClient() {
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                AppLog.e("PopupWebView", "Error loading popup page: ${error?.description}")
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                AppLog.e("PopupWebView", "Popup HTTP error: ${errorResponse?.statusCode}")
            }

        }
        webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView?) {
                onPopupDismiss()
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    AppLog.d("PopupConsole", "Console message at line ${consoleMessage.lineNumber()}")
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
        if (!initialUrl.isNullOrEmpty()) {
            loadUrl(initialUrl)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun BrowserScreen(
    tabId: String,
    url: String,
    reloadTrigger: Int = 0,
    backTrigger: Int = 0,
    forwardTrigger: Int = 0,
    isDesktopMode: Boolean = false,
    mediaSessionManager: MediaSessionManager? = null,
    carInputManager: CarInputManager? = null,
    desktopModeOverride: Boolean? = null,
    mobileZoomOverride: Float? = null,
    desktopZoomOverride: Float? = null,
    isTabActive: Boolean = true,
    isGlobalSearchActive: Boolean = false,
    isAppDarkOverride: Boolean? = null, // <--- Riceve lo stato in tempo reale da MainScreen
    onPageFinished: (String) -> Unit,
    onWebViewCreated: (WebView) -> Unit = {},
    onFullScreenChange: (Boolean) -> Unit = {},
    onInteraction: () -> Unit = {},
) {
    val globalDisplayScale by AppSettings.displayScale
    val globalDesktopScale by AppSettings.desktopScale
    val autoplayMedia by AppSettings.autoplayMedia
    val darkPages by AppSettings.darkPages
    val multiWindowEnabled by AppSettings.multiWindow

    // Valutazione unificata basata sul MainScreen
    val isAppDark = isAppDarkOverride ?: androidx.compose.foundation.isSystemInDarkTheme()
    val activeDarkPages = isAppDark && darkPages

    val actualDesktopMode = desktopModeOverride ?: isDesktopMode
    var daznDesktopForced by remember(tabId) { mutableStateOf(false) }
    val actualDisplayScale = mobileZoomOverride ?: globalDisplayScale
    val actualDesktopScale = desktopZoomOverride ?: globalDesktopScale
    val isYouTubeAdBlockEnabled by com.fcaronte.aabrowser.settings.AdBlockSettings.isYouTubeEnabled
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val voicePrompt = stringResource(R.string.voice_prompt)

    fun needsDesktopForUrl(pageUrl: String?): Boolean =
        actualDesktopMode ||
                isDesktopRequired(pageUrl) ||
                (daznDesktopForced && pageUrl?.contains("dazn.com", ignoreCase = true) == true)

    fun isDaznUrl(pageUrl: String?): Boolean =
        pageUrl?.contains("dazn.com", ignoreCase = true) == true

    val webViewState = remember { mutableStateOf<WebView?>(null) }
    var webViewReference by webViewState
    
    var showInputPopup by remember { mutableStateOf(value = false) }
    var isListening by remember { mutableStateOf(value = false) }
    var customView by remember { mutableStateOf<android.view.View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var popupWebView by remember { mutableStateOf<WebView?>(null) }

    var lastInjectedUrl by remember { mutableStateOf("") }
    var lastProcessedBackTrigger by remember { mutableIntStateOf(backTrigger) }
    var lastProcessedForwardTrigger by remember { mutableIntStateOf(forwardTrigger) }
    var lastProcessedReloadTrigger by remember { mutableIntStateOf(reloadTrigger) }

    // Sync triggers when tab becomes active to avoid accidental triggers
    LaunchedEffect(isTabActive) {
        if (isTabActive) {
            lastProcessedBackTrigger = backTrigger
            lastProcessedForwardTrigger = forwardTrigger
            lastProcessedReloadTrigger = reloadTrigger
        }
    }

    LaunchedEffect(customView) {
        onFullScreenChange(customView != null)
    }

    LaunchedEffect(isTabActive) {
        if (isTabActive) {
            webViewReference?.let { onWebViewCreated(it) }
        }
    }

    var previousDesktopMode by remember(tabId) { mutableStateOf(actualDesktopMode) }

    LaunchedEffect(actualDesktopMode) {
        if (actualDesktopMode != previousDesktopMode) {
            previousDesktopMode = actualDesktopMode
            webViewReference?.let { wv ->
                val needsDesktop = actualDesktopMode || isDesktopRequired(wv.url)
                if (needsDesktop) {
                    setDesktopUserAgent(wv, context)
                } else {
                    setMobileUserAgent(wv, context)
                }
                AppLog.d("BrowserScreen", "Desktop mode toggled (actualDesktopMode: $actualDesktopMode); forcing page reload")
                wv.reload()
            }
        }
    }

    var isFullscreenPending by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    webViewReference?.let {
                        it.onResume()
                        it.invalidate()
                    }
                    if (isFullscreenPending) {
                        AppLog.d("##BrowserScreen", "Resuming fullscreen")
                        webViewReference?.evaluateJavascript(
                            "document.querySelector('video')?.requestFullscreen().catch(() => {})",
                            null
                        )
                        isFullscreenPending = false
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    if (customView != null) {
                        AppLog.d("##BrowserScreen", "App paused, exiting fullscreen and marking as pending")
                        isFullscreenPending = true
                        customViewCallback?.onCustomViewHidden()
                        customView = null
                        customViewCallback = null
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Effetto separato per la pulizia definitiva quando la scheda viene rimossa dalla composizione
    DisposableEffect(tabId) {
        onDispose {
            webViewState.value?.let { webView ->
                AppLog.d("##BrowserScreen", "Tab closed, destroying WebView: $tabId")
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
            mediaSessionManager?.updatePlaybackState(
                PlaybackStateCompat.STATE_NONE,
                0L,
                1.0f
            )
            mediaSessionManager?.updateMetadata("", "", null, 0L)
        }
    }



    LaunchedEffect(Unit) {
        mediaSessionManager?.connect()
    }

    LaunchedEffect(url, isYouTubeAdBlockEnabled) {
        webViewReference?.let {
            val currentUrl = it.url
            if (currentUrl.isNullOrBlank() || (!currentUrl.contains(url) && !url.contains(currentUrl))) {
                AppLog.d("##BrowserScreen", "Loading host: ${safeUrlForLog(url)}")
                it.loadUrl(url)
            } else if (currentUrl.contains("youtube.com") && isYouTubeAdBlockEnabled && lastInjectedUrl != currentUrl) {
                AppLog.d("##BrowserScreen", "Injecting AdBlock from LaunchedEffect (URL changed)")
                WebViewScriptRouter.injectYouTubeAdBlockIfNeeded(it, currentUrl, isYouTubeAdBlockEnabled, isTabActive)
                lastInjectedUrl = currentUrl
            }
        }
    }

    LaunchedEffect(backTrigger) {
        if (isTabActive && backTrigger > lastProcessedBackTrigger) {
            lastProcessedBackTrigger = backTrigger
            if (customView != null) {
                customViewCallback?.onCustomViewHidden()
                customView = null
                customViewCallback = null
                return@LaunchedEffect
            }
            val webView = webViewReference
            if (webView?.canGoBack() == true) {
                webView.goBack()
            }
        } else {
            lastProcessedBackTrigger = backTrigger
        }
    }

    LaunchedEffect(forwardTrigger) {
        if (isTabActive && forwardTrigger > lastProcessedForwardTrigger) {
            lastProcessedForwardTrigger = forwardTrigger
            val webView = webViewReference
            if (webView?.canGoForward() == true) {
                webView.goForward()
            }
        } else {
            lastProcessedForwardTrigger = forwardTrigger
        }
    }

    LaunchedEffect(reloadTrigger) {
        if (isTabActive && reloadTrigger > lastProcessedReloadTrigger) {
            lastProcessedReloadTrigger = reloadTrigger
            webViewReference?.reload()
        } else {
            lastProcessedReloadTrigger = reloadTrigger
        }
    }



    Box(modifier = Modifier.fillMaxSize().background(if (isAppDark) Color.Black else Color.White)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val webView = object : WebView(context) {
                    override fun onPause() {
                        // Impedisce a Chromium di sospendere l'esecuzione multimediale in background
                    }
                    override fun onWindowVisibilityChanged(visibility: Int) {
                        super.onWindowVisibilityChanged(VISIBLE)
                        if (visibility == VISIBLE) {
                            // Forza l'aggiornamento grafico al ritorno in primo piano
                            invalidate()
                        }
                    }
                }.apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true
                    isLongClickable = true
                    // Notifica l'interazione continua durante swipe/scroll senza bloccare la WebView
                    setOnTouchListener { _, _ ->
                        onInteraction()
                        InactivityTracker.notifyInteraction(5000L, AppSettings.persistentNavigation.value)
                        false
                    }

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        @Suppress("DEPRECATION")
                        databaseEnabled = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        // Se autoplay è disattivato, richiede il tocco dell'utente
                        mediaPlaybackRequiresUserGesture = !autoplayMedia && !isDaznUrl(url)
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        allowContentAccess = true
                        allowFileAccess = true
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        setSupportMultipleWindows(multiWindowEnabled)
                        javaScriptCanOpenWindowsAutomatically = multiWindowEnabled
                    }

                    // Abilita i cookie in modo persistente
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    // Sfondo di base per evitare flash bianchi durante il caricamento
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)

                    // Configurazione Tema Scuro (Nativo + Forza Dark)
                    val isNight = isAppDark
                    AppLog.d("##BrowserScreen", "Theme Factory: isNight=$isNight, darkPages=$darkPages")

                    setBackgroundColor(if (isNight) android.graphics.Color.BLACK else android.graphics.Color.WHITE)

                    if (isNight) {
                        // Per far sì che 'prefers-color-scheme: dark' funzioni, su molte versioni
                        // di WebView è necessario impostre FORCE_DARK_ON.
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON)
                        }

                        // Poi usiamo ALGORITHMIC_DARKENING per decidere se vogliamo il filtro forzato
                        // o se vogliamo solo che il sito usi il suo tema scuro nativo.
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, darkPages)
                        }

                        // Strategia: preferisci sempre il tema del sito se disponibile
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                            WebSettingsCompat.setForceDarkStrategy(settings, WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING)
                        }
                    } else {
                        // Forza tema chiaro
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
                        }
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
                        }
                    }

                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        WebViewCompat.addDocumentStartJavaScript(
                            this,
                            BrowserJavascript.getLifecycleAndMetadataScript(),
                            setOf("*")
                        )
                        WebViewCompat.addDocumentStartJavaScript(
                            this,
                            DaznManager.getAuthProxyScript(),
                            setOf("https://www.dazn.com")
                        )
                        WebViewCompat.addDocumentStartJavaScript(
                            this,
                            GoogleLoginManager.getGoogleOauthFixScript(),
                            setOf("*")
                        )
                        WebViewCompat.addDocumentStartJavaScript(
                            this,
                            GoogleLoginManager.getPopupInterceptorScript(),
                            setOf("*")
                        )
                    }

                    val needsDesktop = actualDesktopMode || isDesktopRequired(url)
                    if (needsDesktop) {
                        val ua = setDesktopUserAgent(this, context)
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                            val chromeVersionRegex = Regex("Chrome/([0-9.]+)")
                            val chromeVersion = chromeVersionRegex.find(ua)?.groups?.get(1)?.value ?: "152.0.0.0"
                            WebViewCompat.addDocumentStartJavaScript(
                                this,
                                BrowserJavascript.getDesktopSpoofScript(chromeVersion),
                                setOf("*")
                            )
                        }
                    } else {
                        setMobileUserAgent(this, context)
                    }

                    // Implementazione robusta per il controllo dei media
                    mediaSessionManager?.apply {
                        onPlay = { evaluateJavascript(BrowserJavascript.PLAY_SCRIPT.trimIndent(), null) }
                        onPause = { evaluateJavascript(BrowserJavascript.PAUSE_SCRIPT.trimIndent(), null) }
                        onStop = { evaluateJavascript(BrowserJavascript.STOP_SCRIPT.trimIndent(), null) }
                        onSkipToNext = { evaluateJavascript(BrowserJavascript.NEXT_SCRIPT.trimIndent(), null) }
                        onSkipToPrevious = { evaluateJavascript(BrowserJavascript.PREVIOUS_SCRIPT.trimIndent(), null) }
                        onSeekTo = { pos -> evaluateJavascript(BrowserJavascript.getSeekScript(pos), null) }
                    }

                    addJavascriptInterface(
                        object {
                            @android.webkit.JavascriptInterface
                            @Suppress("unused")
                            fun onVideoStarted(time: Float) {
                                @Suppress("DEPRECATION")
                                mediaSessionManager?.updatePlaybackState(
                                    android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING,
                                    (time * 1000).toLong(),
                                    1.0f
                                )
                            }

                            @android.webkit.JavascriptInterface
                            @Suppress("unused")
                            fun onMediaTimeUpdate(time: Float, speed: Float, isPlaying: Boolean) {
                                @Suppress("DEPRECATION")
                                mediaSessionManager?.updatePlaybackState(
                                    if (isPlaying) {
                                        android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
                                    } else {
                                        android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
                                    },
                                    (time * 1000).toLong(),
                                    speed
                                )
                            }

                            @android.webkit.JavascriptInterface
                            @Suppress("unused")
                            fun onMediaStatusChanged(isPlaying: Boolean, time: Float, speed: Float) {
                                @Suppress("DEPRECATION")
                                mediaSessionManager?.updatePlaybackState(
                                    if (isPlaying) {
                                        android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
                                    } else {
                                        android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
                                    },
                                    (time * 1000).toLong(),
                                    speed
                                )
                            }

                            @android.webkit.JavascriptInterface
                            @Suppress("unused")
                            fun updateMediaMetadata(
                                title: String,
                                artist: String,
                                albumArtUrl: String,
                                duration: Float,
                            ) {
                                mediaSessionManager?.updateMetadata(
                                    title,
                                    artist,
                                    albumArtUrl,
                                    (duration * 1000).toLong()
                                )
                            }

                            @JavascriptInterface
                            @Suppress("unused")
                            fun recMediaStatus(jsonStr: String) {
                                try {
                                    val json = JSONObject(jsonStr)
                                    val title = json.optString("track", "")
                                    val artist = json.optString("artist", "")
                                    val position = json.optLong("position", 0L)
                                    val duration = json.optLong("duration", 0L)
                                    val isPlaying = json.optBoolean("playing", false)
                                    
                                    var coverUrl = json.optString("cover", "")
                                    // Trucco upscaling: forza la risoluzione alta della cover di Spotify
                                    coverUrl = coverUrl.replace("00004851", "0000b273")

                                    AppLog.d("SpotifyDebug", "recMediaStatus -> $title - $artist (Playing: $isPlaying, Cover: $coverUrl)")

                                    val lowerTitle = title.lowercase()
                                    val ignoredTitles = listOf("buonasera", "buongiorno", "buon pomeriggio", "good evening", "good morning", "spotify", "home", "search", "cerca")
                                    if (title.isNotEmpty() && !ignoredTitles.any { lowerTitle.contains(it) }) {
                                        mediaSessionManager?.updateMetadata(title, artist, coverUrl, duration)
                                        mediaSessionManager?.updatePlaybackState(
                                            if (isPlaying) PlaybackStateCompat.STATE_PLAYING 
                                            else PlaybackStateCompat.STATE_PAUSED,
                                            position,
                                            1.0f
                                        )
                                    }
                                } catch (e: Exception) {
                                    AppLog.e("SpotifyBridge", "Error parsing media status", e)
                                }
                            }

                            @android.webkit.JavascriptInterface
                            @Suppress("unused")
                            fun onMetadataUpdated(title: String, faviconUrl: String, currentUrl: String) {
                                post {
                                    TabManager.updateTabTitle(tabId, title)
                                    val isInvalidFavicon = faviconUrl.isBlank() ||
                                            faviconUrl.contains("google.com", ignoreCase = true) ||
                                            currentUrl.contains("accounts.google.com", ignoreCase = true)
                                    if (!isInvalidFavicon) {
                                        TabManager.updateTabFavicon(tabId, faviconUrl)
                                    }
                                }
                            }

                            @JavascriptInterface
                            @Suppress("unused")
                            fun onDaznEventClicked(url: String) {
                                post {
                                    val currentWebView = webViewReference
                                    if (!daznDesktopForced && currentWebView != null) {
                                        daznDesktopForced = true
                                        setDesktopUserAgent(currentWebView, context)
                                        AppLog.d("Dazn", "DAZN event click detected; switching to desktop mode and loading: $url")
                                        if (!url.isNullOrBlank() && url.startsWith("http")) {
                                            currentWebView.loadUrl(url)
                                        } else {
                                            currentWebView.reload()
                                        }
                                    }
                                }
                            }

                            @android.webkit.JavascriptInterface
                            @Suppress("unused")
                            fun onStartAdBlock() {
                                post {
                                    AppLog.d("##BrowserScreen", "AdBlock request, YouTube enabled: $isYouTubeAdBlockEnabled")
                                    val currentUrl = webViewReference?.url ?: ""
                                    WebViewScriptRouter.injectYouTubeAdBlockIfNeeded(webViewReference, currentUrl, isYouTubeAdBlockEnabled, isTabActive)
                                }
                            }

                            @android.webkit.JavascriptInterface
                            @Suppress("unused")
                            fun onStartInput() {
                                AppLog.d("##BrowserScreen", "onStartInput called, isTabActive: $isTabActive, isGlobalSearch: $isGlobalSearchActive")
                                if (isTabActive && !isGlobalSearchActive) {
                                    post { showInputPopup = true }
                                }
                            }

                            @android.webkit.JavascriptInterface
                            @Suppress("unused")
                            fun injectText(text: String) {
                                post {
                                    evaluateJavascript(
                                        BrowserJavascript.getInjectTextScript(text),
                                        null,
                                    )
                                }
                            }

                            @JavascriptInterface
                            @Suppress("unused")
                            fun openInNewTab(url: String) {
                                post {
                                    AppLog.d("##BrowserScreen", "openInNewTab requested for host=${safeUrlForLog(url)}")
                                    TabManager.openOrSwitchTo(url = url)
                                }
                            }

                            @JavascriptInterface
                            @Suppress("unused")
                            fun openPopup(url: String) {
                                post {
                                    AppLog.d("##BrowserScreen", "openPopup requested for host=${safeUrlForLog(url)}")
                                    val popup = createPopupWebView(
                                        context,
                                        url,
                                        webViewReference?.settings?.userAgentString
                                    ) { popupWebView = null }
                                    popupWebView = popup
                                }
                            }

                            @JavascriptInterface
                            @Suppress("unused")
                            fun proxyFetch(
                                urlString: String,
                                method: String,
                                headersJson: String,
                                body: String?,
                                requestUserAgent: String
                            ): String {
                                return runBlocking(Dispatchers.IO) {
                                    val requestStartedAt = android.os.SystemClock.elapsedRealtime()
                                    try {
                                        val url = URL(urlString)
                                        val userAgent = requestUserAgent.ifBlank {
                                            WebSettings.getDefaultUserAgent(context)
                                        }
                                        var hasAuthorizationHeader = false
                                        var hasCookieHeader = false
                                        val conn = (url.openConnection() as HttpURLConnection).apply {
                                            requestMethod = if (method.isBlank()) "GET" else method
                                            connectTimeout = 15000
                                            readTimeout = 15000
                                            instanceFollowRedirects = true
                                            doInput = true

                                            setRequestProperty("User-Agent", userAgent)
                                            setRequestProperty("Origin", "https://www.dazn.com")
                                            setRequestProperty("Referer", "https://www.dazn.com/")

                                            val cookieDazn = CookieManager.getInstance().getCookie("https://www.dazn.com") ?: ""
                                            val cookieIndazn = CookieManager.getInstance().getCookie("https://www.indazn.com") ?: ""
                                            val cookieTarget = CookieManager.getInstance().getCookie(urlString) ?: ""
                                            val combinedCookie = listOf(cookieDazn, cookieIndazn, cookieTarget)
                                                .flatMap { it.split(";") }
                                                .map { it.trim() }
                                                .filter { it.isNotEmpty() }
                                                .distinct()
                                                .joinToString("; ")
                                            if (combinedCookie.isNotEmpty()) {
                                                hasCookieHeader = true
                                                setRequestProperty("Cookie", combinedCookie)
                                            }

                                            try {
                                                val headersObj = JSONObject(headersJson)
                                                val keys = headersObj.keys()
                                                val restricted = setOf("user-agent", "content-length", "host", "connection", "accept-encoding", "expect", "if-modified-since")
                                                while (keys.hasNext()) {
                                                    val k = keys.next()
                                                    val lowerK = k.lowercase()
                                                    if (!lowerK.startsWith("sec-") && !restricted.contains(lowerK)) {
                                                        if (lowerK == "authorization") {
                                                            hasAuthorizationHeader = headersObj.optString(k).isNotBlank()
                                                        }
                                                        if (lowerK == "cookie") {
                                                            val headerCookie = headersObj.getString(k)
                                                            hasCookieHeader = hasCookieHeader || headerCookie.isNotBlank()
                                                            val combined = if (combinedCookie.isEmpty()) headerCookie else "$combinedCookie; $headerCookie"
                                                            setRequestProperty("Cookie", combined)
                                                        } else {
                                                            setRequestProperty(k, headersObj.getString(k))
                                                        }
                                                    }
                                                }
                                            } catch (_: Exception) {}

                                            if ((requestMethod == "POST" || requestMethod == "PUT" || requestMethod == "PATCH")) {
                                                doOutput = true
                                                if (!body.isNullOrEmpty()) {
                                                    outputStream.use { os ->
                                                        os.write(body.toByteArray(Charsets.UTF_8))
                                                    }
                                                }
                                            }
                                        }

                                        val statusCode = conn.responseCode
                                        val statusText = conn.responseMessage ?: "OK"

                                        val responseHeaders = JSONObject()
                                        try {
                                            conn.headerFields?.let { headerFields ->
                                                for ((key, values) in headerFields) {
                                                    if (key != null) {
                                                        if (key.equals("Set-Cookie", ignoreCase = true)) {
                                                            values?.forEach { cookieValue ->
                                                                if (!cookieValue.isNullOrEmpty()) {
                                                                    CookieManager.getInstance().setCookie(urlString, cookieValue)
                                                                }
                                                            }
                                                        } else if (!key.equals("Set-Cookie2", ignoreCase = true)) {
                                                            responseHeaders.put(key, values?.joinToString(",") ?: "")
                                                        }
                                                    }
                                                }
                                                CookieManager.getInstance().flush()
                                            }
                                        } catch (e: Exception) {
                                            AppLog.e("ProxyFetch", "Error saving cookies", e)
                                        }

                                        val inputStream = if (statusCode >= 400) conn.errorStream else conn.inputStream
                                        val resText = inputStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                                        if (
                                            statusCode >= 400 &&
                                            url.host.equals("api.playback.indazn.com", ignoreCase = true) &&
                                            url.path.equals("/v5/Playback", ignoreCase = true)
                                        ) {
                                            val diagnosticHeaders = JSONObject()
                                            val safeHeaderNames = setOf(
                                                "content-type", "server", "via", "x-cache",
                                                "x-request-id", "x-correlation-id", "traceparent",
                                                "x-amz-cf-id", "x-amz-cf-pop", "x-amz-cf-error",
                                                "x-amz-cf-error-code"
                                            )
                                            val headerKeys = responseHeaders.keys()
                                            while (headerKeys.hasNext()) {
                                                val key = headerKeys.next()
                                                if (key.lowercase() in safeHeaderNames) {
                                                    diagnosticHeaders.put(key, responseHeaders.opt(key))
                                                }
                                            }
                                            val requestHeaderNames = JSONObject(headersJson).keys().asSequence()
                                                .map { it.lowercase() }
                                                .sorted()
                                                .joinToString(",")
                                            AppLog.e(
                                                "DaznPlayback",
                                                "Playback HTTP failure status=$statusCode " +
                                                        "authorizationPresent=$hasAuthorizationHeader " +
                                                        "cookiePresent=$hasCookieHeader " +
                                                        "desktopUserAgent=${userAgent.contains("Windows NT", ignoreCase = true)} " +
                                                        "requestHeaderNames=$requestHeaderNames " +
                                                        "responseHeaders=$diagnosticHeaders " +
                                                        "error=${summarizeDaznErrorBody(resText)}"
                                            )
                                        }
                                        AppLog.d(
                                            "ProxyFetch",
                                            "DAZN API request host=${url.host} path=${url.path} method=$method status=$statusCode responseBytes=${resText.toByteArray(Charsets.UTF_8).size} elapsedMs=${android.os.SystemClock.elapsedRealtime() - requestStartedAt}"
                                        )

                                        JSONObject().apply {
                                            put("status", statusCode)
                                            put("statusText", statusText)
                                            put("text", resText)
                                            put("headers", responseHeaders)
                                        }.toString()
                                    } catch (e: Exception) {
                                        AppLog.e(
                                            "AndroidBridge",
                                            "proxyFetch failed for host=${safeUrlForLog(urlString)} (${e.javaClass.simpleName}) elapsedMs=${android.os.SystemClock.elapsedRealtime() - requestStartedAt}"
                                        )
                                        JSONObject().apply {
                                            put("status", 500)
                                            put("statusText", e.message ?: "Error")
                                            put("text", "")
                                        }.toString()
                                    }
                                }
                            }

                        },
                        "AndroidBridge",
                    )

                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?
                        ): Boolean {
                            val context = view?.context ?: return false
                            val popup = createPopupWebView(
                                context,
                                null,
                                view?.settings?.userAgentString
                            ) { popupWebView = null }
                            val transport = resultMsg?.obj as? WebView.WebViewTransport
                            if (transport != null) {
                                transport.webView = popup
                                resultMsg.sendToTarget()
                            }
                            popupWebView = popup
                            return true
                        }

                        override fun onShowCustomView(
                            view: android.view.View?,
                            callback: CustomViewCallback?
                        ) {
                            if (customView != null) {
                                callback?.onCustomViewHidden()
                                return
                            }
                            customView = view
                            customViewCallback = callback
                        }

                        override fun onHideCustomView() {
                            customView = null
                            customViewCallback = null
                        }

                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            title?.let { newTitle ->
                                TabManager.updateTabTitle(tabId, newTitle)
                            }
                        }

                        override fun onPermissionRequest(request: PermissionRequest?) {
                            val resources = request?.resources ?: return
                            for (res in resources) {
                                if (res == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID ||
                                    res == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                                    res == PermissionRequest.RESOURCE_AUDIO_CAPTURE) {
                                    request.grant(arrayOf(res))
                                    return
                                }
                            }
                            request.grant(resources)
                        }

                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            // Rimosso forzatura tema via JS per lasciare gestione nativa
                        }

                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            if (consoleMessage != null) {
                                val msg = consoleMessage.message()
                                if (msg.contains("SpotifyDebug", ignoreCase = true) || msg.contains("Spotify", ignoreCase = true)) {
                                    AppLog.d("SpotifyDebug", "JS Console: $msg")
                                }
                                if (Regex("""(?i)(65[_-]?000[_-]?403|COR-\d+)""").containsMatchIn(msg)) {
                                    AppLog.e("DaznPlayback", "Player error: ${msg.take(300)}")
                                }
                            }
                            return super.onConsoleMessage(consoleMessage)
                        }
                    }

                    webViewClient = @SuppressLint("MissingOnRenderProcessGone")
                    object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: android.graphics.Bitmap?
                        ) {
                            view?.settings?.mediaPlaybackRequiresUserGesture =
                                !autoplayMedia && !isDaznUrl(url)
                            if (needsDesktopForUrl(url)) {
                                view?.let { webView ->
                                    val ua = setDesktopUserAgent(webView, context)
                                    val chromeVersionRegex = Regex("Chrome/([0-9.]+)")
                                    val chromeVersion = chromeVersionRegex.find(ua)?.groups?.get(1)?.value ?: "152.0.0.0"
                                    webView.evaluateJavascript(BrowserJavascript.getDesktopSpoofScript(chromeVersion), null)
                                }
                            } else {
                                view?.let { setMobileUserAgent(it, context) }
                            }

                            // Re-applichiamo i settings del tema ad ogni cambio pagina per sicurezza
                            view?.let {
                                if (isAppDark) {
                                    if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                                        WebSettingsCompat.setForceDark(it.settings, WebSettingsCompat.FORCE_DARK_ON)
                                    }
                                    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(it.settings, darkPages)
                                    }
                                }
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val uri = request?.url?.toString() ?: ""
                            if (uri.startsWith("intent://")) {
                                try {
                                    val intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                                    val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                    val targetUrl = if (!fallbackUrl.isNullOrEmpty()) {
                                        fallbackUrl
                                    } else {
                                        val data = intent.dataString
                                        if (data != null && data.startsWith("https://")) data else null
                                    }
                                    if (!targetUrl.isNullOrEmpty()) {
                                        if (targetUrl.contains("dazn.com", ignoreCase = true)) {
                                            daznDesktopForced = true
                                            view?.let { setDesktopUserAgent(it, context) }
                                        }
                                        view?.loadUrl(targetUrl)
                                        return true
                                    }
                                } catch (e: Exception) {
                                    AppLog.e("BrowserScreen", "Intent parse error", e)
                                }
                                return true
                            }
                            if (uri.contains("dazn.com", ignoreCase = true) && (uri.contains("/watch/") || uri.contains("/event/") || uri.contains("/video/"))) {
                                if (!daznDesktopForced) {
                                    daznDesktopForced = true
                                    view?.let { setDesktopUserAgent(it, context) }
                                    view?.loadUrl(uri)
                                    return true
                                }
                            }
                            if (uri.startsWith("market://") || uri.contains("play.google.com/store/apps")) {
                                AppLog.d("BrowserScreen", "Blocked store link: $uri")
                                return true
                            }
                            return false
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val urlString = request?.url?.toString() ?: ""
                            val requestUrl = request?.url
                            if (AdBlockHost.shouldBlock(urlString)) {
                                return WebResourceResponse(
                                    "text/plain",
                                    "utf-8",
                                    ByteArrayInputStream("".toByteArray())
                                )
                            }

                            // Handling CORS OPTIONS preflight for cross-origin API calls
                            if (request != null && request.method?.equals("OPTIONS", ignoreCase = true) == true) {
                                val isDaznPlaybackPreflight =
                                    requestUrl?.host.equals("api.playback.indazn.com", ignoreCase = true) &&
                                            requestUrl?.path.equals("/v5/Playback", ignoreCase = true)
                                if (isDaznPlaybackPreflight) {
                                    AppLog.d("DaznPlayback", "Passing playback CORS preflight to WebView network stack")
                                    return super.shouldInterceptRequest(view, request)
                                }
                                val reqOrigin = request.requestHeaders["Origin"] ?: "https://www.dazn.com"
                                val reqHeaders = request.requestHeaders["Access-Control-Request-Headers"] ?: "*"
                                val corsHeaders = mapOf(
                                    "Access-Control-Allow-Origin" to reqOrigin,
                                    "Access-Control-Allow-Credentials" to "true",
                                    "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, PUT, DELETE",
                                    "Access-Control-Allow-Headers" to reqHeaders,
                                    "Access-Control-Max-Age" to "86400"
                                )
                                return WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    200,
                                    "OK",
                                    corsHeaders,
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }

                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?
                        ) {
                            val requestUrl = request?.url ?: return
                            if (
                                requestUrl.host.equals("api.playback.indazn.com", ignoreCase = true) &&
                                requestUrl.path.equals("/v5/Playback", ignoreCase = true)
                            ) {
                                val safeHeaders = errorResponse?.responseHeaders.orEmpty()
                                    .filterKeys { key ->
                                        key.equals("Server", ignoreCase = true) ||
                                                key.equals("X-Cache", ignoreCase = true) ||
                                                key.equals("X-Amz-Cf-Pop", ignoreCase = true) ||
                                                key.equals("X-Amz-Cf-Id", ignoreCase = true)
                                    }
                                AppLog.e(
                                    "DaznPlayback",
                                    "WebView playback HTTP error status=${errorResponse?.statusCode} " +
                                            "headers=${JSONObject(safeHeaders).toString()}"
                                )
                            }
                            super.onReceivedHttpError(view, request, errorResponse)
                        }

                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: android.webkit.RenderProcessGoneDetail?
                        ): Boolean {
                            return true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url != null) {
                                onPageFinished(url)
                                AppLog.d("##BrowserScreen", "onPageFinished: ${safeUrlForLog(url)}")

                                view?.let { webView ->
                                    if (url.contains("dazn.com", ignoreCase = true)) {
                                        webView.evaluateJavascript(
                                            DaznManager.getClickInterceptorScript(),
                                            null
                                        )
                                    }
                                    WebViewScriptRouter.routeAndInject(
                                        webView = webView,
                                        urlString = url,
                                        isYouTubeAdBlockEnabled = isYouTubeAdBlockEnabled,
                                        autoplayMedia = autoplayMedia,
                                        displayScale = actualDisplayScale,
                                        desktopScale = actualDesktopScale,
                                        isDesktopMode = needsDesktopForUrl(url),
                                        isTabActive = isTabActive
                                    )
                                }
                            }
                        }
                    }
                    loadUrl(url)
                }
                onWebViewCreated(webView)
                webViewReference = webView
                CarFrameLayout(context).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(-1, -1)
                    addView(webView, android.widget.FrameLayout.LayoutParams(-1, -1))
                }
            },
            update = { view ->
                val webView = view.getChildAt(0) as? WebView
                if (webViewReference != webView) {
                    webViewReference = webView
                }
                webView?.let {
                    if (it.tag != reloadTrigger) {
                        it.reload()
                        it.tag = reloadTrigger
                    }
                    val needsDesktop = needsDesktopForUrl(it.url)
                    it.settings.mediaPlaybackRequiresUserGesture =
                        !autoplayMedia && !isDaznUrl(it.url)
                    if (needsDesktop) {
                        setDesktopUserAgent(it, context)
                    } else {
                        setMobileUserAgent(it, context)
                    }
                    val targetScale = if (needsDesktop) actualDesktopScale else actualDisplayScale
                    it.setInitialScale(if (targetScale == 1.0f) 0 else (targetScale * 100).toInt())

                    it.setBackgroundColor(if (isAppDark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)

                    // Aggiornamento reattivo al cambio di tema (tasto N o spunta darkPages)
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                        val forceMode = if (isAppDark) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
                        WebSettingsCompat.setForceDark(it.settings, forceMode)
                    }

                    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(it.settings, activeDarkPages)
                    }
                }
            },
        )

        customView?.let { view ->
            AndroidView(
                factory = {
                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                    view
                },
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }

        popupWebView?.let { popup ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(0.95f)
                        .background(Color.White, shape = RoundedCornerShape(12.dp))
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.DarkGray)
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Accesso in corso...", color = Color.White)
                            Button(
                                onClick = { popupWebView = null }
                            ) {
                                Text(text = "Chiudi")
                            }
                        }
                        AndroidView(
                            factory = {
                                (popup.parent as? ViewGroup)?.removeView(popup)
                                popup
                            },
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                    }
                }
            }
        }

        val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
        DisposableEffect(Unit) { onDispose { speechRecognizer.destroy() } }

        if (isListening) {
            VoiceListeningPopup(
                onDismiss = {
                    isListening = false
                    try {
                        speechRecognizer.stopListening()
                    } catch (_: Exception) {
                    }
                }
            )
        }

        if (showInputPopup) {
            InputSelectionPopup(
                onKeyboardSelected = {
                    showInputPopup = false
                    webViewReference?.let { webView ->
                        webView.post {
                            webView.requestFocus()
                            carInputManager?.startInput(webView)
                        }
                    }
                },
                onMicSelected = {
                    showInputPopup = false
                    isListening = true
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(RecognizerIntent.EXTRA_PROMPT, voicePrompt)
                    }
                    speechRecognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {
                            isListening = false
                        }

                        override fun onError(error: Int) {
                            isListening = false
                        }

                        override fun onResults(results: Bundle?) {
                            val spokenText =
                                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull()
                            if (!spokenText.isNullOrEmpty()) {
                                webViewReference?.evaluateJavascript(
                                    BrowserJavascript.getInjectTextScript(spokenText),
                                    null
                                )
                            }
                            isListening = false
                        }

                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    speechRecognizer.startListening(intent)
                },
                onDismiss = { showInputPopup = false },
            )
        }
    }
}