package com.fcaronte.aabrowser.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.support.v4.media.session.PlaybackStateCompat
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.fcaronte.aabrowser.CarFrameLayout
import com.fcaronte.aabrowser.CarInputManager
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.mediaservice.MediaFocusManager
import com.fcaronte.aabrowser.mediaservice.MediaSessionManager
import com.fcaronte.aabrowser.model.TabManager
import com.fcaronte.aabrowser.settings.AdBlockSettings
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.utils.AdBlockHost
import com.fcaronte.aabrowser.utils.AdBlockJavascript
import com.fcaronte.aabrowser.utils.AppLog
import com.fcaronte.aabrowser.utils.BrowserJavascript
import com.fcaronte.aabrowser.utils.DaznManager
import com.fcaronte.aabrowser.utils.DaznProxy
import com.fcaronte.aabrowser.utils.GoogleLoginManager
import com.fcaronte.aabrowser.utils.InactivityTracker
import com.fcaronte.aabrowser.utils.IptvSources
import com.fcaronte.aabrowser.utils.JsDialogRequest
import com.fcaronte.aabrowser.utils.LocalPlayer
import com.fcaronte.aabrowser.utils.PendingSitePermission
import com.fcaronte.aabrowser.utils.SitePermission
import com.fcaronte.aabrowser.utils.SitePermissionChoice
import com.fcaronte.aabrowser.utils.PendingSslError
import com.fcaronte.aabrowser.utils.PopupGuard
import com.fcaronte.aabrowser.utils.SitePermissionPrompts
import com.fcaronte.aabrowser.utils.SitePermissions
import com.fcaronte.aabrowser.utils.SponsorBlock
import com.fcaronte.aabrowser.utils.SslErrorPrompts
import com.fcaronte.aabrowser.utils.UrlUtils
import com.fcaronte.aabrowser.utils.MediaVoice
import com.fcaronte.aabrowser.utils.VoiceCommandRouter
import com.fcaronte.aabrowser.utils.VoiceCommands
import com.fcaronte.aabrowser.utils.WebLocation
import com.fcaronte.aabrowser.utils.WebViewScriptRouter
import com.fcaronte.aabrowser.utils.YouTubeManager
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private fun setDesktopUserAgent(webView: WebView, context: Context): String =
    WebViewScriptRouter.setDesktopUserAgent(webView, context)
private fun setMobileUserAgent(webView: WebView, context: Context) =
    WebViewScriptRouter.setMobileUserAgent(webView, context)
private fun isDesktopRequired(url: String?): Boolean = WebViewScriptRouter.isDesktopRequired(url)
private fun safeUrlForLog(url: String?): String =
    AppLog.safeUrlForLog(url)

// Come il blocco popup di Chrome, ma più largo: i login aprono il popup anche qualche secondo dopo il tocco
private const val USER_GESTURE_MS = 10_000L

// Microfono chiesto ad Android quando un sito lo vuole e l'app non ce l'ha (la posizione usa WebLocation.REQUEST_CODE)
private const val MIC_PERMISSION_REQUEST = 1008

// Due crash del motore delle pagine entro questo tempo: la scheda resta vuota invece di riaprire la pagina all'infinito
private const val RENDER_CRASH_RETRY_MS = 5000L

// La tastiera passeggero ha scritto nel campo e lo script gli ha ridato il focus: intanto niente popup tastiera/microfono
private const val INPUT_POPUP_SUPPRESS_MS = 1500L

// Il sito del popup di accesso, come la barra degli indirizzi: "http://" resta visibile perché non è protetto
private fun popupAddress(url: String?): String? {
    val uri = url?.let { Uri.parse(it) } ?: return null
    val host = uri.host ?: return null
    return if (uri.scheme == "https") host else "${uri.scheme}://$host"
}

