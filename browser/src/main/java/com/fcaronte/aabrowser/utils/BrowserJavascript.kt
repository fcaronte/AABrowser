package com.fcaronte.aabrowser.utils

/**
 * Central repository for all JavaScript snippets injected into the WebView.
 */
object BrowserJavascript {

    fun getViewportScript(scale: Float, isDesktop: Boolean): String {
        return if (isDesktop) {
            """
            (function() {
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) { meta = document.createElement('meta'); meta.name = "viewport"; document.head.appendChild(meta); }
                meta.content = "width=device-width, initial-scale=$scale, user-scalable=yes";
                document.body.style.width = '100%';
                document.body.style.boxSizing = 'border-box';
            })();
            """.trimIndent()
        } else {
            """
            (function() {
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) { meta = document.createElement('meta'); meta.name = "viewport"; document.head.appendChild(meta); }
                meta.content = "initial-scale=$scale, user-scalable=yes";
            })();
            """.trimIndent()
        }
    }

    fun getLifecycleAndMetadataScript(): String {
        return """
        (function() {
            $VISIBILITY_MOCK_JS
            $DRM_L3_ENFORCER_JS

            const host = window.location.hostname.toLowerCase();
            const needsAntiPause = host.includes('youtube.com') || host.includes('youtubekids.com') || host.includes('spotify.com') || host.includes('twitch.tv') || host.includes('zappr.stream') || host.includes('zapps.stream');

            if (needsAntiPause) {
                mockVisibility();
                $ANTI_PAUSE_JS
            }

            $METADATA_SYNC_CORE_JS
            
            // Mutually exclusive site-specific metadata extraction
            if (host.includes('youtube.com') || host.includes('youtubekids.com')) {
                ${YouTubeManager.getMetadataScript()}
            } else if (host.includes('spotify.com')) {
                $SPOTIFY_METADATA_JS
            } else if (host.includes('zappr.stream') || host.includes('zapps.stream')) {
                ${ZapprManager.getMetadataScript()}
            }

            $METADATA_SYNC_FINISH_JS

            $MEDIA_LISTENERS_JS
            $INPUT_LISTENERS_JS
            $SELECT_POLYFILL_JS

            if (host.includes('spotify.com') || host.includes('zappr.stream') || host.includes('zapps.stream')) {
                setInterval(() => {
                    const h = window.location.hostname.toLowerCase();
                    if (h.includes('spotify.com') || h.includes('zappr.stream') || h.includes('zapps.stream')) {
                        syncMetadata();
                    }
                }, 1000);
            }
        })();
        """.trimIndent()
    }

    fun getInjectTextScript(text: String): String {
        val sanitized = text.replace("'", "\\'")
        return """
        (function() {
            var el = document.activeElement;
            if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.contentEditable === 'true')) {
                var now = Date.now();
                if (el.lastInjectTime && (now - el.lastInjectTime < 100) && el.lastInjectText === "$sanitized") {
                    return;
                }
                el.lastInjectTime = now;
                el.lastInjectText = "$sanitized";

                var start = el.selectionStart || 0;
                var end = el.selectionEnd || 0;
                var val = el.value || el.innerText || "";
                if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
                    el.value = val.substring(0, start) + "$sanitized" + val.substring(end);
                    el.selectionStart = el.selectionEnd = start + "$sanitized".length;
                    el.focus();
                } else {
                    el.innerText = val.substring(0, start) + "$sanitized" + val.substring(end);
                    el.focus();
                }
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
            }
        })();
        """.trimIndent()
    }

