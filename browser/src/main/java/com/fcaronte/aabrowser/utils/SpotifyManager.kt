package com.fcaronte.aabrowser.utils

import android.webkit.JavascriptInterface

class SpotifyWebBridge(
    private val onMediaStatusChanged: (String) -> Unit
) {
    @JavascriptInterface
    fun recMediaStatus(jsonStr: String) {
        if (jsonStr.isNotEmpty()) {
            onMediaStatusChanged(jsonStr)
        }
    }
}

object SpotifyManager {
    fun getInjectionScript(): String {
        return """
        (function() {
            if (window.aabSpotifyInitialized) return;
            window.aabSpotifyInitialized = true;

            console.log("AABrowser: Spotify Ultimate Track & Cover Observer Active");

            // 1. Inject CSS to hide banners and upgrade dialogs
            const style = document.createElement('style');
            style.textContent = `
                [data-encore-id="banner"], [data-testid="upgrade-modal"] {
                    display: none !important;
                    opacity: 0 !important;
                }
            `;
            document.head.appendChild(style);

            // 2. Anti-Promo Premium automatico
            const removeBanners = setInterval(() => {
                const upgradeModal = document.querySelector('[data-testid="upgrade-modal"]');
                if (upgradeModal) upgradeModal.remove();

                const buttons = document.querySelectorAll('button');
                buttons.forEach(btn => {
                    const text = btn.innerText ? btn.innerText.toLowerCase() : "";
                    if (text.includes("non ora") || text.includes("not now") || text.includes("no thanks")) {
                        btn.click();
                    }
                });
            }, 1000);

            // 3. Poller definitivo (Cheap Path a[href*="/track/"] per titolo/artista + MediaSession/DOM per copertina HD)
            let lastTitle = "";
            setInterval(() => {
                let title = "";
                let artist = "";
                let cover = "";
                let isPlaying = false;
                let position = 0;
                let duration = 0;

                // A. Cheap Path: Cerca a[href*="/track/"] nel player bar o nella pagina
                const nowPlaying = document.querySelector('[data-testid="now-playing-bar"], [data-testid="now-playing-widget"]') || document.body;
                const trackLink = nowPlaying.querySelector('a[href*="/track/"]');
                if (trackLink) {
                    title = (trackLink.innerText || trackLink.textContent || "").trim();
                }

                // B. Cerca l'artista tramite a[href*="/artist/"]
                const artistLink = nowPlaying.querySelector('a[href*="/artist/"]') || document.querySelector('a[href*="/artist/"]');
                if (artistLink) {
                    artist = (artistLink.innerText || artistLink.textContent || "").trim();
                }

                // C. Fallback su MediaSession per titolo/artista se il Cheap Path è vuoto
                if (!title && navigator.mediaSession && navigator.mediaSession.metadata) {
                    const meta = navigator.mediaSession.metadata;
                    title = meta.title || "";
                    artist = meta.artist || "";
                }

                // D. Estrazione copertina (MediaSession artwork array + CDN fallback)
                if (navigator.mediaSession && navigator.mediaSession.metadata && navigator.mediaSession.metadata.artwork) {
                    const artwork = navigator.mediaSession.metadata.artwork;
                    if (artwork.length > 0) {
                        let bestArt = artwork[artwork.length - 1].src || "";
                        for (let art of artwork) {
                            if (art.sizes && art.sizes.includes("640x640")) {
                                bestArt = art.src;
                                break;
                            }
                        }
                        cover = bestArt;
                    }
                }

                if (!cover) {
                    const artEl = nowPlaying.querySelector('img[src*="scdn.co/image"], img[src*="spotifycdn.com"], img');
                    if (artEl && artEl.src && !artEl.src.includes("data:image")) {
                        cover = artEl.src;
                    }
                }

                // Rileva stato di riproduzione
                const pauseBtn = document.querySelector('[data-testid="control-button-pause"], [aria-label="Pause"], [aria-label="Pausa"]');
                isPlaying = !!pauseBtn;

                const media = document.querySelector('audio, video');
                if (media) {
                    position = Math.floor(media.currentTime * 1000);
                    duration = Math.floor(media.duration * 1000);
                    if (!media.paused) isPlaying = true;
                }

                // Trucco upscaling copertina
                if (cover && cover.includes("00004851")) {
                    cover = cover.replace("00004851", "0000b273");
                }

                const lowerTitle = (title || "").toLowerCase();
                const ignoredTitles = ["buonasera", "buongiorno", "buon pomeriggio", "good evening", "good morning", "spotify", "home", "search", "cerca", "lettore web"];
                const shouldIgnore = ignoredTitles.some(ig => lowerTitle.includes(ig));

                if (title && !shouldIgnore && title !== lastTitle) {
                    lastTitle = title;
                    const payload = {
                        track: title,
                        artist: artist,
                        cover: cover,
                        playing: isPlaying,
                        position: isNaN(position) ? 0 : position,
                        duration: isNaN(duration) ? 0 : duration
                    };

                    console.log("SpotifyManager: Sending payload ->", payload);
                    if (window.AndroidBridge && typeof window.AndroidBridge.recMediaStatus === 'function') {
                        window.AndroidBridge.recMediaStatus(JSON.stringify(payload));
                    }
                }
            }, 1000);

        })();
        """.trimIndent()
    }
}
