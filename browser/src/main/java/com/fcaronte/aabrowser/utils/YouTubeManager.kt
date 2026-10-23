package com.fcaronte.aabrowser.utils

object YouTubeManager {
    fun getMetadataScript(): String {
        return """
        let ytTitle = "";
        let ytArtist = "";
        
        if (navigator.mediaSession && navigator.mediaSession.metadata) {
            const meta = navigator.mediaSession.metadata;
            if (meta.title && !meta.title.toLowerCase().includes("youtube")) {
                ytTitle = meta.title;
            }
            if (meta.artist && !meta.artist.toLowerCase().includes("youtube")) {
                ytArtist = meta.artist;
            }
            if (meta.artwork && meta.artwork.length > 0) {
                const lastArt = meta.artwork[meta.artwork.length - 1];
                if (lastArt && lastArt.src) artUrl = lastArt.src;
            }
        }
        
        if (!ytTitle) {
            ytTitle = document.querySelector('.ytp-title-link')?.innerText || 
                     document.querySelector('ytmusic-player-bar .title')?.textContent ||
                     document.querySelector('.ytmusic-player-bar .title')?.textContent ||
                     document.querySelector('.video-title')?.textContent ||
                     document.querySelector('h1.ytd-watch-metadata')?.textContent ||
                     document.querySelector('h1.title.ytd-video-primary-info-renderer')?.textContent ||
                     document.querySelector('yt-formatted-string.ytd-watch-metadata')?.textContent ||
                     "";
        }
        if (!ytArtist) {
            ytArtist = document.querySelector('.ytp-ce-channel-title')?.innerText || 
                      document.querySelector('#upload-info #channel-name')?.innerText ||
                      document.querySelector('ytmusic-player-bar .byline')?.textContent ||
                      document.querySelector('.ytmusic-player-bar .byline')?.textContent ||
                      document.querySelector('#owner #channel-name')?.innerText ||
                      document.querySelector('.channel-name')?.textContent ||
                      "";
        }
        
        if (ytTitle) title = ytTitle.trim();
        if (ytArtist) artist = ytArtist.trim();
        
        const urlParams = new URLSearchParams(window.location.search);
        const v = urlParams.get('v');
        if (v && !artUrl) artUrl = 'https://img.youtube.com/vi/' + v + '/hqdefault.jpg';
        
        if (window.location.host.includes('music.youtube.com')) {
            const mArt = document.querySelector('ytmusic-player-bar img')?.src || document.querySelector('.ytmusic-player-bar img')?.src;
            if (mArt) artUrl = mArt.replace(/=w\d+-h\d+/, '=w512-h512');
        }
        """.trimIndent()
    }

    /**
     * Comando vocale "metti …" (VoiceCommands): nella pagina dei risultati di YouTube o YouTube Music apre il
     * primo risultato, che parte da solo. Mai i risultati sponsorizzati; dopo 15 s senza risultati lascia stare.
     */
    fun getPlayFirstResultScript(): String {
        return """
        (function() {
            if (window.aabPlayFirstTimer) clearInterval(window.aabPlayFirstTimer);
            const startedAt = Date.now();
            const isMusic = location.host.indexOf('music.youtube.com') !== -1;
            const SKIP = 'ytm-promoted-video-renderer, ytm-promoted-sparkles-web-renderer, ytm-companion-ad-renderer, ' +
                'ad-slot-renderer, ytm-ad-slot-renderer, ytd-ad-slot-renderer, ytd-promoted-video-renderer, ' +
                'ytd-promoted-sparkles-web-renderer, ytd-in-feed-ad-layout-renderer';

            function isVisible(el) {
                const r = el.getBoundingClientRect();
                return r.width > 0 && r.height > 0;
            }

            // YouTube Music usa link relativi ("watch?v="), YouTube "/watch?v=" (gli Shorts sono "/shorts/")
            function firstResult() {
                const links = document.querySelectorAll(isMusic ? 'a[href*="watch?v="]' : 'a[href*="/watch?v="]');
                for (const link of links) {
                    if (!link.closest(SKIP) && isVisible(link)) return link;
                }
                return null;
            }

            window.aabPlayFirstTimer = setInterval(function() {
                if (Date.now() - startedAt > 15000) {
                    clearInterval(window.aabPlayFirstTimer);
                    return;
                }
                const link = firstResult();
                if (!link) return;
                clearInterval(window.aabPlayFirstTimer);
                console.log('AABrowser: voice command opens the first result');
                link.click();
            }, 300);
        })();
        """.trimIndent()
    }