    fun getDesktopSpoofScript(chromeVersion: String): String {
        val majorVersion = chromeVersion.split(".").firstOrNull() ?: "152"
        val uaString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
        return """
        (function() {
            try {
                const defineProp = (obj, prop, val) => {
                    try {
                        Object.defineProperty(obj, prop, {
                            get: function() { return val; },
                            configurable: true
                        });
                    } catch(e) {}
                };

                defineProp(navigator, 'platform', 'Win32');
                defineProp(navigator, 'vendor', 'Google Inc.');
                defineProp(navigator, 'appVersion', '5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36');
                defineProp(navigator, 'userAgent', '$uaString');
                defineProp(navigator, 'oscpu', 'Windows NT 10.0; Win64; x64');
                
                if (!window.chrome) {
                    window.chrome = {
                        app: { isInstalled: false },
                        runtime: { connect: function() {}, sendMessage: function() {} },
                        loadTimes: function() { return {}; },
                        csi: function() { return {}; }
                    };
                }

                if (navigator.userAgentData) {
                    Object.defineProperty(navigator, 'userAgentData', {
                        get: function() {
                            return {
                                brands: [
                                    { brand: 'Not(A:Brand', version: '$majorVersion' },
                                    { brand: 'Google Chrome', version: '$majorVersion' },
                                    { brand: 'Chromium', version: '$majorVersion' }
                                ],
                                mobile: false,
                                platform: 'Windows',
                                getHighEntropyValues: function(hints) {
                                    return Promise.resolve({
                                        architecture: 'x86',
                                        bitness: '64',
                                        brands: [
                                            { brand: 'Not(A:Brand', version: '$majorVersion' },
                                            { brand: 'Google Chrome', version: '$majorVersion' },
                                            { brand: 'Chromium', version: '$majorVersion' }
                                        ],
                                        mobile: false,
                                        model: '',
                                        platform: 'Windows',
                                        platformVersion: '10.0.0',
                                        uaFullVersion: '$chromeVersion',
                                        windowsVersion: '10'
                                    });
                                }
                            };
                        },
                        configurable: true
                    });
                }
            } catch(e) {}
        })();
        """.trimIndent()
    }

    const val PLAY_SCRIPT = """
        (function() {
            window.isMediaPlaying = true;
            if (typeof window.aabMarkUserPlay === 'function') window.aabMarkUserPlay();
            
            if (window.aabMediaSessionHandlers && typeof window.aabMediaSessionHandlers['play'] === 'function') {
                try { window.aabMediaSessionHandlers['play'](); return; } catch(e) {}
            }

            function smartClick(el) {
                if (!el) return false;
                try { 
                    el.focus();
                    el.click(); 
                    return true; 
                } catch(e) {
                    try {
                        el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
                        el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
                        el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                        return true;
                    } catch(err) { return false; }
                }
            }

            function tryPlayAll(win) {
                try {
                    win.document.querySelectorAll('video, audio').forEach(v => {
                        if (v.paused) {
                            if (v.readyState === 0 && v.load) v.load();
                            v.play().catch(err => {
                                if (v.load) { try { v.load(); v.play(); } catch(e) {} }
                            });
                        }
                    });
                    for (let i = 0; i < win.frames.length; i++) {
                        tryPlayAll(win.frames[i]);
                    }
                } catch(e) {}
            }

            tryPlayAll(window);

            const playSelectors = [
                '[data-testid="control-button-play"]',
                '[data-testid="control-button-playpause"]',
                '[data-testid="play-button"]',
                '.ytp-play-button',
                'ytmusic-player-bar .play-pause-button',
                '#play-pause-button',
                '.vjs-play-control',
                '.vjs-big-play-button',
                '.play-btn',
                '.play-button',
                '.play-icon',
                '.jw-display-icon-container',
                '.vjs-paused .vjs-play-control',
                'button[aria-label*="Play" i]',
                'button[aria-label*="Riproduci" i]',
                'button[aria-label*="Suona" i]',
                'button[aria-label*="Ascolta" i]',
                'button[title*="Play" i]',
                'button[title*="Riproduci" i]',
                '[aria-label="Play"]',
                '[aria-label="Riproduci"]'
            ];

            for (const sel of playSelectors) {
                const btn = document.querySelector(sel);
                if (btn) {
                    const aria = (btn.getAttribute('aria-label') || btn.getAttribute('title') || '').toLowerCase();
                    if (aria.includes('pause') || aria.includes('pausa')) return;
                    if (smartClick(btn)) break;
                }
            }
        })();
    """

