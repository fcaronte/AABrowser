package com.fcaronte.aabrowser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
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
import com.fcaronte.aabrowser.utils.BrowserJavascript
import com.fcaronte.aabrowser.utils.InactivityTracker
import com.fcaronte.aabrowser.utils.SpotifyManager
import com.fcaronte.aabrowser.utils.WebViewScriptRouter
import java.io.ByteArrayInputStream
import androidx.core.net.toUri
import org.json.JSONObject

object ChromeVersionFetcher {
    private var cachedVersion: String = "152.0.0.0"

    init {
        fetchLatestVersion()
    }

    fun getLatestVersion(): String = cachedVersion

    private fun fetchLatestVersion() {
        Thread {
            try {
                val url = java.net.URL("https://versionhistory.googleapis.com/v1/chrome/platforms/win/channels/stable/versions")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                if (conn.responseCode == 200) {
                    val json = conn.inputStream.bufferedReader().use { it.readText() }
                    val versionRegex = Regex("\"version\"\\s*:\\s*\"([0-9.]+)\"")
                    val match = versionRegex.find(json)
                    if (match != null) {
                        val ver = match.groups[1]?.value
                        if (!ver.isNullOrEmpty()) {
                            cachedVersion = ver
                            android.util.Log.d("ChromeVersionFetcher", "Latest Windows Chrome version fetched: $cachedVersion")
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ChromeVersionFetcher", "Failed to fetch latest version", e)
            }
        }.start()
    }
}

private fun getDynamicDesktopUserAgent(): String = WebViewScriptRouter.getDynamicDesktopUserAgent()

private fun isDesktopRequired(url: String?): Boolean = WebViewScriptRouter.isDesktopRequired(url)

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

    // Valutazione unificata basata sul MainScreen
    val isAppDark = isAppDarkOverride ?: androidx.compose.foundation.isSystemInDarkTheme()
    val activeDarkPages = isAppDark && darkPages

    val actualDesktopMode = desktopModeOverride ?: isDesktopMode
    val actualDisplayScale = mobileZoomOverride ?: globalDisplayScale
    val actualDesktopScale = desktopZoomOverride ?: globalDesktopScale
    val isYouTubeAdBlockEnabled by com.fcaronte.aabrowser.settings.AdBlockSettings.isYouTubeEnabled
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val voicePrompt = stringResource(R.string.voice_prompt)

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

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    webViewReference?.let {
                        it.onResume()
                        it.invalidate()
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
                android.util.Log.d("##BrowserScreen", "Tab closed, destroying WebView: $tabId")
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
                android.util.Log.d("##BrowserScreen", "Loading URL: $url")
                it.loadUrl(url)
            } else if (currentUrl.contains("youtube.com") && isYouTubeAdBlockEnabled && lastInjectedUrl != currentUrl) {
                android.util.Log.d("##BrowserScreen", "Injecting AdBlock from LaunchedEffect (URL changed)")
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
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

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
                        mediaPlaybackRequiresUserGesture = !autoplayMedia
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        allowContentAccess = true
                        allowFileAccess = true
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        setSupportMultipleWindows(true)
                        javaScriptCanOpenWindowsAutomatically = true
                    }

                    // Abilita i cookie in modo persistente
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    // Sfondo di base per evitare flash bianchi durante il caricamento
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)

                    // Configurazione Tema Scuro (Nativo + Forza Dark)
                    val isNight = isAppDark
                    android.util.Log.d("##BrowserScreen", "Theme Factory: isNight=$isNight, darkPages=$darkPages")

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

                    val needsDesktop = actualDesktopMode || isDesktopRequired(url)
                    if (needsDesktop) {
                        settings.userAgentString = getDynamicDesktopUserAgent()
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                            val ua = getDynamicDesktopUserAgent()
                            val chromeVersionRegex = Regex("Chrome/([0-9.]+)")
                            val chromeVersion = chromeVersionRegex.find(ua)?.groups?.get(1)?.value ?: "152.0.0.0"
                            WebViewCompat.addDocumentStartJavaScript(
                                this,
                                BrowserJavascript.getDesktopSpoofScript(chromeVersion),
                                setOf("*")
                            )
                        }
                    } else {
                        settings.userAgentString = null
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

                                    Log.d("SpotifyDebug", "recMediaStatus -> $title - $artist (Playing: $isPlaying, Cover: $coverUrl)")

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
                                    Log.e("SpotifyBridge", "Error parsing media status", e)
                                }
                            }

                            @android.webkit.JavascriptInterface
                            @Suppress("unused")
                            fun onMetadataUpdated(title: String, faviconUrl: String, currentUrl: String) {
                                post {
                                    TabManager.updateTabTitle(tabId, title)
                                    TabManager.updateTabFavicon(tabId, faviconUrl)
                                }
                            }

                            @android.webkit.JavascriptInterface
                            @Suppress("unused")
                            fun onStartAdBlock() {
                                post {
                                    android.util.Log.d("##BrowserScreen", "AdBlock request, YouTube enabled: $isYouTubeAdBlockEnabled")
                                    val currentUrl = webViewReference?.url ?: ""
                                    WebViewScriptRouter.injectYouTubeAdBlockIfNeeded(webViewReference, currentUrl, isYouTubeAdBlockEnabled, isTabActive)
                                }
                            }

                            @android.webkit.JavascriptInterface
                            @Suppress("unused")
                            fun onStartInput() {
                                android.util.Log.d("##BrowserScreen", "onStartInput called, isTabActive: $isTabActive, isGlobalSearch: $isGlobalSearchActive")
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
                                    Log.d("##BrowserScreen", "openInNewTab requested: $url")
                                    TabManager.openOrSwitchTo(url = url)
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
                            val popup = WebView(context).apply {
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
                                    userAgentString = view.settings.userAgentString
                                }
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                                webViewClient = @SuppressLint("MissingOnRenderProcessGone")
                                object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        val url = request?.url?.toString() ?: return false
                                        val mainUrl = webViewReference?.url ?: ""
                                        val mainHost = mainUrl.toUri().host ?: ""
                                        val requestUri = url.toUri()
                                        val requestHost = requestUri.host ?: ""

                                        if (url.contains("code=") || url.contains("token=") || (requestHost.isNotEmpty() && requestHost == mainHost)) {
                                            popupWebView = null
                                            webViewReference?.loadUrl(url)
                                            return true
                                        }
                                        return false
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onCloseWindow(window: WebView?) {
                                        popupWebView = null
                                    }
                                }
                            }
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
                            request?.grant(request.resources)
                        }

                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            // Rimosso forzatura tema via JS per lasciare gestione nativa
                        }

                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            if (consoleMessage != null) {
                                val msg = consoleMessage.message()
                                if (msg.contains("SpotifyDebug", ignoreCase = true) || msg.contains("Spotify", ignoreCase = true)) {
                                    Log.d("SpotifyDebug", "JS Console: $msg")
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
                            if (actualDesktopMode || isDesktopRequired(url)) {
                                val ua = getDynamicDesktopUserAgent()
                                view?.settings?.userAgentString = ua
                                val chromeVersionRegex = Regex("Chrome/([0-9.]+)")
                                val chromeVersion = chromeVersionRegex.find(ua)?.groups?.get(1)?.value ?: "152.0.0.0"
                                view?.evaluateJavascript(BrowserJavascript.getDesktopSpoofScript(chromeVersion), null)
                            } else {
                                view?.settings?.userAgentString = null
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
                                    if (!fallbackUrl.isNullOrEmpty()) {
                                        view?.loadUrl(fallbackUrl)
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("BrowserScreen", "Intent parse error", e)
                                }
                                return true
                            }
                            if (uri.startsWith("market://") || uri.contains("play.google.com/store/apps")) {
                                android.util.Log.d("BrowserScreen", "Blocked store link: $uri")
                                return true
                            }
                            return false
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val urlString = request?.url?.toString() ?: ""
                            if (AdBlockHost.shouldBlock(urlString)) {
                                return WebResourceResponse(
                                    "text/plain",
                                    "utf-8",
                                    ByteArrayInputStream("".toByteArray())
                                )
                            }

                            return super.shouldInterceptRequest(view, request)
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
                                android.util.Log.d("##BrowserScreen", "onPageFinished: $url")

                                view?.let { webView ->
                                    WebViewScriptRouter.routeAndInject(
                                        webView = webView,
                                        urlString = url,
                                        isYouTubeAdBlockEnabled = isYouTubeAdBlockEnabled,
                                        autoplayMedia = autoplayMedia,
                                        displayScale = actualDisplayScale,
                                        desktopScale = actualDesktopScale,
                                        isDesktopMode = actualDesktopMode,
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
                    val needsDesktop = actualDesktopMode || isDesktopRequired(it.url)
                    if (needsDesktop) {
                        it.settings.userAgentString = getDynamicDesktopUserAgent()
                    } else {
                        it.settings.userAgentString = null
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