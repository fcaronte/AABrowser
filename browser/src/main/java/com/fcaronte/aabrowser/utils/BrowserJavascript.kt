package com.fcaronte.aabrowser.utils

object BrowserJavascript {

    fun getViewportScript(scale: Float, isDesktop: Boolean): String {
        return if (isDesktop) {
            """
            (function() {
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) { meta = document.createElement('meta'); meta.name = "viewport"; document.head.appendChild(meta); }
                meta.content = "width=1280, initial-scale=$scale, user-scalable=yes";
                document.body.style.minWidth = '1280px';
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
            // Shield anti-pausa e visibilità sempre attiva
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
                    document.aabMocked = true;
                } catch (e) {}
            };
            mockVisibility();
            
            // Blocca gli eventi di cambio visibilità e focus che causano pause
            const blockEvent = (e) => { 
                if (e.type === 'blur' || e.type === 'mouseleave' || e.type.includes('visibility') || e.type === 'pagehide') {
                    e.stopImmediatePropagation(); 
                }
            };
            ['visibilitychange', 'webkitvisibilitychange', 'blur', 'mouseleave', 'pagehide'].forEach(evt => {
                document.addEventListener(evt, blockEvent, true);
                window.addEventListener(evt, blockEvent, true);
            });

            // Anti-Pausa automatica: distingue la pausa intenzionale dell'utente dalla pausa forzata da transizioni UI / espansioni player
            window.aabUserTappedPause = false;
            window.aabMarkUserPause = function() {
                window.aabUserTappedPause = true;
                setTimeout(function() { window.aabUserTappedPause = false; }, 2000);
            };

            document.addEventListener('click', function(e) {
                const target = e.target;
                if (!target) return;
                const btn = target.closest('button, [role="button"], [data-testid*="pause"], [aria-label*="paus" i]');
                if (btn) {
                    const label = (btn.getAttribute('aria-label') || btn.getAttribute('data-testid') || '').toLowerCase();
                    if (label.includes('pause') || label.includes('pausa')) {
                        if (typeof window.aabMarkUserPause === 'function') window.aabMarkUserPause();
                    }
                }
            }, true);

            const origVideoPause = HTMLVideoElement.prototype.pause;
            const handleMediaPause = function() {
                // Se la riproduzione è attiva e l'utente NON ha premuto esplicitamente il tasto Pausa, blocchiamo la pausa automatica scatenata da espansioni o transizioni DOM
                if (window.isMediaPlaying === true && !window.aabIsAdPlaying && !window.aabUserTappedPause) {
                    console.log("AABrowser: Blocked automatic UI/background pause()");
                    return Promise.resolve();
                }
                return origVideoPause.apply(this, arguments);
            };

            HTMLVideoElement.prototype.pause = handleMediaPause;
            if (window.HTMLAudioElement) {
                HTMLAudioElement.prototype.pause = handleMediaPause;
            }

            function syncPageMetadata() {
                const getFavicon = () => {
                    const icon = document.querySelector('link[rel="apple-touch-icon"]') || 
                                 document.querySelector('link[rel="icon"][sizes="192x192"]') ||
                                 document.querySelector('link[rel="icon"]') ||
                                 document.querySelector('link[rel="shortcut icon"]');
                    return icon ? icon.href : "https://www.google.com/s2/favicons?domain=" + window.location.hostname + "&sz=128";
                };
                if (window.AndroidBridge) {
                    AndroidBridge.onMetadataUpdated(document.title, getFavicon(), window.location.href);
                }
            }
            
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

            const handleNavFinish = () => {
                syncPageMetadata();
                if (window.AndroidBridge) AndroidBridge.onStartAdBlock();
            };
            window.addEventListener('yt-navigate-finish', handleNavFinish);
            window.addEventListener('ytmusic-navigate-finish', handleNavFinish);
            
            setTimeout(syncPageMetadata, 1500);

            // Monitoraggio Metadati Media
            let lastMediaTitle = "";
            let lastDuration = 0;
            function syncMetadata() {
                console.log("AABrowserPlayback JS: syncMetadata called. Host:", window.location.host);
                if (!window.AndroidBridge) return;

                const media = document.querySelector('video, audio');
                const isSpotify = window.location.host.includes('spotify.com');

                if (!media && !isSpotify) {
                    if (lastMediaTitle !== "") {
                        lastMediaTitle = "";
                        lastDuration = 0;
                        if (window.AndroidBridge) {
                            AndroidBridge.updateMediaMetadata("", "", "", 0);
                            AndroidBridge.onMediaStatusChanged(false, 0, 1.0);
                        }
                    }
                    return;
                }

                let title = document.title;
                let artist = "AABrowser Audio";
                let artUrl = "";
                let duration = media && isFinite(media.duration) ? media.duration : 0;

                if (window.location.host.includes('youtube.com')) {
                    const ytTitle = document.querySelector('.ytp-title-link')?.innerText || 
                                     document.querySelector('ytmusic-player-bar .title')?.textContent ||
                                     document.querySelector('.ytmusic-player-bar .title')?.textContent;
                    const ytArtist = document.querySelector('.ytp-ce-channel-title')?.innerText || 
                                     document.querySelector('#upload-info #channel-name')?.innerText ||
                                     document.querySelector('ytmusic-player-bar .byline')?.textContent;
                    
                    if (ytTitle) title = ytTitle.trim();
                    if (ytArtist) artist = ytArtist.trim();
                    
                    const urlParams = new URLSearchParams(window.location.search);
                    const v = urlParams.get('v');
                    if (v) {
                        // HQ Thumbnail for YouTube
                        artUrl = 'https://img.youtube.com/vi/' + v + '/hqdefault.jpg';
                    }
                    
                    if (window.location.host.includes('music.youtube.com')) {
                        let musicArt = document.querySelector('ytmusic-player-bar img')?.src || 
                                         document.querySelector('.ytmusic-player-bar img')?.src;
                        if (musicArt) {
                            // Richiedi versione ad alta risoluzione (es. 512x512)
                            artUrl = musicArt.replace(/=w\d+-h\d+/, '=w512-h512');
                        }
                    }
                }

                if (window.location.host.includes('spotify.com')) {
                    // 1. Cerca prima nella barra di riproduzione in basso (Now Playing Bar)
                    const nowPlayingBar = document.querySelector('[data-testid="now-playing-bar"]');
                    
                    if (nowPlayingBar) {
                        const trackEl = nowPlayingBar.querySelector('[data-testid="track-info-name"] a, [data-testid="context-item-info-title"] a, [data-testid="entity-title"], [data-encore-id="text"]');
                        const artistEl = nowPlayingBar.querySelector('[data-testid="track-info-artists"] a, [data-testid="context-item-info-subtitle"] a, [data-testid="entity-subtitle"]');
                        const artEl = nowPlayingBar.querySelector('img[data-testid="cover-art-image"], img[data-testid="entity-image"], img[src*="scdn.co"], img');

                        if (trackEl && (trackEl.textContent || trackEl.innerText)) {
                            title = (trackEl.textContent || trackEl.innerText).trim();
                        }
                        if (artistEl && (artistEl.textContent || artistEl.innerText)) {
                            artist = (artistEl.textContent || artistEl.innerText).trim();
                        }
                        if (artEl && artEl.src) {
                            artUrl = artEl.src;
                        }
                    }

                    // 2. Fallback su mediaSession se la barra in basso non è pronta, filtrando i titoli generici della playlist
                    if ((!title || title.includes("Top 50") || title.includes("Playlist")) && navigator.mediaSession && navigator.mediaSession.metadata) {
                        const meta = navigator.mediaSession.metadata;
                        if (meta.title && !meta.title.includes("Top 50") && !meta.title.includes("Spotify")) {
                            title = meta.title;
                        }
                        if (meta.artist) artist = meta.artist;
                        if (meta.artwork && meta.artwork.length > 0) {
                            artUrl = meta.artwork[meta.artwork.length - 1].src;
                        }
                    }

                    // Trucco upscaling copertina di Spotify (da bassa a alta risoluzione se presente l'hash)
                    if (artUrl && artUrl.includes("00004851")) {
                        artUrl = artUrl.replace("00004851", "0000b273");
                    }
                }

                const lowerTitle = (title || "").toLowerCase();
                const isGeneric = lowerTitle.includes("lettore web") || lowerTitle.includes("musica per tutti") || lowerTitle === "spotify" || lowerTitle === "home" || lowerTitle === "search" || lowerTitle === "cerca" || lowerTitle === "";

                if (!isGeneric && title && (title !== lastMediaTitle || Math.abs(duration - lastDuration) > 1)) {
                    lastMediaTitle = title;
                    lastDuration = duration;
                    console.log("AABrowserPlayback JS: calling AndroidBridge.updateMediaMetadata ->", title, artist, artUrl, duration);
                    AndroidBridge.updateMediaMetadata(title, artist, artUrl, duration);
                }
            }

            function setupMediaListeners(media) {
                if (media.dataset.mediaListenersAdded) return;
                media.dataset.mediaListenersAdded = 'true';
                media.lastBridgeUpdate = 0;

                media.addEventListener('play', () => {
                    console.log("AABrowserPlayback JS: media element 'play' event triggered");
                    window.isMediaPlaying = true;
                    if (window.AndroidBridge) AndroidBridge.onMediaStatusChanged(true, media.currentTime, media.playbackRate);
                    syncMetadata();
                });
                media.addEventListener('pause', () => {
                    console.log("AABrowserPlayback JS: media element 'pause' event triggered");
                    if (window.AndroidBridge) AndroidBridge.onMediaStatusChanged(false, media.currentTime, media.playbackRate);
                });
                media.addEventListener('timeupdate', () => {
                    const now = Date.now();
                    // Pool di aggiornamento: invia dati a Android max ogni 500ms
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
            mediaObserver.observe(document.body, { childList: true, subtree: true });

            document.querySelectorAll('video, audio').forEach(media => {
                setupMediaListeners(media);
            });
            setTimeout(syncMetadata, 2000);
            
            function setupInputListeners() {
                document.querySelectorAll('input, textarea, [contenteditable="true"]').forEach(el => {
                    if (!el.dataset.listenerAdded) {
                        el.addEventListener('focus', () => window.AndroidBridge && AndroidBridge.onStartInput());
                        el.addEventListener('click', () => window.AndroidBridge && AndroidBridge.onStartInput());
                        el.dataset.listenerAdded = 'true';
                    }
                });
            }
            const inputObserver = new MutationObserver(setupInputListeners);
            inputObserver.observe(document.body, { childList: true, subtree: true });
            setupInputListeners();

            if (window.location.host.includes('spotify.com')) {
                setInterval(() => {
                    if (window.location.host.includes('spotify.com')) {
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
                // Debouncing JS per evitare inserimenti multipli dallo smartphone
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
                    el.focus(); // Assicura che rimanga focused
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

            function smartClick(el) {
                if (!el) return false;
                try { el.click(); } catch(e) {}
                try {
                    ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(evt => {
                        el.dispatchEvent(new MouseEvent(evt, { bubbles: true, cancelable: true, view: window }));
                    });
                } catch(e) {}
                return true;
            }

            document.querySelectorAll('video, audio').forEach(v => {
                if (v.paused) v.play().catch(() => {});
            });

            const playSelectors = [
                '[data-testid="control-button-play"]',
                '[data-testid="control-button-playpause"]',
                '[data-testid="play-button"]',
                '.ytp-play-button',
                'ytmusic-player-bar .play-pause-button',
                '#play-pause-button',
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
                    const label = (btn.getAttribute('aria-label') || btn.getAttribute('data-testid') || '').toLowerCase();
                    if (label.includes('pause') || label.includes('pausa')) return;
                    if (smartClick(btn)) break;
                }
            }
        })();
    """

    const val PAUSE_SCRIPT = """
        (function() {
            window.isMediaPlaying = false;
            if (typeof window.aabMarkUserPause === 'function') window.aabMarkUserPause();

            function smartClick(el) {
                if (!el) return false;
                try { el.click(); } catch(e) {}
                try {
                    ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(evt => {
                        el.dispatchEvent(new MouseEvent(evt, { bubbles: true, cancelable: true, view: window }));
                    });
                } catch(e) {}
                return true;
            }

            document.querySelectorAll('video, audio').forEach(v => {
                try { v.pause(); } catch(e) {}
            });

            const pauseSelectors = [
                '[data-testid="control-button-pause"]',
                '[data-testid="control-button-playpause"]',
                '.ytp-play-button',
                'ytmusic-player-bar .play-pause-button',
                '#play-pause-button',
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
                    const label = (btn.getAttribute('aria-label') || btn.getAttribute('data-testid') || '').toLowerCase();
                    if (label.includes('play') || label.includes('riproduci')) return;
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
                try { el.click(); } catch(e) {}
                try {
                    ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(evt => {
                        el.dispatchEvent(new MouseEvent(evt, { bubbles: true, cancelable: true, view: window }));
                    });
                } catch(e) {}
                return true;
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
            function smartClick(el) {
                if (!el) return false;
                try { el.click(); } catch(e) {}
                try {
                    ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(evt => {
                        el.dispatchEvent(new MouseEvent(evt, { bubbles: true, cancelable: true, view: window }));
                    });
                } catch(e) {}
                return true;
            }

            const nextSelectors = [
                '[data-testid="control-button-skip-forward"]',
                '[data-testid="control-button-skip-right"]',
                '[data-testid="skip-next-button"]',
                '.ytp-next-button',
                'ytmusic-player-bar .next-button',
                '#next-button',
                'button[aria-label*="next" i]',
                'button[aria-label*="successiv" i]',
                'button[aria-label*="avanti" i]',
                'button[aria-label*="skip" i]',
                'button[title*="Next" i]',
                'button[title*="Successivo" i]',
                '[aria-label="Next"]',
                '[aria-label="Successivo"]',
                '[aria-label="Brano successivo"]'
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
                const media = document.querySelector('video, audio');
                if (media) media.currentTime += 10;
            }
        })();
    """

    const val PREVIOUS_SCRIPT = """
        (function() {
            function smartClick(el) {
                if (!el) return false;
                try { el.click(); } catch(e) {}
                try {
                    ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(evt => {
                        el.dispatchEvent(new MouseEvent(evt, { bubbles: true, cancelable: true, view: window }));
                    });
                } catch(e) {}
                return true;
            }

            const prevSelectors = [
                '[data-testid="control-button-skip-back"]',
                '[data-testid="control-button-skip-left"]',
                '[data-testid="skip-previous-button"]',
                '.ytp-prev-button',
                'ytmusic-player-bar .previous-button',
                '#previous-button',
                'button[aria-label*="prev" i]',
                'button[aria-label*="precedent" i]',
                'button[aria-label*="indietro" i]',
                'button[title*="Previous" i]',
                'button[title*="Precedente" i]',
                '[aria-label="Previous"]',
                '[aria-label="Precedente"]',
                '[aria-label="Brano precedente"]'
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

    fun getSpotifyOptimizationScript(): String {
        return """
        (function() {
            if (window.aabSpotifyOptimized) return;
            window.aabSpotifyOptimized = true;
            
            const style = document.createElement('style');
            style.innerHTML = `
                /* Nascondi la sidebar laterale sinistra per ottimizzare lo schermo dell'auto */
                nav[aria-label="Main"], [data-testid="left-sidebar"] {
                    display: none !important;
                }
                /* Espandi l'area principale a tutto schermo */
                .Root__main-view, [data-testid="main-content"] {
                    width: 100% !important;
                    grid-column: 1 / -1 !important;
                    max-width: none !important;
                }
                /* Rimuovi banner pubblicitari o inviti a scaricare l'app desktop */
                [data-testid="banner"], [data-testid="download-desktop-app-button"], header {
                    display: none !important;
                }
                /* Ottimizza la barra di riproduzione in basso */
                [data-testid="now-playing-bar"] {
                    background-color: #121212 !important;
                    border-top: 1px solid #282828;
                }
            `;
            document.head.appendChild(style);
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
}