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
            window.aabSpotifyIntervals = window.aabSpotifyIntervals || [];

            console.log("SpotifyDebug: Spotify Precision Inspector Active");

            // Anti-Promo: Esegue il click sulla "X" del banner promozionale nei primi secondi dall'avvio e poi si spegne definitivamente
            let promoAttempts = 0;
            const removeBanners = setInterval(() => {
                if (!window.location.host.includes('spotify.com')) return;

                promoAttempts++;

                const closeXButtons = document.querySelectorAll(`
                    [data-testid="upgrade-modal"] button,
                    [data-testid="premium-upsell-modal"] button,
                    [data-testid="upsell-overlay"] button,
                    [data-testid="close-button"],
                    [data-testid="modal-close-button"],
                    button[aria-label="Chiudi"],
                    button[aria-label="Close"],
                    button[aria-label="Dismiss"],
                    button[aria-label="chiudi" i],
                    button[aria-label="close" i]
                `);

                closeXButtons.forEach(btn => {
                    const testid = btn.getAttribute('data-testid') || '';
                    if (testid === 'top-bar-back-button' || testid.includes('minimize') || testid.includes('collapse')) {
                        return;
                    }

                    const isPlayer = btn.closest('[data-testid="now-playing-widget"], [data-testid="now-playing-bar"], [data-testid="footer-player"], [data-testid="top-bar"]');
                    if (isPlayer) {
                        return;
                    }

                    console.log("SpotifyDebug: Closing startup banner -> " + (btn.outerHTML || '').substring(0, 100));
                    try {
                        btn.click();
                    } catch(e) {}
                });

                // Spegni definitivamente l'intervallo dopo 8 tentativi (circa 5 secondi dall'avvio)
                if (promoAttempts >= 8) {
                    console.log("SpotifyDebug: Startup anti-promo task finished, shutting down interval.");
                    clearInterval(removeBanners);
                }
            }, 600);
            window.aabSpotifyIntervals.push(removeBanners);

            // 3. Scanner DOM avanzato e Poller per Spotify
            let lastTitle = "";
            let scanCounter = 0;

            const ignoredWords = [
                "company", "about", "jobs", "for the record", "communities", "for artists", 
                "for creators", "for authors", "developers", "advertising", "investors", 
                "vendors", "useful links", "support", "free mobile app", "consumer rights", 
                "popular by country", "top song lyrics", "import your music", "spotify plans", 
                "premium individual", "premium duo", "premium family", "premium student", 
                "spotify free", "legal", "safety & privacy center", "privacy policy", 
                "cookie settings", "about ads", "accessibility", "copyright", "spotify ab", 
                "buonasera", "buongiorno", "buon pomeriggio", "good evening", "good morning", 
                "home", "search", "cerca", "libreria", "library", "premium", "spotify",
                "spotify - web player", "spotify - lettore web", "music for everyone", "musica per tutti",
                "get app", "your library", "prova 3", "try 3", "0€", "0 €", "0€ for 3 months", "for 3 months", "per 3 mesi"
            ];

            function scanSpotifyDOM() {
                scanCounter++;

                // A. MediaSession Metadata
                let msTitle = "", msArtist = "", msAlbum = "", msArt = "";
                if (navigator.mediaSession && navigator.mediaSession.metadata) {
                    const meta = navigator.mediaSession.metadata;
                    msTitle = meta.title || "";
                    msArtist = meta.artist || "";
                    msAlbum = meta.album || "";
                    if (meta.artwork && meta.artwork.length > 0) {
                        msArt = meta.artwork[meta.artwork.length - 1].src || "";
                    }
                }

                // B. Selettori mirati per la barra del player (Mini-Player ed Espanso)
                const playerSelectors = [
                    '[data-testid="now-playing-widget"]',
                    '[data-testid="now-playing-bar"]',
                    '[data-testid="footer-player"]',
                    '[data-testid="action-bar"]',
                    '[class*="nowPlayingBar"]',
                    '[class*="NowPlayingBar"]',
                    '[class*="now-playing-bar"]',
                    '[class*="now-playing"]',
                    '[class*="Root__now-playing-bar"]',
                    '[class*="Root__footer"]',
                    'footer[class*="player"]',
                    'footer[class*="Player"]'
                ];

                let nowPlaying = null;
                for (const sel of playerSelectors) {
                    const found = document.querySelector(sel);
                    if (found && found.innerText && !found.innerText.toLowerCase().includes("company")) {
                        nowPlaying = found;
                        break;
                    }
                }

                // Fallback dinamico su elementi ancorati in basso (position: fixed / sticky)
                if (!nowPlaying) {
                    const allEls = document.body.querySelectorAll('div, footer, section, nav, aside');
                    for (const el of allEls) {
                        try {
                            const style = window.getComputedStyle(el);
                            if ((style.position === 'fixed' || style.position === 'sticky') && el.offsetHeight > 20 && el.offsetHeight < 300) {
                                const rect = el.getBoundingClientRect();
                                if (rect.bottom >= window.innerHeight - 80) {
                                    const text = (el.innerText || '').toLowerCase();
                                    if (!text.includes("company") && !text.includes("cookie settings")) {
                                        nowPlaying = el;
                                        break;
                                    }
                                }
                            }
                        } catch (e) {}
                    }
                }

                let allLeafTexts = [];
                let trackLinks = [];
                let artistLinks = [];
                let coverImgs = [];
                let allButtons = [];

                let title = "";
                let artist = "";
                let cover = "";

                if (nowPlaying) {
                    // Estrazione diretta mirata da selettori specifici del player (Mini-Player ed Espanso)
                    const titleEl = nowPlaying.querySelector('[data-testid="context-item-info-title"], [data-testid="track-info-name"], [data-testid="entity-title"], a[href*="/track/"]');
                    const artistEl = nowPlaying.querySelector('[data-testid="context-item-info-subtitle"], [data-testid="track-info-artists"], [data-testid="entity-subtitle"], a[href*="/artist/"]');
                    const imgEl = nowPlaying.querySelector('img[data-testid="cover-art-image"], img[data-testid="entity-image"], img[src*="scdn.co"], img[src*="spotifycdn.com"], img');

                    if (titleEl && titleEl.innerText && titleEl.innerText.trim()) {
                        title = titleEl.innerText.trim();
                    }
                    if (artistEl && artistEl.innerText && artistEl.innerText.trim()) {
                        artist = artistEl.innerText.trim();
                    }
                    if (imgEl && imgEl.src && !imgEl.src.includes('data:image')) {
                        cover = imgEl.src;
                    }

                    allLeafTexts = Array.from(nowPlaying.querySelectorAll('*'))
                        .filter(el => el.children.length === 0 && el.textContent && el.textContent.trim().length > 0)
                        .map(el => ({
                            text: el.textContent.trim(),
                            tag: el.tagName,
                            testid: el.getAttribute('data-testid') || el.parentElement?.getAttribute('data-testid') || '',
                            encore: el.getAttribute('data-encore-id') || el.parentElement?.getAttribute('data-encore-id') || '',
                            cls: (el.className || '').toString().substring(0, 35)
                        }));

                    trackLinks = Array.from(nowPlaying.querySelectorAll('a[href*="/track/"]')).map(a => ({
                        text: (a.innerText || a.textContent || "").trim(),
                        href: a.href
                    }));

                    artistLinks = Array.from(nowPlaying.querySelectorAll('a[href*="/artist/"]')).map(a => ({
                        text: (a.innerText || a.textContent || "").trim(),
                        href: a.href
                    }));

                    coverImgs = Array.from(nowPlaying.querySelectorAll('img')).map(img => ({
                        src: img.src,
                        alt: img.alt,
                        testid: img.getAttribute('data-testid') || ''
                    })).filter(i => i.src && !i.src.includes('data:image'));

                    allButtons = Array.from(nowPlaying.querySelectorAll('button, [role="button"]')).map(b => ({
                        aria: b.getAttribute('aria-label') || '',
                        testid: b.getAttribute('data-testid') || '',
                        text: (b.innerText || '').trim()
                    }));
                }

                // Global Fallback per Link e Immagini se non trovati dentro la bar
                if (trackLinks.length === 0) {
                    trackLinks = Array.from(document.querySelectorAll('a[href*="/track/"]')).map(a => ({
                        text: (a.innerText || a.textContent || "").trim(),
                        href: a.href
                    })).filter(t => t.text.length > 0);
                }
                if (artistLinks.length === 0) {
                    artistLinks = Array.from(document.querySelectorAll('a[href*="/artist/"]')).map(a => ({
                        text: (a.innerText || a.textContent || "").trim(),
                        href: a.href
                    })).filter(a => a.text.length > 0);
                }
                if (coverImgs.length === 0) {
                    coverImgs = Array.from(document.querySelectorAll('img[data-testid="cover-art-image"], img[src*="scdn.co"], img[src*="spotifycdn.com"]')).map(img => ({
                        src: img.src,
                        alt: img.alt,
                        testid: img.getAttribute('data-testid') || ''
                    })).filter(i => i.src && !i.src.includes('data:image'));
                }

                // Fallback se title o artist non trovati dai selettori diretti
                if (!title) {
                    title = (msTitle && !ignoredWords.some(w => msTitle.toLowerCase().includes(w)) ? msTitle : "") || (trackLinks.length > 0 ? trackLinks[0].text : "");
                }
                if (!artist) {
                    artist = msArtist;
                }

                // Estrazione sequenziale ordinata dal player bar per titolo e artista
                if (allLeafTexts.length > 0) {
                    const validPlayerTexts = allLeafTexts.map(t => t.text).filter(txt => {
                        const txtLow = txt.toLowerCase().trim();
                        if (!txtLow) return false;
                        if (ignoredWords.some(w => txtLow === w || txtLow.includes("copyright") || txtLow.includes("company"))) return false;
                        if (/^\d{1,2}:\d{2}$/.test(txtLow)) return false;
                        return true;
                    });

                    if (validPlayerTexts.length > 0 && !title) {
                        title = validPlayerTexts[0];
                    }
                    if (validPlayerTexts.length > 1 && !artist) {
                        artist = validPlayerTexts[1];
                    }
                }

                // Fallback su artistLinks se l'artista è ancora vuoto
                if (!artist && artistLinks.length > 0) {
                    artist = artistLinks[0].text;
                }

                // Parse da document.title se presente e in formato "Canzone - Artista | Spotify"
                if (!title && document.title && document.title.includes(' | Spotify')) {
                    const cleanDoc = document.title.replace(' | Spotify', '').trim();
                    const cleanDocLow = cleanDoc.toLowerCase();
                    
                    if (!ignoredWords.some(w => cleanDocLow.includes(w))) {
                        if (cleanDoc.includes(' - ')) {
                            const parts = cleanDoc.split(' - ');
                            title = parts[0].trim();
                            if (!artist && parts[1]) artist = parts[1].trim();
                        } else if (cleanDoc.includes(' • ')) {
                            const parts = cleanDoc.split(' • ');
                            title = parts[0].trim();
                            if (!artist && parts[1]) artist = parts[1].trim();
                        }
                    }
                }

                // Filtro finale di sicurezza: azzera il titolo se è una parola generica da ignorare
                if (title) {
                    const tLow = title.toLowerCase().trim();
                    if (ignoredWords.some(w => tLow === w || tLow.includes("copyright") || tLow.includes("company"))) {
                        title = "";
                    }
                }

                if (!cover) {
                    cover = msArt || (coverImgs.length > 0 ? coverImgs[0].src : "");
                }

                const media = document.querySelector('audio, video');
                const mediaState = media ? {
                    paused: media.paused,
                    currentTime: media.currentTime,
                    duration: media.duration,
                    src: media.src
                } : null;

                // Rilevamento dello stato di riproduzione (Play / Pausa)
                const pauseBtn = document.querySelector('[data-testid="control-button-pause"], [data-testid*="pause" i], [aria-label*="paus" i], [title*="paus" i]');
                const playBtn = document.querySelector('[data-testid="control-button-play"], [data-testid*="play" i], [aria-label*="play" i], [aria-label*="riproduci" i], [title*="play" i], [title*="riproduci" i]');

                let isPlayingState = false;
                if (pauseBtn) {
                    isPlayingState = true;
                } else if (playBtn) {
                    isPlayingState = false;
                } else if (media) {
                    isPlayingState = !media.paused;
                } else if (title && title.length > 0) {
                    // Fallback: se un brano valido è caricato nel player di Spotify e non c'è un tasto Play esplicito in viste di pausa, assumiamo che stia riproducendo
                    isPlayingState = true;
                }

                let isPlaying = isPlayingState;
                let position = media ? Math.floor(media.currentTime * 1000) : 0;
                let duration = media ? Math.floor(media.duration * 1000) : 0;

                if (cover && cover.includes("00004851")) {
                    cover = cover.replace("00004851", "0000b273");
                }

                // Stampa di ispezione profonda nei log
                console.log("SpotifyDebug: ==== INSPECTOR SCAN #" + scanCounter + " ====");
                console.log("SpotifyDebug: Document Title -> " + document.title);
                console.log("SpotifyDebug: Player Bar Found -> " + (!!nowPlaying));
                console.log("SpotifyDebug: MediaSession -> Title: '" + msTitle + "', Artist: '" + msArtist + "', Art: '" + msArt + "'");
                console.log("SpotifyDebug: Player Leaf Texts -> " + JSON.stringify(allLeafTexts));
                console.log("SpotifyDebug: Track Links -> " + JSON.stringify(trackLinks));
                console.log("SpotifyDebug: Artist Links -> " + JSON.stringify(artistLinks));
                console.log("SpotifyDebug: Cover Images -> " + JSON.stringify(coverImgs));
                console.log("SpotifyDebug: Controls -> PlayBtn: " + (!!playBtn) + ", PauseBtn: " + (!!pauseBtn) + ", Playing: " + isPlaying);
                console.log("SpotifyDebug: HTML5 Media -> " + JSON.stringify(mediaState));
                console.log("SpotifyDebug: RESOLVED PAIR -> Title: '" + title + "', Artist: '" + artist + "', Cover: '" + cover + "', Playing: " + isPlaying);
                console.log("SpotifyDebug: ==========================================");

                return { title, artist, cover, isPlaying, position, duration };
            }

            window.scanSpotifyNow = function() {
                console.log("SpotifyDebug: Manual Scan Requested!");
                return scanSpotifyDOM();
            };

            const pollMedia = setInterval(() => {
                if (!window.location.host.includes('spotify.com')) return;

                const scan = scanSpotifyDOM();
                const title = scan.title;
                const artist = scan.artist;
                const cover = scan.cover;
                const isPlaying = scan.isPlaying;
                const position = scan.position;
                const duration = scan.duration;

                const lowerTitle = (title || "").toLowerCase();
                const shouldIgnore = !title || ignoredWords.some(ig => lowerTitle === ig || lowerTitle.includes("company") || lowerTitle.includes("copyright"));

                if (title && !shouldIgnore && (title !== lastTitle || scanCounter % 3 === 0)) {
                    lastTitle = title;
                    const payload = {
                        track: title,
                        artist: artist,
                        cover: cover,
                        playing: isPlaying,
                        position: isNaN(position) ? 0 : position,
                        duration: isNaN(duration) ? 0 : duration
                    };

                    console.log("SpotifyDebug: Sending payload to AndroidBridge -> " + JSON.stringify(payload));
                    if (window.AndroidBridge && typeof window.AndroidBridge.recMediaStatus === 'function') {
                        window.AndroidBridge.recMediaStatus(JSON.stringify(payload));
                    }
                }
            }, 1000);
            window.aabSpotifyIntervals.push(pollMedia);

        })();
        """.trimIndent()
    }
}
