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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.fcaronte.aabrowser.SubscriptionsManager
import com.fcaronte.aabrowser.mediaservice.MediaSessionManager
import com.fcaronte.aabrowser.model.TabManager
import com.fcaronte.aabrowser.settings.AppSettings
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import kotlin.time.Duration.Companion.milliseconds

private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

private fun isDesktopRequired(url: String?): Boolean {
    val lowUrl = url?.lowercase() ?: ""
    return lowUrl.contains("whatsapp.com") ||
            lowUrl.contains("messenger.com") ||
            lowUrl.contains("web.telegram.org") ||
            lowUrl.contains("web.skype.com")
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    url: String,
    reloadTrigger: Int = 0,
    backTrigger: Int = 0,
    isDesktopMode: Boolean = false,
    mediaSessionManager: MediaSessionManager? = null,
    carInputManager: CarInputManager? = null,
    onPageFinished: (String) -> Unit,
    onWebViewCreated: (WebView) -> Unit = {},
) {
    val darkPages by AppSettings.darkPages
    val displayScale by AppSettings.displayScale
    val desktopScale by AppSettings.desktopScale
    val context = LocalContext.current
    val voicePrompt = stringResource(R.string.voice_prompt)

    var webViewReference by remember { mutableStateOf<WebView?>(null) }
    var showInputPopup by remember { mutableStateOf(value = false) }
    var isListening by remember { mutableStateOf(value = false) }

    LaunchedEffect(Unit) {
        mediaSessionManager?.connect()
    }

    LaunchedEffect(url) {
        webViewReference?.let {
            val currentUrl = it.url
            if (currentUrl.isNullOrBlank() || (!currentUrl.contains(url) && !url.contains(currentUrl))) {
                it.loadUrl(url)
            }
        }
    }

    LaunchedEffect(backTrigger) {
        if (backTrigger > 0) {
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
                val webView = WebView(context).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

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
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    }

                    // Abilita i cookie in modo persistente
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, darkPages)
                    }

                    if (isDesktopMode || isDesktopRequired(url)) {
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
                                mediaSessionManager?.updateMetadata(title, artist, albumArtUrl, (duration * 1000).toLong())
                            }

                            @android.webkit.JavascriptInterface
                            @Suppress("unused")
                            fun onMetadataUpdated(title: String, faviconUrl: String) {
                                post {
                                    TabManager.activeTab?.let { currentTab ->
                                        TabManager.updateTabTitle(currentTab.id, title)
                                        TabManager.updateTabFavicon(currentTab.id, faviconUrl)
                                    }
                                }
                            }

                            @android.webkit.JavascriptInterface
                            @Suppress("unused")
                            fun onStartInput() {
                                post { showInputPopup = true }
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
                                                var start = el.selectionStart || 0;
                                                var end = el.selectionEnd || 0;
                                                var val = el.value || el.innerText || "";
                                                if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
                                                    el.value = val.substring(0, start) + "$text" + val.substring(end);
                                                    el.selectionStart = el.selectionEnd = start + "$text".length;
                                                } else {
                                                    el.innerText = val.substring(0, start) + "$text" + val.substring(end);
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
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            if (isDesktopMode || isDesktopRequired(url)) {
                                view?.settings?.userAgentString = DESKTOP_USER_AGENT
                            } else {
                                view?.settings?.userAgentString = null
                            }
                        }

                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            if (SubscriptionsManager.shouldBlock(request?.url?.toString() ?: "")) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                            return true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url != null) {
                                onPageFinished(url)
                                if (url.contains("youtube.com")) injectYouTubeAdBlock(view)

                                // Salva i cookie su disco ad ogni caricamento terminato
                                CookieManager.getInstance().flush()

                                val needsDesktop = isDesktopMode || isDesktopRequired(url)
                                if (needsDesktop) {
                                    view?.evaluateJavascript(
                                        """
                                        (function() {
                                            var meta = document.querySelector('meta[name="viewport"]');
                                            if (!meta) { meta = document.createElement('meta'); meta.name = "viewport"; document.head.appendChild(meta); }
                                            meta.content = "width=1280, initial-scale=" + $desktopScale + ", user-scalable=yes";
                                            document.body.style.minWidth = '1280px';
                                        })();
                                        """.trimIndent(),
                                        null,
                                    )
                                }

                                view?.evaluateJavascript(
                                    """
                                    (function() {
                                        // Shield anti-pausa e visibilità sempre attiva
                                        Object.defineProperty(document, 'hidden', { value: false, writable: false });
                                        Object.defineProperty(document, 'visibilityState', { value: 'visible', writable: false });

                                        function syncPageMetadata() {
                                            AndroidBridge.onMetadataUpdated(document.title, "https://www.google.com/s2/favicons?domain=" + window.location.hostname + "&sz=128");
                                        }
                                        setTimeout(syncPageMetadata, 1500);

                                        // Monitoraggio Metadati Media
                                        let lastTitle = "";
                                        let lastDuration = 0;
                                        function syncMetadata() {
                                            const video = document.querySelector('video');
                                            if (!video) return;

                                            let title = document.title;
                                            let artist = "AA Browser Audio";
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

                                            video.addEventListener('play', () => {
                                                AndroidBridge.onMediaStatusChanged(true, video.currentTime);
                                                syncMetadata();
                                            });
                                            video.addEventListener('pause', () => {
                                                AndroidBridge.onMediaStatusChanged(false, video.currentTime);
                                            });
                                            video.addEventListener('timeupdate', () => {
                                                if (!video.isSeeking) {
                                                    AndroidBridge.onMediaTimeUpdate(video.currentTime);
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
                    val needsDesktop = isDesktopMode || isDesktopRequired(it.url)
                    val targetScale = if (needsDesktop) desktopScale else displayScale
                    it.setInitialScale(if (targetScale == 1.0f) 0 else (targetScale * 100).toInt())
                }
            },
        )

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
                        Icon(Icons.Default.Mic, null, tint = Color.Red, modifier = Modifier.size(48.dp))
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
                    webViewReference?.let { carInputManager?.startInput(it) }
                },
                onMicSelected = {
                    showInputPopup = false
                    isListening = true
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
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
                            val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                            if (!spokenText.isNullOrEmpty()) {
                                webViewReference?.evaluateJavascript("AndroidBridge.injectText('${spokenText.replace("'", "\\'")}');", null)
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


@Composable
fun InputSelectionPopup(
    onKeyboardSelected: () -> Unit,
    onMicSelected: () -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) {
        delay(3000.milliseconds)
        onDismiss()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { },
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Row(modifier = Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                IconButton(onClick = onKeyboardSelected, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Default.Keyboard, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onMicSelected, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Default.Mic, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

private fun injectYouTubeAdBlock(webView: WebView?) {
    webView?.evaluateJavascript(
        """
        (function() {
            setInterval(() => {
                document.querySelectorAll('.video-ads, .ytp-ad-module, .ytp-ad-overlay-container, .ytp-ad-message-container').forEach(el => el.style.display = 'none');
                const skipButton = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-ad-skip-button-slot');
                if (skipButton) skipButton.click();
                const video = document.querySelector('video');
                if (video && (document.querySelector('.ad-showing') || document.querySelector('.ad-interrupting'))) {
                    if (isFinite(video.duration)) video.currentTime = video.duration;
                    video.playbackRate = 16.0; video.muted = true;
                }
            }, 250);

            // Gestione Intelligente della Velocità di Riproduzione
            function handlePlaybackSpeed() {
                const video = document.querySelector('video');
                if (!video) return;

                const isMusic = window.location.host.includes('music.youtube.com') || 
                                document.title.toLowerCase().includes('official music video') ||
                                document.title.toLowerCase().includes(' - topic') ||
                                !!document.querySelector('.ytp-ce-channel-title')?.innerText.toLowerCase().includes('topic');

                if (isMusic) {
                    if (video.playbackRate !== 1.0) {
                        video.playbackRate = 1.0;
                    }
                } else {
                    // Recupera l'ultima velocità salvata (default 1x)
                    const savedSpeed = parseFloat(localStorage.getItem('yt-custom-speed') || '1.0');
                    if (!video.dataset.speedInitialized) {
                        video.playbackRate = savedSpeed;
                        video.dataset.speedInitialized = 'true';
                    }

                    // Ascolta i cambiamenti manuali dell'utente
                    if (!video.dataset.speedListenerAdded) {
                        video.addEventListener('ratechange', () => {
                            if (!isMusic) {
                                localStorage.setItem('yt-custom-speed', video.playbackRate.toString());
                            }
                        });
                        video.dataset.speedListenerAdded = 'true';
                    }
                }
            }
            setInterval(handlePlaybackSpeed, 2000);
        })();
        """.trimIndent(),
        null,
    )
}