    const val PAUSE_SCRIPT = """
        (function() {
            window.isMediaPlaying = false;
            if (typeof window.aabMarkUserPause === 'function') window.aabMarkUserPause();

            if (window.aabMediaSessionHandlers && typeof window.aabMediaSessionHandlers['pause'] === 'function') {
                try { window.aabMediaSessionHandlers['pause'](); return; } catch(e) {}
            }

            function smartClick(el) {
                if (!el) return false;
                try { el.click(); return true; } catch(e) {
                    try {
                        el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                        return true;
                    } catch(err) { return false; }
                }
            }

            function tryPauseAll(win) {
                try {
                    win.document.querySelectorAll('video, audio').forEach(v => {
                        try { v.pause(); } catch(e) {}
                    });
                    for (let i = 0; i < win.frames.length; i++) {
                        tryPauseAll(win.frames[i]);
                    }
                } catch(e) {}
            }

            tryPauseAll(window);

            const pauseSelectors = [
                '[data-testid="control-button-pause"]',
                '[data-testid="control-button-playpause"]',
                '.ytp-play-button',
                'ytmusic-player-bar .play-pause-button',
                '#play-pause-button',
                '.vjs-play-control',
                '.pause-btn',
                '.pause-button',
                '.pause-icon',
                'button[aria-label*="Pause" i]',
                'button[aria-label*="Pausa" i]',
                'button[aria-label*="In pausa" i]',
                'button[title*="Pause" i]',
                'button[title*="Pausa" i]',
                '[aria-label="Pause"]',
                '[aria-label="Pausa"]'
            ];

            for (const sel of pauseSelectors) {
                const btn = document.querySelector(sel);
                if (btn) {
                    const aria = (btn.getAttribute('aria-label') || btn.getAttribute('title') || '').toLowerCase();
                    if (aria.includes('play') || aria.includes('riproduci')) return;
                    if (smartClick(btn)) break;
                }
            }
        })();
    """

    const val STOP_SCRIPT = """
        (function() {
            window.isMediaPlaying = false;
            if (typeof window.aabMarkUserPause === 'function') window.aabMarkUserPause();

            function smartClick(el) {
                if (!el) return false;
                try { el.click(); return true; } catch(e) {
                    try {
                        el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                        return true;
                    } catch(err) { return false; }
                }
            }

            document.querySelectorAll('video, audio').forEach(v => {
                try { v.pause(); } catch(e) {}
            });

            const pauseSelectors = [
                '[data-testid="control-button-pause"]',
                '[data-testid="control-button-playpause"]',
                '.ytp-play-button',
                'ytmusic-player-bar .play-pause-button',
                'button[aria-label*="Pause" i]',
                'button[aria-label*="Pausa" i]'
            ];

            for (const sel of pauseSelectors) {
                const btn = document.querySelector(sel);
                if (btn) {
                    if (smartClick(btn)) break;
                }
            }
        })();
    """

