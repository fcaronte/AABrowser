package com.fcaronte.aabrowser.utils

import com.fcaronte.aabrowser.utils.AppLog
import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.core.net.toUri

object WebViewScriptRouter {

    /**
     * Determines whether desktop mode is required for specific sites (like WhatsApp, Messenger, etc.).
     */
    fun isDesktopRequired(url: String?): Boolean {
        val lowUrl = url?.lowercase() ?: ""
        return lowUrl.contains("whatsapp.com") ||
                lowUrl.contains("whatsapp.net") ||
                lowUrl.contains("messenger.com") ||
                lowUrl.contains("web.telegram.org") ||
                lowUrl.contains("web.skype.com") ||
                lowUrl.contains("google.com") ||
                lowUrl.contains("accounts.google.com") ||
                lowUrl.contains("login") ||
                lowUrl.contains("signin") ||
                lowUrl.contains("auth") ||
                lowUrl.contains("oauth")
    }

    fun setDesktopUserAgent(webView: WebView, context: Context): String {
        val defaultUserAgent = WebSettings.getDefaultUserAgent(context)
        val version = Regex("Chrome/([0-9.]+)").find(defaultUserAgent)?.groupValues?.get(1)
        if (version == null) {
            AppLog.e("WebViewIdentity", "Unable to determine installed WebView Chrome version; keeping its default identity")
            setMobileUserAgent(webView, context)
            return defaultUserAgent
        }

        val userAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Safari/537.36"
        webView.settings.userAgentString = userAgent
        setUserAgentMetadata(
            webView,
            version = version,
            platform = "Windows",
            platformVersion = "10.0.0",
            architecture = "x86",
            model = "",
            mobile = false,
            bitness = 64
        )
        AppLog.d(
            "WebViewIdentity",
            "Desktop UA aligned to installed WebView Chrome/$version; UA metadata supported=${WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)}"
        )
        return userAgent
    }

    fun setMobileUserAgent(webView: WebView, context: Context) {
        webView.settings.userAgentString = null
        val defaultUserAgent = WebSettings.getDefaultUserAgent(context)
        val version = Regex("Chrome/([0-9.]+)").find(defaultUserAgent)?.groupValues?.get(1)
        if (version == null) {
            AppLog.e("WebViewIdentity", "Unable to determine installed WebView Chrome version for mobile metadata")
            return
        }
        setUserAgentMetadata(
            webView,
            version = version,
            platform = "Android",
            platformVersion = Build.VERSION.RELEASE,
            architecture = "",
            model = Build.MODEL,
            mobile = true,
            bitness = UserAgentMetadata.BITNESS_DEFAULT
        )
    }

    private fun setUserAgentMetadata(
        webView: WebView,
        version: String,
        platform: String,
        platformVersion: String,
        architecture: String,
        model: String,
        mobile: Boolean,
        bitness: Int
    ) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return

        val majorVersion = version.substringBefore('.')
        val brands = listOf(
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Not(A:Brand")
                .setMajorVersion("99")
                .setFullVersion("99.0.0.0")
                .build(),
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Google Chrome")
                .setMajorVersion(majorVersion)
                .setFullVersion(version)
                .build(),
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Chromium")
                .setMajorVersion(majorVersion)
                .setFullVersion(version)
                .build()
        )
        val metadata = UserAgentMetadata.Builder()
            .setBrandVersionList(brands)
            .setFullVersion(version)
            .setPlatform(platform)
            .setPlatformVersion(platformVersion)
            .setArchitecture(architecture)
            .setModel(model)
            .setMobile(mobile)
            .setBitness(bitness)
            .setWow64(false)
            .build()
        WebSettingsCompat.setUserAgentMetadata(webView.settings, metadata)
    }

    /**
     * Generates script to clean up previous domain interval loops and state.
     */
    private fun getCleanupScript(): String {
        return """
        (function() {
            var host = (window.location.host || '').toLowerCase();
            var isYT = host.includes('youtube.com') || host.includes('youtu.be') || host.includes('youtubekids.com');
            var isSpot = host.includes('spotify.com');

            if (!isYT && window.aabIntervals && Array.isArray(window.aabIntervals)) {
                window.aabIntervals.forEach(function(i) { clearInterval(i); });
                window.aabIntervals = [];
                window.aabIsAdPlaying = false;
            }

            if (!isSpot && window.aabSpotifyIntervals && Array.isArray(window.aabSpotifyIntervals)) {
                window.aabSpotifyIntervals.forEach(function(i) { clearInterval(i); });
                window.aabSpotifyIntervals = [];
                window.aabSpotifyInitialized = false;
            }
        })();
        """.trimIndent()
    }

