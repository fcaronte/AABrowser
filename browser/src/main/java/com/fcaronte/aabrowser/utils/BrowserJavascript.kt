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

            // Fix per il background play: intercetta le chiamate a pause()
            const originalPause = HTMLVideoElement.prototype.pause;
            HTMLVideoElement.prototype.pause = function() {
                // YouTube mobile spesso forza la pausa quando l'app va in background.
                // Lo blocchiamo se siamo in uno stato di riproduzione desiderata, 
                // MA solo se non stiamo interagendo (permettendo pause manuali).
                if (window.isMediaPlaying === true && !window.aabIsAdPlaying && (document.hidden || document.visibilityState === 'hidden')) {
                    console.log("AABrowser: Blocked background pause()");
                    return Promise.resolve();
                }
                return originalPause.apply(this, arguments);
            };

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
                const video = document.querySelector('video');
                if (!video || !window.AndroidBridge) return;

                let title = document.title;
                let artist = "AABrowser Audio";
                let artUrl = "";
                let duration = isFinite(video.duration) ? video.duration : 0;

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

                if (title && (title !== lastMediaTitle || Math.abs(duration - lastDuration) > 1)) {
                    lastMediaTitle = title;
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
                    if (window.AndroidBridge) AndroidBridge.onMediaStatusChanged(true, video.currentTime, video.playbackRate);
                    syncMetadata();
                });
                video.addEventListener('pause', () => {
                    if (window.AndroidBridge) AndroidBridge.onMediaStatusChanged(false, video.currentTime, video.playbackRate);
                });
                video.addEventListener('timeupdate', () => {
                    const now = Date.now();
                    // Pool di aggiornamento: invia dati a Android max ogni 500ms
                    if (now - video.lastBridgeUpdate > 500) {
                        if (window.AndroidBridge) AndroidBridge.onMediaTimeUpdate(video.currentTime, video.playbackRate, !video.paused);
                        video.lastBridgeUpdate = now;
                    }
                });
                video.addEventListener('durationchange', syncMetadata);
            }

            const videoObserver = new MutationObserver(() => {
                const video = document.querySelector('video');
                if (video) setupVideoListeners(video);
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
                        el.addEventListener('focus', () => window.AndroidBridge && AndroidBridge.onStartInput());
                        el.addEventListener('click', () => window.AndroidBridge && AndroidBridge.onStartInput());
                        el.dataset.listenerAdded = 'true';
                    }
                });
            }
            const inputObserver = new MutationObserver(setupInputListeners);
            inputObserver.observe(document.body, { childList: true, subtree: true });
            setupInputListeners();
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

    const val PLAY_SCRIPT = """
        (function() {
            window.isMediaPlaying = true;
            document.querySelectorAll('video').forEach(v => {
                if (v.paused) v.play().catch(e => console.log("Play failed: ", e));
            });
        })();
    """

    const val PAUSE_SCRIPT = """
        (function() {
            window.isMediaPlaying = false;
            document.querySelectorAll('video').forEach(v => v.pause());
        })();
    """

    const val STOP_SCRIPT = """
        (function() {
            window.isMediaPlaying = false;
            document.querySelectorAll('video').forEach(v => v.pause());
        })();
    """

    const val NEXT_SCRIPT = """
        (function() {
            const nextBtn = document.querySelector('.ytp-next-button, ytmusic-player-bar .next-button, [aria-label="Next"], [title="Next"]');
            if (nextBtn) nextBtn.click();
            else {
                const video = document.querySelector('video');
                if (video) video.currentTime += 10;
            }
        })();
    """

    const val PREVIOUS_SCRIPT = """
        (function() {
            const prevBtn = document.querySelector('.ytp-prev-button, ytmusic-player-bar .previous-button, [aria-label="Previous"], [title="Previous"]');
            if (prevBtn) prevBtn.click();
            else {
                const video = document.querySelector('video');
                if (video) video.currentTime -= 10;
            }
        })();
    """

    fun getSeekScript(pos: Long): String {
        return """
        (function() {
            const video = document.querySelector('video');
            if (video) video.currentTime = ${pos / 1000.0};
        })();
        """.trimIndent()
    }
}