    const val NEXT_SCRIPT = """
        (function() {
            if (window.aabMediaSessionHandlers && typeof window.aabMediaSessionHandlers['nexttrack'] === 'function') {
                try {
                    window.aabMediaSessionHandlers['nexttrack']();
                    return;
                } catch(e) {}
            }

            function smartClick(el) {
                if (!el) return false;
                try { el.click(); return true; } catch(e) {
                    try {
                        el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                        return true;
                    } catch(err) { return false; }
                }
            }

            const nextSelectors = [
                '[data-testid="control-button-skip-forward"]',
                '[data-testid="control-button-skip-right"]',
                '[data-testid="skip-next-button"]',
                '[data-testid*="skip-forward"]',
                '[data-testid*="skip-next"]',
                '.ytp-next-button',
                'ytmusic-player-bar .next-button',
                '#next-button',
                'button[aria-label*="next" i]',
                'button[aria-label*="successiv" i]',
                'button[aria-label*="avanti" i]',
                'button[aria-label*="prossim" i]',
                'button[aria-label*="skip" i]',
                'button[aria-label*="brano successivo" i]',
                'button[title*="Next" i]',
                'button[title*="Successivo" i]'
            ];

            let clicked = false;
            for (const sel of nextSelectors) {
                const btn = document.querySelector(sel);
                if (btn) {
                    clicked = smartClick(btn);
                    if (clicked) break;
                }
            }

            if (!clicked) {
                try {
                    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'MediaTrackNext', code: 'MediaTrackNext', bubbles: true }));
                } catch(e) {}
                const media = document.querySelector('video, audio');
                if (media) media.currentTime += 10;
            }
        })();
    """

    const val PREVIOUS_SCRIPT = """
        (function() {
            if (window.aabMediaSessionHandlers && typeof window.aabMediaSessionHandlers['previoustrack'] === 'function') {
                try {
                    window.aabMediaSessionHandlers['previoustrack']();
                    return;
                } catch(e) {}
            }

            function smartClick(el) {
                if (!el) return false;
                try { el.click(); return true; } catch(e) {
                    try {
                        el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                        return true;
                    } catch(err) { return false; }
                }
            }

            const prevSelectors = [
                '[data-testid="control-button-skip-back"]',
                '[data-testid="control-button-skip-left"]',
                '[data-testid="skip-previous-button"]',
                '[data-testid*="skip-back"]',
                '[data-testid*="skip-prev"]',
                '.ytp-prev-button',
                'ytmusic-player-bar .previous-button',
                '#previous-button',
                'button[aria-label*="prev" i]',
                'button[aria-label*="precedent" i]',
                'button[aria-label*="indietro" i]',
                'button[aria-label*="brano precedente" i]',
                'button[title*="Previous" i]',
                'button[title*="Precedente" i]'
            ];

            let clicked = false;
            for (const sel of prevSelectors) {
                const btn = document.querySelector(sel);
                if (btn) {
                    clicked = smartClick(btn);
                    if (clicked) break;
                }
            }

            if (!clicked) {
                try {
                    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'MediaTrackPrevious', code: 'MediaTrackPrevious', bubbles: true }));
                } catch(e) {}
                const media = document.querySelector('video, audio');
                if (media) media.currentTime -= 10;
            }
        })();
    """

    fun getSeekScript(pos: Long): String {
        return """
        (function() {
            const media = document.querySelector('video, audio');
            if (media) media.currentTime = ${pos / 1000.0};
        })();
        """.trimIndent()
    }

    fun getLongPressLinkScript(): String {
        return """
        (function() {
            if (window.aabLongPressInitialized) return;
            window.aabLongPressInitialized = true;
            
            let pressTimer = null;
            let targetUrl = null;
            
            function getLinkUrl(element) {
                let el = element;
                while (el && el !== document.body) {
                    if (el.tagName === 'A' && el.href) return el.href;
                    if (el.getAttribute && el.getAttribute('data-href')) return el.getAttribute('data-href');
                    if (el.getAttribute && el.getAttribute('data-url')) return el.getAttribute('data-url');
                    el = el.parentElement;
                }
                return null;
            }
            
            document.addEventListener('touchstart', function(e) {
                let url = getLinkUrl(e.target);
                if (url) {
                    targetUrl = url;
                    if (pressTimer) clearTimeout(pressTimer);
                    pressTimer = setTimeout(function() {
                        if (targetUrl && window.AndroidBridge && window.AndroidBridge.openInNewTab) {
                            AndroidBridge.openInNewTab(targetUrl);
                            targetUrl = null;
                        }
                    }, 600);
                } else {
                    targetUrl = null;
                }
            }, {passive: true});
            
            document.addEventListener('touchmove', function(e) {
                if (pressTimer) {
                    clearTimeout(pressTimer);
                    pressTimer = null;
                    targetUrl = null;
                }
            }, {passive: true});
            
            document.addEventListener('touchend', function(e) {
                if (pressTimer) {
                    clearTimeout(pressTimer);
                    pressTimer = null;
                    targetUrl = null;
                }
            }, {passive: true});
            
            document.addEventListener('contextmenu', function(e) {
                let url = getLinkUrl(e.target);
                if (url) {
                    e.preventDefault();
                    if (window.AndroidBridge && window.AndroidBridge.openInNewTab) {
                        AndroidBridge.openInNewTab(url);
                    }
                }
            });
        })();
        """.trimIndent()
    }