    /**
     * Sul player mobile di YouTube la barra di avanzamento si può solo trascinare: un tap non sposta il video.
     * Questo script fa saltare il video nel punto toccato (Main e Wide; nella Wide i trascinamenti
     * diventano scroll della pagina, quindi il tap è l'unico modo per spostarsi nel video).
     * Il trascinamento resta gestito da YouTube: lo script non blocca né modifica nessun evento.
     */
    fun getTapToSeekScript(): String {
        return """
        (function() {
            if (window.aabTapSeekInitialized) return;
            window.aabTapSeekInitialized = true;

            // Oltre questa distanza (px CSS) è un trascinamento: lo gestisce YouTube
            const MOVE_TOLERANCE = 12;
            let tap = null;

            window.addEventListener('pointerdown', function(e) {
                tap = e.isPrimary ? { id: e.pointerId, x: e.clientX, y: e.clientY, target: e.target } : null;
            }, true);

            window.addEventListener('pointermove', function(e) {
                if (tap && e.pointerId === tap.id &&
                    Math.hypot(e.clientX - tap.x, e.clientY - tap.y) > MOVE_TOLERANCE) {
                    tap = null;
                }
            }, true);

            window.addEventListener('pointercancel', function() { tap = null; }, true);

            window.addEventListener('pointerup', function(e) {
                const t = tap;
                tap = null;
                if (!t || e.pointerId !== t.id || window.aabIsAdPlaying) return;

                // Con i controlli nascosti il tap serve solo a mostrarli
                const bar = document.querySelector('.ytPlayerProgressBarDragContainer');
                if (!bar || getComputedStyle(bar).visibility !== 'visible') return;

                const r = bar.getBoundingClientRect();
                if (r.width <= 0 || t.x < r.left || t.x > r.right || t.y < r.top || t.y > r.bottom) return;

                // Pulsanti sovrapposti alla barra (es. schermo intero): il tap è loro
                const target = t.target instanceof Element ? t.target : null;
                if (target && !bar.contains(target) && target.closest('button, a, [role="button"]')) return;

                const video = document.querySelector('video');
                if (!video || !isFinite(video.duration) || video.duration <= 0) return;

                const rtl = getComputedStyle(bar).direction === 'rtl';
                const fraction = Math.min(1, Math.max(0, (rtl ? r.right - t.x : t.x - r.left) / r.width));
                video.currentTime = fraction * video.duration;
            }, true);
        })();
        """.trimIndent()
    }

    /**
     * Su schermo piccolo / mobile, la playlist di YouTube si trova in basso sotto il video.
     * Quando il brano cambia, YouTube focalizza o fa lo scroll della canzone nella playlist,
     * spostando lo schermo verso il basso e nascondendo il video in alto.
     * Questo script intercetta scrollIntoView, focus e scrollTo sugli elementi della playlist
     * per mantenere il video visibile in alto quando l'utente si trova a inizio pagina.
     */
    fun getPreventPlaylistScrollScript(): String {
        return """
        (function() {
            if (window.aabPreventPlaylistScrollInitialized) return;
            window.aabPreventPlaylistScrollInitialized = true;

            const host = (window.location.host || '').toLowerCase();
            const isYT = host.includes('youtube.com') || host.includes('youtu.be') || host.includes('youtubekids.com');
            if (!isYT) return;

            const PLAYLIST_SELECTOR = '#playlist, [id*="playlist"], ytm-playlist, ytm-playlist-panel-renderer, ' +
                'ytm-playlist-video-renderer, ytm-playlist-panel-video-renderer, ytd-playlist-panel-renderer, ' +
                'ytd-playlist-panel-video-renderer, ytmusic-player-queue, ytmusic-player-queue-item, ' +
                'ytm-item-section-renderer, .playlist-items, [class*="playlist"], [class*="queue"]';

            function isPlaylistElement(el) {
                try {
                    return el && el.closest && el.closest(PLAYLIST_SELECTOR) !== null;
                } catch (e) {
                    return false;
                }
            }

            // 1. Intercetta scrollIntoView per elementi della playlist
            const origScrollIntoView = Element.prototype.scrollIntoView;
            Element.prototype.scrollIntoView = function(arg) {
                if (isPlaylistElement(this)) {
                    const wasAtTop = window.scrollY < 120;
                    const res = origScrollIntoView.apply(this, arguments);
                    if (wasAtTop && window.scrollY > 0) {
                        window.scrollTo(0, 0);
                    }
                    return res;
                }
                return origScrollIntoView.apply(this, arguments);
            };

            // 2. Intercetta focus per elementi della playlist (passa preventScroll: true)
            const origFocus = HTMLElement.prototype.focus;
            HTMLElement.prototype.focus = function(options) {
                if (isPlaylistElement(this)) {
                    let opts = options || {};
                    if (typeof opts === 'object') {
                        opts = Object.assign({}, opts, { preventScroll: true });
                    } else {
                        opts = { preventScroll: true };
                    }
                    const wasAtTop = window.scrollY < 120;
                    const res = origFocus.call(this, opts);
                    if (wasAtTop && window.scrollY > 0) {
                        window.scrollTo(0, 0);
                    }
                    return res;
                }
                return origFocus.apply(this, arguments);
            };

            // 3. Intercetta window.scrollTo se l'elemento attivo/focalizzato fa parte della playlist
            const origWindowScrollTo = window.scrollTo;
            window.scrollTo = function() {
                let y = 0;
                if (arguments.length === 1 && typeof arguments[0] === 'object' && arguments[0] !== null) {
                    y = arguments[0].top || 0;
                } else if (arguments.length >= 2) {
                    y = arguments[1] || 0;
                }
                if (y > 0 && window.scrollY < 120 && isPlaylistElement(document.activeElement)) {
                    return;
                }
                return origWindowScrollTo.apply(this, arguments);
            };

            // 4. Ripristina lo scroll in cima sui cambi brano se l'utente era sul video
            function keepVideoInView() {
                if (window.scrollY < 120 && window.scrollY > 0) {
                    origWindowScrollTo.call(window, 0, 0);
                }
            }

            function onTrackChange() {
                if (window.scrollY < 120) {
                    keepVideoInView();
                    [50, 100, 200, 350, 500, 750, 1000, 1500].forEach(function(delay) {
                        setTimeout(keepVideoInView, delay);
                    });
                }
            }

            window.addEventListener('yt-navigate-finish', onTrackChange);
            window.addEventListener('ytmusic-navigate-finish', onTrackChange);
            window.addEventListener('popstate', onTrackChange);

            document.addEventListener('play', function(e) {
                if (e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) {
                    onTrackChange();
                }
            }, true);
        })();
        """.trimIndent()
    }
}

