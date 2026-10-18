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
}