    /**
     * Central controller for routing and injecting scripts into the WebView.
     * Guarantees domain isolation and prevents scripts for Spotify, YouTube, YT Music, Chat apps, etc.
     * from interfering with each other or running on wrong pages.
     */
    fun routeAndInject(
        webView: WebView,
        urlString: String,
        isYouTubeAdBlockEnabled: Boolean,
        autoplayMedia: Boolean,
        displayScale: Float,
        desktopScale: Float,
        isDesktopMode: Boolean,
        isTabActive: Boolean = true
    ) {
        val lowUrl = urlString.lowercase()
        val isSpotify = lowUrl.contains("spotify.com")
        val isYouTube = lowUrl.contains("youtube.com") || lowUrl.contains("youtu.be") || lowUrl.contains("youtubekids.com")
        val isChatApp = lowUrl.contains("whatsapp.com") || lowUrl.contains("whatsapp.net") || lowUrl.contains("telegram.org")

        val host = urlString.toUri().host ?: "unknown"
        AppLog.d("ScriptRouter", "Routing scripts for host=$host (Spotify: $isSpotify, YouTube: $isYouTube, Chat: $isChatApp, ActiveTab: $isTabActive)")

        // 1. Script di pulizia per fermare eventuali intervalli JS rimasti da un dominio precedente
        webView.evaluateJavascript(getCleanupScript(), null)

        // 2. Script utility per navigazione su long press dei link
        webView.evaluateJavascript(BrowserJavascript.getLongPressLinkScript(), null)

        // 3. Isolamento rigoroso degli script per dominio
        when {
            isSpotify -> {
                // Spotify Mobile/Desktop: Inietta pulizia banner promo, auto-clic X e metadati
                webView.evaluateJavascript(SpotifyManager.getInjectionScript(), null)

                val needsDesktop = isDesktopMode || isDesktopRequired(urlString)
                val targetScale = if (needsDesktop) desktopScale else displayScale
                webView.evaluateJavascript(BrowserJavascript.getViewportScript(targetScale, needsDesktop), null)

                if (needsDesktop) {
                    val ua = webView.settings.userAgentString.orEmpty()
                    val chromeVersionRegex = Regex("Chrome/([0-9.]+)")
                    val chromeVersion = chromeVersionRegex.find(ua)?.groups?.get(1)?.value ?: "152.0.0.0"
                    webView.evaluateJavascript(BrowserJavascript.getDesktopSpoofScript(chromeVersion), null)
                }

                webView.evaluateJavascript(BrowserJavascript.getLifecycleAndMetadataScript(), null)
            }

            isYouTube -> {
                // YouTube / YouTube Music: Solo script AdBlock YouTube + Metadata Lifecycle YouTube/HTML5
                if (isYouTubeAdBlockEnabled) {
                    webView.evaluateJavascript(AdBlockJavascript.getYouTubeAdBlockScript(), null)
                }

                if (!autoplayMedia) {
                    webView.evaluateJavascript("(function() { document.querySelectorAll('video, audio').forEach(el => el.pause()); })();", null)
                }

                val needsDesktop = isDesktopMode || isDesktopRequired(urlString)
                val targetScale = if (needsDesktop) desktopScale else displayScale
                webView.evaluateJavascript(BrowserJavascript.getViewportScript(targetScale, needsDesktop), null)

                if (needsDesktop) {
                    val ua = webView.settings.userAgentString.orEmpty()
                    val chromeVersionRegex = Regex("Chrome/([0-9.]+)")
                    val chromeVersion = chromeVersionRegex.find(ua)?.groups?.get(1)?.value ?: "152.0.0.0"
                    webView.evaluateJavascript(BrowserJavascript.getDesktopSpoofScript(chromeVersion), null)
                }

                webView.evaluateJavascript(BrowserJavascript.getLifecycleAndMetadataScript(), null)
            }

            isChatApp -> {
                // WhatsApp / Telegram: Centratura QR + Viewport + Lifecycle base
                webView.evaluateJavascript(BrowserJavascript.getCenterQrScript(), null)

                val needsDesktop = isDesktopMode || isDesktopRequired(urlString)
                val targetScale = if (needsDesktop) desktopScale else displayScale
                webView.evaluateJavascript(BrowserJavascript.getViewportScript(targetScale, needsDesktop), null)

                if (needsDesktop) {
                    val ua = webView.settings.userAgentString.orEmpty()
                    val chromeVersionRegex = Regex("Chrome/([0-9.]+)")
                    val chromeVersion = chromeVersionRegex.find(ua)?.groups?.get(1)?.value ?: "152.0.0.0"
                    webView.evaluateJavascript(BrowserJavascript.getDesktopSpoofScript(chromeVersion), null)
                }

                webView.evaluateJavascript(BrowserJavascript.getLifecycleAndMetadataScript(), null)
            }

            else -> {
                // Tutti gli altri siti generici: solo utility base e media listener HTML5
                if (!autoplayMedia) {
                    webView.evaluateJavascript("(function() { document.querySelectorAll('video, audio').forEach(el => el.pause()); })();", null)
                }

                val needsDesktop = isDesktopMode || isDesktopRequired(urlString)
                val targetScale = if (needsDesktop) desktopScale else displayScale
                webView.evaluateJavascript(BrowserJavascript.getViewportScript(targetScale, needsDesktop), null)

                if (needsDesktop) {
                    val ua = webView.settings.userAgentString.orEmpty()
                    val chromeVersionRegex = Regex("Chrome/([0-9.]+)")
                    val chromeVersion = chromeVersionRegex.find(ua)?.groups?.get(1)?.value ?: "152.0.0.0"
                    webView.evaluateJavascript(BrowserJavascript.getDesktopSpoofScript(chromeVersion), null)
                }

                webView.evaluateJavascript(BrowserJavascript.getLifecycleAndMetadataScript(), null)
            }
        }

        CookieManager.getInstance().flush()
    }

    /**
     * Inietta lo script AdBlock solo se l'URL appartiene a YouTube/YouTube Music.
     */
    fun injectYouTubeAdBlockIfNeeded(
        webView: WebView?,
        urlString: String?,
        isYouTubeAdBlockEnabled: Boolean,
        isTabActive: Boolean = true
    ) {
        if (webView == null || urlString.isNullOrBlank() || !isYouTubeAdBlockEnabled) return

        val lowUrl = urlString.lowercase()
        val isYouTube = lowUrl.contains("youtube.com") || lowUrl.contains("youtu.be") || lowUrl.contains("youtubekids.com")

        if (isYouTube) {
            AppLog.d("ScriptRouter", "Injecting YouTube AdBlock script for host=${urlString.toUri().host ?: "unknown"}")
            webView.evaluateJavascript(AdBlockJavascript.getYouTubeAdBlockScript(), null)
        }
    }
}