    fun getCenterQrScript(): String {
        return """
        (function() {
            if (window.aabCentered) return;
            window.aabCentered = true;
            setTimeout(() => {
                const maxScrollX = document.documentElement.scrollWidth - window.innerWidth;
                if (maxScrollX > 0) {
                    window.scrollTo(maxScrollX / 2, 0);
                }
            }, 1200);
        })();
        """.trimIndent()
    }

    // --- INTERNAL JAVASCRIPT BLOCKS ---

    /**
     * DRM L3 Enforcer script.
     * Patches navigator.requestMediaKeySystemAccess to enforce SW_SECURE_DECODE (DRM L3)
     * for Widevine compatibility on streaming platforms.
     * Credits/Inspired by: https://github.com/kododake/AABrowser/
     */
    private const val DRM_L3_ENFORCER_JS = """
        (function() {
            try {
                const host = window.location.hostname;
                if (host && (
                    host === 'youtube.com' || host.endsWith('.youtube.com') ||
                    host === 'googlevideo.com' || host.endsWith('.googlevideo.com') ||
                    host === 'youtube-nocookie.com' || host.endsWith('.youtube-nocookie.com')
                )) {
                    return;
                }
            } catch (_) {}

            if (typeof navigator === 'undefined' || !navigator.requestMediaKeySystemAccess) {
                return;
            }

            const patchKey = typeof Symbol !== 'undefined' && Symbol.for ? Symbol.for('__aab_drm_l3_enforced__') : '__aab_drm_l3_enforced__';
            if (navigator[patchKey]) return;

            const originalRequest = navigator.requestMediaKeySystemAccess;
            const patchedRequest = function requestMediaKeySystemAccess(keySystem, configs) {
                if (keySystem === 'com.widevine.alpha' && configs) {
                    try {
                        const newConfigs = Array.from(configs).map(config => {
                            const newConfig = Object.assign({}, config);
                            if (config.videoCapabilities) {
                                newConfig.videoCapabilities = Array.from(config.videoCapabilities).map(cap => {
                                    const newCap = Object.assign({}, cap);
                                    if (newCap.robustness && typeof newCap.robustness === 'string' && newCap.robustness.startsWith('HW_SECURE')) {
                                        newCap.robustness = 'SW_SECURE_DECODE';
                                    }
                                    return newCap;
                                });
                            }
                            if (config.audioCapabilities) {
                                newConfig.audioCapabilities = Array.from(config.audioCapabilities).map(cap => {
                                    const newCap = Object.assign({}, cap);
                                    if (newCap.robustness && typeof newCap.robustness === 'string' && newCap.robustness.startsWith('HW_SECURE')) {
                                        newCap.robustness = 'SW_SECURE_DECODE';
                                    }
                                    return newCap;
                                });
                            }
                            return newConfig;
                        });
                        return originalRequest.call(navigator, keySystem, newConfigs).catch(function() {
                            return originalRequest.call(navigator, keySystem, configs);
                        });
                    } catch (_) {
                        return originalRequest.call(navigator, keySystem, configs);
                    }
                }
                return originalRequest.call(navigator, keySystem, configs);
            };

            try {
                Object.defineProperty(patchedRequest, 'name', { value: 'requestMediaKeySystemAccess' });
                patchedRequest.toString = function() {
                    return 'function requestMediaKeySystemAccess() { [native code] }';
                };
            } catch (_) {}

            try {
                Object.defineProperty(navigator, patchKey, { value: true, enumerable: false, configurable: false });
                Object.defineProperty(navigator, 'requestMediaKeySystemAccess', {
                    value: patchedRequest,
                    writable: true,
                    configurable: true
                });
            } catch (_) {
                navigator.requestMediaKeySystemAccess = patchedRequest;
            }
        })();
    """

