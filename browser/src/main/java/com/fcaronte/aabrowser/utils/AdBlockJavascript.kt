package com.fcaronte.aabrowser.utils

object AdBlockJavascript {

    fun getYouTubeAdBlockScript(): String {
        return """
    (function() {
        if (window.aabIntervals) {
            window.aabIntervals.forEach(clearInterval);
        }
        window.aabIntervals = [];

        console.log("AABrowser: YouTube AdBlock Active (v8.1-SafeSkip)");

        const mainLoop = setInterval(() => {
            const video = document.querySelector('video');
            
            // Sicurezza extra: se il video dura più di 35 secondi, NON è una pubblicità (evita falsi salti alla fine)
            const isActuallyAd = video && isFinite(video.duration) && video.duration < 35;

            const adShowing = !!(
                document.querySelector('.ad-showing') || 
                document.querySelector('.ad-interrupting') || 
                document.querySelector('.ytp-ad-player-overlay') || 
                document.querySelector('.ytp-ad-module') ||
                document.querySelector('.ad-container') ||
                document.querySelector('.ad-display') ||
                document.querySelector('.ytp-ad-player-overlay-layout')
            );

            const skipBtnSelectors = [
                '.ytp-ad-skip-button', '.ytp-ad-skip-button-modern', '.ytp-ad-skip-button-slot', 
                '.ytp-skip-ad-button', '.ytp-ad-skip-button-container', '.ytp-ad-skip-button-text',
                '.ytp-ad-preview-container', '[class*="skip-button"]'
            ];
            
            let skipBtn = skipBtnSelectors.map(s => document.querySelector(s)).find(el => el && el.offsetParent !== null);

            if (!skipBtn) {
                const buttons = document.querySelectorAll('button, [role="button"]');
                for (const btn of buttons) {
                    const t = (btn.innerText || "").toLowerCase();
                    if ((t.includes("skip") || t.includes("salta")) && btn.offsetParent !== null) {
                        skipBtn = btn;
                        break;
                    }
                }
            }

            // Attiviamo il blocco solo se c'è un ad E la durata conferma che è una pubblicità (< 35 sec)
            if ((adShowing || skipBtn) && isActuallyAd) {
                window.aabIsAdPlaying = true;
                
                if (video) {
                    video.muted = true;
                    video.playbackRate = 16.0;
                    if (isFinite(video.duration) && video.currentTime < video.duration - 0.2) {
                        video.currentTime = video.duration - 0.1;
                    }
                    if (video.paused) video.play().catch(() => {});
                }

                if (skipBtn) {
                    try {
                        skipBtn.click();
                        skipBtn.querySelectorAll('*').forEach(c => c.click());
                    } catch (e) {}
                }
            } else if (window.aabIsAdPlaying || (!adShowing && !skipBtn)) {
                // Se l'ad è finito o non c'è, ripristiniamo la velocità normale
                if (window.aabIsAdPlaying) {
                    window.aabIsAdPlaying = false;
                }
                if (video && video.playbackRate === 16.0) {
                    video.muted = false;
                    const isMusic = window.location.host.includes('music.youtube.com') || document.title.toLowerCase().includes('official music video');
                    video.playbackRate = isMusic ? 1.0 : parseFloat(localStorage.getItem('yt-custom-speed') || '1.0');
                }
            }

            const hideSelectors = ['.video-ads', '.ytp-ad-module', '#masthead-ad', 'ytd-ad-slot-renderer', '#player-ads', 'ytmusic-ad-bar'];
            hideSelectors.forEach(s => {
                document.querySelectorAll(s).forEach(el => { if(el.style.display !== 'none') el.style.display = 'none'; });
            });
            document.querySelectorAll('.ytp-ad-overlay-close-button, .ytp-ad-overlay-close-container').forEach(b => b.click());

        }, 250);
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

            // Seek Fix: Supporto migliorato per progress bar
            const pb = document.querySelector('.ytp-progress-bar, .ytp-progress-list, .ytp-progress-bar-container, .ytp-scrubber-container');
            if (pb && !pb.dataset.aabSeekFixed) {
                pb.style.pointerEvents = 'auto';
                const handleSeek = (e) => {
                    const v = document.querySelector('video');
                    if (v && isFinite(v.duration)) {
                        const rect = pb.getBoundingClientRect();
                        const x = e.clientX || (e.touches && e.touches[0] ? e.touches[0].clientX : 0);
                        const pct = (x - rect.left) / rect.width;
                        if (pct >= 0 && pct <= 1) {
                            v.currentTime = pct * v.duration;
                            v.dispatchEvent(new Event('seeked'));
                            v.dispatchEvent(new Event('timeupdate'));
                            e.preventDefault();
                            e.stopPropagation();
                        }
                    }
                };
                pb.addEventListener('click', handleSeek, true);
                pb.addEventListener('touchstart', handleSeek, {passive: false, capture: true});
                pb.dataset.aabSeekFixed = 'true';
            }
        }, 1000);
        window.aabIntervals.push(slowLoop);

    })();
    """.trimIndent()
    }
}