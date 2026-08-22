package com.fcaronte.aabrowser.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.webkit.WebViewFeature
import com.fcaronte.aabrowser.CarFrameLayout
import com.fcaronte.aabrowser.CarInputManager
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.mediaservice.MediaSessionManager
import com.fcaronte.aabrowser.model.TabManager
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.utils.AdBlockHost
import com.fcaronte.aabrowser.utils.AdBlockJavascript
import com.fcaronte.aabrowser.utils.InactivityTracker
import java.io.ByteArrayInputStream

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

private fun isDesktopRequired(url: String?): Boolean {
    val lowUrl = url?.lowercase() ?: ""
    return lowUrl.contains("whatsapp.com") ||
            lowUrl.contains("messenger.com") ||
            lowUrl.contains("web.telegram.org") ||
            lowUrl.contains("web.skype.com")
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun BrowserScreen(
    url: String,
    reloadTrigger: Int = 0,
    backTrigger: Int = 0,
    isDesktopMode: Boolean = false,
    mediaSessionManager: MediaSessionManager? = null,
    carInputManager: CarInputManager? = null,
    desktopModeOverride: Boolean? = null,
    mobileZoomOverride: Float? = null,
    desktopZoomOverride: Float? = null,
    isTabActive: Boolean = true,
    isGlobalSearchActive: Boolean = false,
    onPageFinished: (String) -> Unit,
    onWebViewCreated: (WebView) -> Unit = {},
    onFullScreenChange: (Boolean) -> Unit = {},
    onInteraction: () -> Unit = {},
) {
    val darkPages by AppSettings.darkPages
    val globalDisplayScale by AppSettings.displayScale
    val globalDesktopScale by AppSettings.desktopScale

    val actualDesktopMode = desktopModeOverride ?: isDesktopMode
    val actualDisplayScale = mobileZoomOverride ?: globalDisplayScale
    val actualDesktopScale = desktopZoomOverride ?: globalDesktopScale
    val isYouTubeAdBlockEnabled by com.fcaronte.aabrowser.settings.AdBlockSettings.isYouTubeEnabled
    val context = LocalContext.current
    val voicePrompt = stringResource(R.string.voice_prompt)

    var webViewReference by remember { mutableStateOf<WebView?>(null) }
    var showInputPopup by remember { mutableStateOf(value = false) }
    var isListening by remember { mutableStateOf(value = false) }
    var customView by remember { mutableStateOf<android.view.View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    var lastInjectedUrl by remember { mutableStateOf("") }

    LaunchedEffect(customView) {
        onFullScreenChange(customView != null)
    }

    LaunchedEffect(isTabActive) {
        if (isTabActive) {
            webViewReference?.let { onWebViewCreated(it) }
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
                it.evaluateJavascript(AdBlockJavascript.getYouTubeAdBlockScript(), null)
                lastInjectedUrl = currentUrl
            }
        }
    }

    LaunchedEffect(backTrigger) {
        if (backTrigger > 0) {
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
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val webView = object : WebView(context) {
                    // Impedisce alla WebView di andare in pausa profonda quando l'app è in background
                    // Questo aiuta a mantenere l'esecuzione degli script e del video
                    override fun onPause() {
                        // Non chiamiamo super.onPause() per evitare che il motore Chromium sospenda tutto
                        // ma informiamo il sistema che siamo ancora "attivi" per i media
                    }
                    override fun onWindowVisibilityChanged(visibility: Int) {
                        // Forza la visibilità a essere sempre VISIBLE per Chromium
                        super.onWindowVisibilityChanged(VISIBLE)
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
                        mediaPlaybackRequiresUserGesture = false
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        allowContentAccess = true
                        allowFileAccess = true
                        mixedContentMode =
                            android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    }

                    // Abilita i cookie in modo persistente
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, darkPages)
                    }

                    if (actualDesktopMode || isDesktopRequired(url)) {
                        settings.userAgentString = DESKTOP_USER_AGENT
                    }

                    // Implementazione robusta per il controllo dei media
                    mediaSessionManager?.apply {
                        onPlay = {
                            evaluateJavascript(
                                """
                                (function() {
                                    window.isMediaPlaying = true;
                                    document.querySelectorAll('video').forEach(v => {
                                        if (v.paused) v.play().catch(e => console.log("Play failed: ", e));
                                    });
                                })();
                                """.trimIndent(),
                                null,
                            )
                        }
                        onPause = {
                            evaluateJavascript(
                                """
                                (function() {
                                    window.isMediaPlaying = false;
                                    document.querySelectorAll('video').forEach(v => v.pause());
                                })();
                                """.trimIndent(),
                                null,
                            )
                        }
                        onStop = {
                            evaluateJavascript(
                                """
                                (function() {
                                    window.isMediaPlaying = false;
                                    document.querySelectorAll('video').forEach(v => v.pause());
                                })();
                                """.trimIndent(),
                                null,
                            )
                        }
                        onSkipToNext = {
                            evaluateJavascript(
                                """
                                (function() {
                                    const nextBtn = document.querySelector('.ytp-next-button, ytmusic-player-bar .next-button, [aria-label="Next"], [title="Next"]');
                                    if (nextBtn) nextBtn.click();
                                    else {
                                        const video = document.querySelector('video');
                                        if (video) video.currentTime += 10;
                                    }
                                })();
                                """.trimIndent(),
                                null,
                            )
                        }
                        onSkipToPrevious = {
                            evaluateJavascript(
                                """
                                (function() {
                                    const prevBtn = document.querySelector('.ytp-prev-button, ytmusic-player-bar .previous-button, [aria-label="Previous"], [title="Previous"]');
                                    if (prevBtn) prevBtn.click();
                                    else {
                                        const video = document.querySelector('video');
                                        if (video) video.currentTime -= 10;
                                    }
                                })();
                                """.trimIndent(),
                                null,
                            )
                        }
                        onSeekTo = { pos ->
                            evaluateJavascript(
                                """
                                (function() {
                                    const video = document.querySelector('video');
                                    if (video) video.currentTime = ${pos / 1000.0};
                                })();
                                """.trimIndent(),
                                null,
                            )
                        }
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
                                )
                            }

                            @android.webkit.JavascriptInterface
                            @Suppress("unused")
                            fun onMediaTimeUpdate(time: Float) {
                                @Suppress("DEPRECATION")
                                mediaSessionManager?.updatePlaybackState(
                                    android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING,
                                    (time * 1000).toLong(),
                                )
                            }

                            @android.webkit.JavascriptInterface
                            @Suppress("unused")
                            fun onMediaStatusChanged(isPlaying: Boolean, time: Float) {
                                @Suppress("DEPRECATION")
                                mediaSessionManager?.updatePlaybackState(
                                    if (isPlaying) {
                                        android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
                                    } else {
                                        android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
                                    },
                                    (time * 1000).toLong(),
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

                            @android.webkit.JavascriptInterface
                            @Suppress("unused")
                            fun onMetadataUpdated(title: String, faviconUrl: String, currentUrl: String) {
                                post {
                                    TabManager.activeTab?.let { currentTab ->
                                        TabManager.updateTabTitle(currentTab.id, title)
                                        TabManager.updateTabFavicon(currentTab.id, faviconUrl)
                                    }
                                }
                            }

                            @android.webkit.JavascriptInterface
                            @Suppress("unused")
                            fun onStartAdBlock() {
                                post {
                                    android.util.Log.d("##BrowserScreen", "AdBlock request, YouTube enabled: $isYouTubeAdBlockEnabled")
                                    if (isYouTubeAdBlockEnabled) {
                                        webViewReference?.evaluateJavascript(AdBlockJavascript.getYouTubeAdBlockScript(), null)
                                    }
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
                                        """
                                        (function() {
                                            var el = document.activeElement;
                                            if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.contentEditable === 'true')) {
                                                // Debouncing JS per evitare inserimenti multipli dallo smartphone
                                                var now = Date.now();
                                                if (el.lastInjectTime && (now - el.lastInjectTime < 100) && el.lastInjectText === "$text") {
                                                    return;
                                                }
                                                el.lastInjectTime = now;
                                                el.lastInjectText = "$text";

                                                var start = el.selectionStart || 0;
                                                var end = el.selectionEnd || 0;
                                                var val = el.value || el.innerText || "";
                                                if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
                                                    el.value = val.substring(0, start) + "$text" + val.substring(end);
                                                    el.selectionStart = el.selectionEnd = start + "$text".length;
                                                    el.focus(); // Assicura che rimanga focused
                                                } else {
                                                    el.innerText = val.substring(0, start) + "$text" + val.substring(end);
                                                    el.focus();
                                                }
                                                el.dispatchEvent(new Event('input', { bubbles: true }));
                                                el.dispatchEvent(new Event('change', { bubbles: true }));
                                            }
                                        })();
                                        """.trimIndent(),
                                        null,
                                    )
                                }
                            }
                        },
                        "AndroidBridge",
                    )

                    webChromeClient = object : WebChromeClient() {
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
                                TabManager.activeTab?.let { currentTab ->
                                    TabManager.updateTabTitle(currentTab.id, newTitle)
                                }
                            }
                        }

                        override fun onPermissionRequest(request: PermissionRequest?) {
                            request?.grant(request.resources)
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: android.graphics.Bitmap?
                        ) {
                            if (actualDesktopMode || isDesktopRequired(url)) {
                                view?.settings?.userAgentString = DESKTOP_USER_AGENT
                            } else {
                                view?.settings?.userAgentString = null
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
                            if (AdBlockHost.shouldBlock(request?.url?.toString() ?: "")) {
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

                                if (url.contains("youtube.com") && isYouTubeAdBlockEnabled && lastInjectedUrl != url) {
                                    android.util.Log.d("##BrowserScreen", "Injecting AdBlock from onPageFinished")
                                    view?.evaluateJavascript(AdBlockJavascript.getYouTubeAdBlockScript(), null)
                                    lastInjectedUrl = url
                                }

                                // Salva i cookie su disco ad ogni caricamento terminato
                                CookieManager.getInstance().flush()

                                val needsDesktop = actualDesktopMode || isDesktopRequired(url)
                                if (needsDesktop) {
                                    view?.evaluateJavascript(
                                        """
                                        (function() {
                                            var meta = document.querySelector('meta[name="viewport"]');
                                            if (!meta) { meta = document.createElement('meta'); meta.name = "viewport"; document.head.appendChild(meta); }
                                            meta.content = "width=1280, initial-scale=" + $actualDesktopScale + ", user-scalable=yes";
                                            document.body.style.minWidth = '1280px';
                                        })();
                                        """.trimIndent(),
                                        null,
                                    )
                                } else {
                                    view?.evaluateJavascript(
                                        """
                                        (function() {
                                            var meta = document.querySelector('meta[name="viewport"]');
                                            if (!meta) { meta = document.createElement('meta'); meta.name = "viewport"; document.head.appendChild(meta); }
                                            meta.content = "initial-scale=" + $actualDisplayScale + ", user-scalable=yes";
                                        })();
                                        """.trimIndent(),
                                        null,
                                    )
                                }

                                view?.evaluateJavascript(
                                    """
                                    (function() {
                                        // Shield anti-pausa e visibilità sempre attiva
                                        const mockVisibility = () => {
                                            const props = { value: false, writable: false, configurable: true };
                                            const visibleProps = { value: 'visible', writable: false, configurable: true };
                                            Object.defineProperty(document, 'hidden', props);
                                            Object.defineProperty(document, 'visibilityState', visibleProps);
                                            Object.defineProperty(document, 'webkitVisibilityState', visibleProps);
                                            Object.defineProperty(document, 'webkitHidden', props);
                                            // Mock per evitare che YouTube rilevi la perdita di focus della finestra
                                            Object.defineProperty(window, 'onblur', { value: null, writable: true });
                                            Object.defineProperty(document, 'onblur', { value: null, writable: true });
                                            document.hasFocus = () => true;
                                        };
                                        mockVisibility();
                                        
                                        // Blocca gli eventi di cambio visibilità e focus che causano pause
                                        const blockEvent = (e) => { 
                                            if (e.type === 'blur' || e.type === 'mouseleave' || e.type.includes('visibility')) {
                                                e.stopImmediatePropagation(); 
                                                mockVisibility(); 
                                            }
                                        };
                                        ['visibilitychange', 'webkitvisibilitychange', 'blur', 'mouseleave'].forEach(evt => {
                                            document.addEventListener(evt, blockEvent, true);
                                            window.addEventListener(evt, blockEvent, true);
                                        });

                                        // Fix per il background play e transizione full-screen
                                        const originalPause = HTMLVideoElement.prototype.pause;
                                        HTMLVideoElement.prototype.pause = function() {
                                            // YouTube usa pause() quando l'utente cambia tab o minimizza.
                                            // Blocca la pausa se la pagina è nascosta o se stiamo in fullscreen (che spesso stacca il video dal DOM principale)
                                            if (document.hidden || document.visibilityState === 'hidden' || document.webkitVisibilityState === 'hidden') {
                                                console.log("AABrowser: Prevented pause in background");
                                                if (window.aabIsAdPlaying) return originalPause.apply(this, arguments);
                                                return Promise.resolve();
                                            }
                                            // Se la pausa viene chiamata ma il video dovrebbe essere in play (stato interno nostro)
                                            if (window.isMediaPlaying === true && !window.aabIsAdPlaying) {
                                                console.log("AABrowser: Prevented forced pause while state is playing");
                                                return Promise.resolve();
                                            }
                                            return originalPause.apply(this, arguments);
                                        };

                                        function syncPageMetadata() {
                                            const getFavicon = () => {
                                                const icon = document.querySelector('link[rel="apple-touch-icon"]') || 
                                                             document.querySelector('link[rel="icon"][sizes="192x192"]') ||
                                                             document.querySelector('link[rel="icon"][sizes="96x96"]') ||
                                                             document.querySelector('link[rel="icon"]') ||
                                                             document.querySelector('link[rel="shortcut icon"]');
                                                return icon ? icon.href : "https://www.google.com/s2/favicons?domain=" + window.location.hostname + "&sz=128";
                                            };
                                            AndroidBridge.onMetadataUpdated(document.title, getFavicon(), window.location.href);
                                        }
                                        
                                        // Monitoraggio cambiamenti titolo e URL (per SPA come YouTube)
                                        let lastHref = window.location.href;
                                        let lastTitle = document.title;
                                        const observer = new MutationObserver(() => {
                                            if (window.location.href !== lastHref) {
                                                lastHref = window.location.href;
                                                syncPageMetadata();
                                                if (window.location.host.includes('youtube.com')) {
                                                    AndroidBridge.onStartAdBlock();
                                                }
                                            } else if (document.title !== lastTitle) {
                                                lastTitle = document.title;
                                                syncPageMetadata();
                                            }
                                        });
                                        observer.observe(document.querySelector('title') || document.documentElement, { subtree: true, characterData: true, childList: true });

                                        // Eventi specifici YouTube e YouTube Music
                                        const handleNavFinish = () => {
                                            syncPageMetadata();
                                            // Re-inizializza AdBlock su navigazione interna
                                            window.aabAdBlockInitialized = false;
                                            AndroidBridge.onStartAdBlock();
                                        };
                                        window.addEventListener('yt-navigate-finish', handleNavFinish);
                                        window.addEventListener('ytmusic-navigate-finish', handleNavFinish);
                                        
                                        setTimeout(syncPageMetadata, 1500);

                                        // Monitoraggio Metadati Media
                                        let lastMediaTitle = "";
                                        let lastDuration = 0;
                                        function syncMetadata() {
                                            const video = document.querySelector('video');
                                            if (!video) return;

                                            let title = document.title;
                                            let artist = "AABrowser Audio";
                                            let artUrl = "";
                                            let duration = isFinite(video.duration) ? video.duration : 0;

                                            if (window.location.host.includes('youtube.com')) {
                                                const ytTitle = document.querySelector('.ytp-title-link')?.innerText || 
                                                                 document.querySelector('ytmusic-player-bar .title')?.textContent ||
                                                                 document.querySelector('.ytmusic-player-bar .title')?.textContent ||
                                                                 document.querySelector('ytmusic-player-bar a.title')?.textContent ||
                                                                 document.querySelector('.title.ytmusic-player-bar')?.textContent;
                                                const ytArtist = document.querySelector('.ytp-ce-channel-title')?.innerText || 
                                                                 document.querySelector('.yt-user-info')?.innerText ||
                                                                 document.querySelector('#upload-info #channel-name')?.innerText ||
                                                                 document.querySelector('ytmusic-player-bar .byline')?.textContent ||
                                                                 document.querySelector('.ytmusic-player-bar .byline')?.textContent ||
                                                                 document.querySelector('ytmusic-player-bar .byline a')?.textContent;
                                                
                                                if (ytTitle) title = ytTitle.trim();
                                                if (ytArtist) artist = ytArtist.trim();
                                                
                                                const urlParams = new URLSearchParams(window.location.search);
                                                const v = urlParams.get('v');
                                                if (v) artUrl = 'https://img.youtube.com/vi/' + v + '/0.jpg';
                                                
                                                // Supporto specifico per YT Music (copertina)
                                                if (window.location.host.includes('music.youtube.com')) {
                                                    const musicArt = document.querySelector('ytmusic-player-bar img')?.src || 
                                                                     document.querySelector('.ytmusic-player-bar img')?.src ||
                                                                     document.querySelector('#thumbnail img')?.src;
                                                    if (musicArt) artUrl = musicArt;
                                                }
                                            }

                                            if (title && (title !== lastTitle || Math.abs(duration - lastDuration) > 1)) {
                                                lastTitle = title;
                                                lastDuration = duration;
                                                AndroidBridge.updateMediaMetadata(title, artist, artUrl, duration);
                                            }
                                        }

                                        function setupVideoListeners(video) {
                                            if (video.dataset.mediaListenersAdded) return;
                                            video.dataset.mediaListenersAdded = 'true';
                                            video.lastBridgeUpdate = 0;

                                            video.addEventListener('play', () => {
                                                window.isMediaPlaying = true;
                                                AndroidBridge.onMediaStatusChanged(true, video.currentTime);
                                                syncMetadata();
                                            });
                                            video.addEventListener('pause', () => {
                                                // Non cambiamo window.isMediaPlaying qui perché potrebbe essere una pausa forzata
                                                // che vogliamo contrastare. Lo stato viene cambiato solo da input utente/MediaSession.
                                                AndroidBridge.onMediaStatusChanged(false, video.currentTime);
                                            });
                                            video.addEventListener('timeupdate', () => {
                                                if (!video.isSeeking) {
                                                    const now = Date.now();
                                                    // Throttling: invia aggiornamenti al MediaService ogni secondo (per fluidità)
                                                    const timeDiff = Math.abs(video.currentTime - (video.lastReportedTime || 0));
                                                    if (now - video.lastBridgeUpdate > 1000 || timeDiff > 2) {
                                                        AndroidBridge.onMediaTimeUpdate(video.currentTime);
                                                        video.lastBridgeUpdate = now;
                                                        video.lastReportedTime = video.currentTime;
                                                    }
                                                }
                                            });
                                            video.addEventListener('durationchange', () => {
                                                syncMetadata();
                                            });
                                        }

                                        const videoObserver = new MutationObserver(() => {
                                            const video = document.querySelector('video');
                                            if (video) {
                                                setupVideoListeners(video);
                                            }
                                        });
                                        videoObserver.observe(document.body, { childList: true, subtree: true });

                                        const video = document.querySelector('video');
                                        if (video) {
                                            setupVideoListeners(video);
                                            setTimeout(syncMetadata, 2000);
                                        }
                                        
                                        function setupInputListeners() {
                                            document.querySelectorAll('input, textarea, [contenteditable="true"]').forEach(el => {
                                                if (!el.dataset.listenerAdded) {
                                                    el.addEventListener('focus', () => AndroidBridge.onStartInput());
                                                    // Alcuni elementi mobile potrebbero aver bisogno di click se focus non scatta
                                                    el.addEventListener('click', () => AndroidBridge.onStartInput());
                                                    el.dataset.listenerAdded = 'true';
                                                }
                                            });
                                        }
                                        const inputObserver = new MutationObserver(setupInputListeners);
                                        inputObserver.observe(document.body, { childList: true, subtree: true });
                                        setupInputListeners();
                                    })();
                                    """.trimIndent(),
                                    null,
                                )
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
                webViewReference = webView
                webView?.let {
                    if (it.tag != reloadTrigger) {
                        it.reload()
                        it.tag = reloadTrigger
                    }
                    val needsDesktop = actualDesktopMode || isDesktopRequired(it.url)
                    val targetScale = if (needsDesktop) actualDesktopScale else actualDisplayScale
                    it.setInitialScale(if (targetScale == 1.0f) 0 else (targetScale * 100).toInt())
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

        val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
        DisposableEffect(Unit) { onDispose { speechRecognizer.destroy() } }

        if (isListening) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        isListening = false
                        try {
                            speechRecognizer.stopListening()
                        } catch (_: Exception) {
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .background(Color.DarkGray, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Mic,
                            null,
                            tint = Color.Red,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.voice_listening), color = Color.White)
                    }
                }
            }
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
                                    "AndroidBridge.injectText('${
                                        spokenText.replace(
                                            "'",
                                            "\\'"
                                        )
                                    }');", null
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