    private const val VISIBILITY_MOCK_JS = """
        const mockVisibility = () => {
            try {
                if (document.aabMocked) return;
                const defineProp = (obj, prop, val) => {
                    Object.defineProperty(obj, prop, {
                        get: () => val,
                        set: () => {},
                        configurable: true
                    });
                };
                defineProp(document, 'hidden', false);
                defineProp(document, 'visibilityState', 'visible');
                defineProp(document, 'webkitVisibilityState', 'visible');
                defineProp(document, 'webkitHidden', false);
                document.hasFocus = () => true;
                window.aabUserTappedPause = false;
                window.aabMarkUserPause = () => { window.aabUserTappedPause = true; window.isMediaPlaying = false; };
                window.aabMarkUserPlay = () => { window.aabUserTappedPause = false; window.isMediaPlaying = true; };
                
                // Track user interaction to allow legitimate pause() calls
                window.aabLastInteraction = 0;
                ['mousedown', 'touchstart', 'keydown'].forEach(evt => {
                    document.addEventListener(evt, () => { window.aabLastInteraction = Date.now(); }, true);
                });

                document.aabMocked = true;
            } catch (e) {}
        };
    """

    private const val ANTI_PAUSE_JS = """
        const blockEvent = (e) => { 
            if (e.type === 'blur' || e.type === 'mouseleave' || e.type.includes('visibility') || e.type === 'pagehide') {
                e.stopImmediatePropagation(); 
            }
        };
        ['visibilitychange', 'webkitvisibilitychange', 'blur', 'mouseleave', 'pagehide'].forEach(evt => {
            document.addEventListener(evt, blockEvent, true);
            window.addEventListener(evt, blockEvent, true);
        });
        const origVideoPause = HTMLVideoElement.prototype.pause;
        const handleMediaPause = function() {
            const timeSinceInteraction = Date.now() - (window.aabLastInteraction || 0);
            const isUserAction = timeSinceInteraction < 1000;
            
            if (window.isMediaPlaying === true && !window.aabIsAdPlaying && !window.aabUserTappedPause && !isUserAction) {
                console.log("AABrowser: Blocked automatic background pause()");
                return Promise.resolve();
            }
            return origVideoPause.apply(this, arguments);
        };
        HTMLVideoElement.prototype.pause = handleMediaPause;
        if (window.HTMLAudioElement) {
            HTMLAudioElement.prototype.pause = handleMediaPause;
        }
    """

