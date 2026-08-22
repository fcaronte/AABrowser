package com.fcaronte.aabrowser.utils

object AdBlockJavascript {

    fun getYouTubeAdBlockScript(): String {
        return """
    (function() {
        if (window.aabIntervals) {
            window.aabIntervals.forEach(clearInterval);
        }
        window.aabIntervals = [];

        console.log("AABrowser: YouTube AdBlock Active (v10.0-StableSync)");

        const isVisible = (el) => {
            return !!(el && el.offsetParent !== null);
        };

        const mainLoop = setInterval(() => {
            const video = document.querySelector('video');
            if (!video) return;

            const isMusic = window.location.host.includes('music.youtube.com');
            const player = document.querySelector('#movie_player') || document.querySelector('.html5-video-player');
            
            // Rilevamento basato sugli stati nativi del player di YouTube (senza trucchi sulla durata)
            let adShowing = player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'));

            if (isMusic && !adShowing) {
                const musicAdBar = document.querySelector('ytmusic-ad-bar');
                adShowing = isVisible(musicAdBar);
            }

            const skipBtnSelectors = [
                '.ytp-ad-skip-button', '.ytp-ad-skip-button-modern', '.ytp-ad-skip-button-slot', 
                '.ytp-skip-ad-button', '.ytp-ad-skip-button-container', '.ytp-ad-skip-button-text',
                '.ytp-ad-preview-container', '.ytmusic-skip-ad-button', '[class*="skip-button"]'
            ];
            
            let skipBtn = skipBtnSelectors.map(s => document.querySelector(s)).find(isVisible);

            if (!skipBtn) {
                const buttons = document.querySelectorAll('button, [role="button"]');
                for (const btn of buttons) {
                    const t = (btn.innerText || "").toLowerCase();
                    if ((t.includes("skip") || t.includes("salta")) && isVisible(btn)) {
                        skipBtn = btn;
                        break;
                    }
                }
            }

            // Verifica overlay pubblicitario visibile
            let isActuallyAd = adShowing;
            if (!isActuallyAd && skipBtn) {
                const adOverlay = document.querySelector('.ytp-ad-player-overlay-layout') || 
                                  document.querySelector('.ytp-ad-player-overlay') ||
                                  document.querySelector('.ytp-ad-module');
                if (isVisible(adOverlay)) {
                    isActuallyAd = true;
                }
            }

            if (isActuallyAd) {
                window.aabIsAdPlaying = true;
                video.muted = true;
                video.playbackRate = 16.0;
                
                if (skipBtn) {
                    try {
                        skipBtn.click();
                        skipBtn.querySelectorAll('*').forEach(c => c.click());
                    } catch (e) {}
                }

                if (video.paused) video.play().catch(() => {});
            } else {
                if (window.aabIsAdPlaying) {
                    window.aabIsAdPlaying = false;
                    video.muted = false;
                    const isMusicVideo = isMusic || document.title.toLowerCase().includes('official music video');
                    const savedSpeed = parseFloat(localStorage.getItem('yt-custom-speed') || '1.0');
                    video.playbackRate = isMusicVideo ? 1.0 : savedSpeed;
                    
                    // Sincronizzazione immediata al termine dell'Ad per evitare il "drift" della seekbar
                    if (window.AndroidBridge) {
                        AndroidBridge.onMediaStatusChanged(!video.paused, video.currentTime, video.playbackRate);
                    }
                }
            }

            // Nasconde i contenitori pubblicitari senza interferire con il player principale
            const hideSelectors = ['.video-ads', '.ytp-ad-module', '#masthead-ad', 'ytd-ad-slot-renderer', '#player-ads', 'ytmusic-ad-bar'];
            hideSelectors.forEach(s => {
                document.querySelectorAll(s).forEach(el => { 
                    if (el.style.display !== 'none') {
                        el.style.display = 'none';
                        el.style.pointerEvents = 'none';
                    }
                });
            });
            document.querySelectorAll('.ytp-ad-overlay-close-button, .ytp-ad-overlay-close-container').forEach(b => b.click());

        }, 500);
        window.aabIntervals.push(mainLoop);

        const slowLoop = setInterval(() => {
            const video = document.querySelector('video');
            if (video && !window.aabIsAdPlaying) {
                const isMusic = window.location.host.includes('music.youtube.com') || document.title.toLowerCase().includes('official music video');
                if (isMusic) {
                    if (video.playbackRate !== 1.0) video.playbackRate = 1.0;
                } else {
                    const savedSpeed = parseFloat(localStorage.getItem('yt-custom-speed') || '1.0');
                    if (!video.dataset.speedInitialized) {
                        video.playbackRate = savedSpeed;
                        video.dataset.speedInitialized = 'true';
                    }
                }
            }

            // Sblocco pulito dei tocchi sulla seekbar e i controlli, SENZA override manuali che rompono la sync
            const pbContainers = document.querySelectorAll('.ytp-progress-bar-container, .ytp-progress-bar, .ytp-chrome-bottom');
            pbContainers.forEach(pb => {
                if (pb && !pb.dataset.aabTouchFixed) {
                    pb.style.pointerEvents = 'auto';
                    pb.dataset.aabTouchFixed = 'true';
                }
            });

        }, 1000);
        window.aabIntervals.push(slowLoop);

    })();
    """.trimIndent()
    }
}