private fun createPopupWebView(
    context: Context,
    initialUrl: String? = null,
    userAgent: String?,
    onPageChanged: (String?) -> Unit,
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
            // Solo pagine web di accesso: niente file locali, Safe Browsing acceso come nelle schede
            allowContentAccess = false
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
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
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                onPageChanged(url)
            }

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
    carInputManager: CarInputManager? = null,
    desktopModeOverride: Boolean? = null,
    mobileZoomOverride: Float? = null,
    desktopZoomOverride: Float? = null,
    isTabActive: Boolean = true,
    // false con sopra la home, le Impostazioni o l'elenco delle schede (le pagine restano aperte sotto)
    isScreenVisible: Boolean = true,
    isGlobalSearchActive: Boolean = false,
    isAppDarkOverride: Boolean? = null, // <--- Riceve lo stato in tempo reale da MainScreen
    onPageFinished: (String) -> Unit,
    onWebViewCreated: (WebView) -> Unit = {},
    onFullScreenChange: (Boolean) -> Unit = {},
    onInteraction: () -> Unit = {},
    // Messaggio nel riquadro della Main: in auto un Toast compare sul telefono, non sullo schermo dell'auto
    onShowFeedback: (String) -> Unit = {},
    // Aggiungere una lista dalle pagine di TV e Radio (solo sul telefono): le Impostazioni, al punto giusto
    onOpenMediaLists: (IptvSources.Section) -> Unit = {},
) {
    val globalDisplayScale by AppSettings.displayScale
    val globalDesktopScale by AppSettings.desktopScale
    val autoplayMedia by AppSettings.autoplayMedia
    val forceMaxYouTubeQuality by AppSettings.forceMaxYouTubeQuality
    val youtubeQuality by AppSettings.youtubeQuality
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

    // Ogni scheda ha il suo collegamento al player di Android Auto: lo controlla l'ultima che avvia un media
    val mediaSessionManager = remember(tabId) {
        if (AppSettings.mediaPlayerEnabled.value) {
            MediaSessionManager(context.applicationContext, MediaFocusManager.Source.MAIN_APP)
        } else {
            null
        }
    }

    // I callback della WebView vengono creati una volta sola: da qui leggono sempre il valore attuale
    val currentIsTabActive by rememberUpdatedState(isTabActive)
    val currentIsGlobalSearchActive by rememberUpdatedState(isGlobalSearchActive)

    fun needsDesktopForUrl(pageUrl: String?): Boolean =
        actualDesktopMode ||
                isDesktopRequired(pageUrl) ||
                (daznDesktopForced && pageUrl?.contains("dazn.com", ignoreCase = true) == true)

    fun isDaznUrl(pageUrl: String?): Boolean =
        pageUrl?.contains("dazn.com", ignoreCase = true) == true

    fun updateMediaCapabilities(pageUrl: String?) {
        val uri = try {
            pageUrl?.let { Uri.parse(it) }
        } catch (_: Exception) {
            null
        }

        val host = uri?.host?.lowercase() ?: ""
        val isYouTube =
            host == "youtube.com" ||
                    host == "www.youtube.com" ||
                    host.endsWith(".youtube.com")

        // Siti con una coda di brani (anche CarWeb Media, il lettore locale): skip avanti/indietro invece di +/-10 s
        val isTrackSite =
            host == "open.spotify.com" ||
                    host == "spotify.com" ||
                    host.endsWith(".spotify.com") ||
                    host == "music.youtube.com" ||
                    LocalPlayer.isPlayerUrl(pageUrl)

        val isYouTubePlaylist =
            isYouTube &&
                    (
                            !uri?.getQueryParameter("list").isNullOrBlank() ||
                                    uri?.path?.equals("/playlist", ignoreCase = true) == true
                            )

        when {
            isTrackSite -> {
                AppLog.d(
                    "AABrowserPlayback",
                    "Media capabilities: track site (Spotify / YouTube Music / CarWeb Media) -> NEXT/PREVIOUS"
                )
                mediaSessionManager?.setPlaybackCapabilities(
                    canSeek = false,
                    canSkipNext = true,
                    canSkipPrevious = true
                )
            }

            isYouTubePlaylist -> {
                AppLog.d(
                    "AABrowserPlayback",
                    "Media capabilities: YouTube playlist -> NEXT/PREVIOUS"
                )
                mediaSessionManager?.setPlaybackCapabilities(
                    canSeek = false,
                    canSkipNext = true,
                    canSkipPrevious = true
                )
            }

            else -> {
                AppLog.d(
                    "AABrowserPlayback",
                    "Media capabilities: normal player -> SEEK"
                )
                mediaSessionManager?.setPlaybackCapabilities(
                    canSeek = true,
                    canSkipNext = false,
                    canSkipPrevious = false
                )
            }
        }
    }

    val webViewState = remember { mutableStateOf<WebView?>(null) }
    var webViewReference by webViewState

    var showInputPopup by remember { mutableStateOf(value = false) }
    // Tastiera passeggero (in auto, al posto di quella di Android Auto) aperta su un campo: testo e suggerimento
    var passengerField by remember { mutableStateOf<Pair<String, String>?>(null) }
    var suppressInputPopupUntil by remember { mutableLongStateOf(0L) }
    val passengerKeyboard by AppSettings.passengerKeyboard
    val fieldHint = stringResource(R.string.wide_field_input_hint)
    var isListening by remember { mutableStateOf(value = false) }
    var customView by remember { mutableStateOf<android.view.View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var popupWebView by remember { mutableStateOf<WebView?>(null) }
    var popupUrl by remember { mutableStateOf<String?>(null) }

    // alert, confirm e prompt della pagina, mostrati nella scheda visibile
    var jsDialog by remember { mutableStateOf<JsDialogRequest?>(null) }

    // Ultimo tocco sulla pagina (letto dal thread del JavaBridge)
    val lastTouchAt = remember { AtomicLong(0L) }
    // Tocco mandato dall'app (rientro a schermo intero): non conta come tocco dell'utente
    val syntheticTap = remember { AtomicBoolean(false) }

    // Motore delle pagine chiuso (crash, o Android che libera memoria): la WebView morta non si può più usare, se ne
    // crea una nuova che riapre la pagina (la WebView si ricrea cambiando questa chiave)
    var webViewGeneration by remember { mutableIntStateOf(0) }
    val lastRenderCrashAt = remember { AtomicLong(0L) }

    // File e cartelle chiesti dalla pagina (sull'auto la pagina riceve l'ultima scelta fatta sul telefono)
    val fileChooser = rememberWebFileChooser()

    // Certificato non valido (es. server di casa autofirmato): come in Chrome la pagina aspetta la conferma
    var sslPrompt by remember { mutableStateOf<PendingSslError?>(null) }
    val sslPrompts = remember { SslErrorPrompts(context) { sslPrompt = it } }

    // Microfono, posizione e (in auto) file del telefono: il sito chiede, la scelta resta per quel sito.
    // Senza Activity (MainCarActivity) la scheda è sullo schermo dell'auto
    val activity = LocalActivity.current
    var sitePrompt by remember { mutableStateOf<PendingSitePermission?>(null) }
    val sitePrompts = remember { SitePermissionPrompts(context, inCar = activity == null) { sitePrompt = it } }
    // Permesso che il telefono non ha dato all'app: sul telefono lo chiede Android e il sito aspetta la risposta,
    // in auto si avvisa, perché la finestra di Android compare solo sul telefono
    val locationCarMsg = stringResource(R.string.web_location_phone_toast)
    val micPhoneMsg = stringResource(R.string.site_permission_mic_phone)
    sitePrompts.onPhonePermissionMissing = { permission ->
        when {
            activity == null -> {
                onShowFeedback(if (permission == SitePermission.LOCATION) locationCarMsg else micPhoneMsg)
                false
            }
            permission == SitePermission.LOCATION -> WebLocation.requestMissing(activity)
            permission == SitePermission.MICROPHONE -> {
                activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_REQUEST)
                true
            }
            else -> false
        }
    }
    // Risposta di Android (MainActivity): le richieste dei siti in attesa ripartono
    val phonePermissionAnswers by SitePermissions.phonePermissionAnswers
    LaunchedEffect(phonePermissionAnswers) { sitePrompts.onPhonePermissionsAnswered() }

    var lastInjectedUrl by remember { mutableStateOf("") }
    // Ultimo indirizzo che la WebView ha detto di aver aperto: la scheda lo annota soltanto. Riaprirlo mentre la
    // WebView è già altrove annullerebbe il tasto indietro e cancellerebbe le pagine "avanti" della cronologia
    var lastReportedUrl by remember { mutableStateOf<String?>(null) }
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
            // I tasti del player restano quelli della pagina: ogni scheda ha il suo MediaSessionManager
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
                        isFullscreenPending = false
                        // Solo la scheda visibile (una in background resta com'è), e a WebView di nuovo pronta
                        webViewReference?.takeIf { currentIsTabActive }?.let { webView ->
                            FullscreenRestore(webView, syntheticTap) { customView != null }.start()
                        }
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    // A schermo intero in background la riproduzione si fermava: si esce e al ritorno si rientra
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
            // Se il player era di questa scheda viene azzerato
            mediaSessionManager?.disconnect()
            sslPrompts.cancelAll()
            sitePrompts.cancelAll()
            jsDialog?.cancel()
            webViewState.value?.let { webView ->
                AppLog.d("##BrowserScreen", "Tab closed, destroying WebView: $tabId")
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        }
    }



    LaunchedEffect(Unit) {
        mediaSessionManager?.connect()
    }

    // Comando vocale per CarWeb Media con la pagina già aperta in questa scheda (quella che si apre chiede da sola)
    val mediaVoiceVersion by MediaVoice.version
    LaunchedEffect(isTabActive, mediaVoiceVersion) {
        if (isTabActive && MediaVoice.hasPending() && LocalPlayer.isPlayerUrl(webViewReference?.url)) {
            webViewReference?.evaluateJavascript(MediaVoice.CHECK_SCRIPT, null)
        }
    }

    // Liste di TV e Radio cambiate nelle Impostazioni: la pagina aperta aggiorna il suo menu
    val mediaListsVersion by IptvSources.version
    LaunchedEffect(mediaListsVersion) {
        if (mediaListsVersion > 0 && LocalPlayer.isPlayerUrl(webViewReference?.url)) {
            webViewReference?.evaluateJavascript(LocalPlayer.SOURCES_CHANGED_SCRIPT, null)
        }
    }

    LaunchedEffect(url, isYouTubeAdBlockEnabled) {
        if (url == lastReportedUrl) return@LaunchedEffect
        webViewReference?.let {
            val currentUrl = it.url
            val currentClean = currentUrl?.trimEnd('/') ?: ""
            val targetClean = url.trimEnd('/')
            if (currentClean.isBlank() || currentClean != targetClean) {
                AppLog.d("##BrowserScreen", "Loading host: ${safeUrlForLog(url)}")
                it.loadUrl(url)
            }
        }
    }

    // Per gestire l'AdBlock in modo reattivo su URL già caricati
    LaunchedEffect(isYouTubeAdBlockEnabled, url) {
        webViewReference?.let {
            if (it.url?.contains("youtube.com") == true && isYouTubeAdBlockEnabled && lastInjectedUrl != it.url) {
                AppLog.d("##BrowserScreen", "Injecting AdBlock (Sync)")
                WebViewScriptRouter.injectYouTubeAdBlockIfNeeded(it, it.url ?: "", isYouTubeAdBlockEnabled, isTabActive)
                lastInjectedUrl = it.url ?: ""
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
        key(webViewGeneration) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    // Sito della pagina principale, letto dal thread del ponte JavaScript (proxy DAZN)
                    val pageHost = AtomicReference(Uri.parse(url).host)
                    // Finestre aperte dagli script di questa scheda: le pubblicità a ogni tocco (PopupGuard)
                    val popupAttempts = PopupGuard.Attempts()
                    // Comando vocale "metti …" su questa scheda: su YouTube il video parte anche con l'autoplay spento
                    var voiceAutoplay = false
                    val webView = object : WebView(context) {
                        override fun onPause() {
                            // Impedito il blocco forzato
                            return
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
                            if (!syntheticTap.get()) {
                                lastTouchAt.set(SystemClock.uptimeMillis())
                                onInteraction()
                                InactivityTracker.notifyInteraction(5000L, AppSettings.persistentNavigation.value)
                            }
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
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
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
                            // Ultimo campo con il focus: se la pagina glielo toglie, tastiera dell'auto e dettatura
                            // scrivono comunque lì (getInjectTextScript), come nella Wide
                            WebViewCompat.addDocumentStartJavaScript(
                                this,
                                BrowserJavascript.getAutoSaveActiveElementScript(),
                                setOf("*")
                            )
                            // Elemento a schermo intero (player di YouTube, video): ci si rientra al ritorno dal background
                            WebViewCompat.addDocumentStartJavaScript(
                                this,
                                BrowserJavascript.getFullscreenMemoryScript(),
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
                            // Prima del player di YouTube: toglie i dati delle pubblicità (riserva: lo script a pagina finita)
                            WebViewCompat.addDocumentStartJavaScript(
                                this,
                                AdBlockJavascript.getYouTubeAdPruneScript(),
                                AdBlockJavascript.YOUTUBE_ORIGINS
                            )
                            // Previene lo scroll/focus automatico verso la playlist in basso su piccolo schermo
                            WebViewCompat.addDocumentStartJavaScript(
                                this,
                                YouTubeManager.getPreventPlaylistScrollScript(),
                                AdBlockJavascript.YOUTUBE_ORIGINS
                            )
                            // SponsorBlock: salta sponsor e le altre parti scelte (chiede le categorie al ponte)
                            WebViewCompat.addDocumentStartJavaScript(
                                this,
                                SponsorBlock.script(context),
                                AdBlockJavascript.YOUTUBE_ORIGINS
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
                            // Spotify e gli altri siti a brani sono gestiti dentro gli script (Media Session
                            // della pagina o pulsante del brano), così vale anche per la Wide e per le schede
                            // arrivate su Spotify navigando: qui `url` sarebbe quello con cui è nata la scheda
                            onSkipToNext = { evaluateJavascript(BrowserJavascript.NEXT_SCRIPT.trimIndent(), null) }
                            onSkipToPrevious = { evaluateJavascript(BrowserJavascript.PREVIOUS_SCRIPT.trimIndent(), null) }
                            onSeekTo = { pos -> evaluateJavascript(BrowserJavascript.getSeekScript(pos), null) }
                            // Solo la scheda visibile può prendersi il player avviando un media
                            canTakeFocus = { currentIsTabActive }
                        }
                        // Titolo della pagina finché il sito non invia i metadati del media
                        mediaSessionManager?.fallbackTitle = { title }

                        addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                @Suppress("unused")
                                fun onVideoStarted(time: Float) {
                                    AppLog.d("BrowserScreenBridge", "onVideoStarted time=$time, tabId=$tabId, isTabActive=$currentIsTabActive")
                                    @Suppress("DEPRECATION")
                                    mediaSessionManager?.updatePlaybackState(
                                        PlaybackStateCompat.STATE_PLAYING,
                                        (time * 1000).toLong(),
                                        1.0f,
                                        isPlaybackStart = true
                                    )
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun onMediaTimeUpdate(time: Float, speed: Float, isPlaying: Boolean) {
                                    AppLog.d("BrowserScreenBridge", "onMediaTimeUpdate time=$time, speed=$speed, isPlaying=$isPlaying, tabId=$tabId")
                                    @Suppress("DEPRECATION")
                                    mediaSessionManager?.updatePlaybackState(
                                        if (isPlaying) {
                                            PlaybackStateCompat.STATE_PLAYING
                                        } else {
                                            PlaybackStateCompat.STATE_PAUSED
                                        },
                                        (time * 1000).toLong(),
                                        speed
                                    )
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun onMediaStatusChanged(isPlaying: Boolean, time: Float, speed: Float) {
                                    AppLog.d("BrowserScreenBridge", "onMediaStatusChanged isPlaying=$isPlaying, time=$time, speed=$speed, tabId=$tabId")
                                    @Suppress("DEPRECATION")
                                    mediaSessionManager?.updatePlaybackState(
                                        if (isPlaying) {
                                            PlaybackStateCompat.STATE_PLAYING
                                        } else {
                                            PlaybackStateCompat.STATE_PAUSED
                                        },
                                        (time * 1000).toLong(),
                                        speed,
                                        isPlaybackStart = isPlaying
                                    )
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun updateMediaMetadata(
                                    title: String,
                                    artist: String,
                                    albumArtUrl: String,
                                    duration: Float,
                                ) {
                                    AppLog.d("BrowserScreenBridge", "updateMediaMetadata title='$title', artist='$artist', duration=$duration, tabId=$tabId")
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

                                @JavascriptInterface
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

                                // "Aggiungi una lista" nelle pagine di TV e Radio; false in auto, dove le liste non si
                                // aggiungono: la pagina avvisa di farlo dal telefono
                                @JavascriptInterface
                                @Suppress("unused")
                                fun openMediaLists(section: String): Boolean {
                                    if (activity == null || !LocalPlayer.isPlayerHost(pageHost.get())) return false
                                    val target = IptvSources.Section.entries
                                        .firstOrNull { it.name.equals(section, ignoreCase = true) } ?: return false
                                    post { onOpenMediaLists(target) }
                                    return true
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

                                @JavascriptInterface
                                @Suppress("unused")
                                fun onStartAdBlock() {
                                    post {
                                        AppLog.d("##BrowserScreen", "AdBlock request, YouTube enabled: $isYouTubeAdBlockEnabled")
                                        val currentUrl = webViewReference?.url ?: ""
                                        WebViewScriptRouter.injectYouTubeAdBlockIfNeeded(webViewReference, currentUrl, isYouTubeAdBlockEnabled, isTabActive)
                                    }
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun updateMediaCapabilities(canSeek: Boolean, canSkipNext: Boolean, canSkipPrevious: Boolean) {
                                    post {
                                        mediaSessionManager?.setPlaybackCapabilities(canSeek, canSkipNext, canSkipPrevious)
                                    }
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun onStartInput() {
                                    AppLog.d("##BrowserScreen", "onStartInput called, isTabActive: $currentIsTabActive, isGlobalSearch: $currentIsGlobalSearchActive")
                                    if (currentIsTabActive && !currentIsGlobalSearchActive) {
                                        post {
                                            // Non sopra la tastiera passeggero, né subito dopo che ha scritto nel campo
                                            if (passengerField == null && SystemClock.uptimeMillis() >= suppressInputPopupUntil) {
                                                showInputPopup = true
                                            }
                                        }
                                    }
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun onFolderInputClicked() {
                                    fileChooser.onFolderInputClicked()
                                }

                                // window.open delle pagine (GoogleLoginManager): [tappedLink] = il link toccato, se c'era
                                @JavascriptInterface
                                @Suppress("unused")
                                fun openInNewTab(url: String, tappedLink: String) {
                                    // Solo pagine web: dalle pagine non devono arrivare file://, content://, intent://...
                                    if (!UrlUtils.isWebUrl(url) || !hasRecentTouch("openInNewTab")) return
                                    if (!PopupGuard.allowsPopup(url, pageHost.get(), tappedLink, popupAttempts, isWindow = true)) return
                                    post {
                                        AppLog.d("##BrowserScreen", "openInNewTab requested for host=${safeUrlForLog(url)}")
                                        TabManager.openOrSwitchTo(url = url)
                                    }
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun openLinkInNewTab(url: String) {
                                    if (!hasRecentTouch("openLinkInNewTab")) return
                                    post {
                                        val uri = Uri.parse(url)
                                        if ((uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()) {
                                            AppLog.d("##BrowserScreen", "Long-pressed link opened in new tab: ${safeUrlForLog(url)}")
                                            TabManager.addTab(url = uri.toString())
                                        } else {
                                            AppLog.w("##BrowserScreen", "Ignoring invalid URL for new tab: ${safeUrlForLog(url)}")
                                        }
                                    }
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun openPopup(url: String, tappedLink: String) {
                                    // Solo pagine web, e solo subito dopo un tocco
                                    if (!UrlUtils.isWebUrl(url) || !hasRecentTouch("openPopup")) return
                                    if (!PopupGuard.allowsPopup(url, pageHost.get(), tappedLink, popupAttempts, isWindow = true)) return
                                    post {
                                        AppLog.d("##BrowserScreen", "openPopup requested for host=${safeUrlForLog(url)}")
                                        popupUrl = url
                                        val popup = createPopupWebView(
                                            context,
                                            url,
                                            webViewReference?.settings?.userAgentString,
                                            onPageChanged = { popupUrl = it }
                                        ) { popupWebView = null }
                                        popupWebView = popup
                                    }
                                }

                                private fun hasRecentTouch(action: String): Boolean {
                                    val recent = SystemClock.uptimeMillis() - lastTouchAt.get() <= USER_GESTURE_MS
                                    if (!recent) AppLog.w("##BrowserScreen", "$action ignored: no recent tap on the page")
                                    return recent
                                }

                                @JavascriptInterface
                                @Suppress("unused")
                                fun proxyFetch(
                                    urlString: String,
                                    method: String,
                                    headersJson: String,
                                    body: String?,
                                    requestUserAgent: String
                                ): String = DaznProxy.proxyFetch(
                                    context, pageHost.get(), urlString, method, headersJson, body, requestUserAgent
                                )

                                @JavascriptInterface
                                @Suppress("unused")
                                fun saveYouTubeSpeed(speed: Float) {
                                    AppSettings.setYouTubeSpeed(context, speed)
                                    AppLog.d("YouTubeSpeed", "Saved playback speed = $speed")
                                }

                                // Lo chiede lo script dei dati del player di YouTube all'inizio della pagina
                                @JavascriptInterface
                                @Suppress("unused")
                                fun isYouTubeAdPruneOn(): Boolean = AdBlockSettings.isYouTubePruneOn(pageHost.get())

                                // Window.open e link cliccati da uno script: si aprono? (PopupGuard)
                                @JavascriptInterface
                                @Suppress("unused")
                                fun allowsPopup(url: String, tappedLink: String): Boolean =
                                    PopupGuard.allowsPopup(url, pageHost.get(), tappedLink, popupAttempts, isWindow = false)

                                // Lo script di SponsorBlock all'inizio delle pagine di YouTube: cosa saltare e il messaggio
                                @JavascriptInterface
                                @Suppress("unused")
                                fun sponsorBlockCategories(): String = SponsorBlock.categoriesJson()

                                @JavascriptInterface
                                @Suppress("unused")
                                fun sponsorBlockMessage(): String = context.getString(R.string.sponsorblock_skipped)
                            },
                            "AndroidBridge",
                        )

                        webChromeClient = object : WebChromeClient() {
                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallback: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?
                            ): Boolean {
                                // false: la richiesta viene annullata dalla WebView (il callback non va chiamato)
                                if (filePathCallback == null) return false
                                // In auto il selettore non si apre: l'ultima scelta del telefono, se il sito ha il permesso
                                if (!fileChooser.canOpenPicker) {
                                    return sitePrompts.onCarFileRequest(
                                        webView?.url,
                                        filePathCallback,
                                        fileChooserParams?.mode ?: FileChooserParams.MODE_OPEN
                                    )
                                }
                                return fileChooser.show(filePathCallback, fileChooserParams)
                            }

                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message?
                            ): Boolean {
                                val context = view?.context ?: return false
                                popupUrl = null
                                val popup = createPopupWebView(
                                    context,
                                    null,
                                    view.settings?.userAgentString,
                                    onPageChanged = { popupUrl = it }
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

                            // DRM sempre, microfono come scelto per il sito, fotocamera mai
                            override fun onPermissionRequest(request: PermissionRequest?) {
                                request?.let(sitePrompts::onPermissionRequest)
                            }

                            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                                request?.let(sitePrompts::onPermissionRequestCanceled)
                            }

                            override fun onGeolocationPermissionsShowPrompt(
                                origin: String?,
                                callback: GeolocationPermissions.Callback?
                            ) {
                                sitePrompts.onGeolocationPrompt(origin, callback)
                            }

                            override fun onGeolocationPermissionsHidePrompt() {
                                sitePrompts.onGeolocationHidePrompt()
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

                            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean =
                                result != null && showJsDialog(JsDialogRequest(JsDialogRequest.Kind.ALERT, url, message, null, result))

                            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean =
                                result != null && showJsDialog(JsDialogRequest(JsDialogRequest.Kind.CONFIRM, url, message, null, result))

                            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?): Boolean =
                                result != null && showJsDialog(JsDialogRequest(JsDialogRequest.Kind.PROMPT, url, message, defaultValue, result))

                            // "Vuoi lasciare la pagina?": senza risposta la WebView cerca un'Activity per chiederlo, qui
                            // non la trova (contesto localizzato) e annulla: indietro, avanti e i link non facevano niente
                            override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean =
                                result != null && showJsDialog(JsDialogRequest(JsDialogRequest.Kind.CONFIRM, url, message, null, result))

                            // Solo la scheda visibile mostra la finestra; dalle altre la pagina riceve Annulla
                            private fun showJsDialog(request: JsDialogRequest): Boolean {
                                if (!currentIsTabActive || jsDialog != null) request.cancel() else jsDialog = request
                                return true
                            }
                        }

                        webViewClient = @SuppressLint("MissingOnRenderProcessGone")
                        object : WebViewClient() {
                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: android.graphics.Bitmap?
                            ) {
                                pageHost.set(url?.let { Uri.parse(it).host })

                                // Nuova pagina: il media della precedente non esiste più
                                mediaSessionManager?.releaseMediaFocus()
                                updateMediaCapabilities(url)
                                // e neanche le sue richieste in attesa di un avviso di certificato o di un permesso
                                sslPrompts.cancelAll()
                                sitePrompts.onPageStarted(url)
                                jsDialog?.cancel()
                                jsDialog = null

                                if (!VoiceCommands.isYouTubeUrl(url)) voiceAutoplay = false
                                // Video condiviso dal telefono con "CarWeb in auto": parte da solo, come con "metti …"
                                if (currentIsTabActive &&
                                    VoiceCommandRouter.consumeAutoplay(VoiceCommandRouter.Target.MAIN, url)
                                ) {
                                    voiceAutoplay = true
                                }
                                // Le pagine di CarWeb Media partono solo se glielo si chiede (tocco o comando vocale)
                                view?.settings?.mediaPlaybackRequiresUserGesture =
                                    !autoplayMedia && !isDaznUrl(url) && !voiceAutoplay && !LocalPlayer.isPlayerUrl(url)
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
                                    // Solo dalla pagina principale e solo verso indirizzi web: un iframe (es. una
                                    // pubblicità) con ripiego "javascript:" faceva eseguire il suo codice nella pagina
                                    if (request?.isForMainFrame != true) return true
                                    try {
                                        val intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                                        val targetUrl = intent.getStringExtra("browser_fallback_url")
                                            ?.takeIf { UrlUtils.isWebUrl(it) }
                                            ?: intent.dataString?.takeIf { it.startsWith("https://") }
                                        if (targetUrl != null) {
                                            if (targetUrl.contains("dazn.com", ignoreCase = true)) {
                                                daznDesktopForced = true
                                                view?.let { setDesktopUserAgent(it, context) }
                                            }
                                            view?.loadUrl(targetUrl)
                                            return true
                                        }
                                    } catch (e: Exception) {
                                        AppLog.w("BrowserScreen", "Intent parse error: ${e.javaClass.simpleName}")
                                    }
                                    return true
                                }
                                // Pagine pubblicitarie, e la pagina che va altrove subito dopo un pop-up: si resta qui
                                if (request?.isForMainFrame == true && !PopupGuard.allowsNavigation(
                                        uri, pageHost.get(), request.hasGesture(), request.isRedirect, popupAttempts
                                    )
                                ) {
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
                                // Prima che la pagina nuova esegua i suoi script (onPageStarted arriva dopo)
                                if (request?.isForMainFrame == true) pageHost.set(request.url?.host)

                                // Lettore locale dell'app (https://player.local/): pagina e file della cartella scelta
                                LocalPlayer.intercept(context, request)?.let { return it }

                                val urlString = request?.url?.toString() ?: ""
                                val requestUrl = request?.url
                                if (AdBlockHost.shouldBlock(urlString, pageHost.get())) {
                                    return AdBlockHost.blockedResponse(request)
                                }

                                // DAZN: risposta alle richieste CORS "OPTIONS" delle sue chiamate (come nella Wide). Solo DAZN, con il
                                // dominio esatto: rispondere "ok" al posto del server per tutti i siti permetteva a qualsiasi pagina
                                // richieste con i cookie verso altri siti e i dispositivi di casa, che il browser avrebbe bloccato
                                val origin = request?.requestHeaders?.get("Origin")
                                if (
                                    request != null && request.method?.equals("OPTIONS", ignoreCase = true) == true &&
                                    (DaznProxy.isDaznHost(requestUrl?.host) || DaznProxy.isDaznHost(origin?.let { Uri.parse(it).host }))
                                ) {
                                    val isDaznPlaybackPreflight =
                                        requestUrl?.host.equals("api.playback.indazn.com", ignoreCase = true) &&
                                                requestUrl?.path.equals("/v5/Playback", ignoreCase = true)
                                    if (isDaznPlaybackPreflight) {
                                        AppLog.d("DaznPlayback", "Passing playback CORS preflight to WebView network stack")
                                        return super.shouldInterceptRequest(view, request)
                                    }
                                    val reqOrigin = origin ?: "https://www.dazn.com"
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
                                                "headers=${JSONObject(safeHeaders)}"
                                    )
                                }
                                super.onReceivedHttpError(view, request, errorResponse)
                            }

                            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                if (handler == null || error == null) {
                                    handler?.cancel()
                                    return
                                }
                                sslPrompts.onSslError(handler, error)
                            }

                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: android.webkit.RenderProcessGoneDetail?
                            ): Boolean {
                                AppLog.e("##BrowserScreen", "Render process gone (crash=${detail?.didCrash()}): tab $tabId")
                                val now = SystemClock.uptimeMillis()
                                val again = now - lastRenderCrashAt.getAndSet(now) < RENDER_CRASH_RETRY_MS
                                // La pagina non c'è più: con lei il player e le richieste in attesa
                                mediaSessionManager?.releaseMediaFocus()
                                sslPrompts.cancelAll()
                                sitePrompts.cancelAll()
                                jsDialog?.cancel()
                                jsDialog = null
                                view?.let {
                                    (it.parent as? ViewGroup)?.removeView(it)
                                    it.destroy()
                                }
                                webViewReference = null
                                if (!again) webViewGeneration++
                                return true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (url != null) {
                                    // La WebView chiama questo metodo anche per una navigazione annullata, con il suo
                                    // indirizzo (indietro toccato prima che la pagina rispondesse, link bloccato): la
                                    // scheda annota solo la pagina che la WebView sta davvero mostrando
                                    if (view?.url == url) {
                                        lastReportedUrl = url
                                        // Niente updateMediaCapabilities qui: i tasti predefiniti li mette onPageStarted
                                        // e a pagina caricata li aggiorna la pagina stessa (Media Session, playlist YouTube)
                                        onPageFinished(url)
                                    }
                                    AppLog.d("##BrowserScreen", "onPageFinished: ${safeUrlForLog(url)}")

                                    view?.let { webView ->
                                        if (url.contains("dazn.com", ignoreCase = true)) {
                                            webView.evaluateJavascript(
                                                DaznManager.getClickInterceptorScript(),
                                                null
                                            )
                                        }
                                        // Ripristina la velocità YouTube salvata lato Android
                                        if (url.contains("youtube.com", ignoreCase = true) &&
                                            !url.contains("music.youtube.com", ignoreCase = true)) {
                                            val savedSpeed = AppSettings.getYouTubeSpeed(context)
                                            webView.evaluateJavascript(
                                                "localStorage.setItem('yt-custom-speed', '$savedSpeed');",
                                                null
                                            )
                                            AppLog.d("YouTubeSpeed", "Restored playback speed = $savedSpeed")
                                        }
                                        // Ricerca del comando vocale "metti …": parte il primo risultato
                                        if (currentIsTabActive && VoiceCommandRouter.consumePlayFirstResult(
                                                VoiceCommandRouter.Target.MAIN, url
                                            )
                                        ) {
                                            voiceAutoplay = true
                                            webView.settings.mediaPlaybackRequiresUserGesture = false
                                            webView.evaluateJavascript(YouTubeManager.getPlayFirstResultScript(), null)
                                        }
                                        WebViewScriptRouter.routeAndInject(
                                            webView = webView,
                                            urlString = url,
                                            isYouTubeAdBlockEnabled = isYouTubeAdBlockEnabled,
                                            forceMaxYouTubeQuality = forceMaxYouTubeQuality,
                                            youtubeQuality = youtubeQuality,
                                            autoplayMedia = autoplayMedia || voiceAutoplay,
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
                    if (isTabActive) {
                        updateMediaCapabilities(webView.url ?: url)
                    }
                    CarFrameLayout(context).apply {
                        layoutParams = ViewGroup.LayoutParams(-1, -1)
                        addView(webView, android.widget.FrameLayout.LayoutParams(-1, -1))
                    }
                },
                update = { view ->
                    val webView = view.getChildAt(0) as? WebView
                    if (webViewReference != webView) {
                        webViewReference = webView
                    }
                    webView?.let {
                        // Niente reload() qui: lo fa LaunchedEffect(reloadTrigger), solo per la scheda attiva. Questo
                        // confrontava il tag con reloadTrigger, che per le schede inattive vale 0: aprendo una scheda
                        // nuova quella che stava suonando si ricaricava (video fermo, player di nuovo sul meteo)
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
        }

        // Una vista nuova a ogni schermo intero: senza key, se la WebView ne passava un'altra prima che la vecchia
        // uscisse dalla composizione (rientro dal background), restava attaccata la vecchia e la pagina si fermava
        customView?.let { view ->
            key(view) {
                AndroidView(
                    factory = {
                        (view.parent as? ViewGroup)?.removeView(view)
                        view
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                )
            }
        }

        popupWebView?.let { popup ->
            DisposableEffect(popup) {
                onDispose {
                    (popup.parent as? ViewGroup)?.removeView(popup)
                    popup.stopLoading()
                    popup.destroy()
                }
            }
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
                            Text(
                                text = popupAddress(popupUrl) ?: stringResource(R.string.popup_signing_in),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            Button(
                                onClick = { popupWebView = null }
                            ) {
                                Text(text = stringResource(R.string.close_button))
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
                        if (carInputManager != null && passengerKeyboard) {
                            // Tastiera dell'app: il campo si memorizza prima (come nella Wide), poi Invio scrive lì
                            webView.evaluateJavascript(BrowserJavascript.getCaptureInputTargetScript()) { result ->
                                val field = result?.takeIf { it.startsWith("{") }
                                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                                AppLog.d("##BrowserScreen", "Passenger keyboard: field captured=${field != null}")
                                passengerField = field?.optString("value").orEmpty() to
                                        (field?.optString("hint")?.takeIf { it.isNotBlank() } ?: fieldHint)
                            }
                        } else {
                            webView.post {
                                webView.requestFocus()
                                carInputManager?.startInput(webView)
                            }
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
                                // Come è stato scritto (exec, refocused:exec, no-target, ...): solo nei log
                                webViewReference?.evaluateJavascript(
                                    BrowserJavascript.getInjectTextScript(spokenText)
                                ) { written -> AppLog.d("##BrowserScreen", "Voice input written: $written") }
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

        passengerField?.takeIf { isTabActive }?.let { (initialText, hint) ->
            PassengerKeyboardPanel(
                initialText = initialText,
                hint = hint,
                onDone = { text ->
                    passengerField = null
                    suppressInputPopupUntil = SystemClock.uptimeMillis() + INPUT_POPUP_SUPPRESS_MS
                    // Il risultato dice come è stato scritto (typed:exec, typed:paste, no-target, ...): solo nei log
                    webViewReference?.evaluateJavascript(BrowserJavascript.getSetInputTextScript(text)) { written ->
                        AppLog.d("##BrowserScreen", "Passenger keyboard text written: $written")
                    }
                },
                onClose = { passengerField = null },
            )
        }

        WebFileChooserDialog(fileChooser)

        // Le schede in background restano in attesa: l'avviso compare quando le apri
        sslPrompt?.takeIf { isTabActive }?.let { prompt ->
            SslWarningPopup(
                host = prompt.host,
                onProceed = { sslPrompts.answer(prompt, proceed = true) },
                onCancel = { sslPrompts.answer(prompt, proceed = false) },
            )
        }

        sitePrompt?.takeIf { isTabActive && sslPrompt == null }?.let { prompt ->
            SitePermissionPopup(prompt = prompt, onChoice = { sitePrompts.answer(prompt, it) })
        }

        jsDialog?.takeIf { isTabActive }?.let { request ->
            JsDialogPopup(request) { if (jsDialog === request) jsDialog = null }
        }

        // Indietro del telefono: chiude quello che sta sopra la pagina, come il tocco fuori (prima il più in alto)
        val closeOnBack: (() -> Unit)? = when {
            jsDialog != null -> ({
                jsDialog?.dismiss()
                jsDialog = null
            })
            sslPrompt != null -> ({ sslPrompt?.let { sslPrompts.answer(it, proceed = false) } })
            sitePrompt != null -> ({ sitePrompt?.let { sitePrompts.answer(it, SitePermissionChoice.DISMISS) } })
            popupWebView != null -> ({ popupWebView = null })
            else -> null
        }
        PhoneBackHandler(enabled = isTabActive && isScreenVisible && closeOnBack != null) { closeOnBack?.invoke() }
    }
}