    private const val METADATA_SYNC_CORE_JS = """
        function syncPageMetadata() {
            const getFavicon = () => {
                const icon = document.querySelector('link[rel="apple-touch-icon"]') || 
                             document.querySelector('link[rel="icon"][sizes="192x192"]') ||
                             document.querySelector('link[rel="icon"]') ||
                             document.querySelector('link[rel="shortcut icon"]');
                return icon ? icon.href : "";
            };
            if (window.AndroidBridge) {
                AndroidBridge.onMetadataUpdated(document.title, getFavicon(), window.location.href);
            }
        }
        
        // Force redraw on visibility change to fix frozen video image
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'visible') {
                window.dispatchEvent(new Event('resize'));
                setTimeout(() => { document.body.style.display = 'none'; document.body.offsetHeight; document.body.style.display = ''; }, 50);
            }
        });

        let lastHref = window.location.href;
        let lastTitle = document.title;
        const observer = new MutationObserver(() => {
            if (window.location.href !== lastHref) {
                lastHref = window.location.href;
                syncPageMetadata();
                if (window.location.host.includes('youtube.com') && window.AndroidBridge) {
                    AndroidBridge.onStartAdBlock();
                }
            } else if (document.title !== lastTitle) {
                lastTitle = document.title;
                syncPageMetadata();
            }
        });
        observer.observe(document.querySelector('title') || document.documentElement, { subtree: true, characterData: true, childList: true });
        window.addEventListener('yt-navigate-finish', syncPageMetadata);
        window.addEventListener('ytmusic-navigate-finish', syncPageMetadata);
        setTimeout(syncPageMetadata, 1500);

        let lastMediaTitle = "";
        let lastDuration = 0;
        function syncMetadata() {
            if (!window.AndroidBridge) return;
            const media = document.querySelector('video, audio');
            const isSpotify = window.location.host.includes('spotify.com');
            if (!media && !isSpotify) {
                if (lastMediaTitle !== "") {
                    lastMediaTitle = "";
                    lastDuration = 0;
                    AndroidBridge.updateMediaMetadata("", "", "", 0);
                    AndroidBridge.onMediaStatusChanged(false, 0, 1.0);
                }
                return;
            }
            let title = document.title;
            let artist = "AABrowser Audio";
            let artUrl = "";
            let duration = media && isFinite(media.duration) ? media.duration : 0;
    """

    private const val SPOTIFY_METADATA_JS = """
        const nowPlayingBar = document.querySelector('[data-testid="now-playing-bar"]');
        if (nowPlayingBar) {
            const trackEl = nowPlayingBar.querySelector('[data-testid="track-info-name"] a, [data-testid="context-item-info-title"] a, [data-testid="entity-title"]');
            const artistEl = nowPlayingBar.querySelector('[data-testid="track-info-artists"] a, [data-testid="context-item-info-subtitle"] a, [data-testid="entity-subtitle"]');
            const artEl = nowPlayingBar.querySelector('img[data-testid="cover-art-image"], img[data-testid="entity-image"], img[src*="scdn.co"]');
            if (trackEl) title = (trackEl.textContent || trackEl.innerText).trim();
            if (artistEl) artist = (artistEl.textContent || artistEl.innerText).trim();
            if (artEl && artEl.src) artUrl = artEl.src;
        }
        if ((!title || title.includes("Top 50")) && navigator.mediaSession && navigator.mediaSession.metadata) {
            const meta = navigator.mediaSession.metadata;
            if (meta.title && !meta.title.includes("Spotify")) title = meta.title;
            if (meta.artist) artist = meta.artist;
            if (meta.artwork && meta.artwork.length > 0) artUrl = meta.artwork[meta.artwork.length - 1].src;
        }
        if (artUrl && artUrl.includes("00004851")) artUrl = artUrl.replace("00004851", "0000b273");
    """

    private const val METADATA_SYNC_FINISH_JS = """
            const lowerTitle = (title || "").toLowerCase();
            const isGeneric = lowerTitle.includes("lettore web") || lowerTitle.includes("musica per tutti") || lowerTitle === "spotify" || lowerTitle === "home" || lowerTitle === "search" || lowerTitle === "cerca" || lowerTitle === "";

            if (!isGeneric && title && (title !== lastMediaTitle || Math.abs(duration - lastDuration) > 1)) {
                lastMediaTitle = title;
                lastDuration = duration;
                AndroidBridge.updateMediaMetadata(title, artist, artUrl, duration);
            }
        }
    """

