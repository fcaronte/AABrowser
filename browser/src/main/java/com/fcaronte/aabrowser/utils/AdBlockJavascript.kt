package com.fcaronte.aabrowser.utils

object AdBlockJavascript {

    fun getYouTubeAdBlockScript(): String {
        return """
    (function() {
        if (window.aabIntervals) {
            window.aabIntervals.forEach(clearInterval);
        }
        window.aabIntervals = [];

        console.log("AABrowser: YouTube AdBlock Active (v10.1-MusicFix)");

        const isVisible = (el) => {
            return !!(el && el.offsetParent !== null);
        };

        const mainLoop = setInterval(() => {
            if (!window.location.host.includes('youtube.com') && !window.location.host.includes('youtu.be') && !window.location.host.includes('youtubekids.com')) return;
            const video = document.querySelector('video');
            if (!video) return;

            const isMusic = window.location.host.includes('music.youtube.com');
            const player = document.querySelector('#movie_player') || document.querySelector('.html5-video-player') || document.querySelector('ytmusic-player');
            
            // Rilevamento basato sugli stati nativi del player di YouTube e YouTube Music
            let adShowing = player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'));

            if (isMusic && !adShowing) {
                const musicAdBar = document.querySelector('ytmusic-ad-bar') || document.querySelector('ytmusic-player-bar[is-ad-playing]');
                const adBadge = document.querySelector('.ytmusic-ad-badge, [class*="ad-badge"], .sponsor-overlay');
                const playerBar = document.querySelector('ytmusic-player-bar');
                const hasAdAttr = playerBar && (playerBar.hasAttribute('player-queue-is-ad') || playerBar.getAttribute('ad-state') === 'playing' || playerBar.classList.contains('ad-playing'));
                
                adShowing = isVisible(musicAdBar) || isVisible(adBadge) || hasAdAttr;
            }

            const skipBtnSelectors = [
                '.ytp-ad-skip-button', '.ytp-ad-skip-button-modern', '.ytp-ad-skip-button-slot', 
                '.ytp-skip-ad-button', '.ytp-ad-skip-button-container', '.ytp-ad-skip-button-text',
                '.ytp-ad-preview-container', '.ytmusic-skip-ad-button', '[class*="skip-button"]'
            ];
            
            let skipBtn = skipBtnSelectors.map(s => document.querySelector(s)).find(isVisible);

            if (!skipBtn) {
                const buttons = document.querySelectorAll('button, [role="button"], .ytmusic-skip-ad-button');
                for (const btn of buttons) {
                    const t = (btn.innerText || "").toLowerCase();
                    const aria = (btn.getAttribute('aria-label') || "").toLowerCase();
                    if ((t.includes("skip") || t.includes("salta") || aria.includes("skip") || aria.includes("salta")) && isVisible(btn)) {
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
                    
                    // Ritardo di assestamento per permettere a YouTube di ripristinare il timing del video principale
                    // ed evitare il salto a inizio video o il disalignamento della seekbar.
                    setTimeout(() => {
                        if (!window.aabIsAdPlaying && video) {
                            video.playbackRate = isMusicVideo ? 1.0 : savedSpeed;
                            if (window.AndroidBridge) {
                                AndroidBridge.onMediaStatusChanged(!video.paused, video.currentTime, video.playbackRate);
                            }
                        }
                    }, 350);
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
            if (!window.location.host.includes('youtube.com') && !window.location.host.includes('youtu.be')) return;
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

            // Sblocco pulito dei tocchi sulla seekbar e i controlli, SENZA interferire con Android Auto o i tocchi utente
            const pbContainers = document.querySelectorAll('.ytp-progress-bar-container, .ytp-progress-bar, .ytp-chrome-bottom, ytmusic-player-bar');
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
