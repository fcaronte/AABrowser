package com.fcaronte.aabrowser.utils

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

                if (promoAttempts >= 8) {
                    console.log("SpotifyDebug: Startup anti-promo task finished, shutting down interval.");
                    clearInterval(removeBanners);
                }
            }, 600);
            window.aabSpotifyIntervals.push(removeBanners);

            // Parser del tempo ("1:23" -> ms)
            function parseTimeToMs(timeStr) {
                if (!timeStr) return 0;
                const parts = timeStr.trim().split(':');
                if (parts.length === 2) {
                    const min = parseInt(parts[0], 10) || 0;
                    const sec = parseInt(parts[1], 10) || 0;
                    return (min * 60 + sec) * 1000;
                } else if (parts.length === 3) {
                    const hr = parseInt(parts[0], 10) || 0;
                    const min = parseInt(parts[1], 10) || 0;
                    const sec = parseInt(parts[2], 10) || 0;
                    return (hr * 3600 + min * 60 + sec) * 1000;
                }
                return 0;
            }

            function getSpotifyTimeState() {
                if (window.aabMediaPosition && window.aabMediaPosition.duration > 0) {
                    return window.aabMediaPosition;
                }

                const posEl = document.querySelector('[data-testid="playback-position"]');
                const durEl = document.querySelector('[data-testid="playback-duration"]');
                if (posEl && durEl) {
                    const p = parseTimeToMs(posEl.innerText || posEl.textContent);
                    const d = parseTimeToMs(durEl.innerText || durEl.textContent);
                    if (d > 0) return { position: p, duration: d };
                }

                const timeSpans = Array.from(document.querySelectorAll('span, div')).filter(el => {
                    const txt = (el.innerText || el.textContent || '').trim();
                    return /^\d{1,2}:\d{2}$/.test(txt);
                });

                if (timeSpans.length >= 2) {
                    const p = parseTimeToMs(timeSpans[0].innerText || timeSpans[0].textContent);
                    const d = parseTimeToMs(timeSpans[timeSpans.length - 1].innerText || timeSpans[timeSpans.length - 1].textContent);
                    if (d > 0) return { position: p, duration: d };
                }

                const pBar = document.querySelector('[role="progressbar"], [data-testid="playback-progressbar"]');
                if (pBar) {
                    const now = parseFloat(pBar.getAttribute('aria-valuenow'));
                    const max = parseFloat(pBar.getAttribute('aria-valuemax'));
                    if (!isNaN(now) && !isNaN(max) && max > 0) {
                        if (max > 10) {
                            return { position: Math.floor(now * 1000), duration: Math.floor(max * 1000) };
                        }
                    }
                }

                const media = document.querySelector('audio, video');
                if (media && isFinite(media.duration) && media.duration > 0) {
                    return { position: Math.floor(media.currentTime * 1000), duration: Math.floor(media.duration * 1000) };
                }

                return { position: 0, duration: 0 };
            }

            // 3. Scanner DOM avanzato e Poller per Spotify
            let lastTitle = "";
            let lastArtist = "";
            let lastPlaying = null;
            let lastPosition = -1;
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
                    const titleEl = nowPlaying.querySelector('[data-testid="context-item-info-title"], [data-testid="track-info-name"], [data-testid="entity-title"]');
                    const artistEl = nowPlaying.querySelector('[data-testid="context-item-info-subtitle"], [data-testid="track-info-artists"], [data-testid="entity-subtitle"]');
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

                // 1. Estrazione PRIORITARIA dai testi foglia della barra del player (nowPlaying)
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

                // Se il titolo contiene righe multiple (es. "Mediterranea\nIrama"), dividi pulito
                if (title && title.includes('\n')) {
                    const titleLines = title.split('\n').map(s => s.trim()).filter(Boolean);
                    title = titleLines[0];
                    if (!artist && titleLines.length > 1) {
                        artist = titleLines[1];
                    }
                }

                // Fallback MediaSession / Document Title se il player bar è privo di dati
                if (!title) {
                    title = (msTitle && !ignoredWords.some(w => msTitle.toLowerCase().includes(w)) ? msTitle : "");
                }
                if (!artist) {
                    artist = msArtist;
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

                // Mantieni l'ultimo artista valido per evitare oscillazioni se il DOM si ricarica per 1 frame
                if (!artist && lastArtist && title === lastTitle) {
                    artist = lastArtist;
                }
                if (artist) {
                    lastArtist = artist;
                }

                // Filtro finale di sicurezza: azzera il titolo se è una parola generica da ignorare
                if (title) {
                    const tLow = title.toLowerCase().trim();
                    if (ignoredWords.some(w => tLow === w || tLow.includes("copyright") || tLow.includes("company"))) {
                        title = "";
                    }
                }

                if (!cover) {
                    cover = msArt || (coverImgs.length > 0 ? (coverImgs[0].src || coverImgs[0]) : "");
                }

                const media = document.querySelector('audio, video');

                // Rilevamento dello stato di riproduzione (Play / Pausa)
                const pauseBtn = document.querySelector('[data-testid="control-button-pause"], button[aria-label*="paus" i], button[aria-label*="pause" i]');
                const playBtn = document.querySelector('[data-testid="control-button-play"], button[aria-label*="riproduci" i], button[aria-label*="play" i]');

                let isPlaying = false;
                if (pauseBtn && !playBtn) {
                    isPlaying = true;
                } else if (playBtn) {
                    isPlaying = false;
                } else if (media) {
                    isPlaying = !media.paused;
                } else {
                    const playPauseBtn = document.querySelector('[data-testid="control-button-playpause"]');
                    if (playPauseBtn) {
                        const aria = (playPauseBtn.getAttribute('aria-label') || '').toLowerCase();
                        if (aria.includes('paus')) {
                            isPlaying = true;
                        } else if (aria.includes('play') || aria.includes('riproduci')) {
                            isPlaying = false;
                        }
                    } else if (title && title.length > 0) {
                        isPlaying = true;
                    }
                }

                if (cover && cover.includes("00004851")) {
                    cover = cover.replace("0000b273", "0000b273");
                }

                // Estrazione posizione e durata (timeline)
                const timeState = getSpotifyTimeState();
                let position = timeState.position;
                let duration = timeState.duration;

                // Stampa di ispezione profonda nei log
                console.log("SpotifyDebug: ==== INSPECTOR SCAN #" + scanCounter + " ====");
                console.log("SpotifyDebug: Document Title -> " + document.title);
                console.log("SpotifyDebug: Player Bar Found -> " + (!!nowPlaying));
                console.log("SpotifyDebug: MediaSession -> Title: '" + msTitle + "', Artist: '" + msArtist + "', Art: '" + msArt + "'");
                console.log("SpotifyDebug: Controls -> PlayBtn: " + (!!playBtn) + ", PauseBtn: " + (!!pauseBtn) + ", Playing: " + isPlaying);
                console.log("SpotifyDebug: Timeline -> Position: " + position + "ms, Duration: " + duration + "ms");
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

                const positionChanged = Math.abs(position - lastPosition) > 800;

                if (title && !shouldIgnore && (title !== lastTitle || artist !== lastArtist || isPlaying !== lastPlaying || positionChanged)) {
                    lastTitle = title;
                    lastArtist = artist;
                    lastPlaying = isPlaying;
                    lastPosition = position;
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
            }, 500);
            window.aabSpotifyIntervals.push(pollMedia);

        })();
        """.trimIndent()
    }
}