    private const val MEDIA_LISTENERS_JS = """
        function setupMediaListeners(media) {
            if (media.dataset.mediaListenersAdded) return;
            media.dataset.mediaListenersAdded = 'true';
            media.lastBridgeUpdate = 0;
            media.addEventListener('play', () => {
                window.isMediaPlaying = true;
                if (window.AndroidBridge) AndroidBridge.onMediaStatusChanged(true, media.currentTime, media.playbackRate);
                syncMetadata();
            });
            media.addEventListener('pause', () => {
                if (window.AndroidBridge) AndroidBridge.onMediaStatusChanged(false, media.currentTime, media.playbackRate);
            });
            media.addEventListener('timeupdate', () => {
                const now = Date.now();
                if (now - media.lastBridgeUpdate > 500) {
                    if (window.AndroidBridge) AndroidBridge.onMediaTimeUpdate(media.currentTime, media.playbackRate, !media.paused);
                    media.lastBridgeUpdate = now;
                }
            });
            media.addEventListener('durationchange', syncMetadata);
        }
        const mediaObserver = new MutationObserver(() => {
            document.querySelectorAll('video, audio').forEach(media => setupMediaListeners(media));
        });
        mediaObserver.observe(document, { childList: true, subtree: true });
        document.querySelectorAll('video, audio').forEach(media => setupMediaListeners(media));
    """

    private const val INPUT_LISTENERS_JS = """
        function notifyInputInteraction(event) {
            const target = event.target;
            if (target instanceof Element && target.closest('input, textarea, [contenteditable="true"]')) {
                if (window.AndroidBridge) AndroidBridge.onStartInput();
            }
        }
        document.addEventListener('focusin', notifyInputInteraction, true);
        document.addEventListener('click', notifyInputInteraction, true);
    """

    private const val SELECT_POLYFILL_JS = """
        document.addEventListener('click', function(e) {
            var select = e.target.closest('select');
            if (!select) return;
            e.preventDefault();
            e.stopPropagation();
            var existing = document.getElementById('aab-custom-dropdown');
            if (existing) existing.remove();
            var options = select.options;
            if (!options || options.length === 0) return;
            var dropdown = document.createElement('div');
            dropdown.id = 'aab-custom-dropdown';
            dropdown.style = 'position:fixed;z-index:2147483647;left:5%;top:5%;width:90%;max-height:90%;overflow-y:auto;background:#1a1a1a;color:#fff;border:2px solid #555;border-radius:12px;box-shadow:0 10px 30px rgba(0,0,0,0.9);padding:10px;font-family:sans-serif;';
            var header = document.createElement('div');
            header.style = 'padding:12px 15px;font-weight:bold;font-size:18px;border-bottom:1px solid #333;display:flex;justify-content:space-between;align-items:center;color:#aaa;';
            header.innerText = 'Seleziona:';
            var closeBtn = document.createElement('button');
            closeBtn.innerText = '✕';
            closeBtn.style = 'background:transparent;color:#ff5252;border:none;font-size:22px;cursor:pointer;';
            closeBtn.onclick = () => dropdown.remove();
            header.appendChild(closeBtn);
            dropdown.appendChild(header);
            var container = document.createElement('div');
            container.style = 'display:flex;flex-direction:column;';
            for (var i = 0; i < options.length; i++) {
                (function(index) {
                    var opt = options[index];
                    if (opt.disabled) return;
                    var item = document.createElement('div');
                    item.style = 'padding:18px 15px;border-bottom:1px solid #2a2a2a;cursor:pointer;font-size:18px;border-radius:6px;';
                    if (opt.selected || select.selectedIndex === index) {
                        item.style.backgroundColor = '#2196F3';
                    }
                    item.onclick = () => {
                        select.selectedIndex = index;
                        select.dispatchEvent(new Event('input', { bubbles: true }));
                        select.dispatchEvent(new Event('change', { bubbles: true }));
                        dropdown.remove();
                    };
                    item.innerText = opt.text;
                    container.appendChild(item);
                })(i);
            }
            dropdown.appendChild(container);
            document.body.appendChild(dropdown);
        }, true);
    """
}
