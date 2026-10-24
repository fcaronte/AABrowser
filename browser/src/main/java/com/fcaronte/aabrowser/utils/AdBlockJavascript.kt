package com.fcaronte.aabrowser.utils

object AdBlockJavascript {

    /** Origini dello script che toglie le pubblicità dai dati del player (inizio documento). */
    val YOUTUBE_ORIGINS = setOf(
        "https://youtube.com", "https://*.youtube.com",
        "https://youtubekids.com", "https://*.youtubekids.com"
    )

    /**
     * Prima difesa contro le pubblicità di YouTube, all'inizio di ogni documento: toglie i dati delle pubblicità
     * dalle risposte del player (dati iniziali della pagina, fetch e XMLHttpRequest di /youtubei/v1/player,
     * get_watch, pbj=1), così il player non sa di averne e non le carica. Le pubblicità che passano lo stesso
     * (per esempio unite al video dal server) restano allo script di [getYouTubeAdBlockScript]: 16x, muto, Salta.
     * Si attiva solo con l'AdBlock di YouTube e la rimozione anticipata accesi e il sito fuori dalla whitelist
     * (AndroidBridge.isYouTubeAdPruneOn). Con le pubblicità che YouTube inserisce nel video dal server, toglierne i dati
     * lascia lo schermo nero per la loro durata (succede anche con uBlock Origin): per questo si può spegnere.
     * Diagnostica nel logcat ("chromium"): cosa è stato tolto e dopo quanto parte il video.
     */
    fun getYouTubeAdPruneScript(): String = """
    (function() {
        if (window.__aabAdPrune) return;
        window.__aabAdPrune = true;
        try {
            const bridge = window.AndroidBridge;
            if (!bridge || typeof bridge.isYouTubeAdPruneOn !== 'function' || !bridge.isYouTubeAdPruneOn()) return;
        } catch (e) {
            return;
        }

        const AD_KEYS = ['adPlacements', 'adSlots', 'playerAds', 'adBreakHeartbeatParams'];

        // Cosa c'era (solo per il log): numero di pubblicità, tipi di posizionamento e di slot. Mai un errore qui:
        // la rimozione deve avvenire comunque
        function describe(o, found) {
            try {
                describeAds(o, found);
            } catch (e) {}
        }

        function describeAds(o, found) {
            (o.adPlacements || []).forEach(function(item) {
                const renderer = item && item.adPlacementRenderer;
                const kind = renderer && renderer.config && renderer.config.adPlacementConfig &&
                    renderer.config.adPlacementConfig.kind;
                found.placements.push((kind || '?').replace('AD_PLACEMENT_KIND_', '') + ':' +
                    Object.keys((renderer && renderer.renderer) || {}).join('+'));
            });
            (o.adSlots || []).forEach(function(item) {
                const slot = item && item.adSlotRenderer;
                const type = slot && slot.adSlotMetadata && slot.adSlotMetadata.slotType;
                const layout = slot && slot.fulfillmentContent && slot.fulfillmentContent.fulfilledLayout;
                found.slots.push((type || '?').replace('SLOT_TYPE_', '') + ':' + Object.keys(layout || {}).join('+'));
            });
            if (o.playerAds) found.playerAds += o.playerAds.length || 1;
            if (o.streamingData && o.streamingData.serverAbrStreamingUrl) found.sabr = true;
            if (o.videoDetails && o.videoDetails.videoId) found.video = o.videoDetails.videoId;
        }

        function pruneObject(o, found) {
            if (!o || typeof o !== 'object') return;
            if (found) describe(o, found);
            for (const key of AD_KEYS) {
                if (Object.prototype.hasOwnProperty.call(o, key)) delete o[key];
            }
            // Avviso "gli adblocker non sono consentiti", che arriva insieme ai dati del player
            const renderers = o.auxiliaryUi && o.auxiliaryUi.messageRenderers;
            if (renderers && typeof renderers === 'object') {
                delete renderers.bkaEnforcementMessageViewModel;
                delete renderers.enforcementMessageViewModel;
            }
        }

        // Risposta del player in cima, dentro playerResponse o in un elenco (get_watch, pbj=1)
        function pruneInto(data, found) {
            if (!data || typeof data !== 'object') return;
            if (Array.isArray(data)) {
                data.forEach(function(item) { pruneInto(item, found); });
                return;
            }
            pruneObject(data, found);
            if (data.playerResponse) pruneObject(data.playerResponse, found);
        }

        function prune(data) {
            const found = { placements: [], slots: [], playerAds: 0, sabr: false, video: '' };
            pruneInto(data, found);
            if (found.placements.length || found.slots.length || found.playerAds) {
                console.log('AABrowser: ad data removed video=' + found.video + ' sabr=' + found.sabr +
                    ' placements=[' + found.placements.join(', ') + '] slots=[' + found.slots.join(', ') +
                    '] playerAds=' + found.playerAds);
                watchStart(found.video);
            }
            return data;
        }

        // Dopo una rimozione: quando parte davvero il video. Righe solo ai cambi (stato del player, media fermo o in
        // movimento), poi "content playing after N s": un nero lungo qui è la pubblicità inserita nel video
        let watchTimer = null;
        function watchStart(videoId) {
            if (watchTimer) clearInterval(watchTimer);
            const startedAt = Date.now();
            let lastKey = '';
            let lastMedia = -1;
            let lastContent = -1;
            watchTimer = setInterval(function() {
                const elapsed = Math.round((Date.now() - startedAt) / 1000);
                const player = document.querySelector('#movie_player');
                const video = document.querySelector('#movie_player video') || document.querySelector('video');
                if (!player || !video) {
                    if (elapsed > 45) clearInterval(watchTimer);
                    return;
                }
                const content = typeof player.getCurrentTime === 'function' ? player.getCurrentTime() : -1;
                const state = typeof player.getPlayerState === 'function' ? player.getPlayerState() : -9;
                const ad = player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting');
                const mediaMoving = lastMedia >= 0 && video.currentTime > lastMedia + 0.3;
                const contentMoving = lastContent >= 0 && content > lastContent + 0.3;
                lastMedia = video.currentTime;
                lastContent = content;
                const key = [state, ad, video.paused, video.readyState, mediaMoving, contentMoving].join(',');
                if (key !== lastKey) {
                    lastKey = key;
                    console.log('AABrowser: after ad removal +' + elapsed + 's state=' + state + ' ad=' + ad +
                        ' paused=' + video.paused + ' ready=' + video.readyState +
                        ' media=' + video.currentTime.toFixed(1) + '/' + Math.round(video.duration || 0) +
                        (mediaMoving ? ' moving' : ' still') + ' content=' + content.toFixed(1) +
                        (contentMoving ? ' moving' : ' still'));
                }
                if (contentMoving || elapsed > 45) {
                    console.log('AABrowser: content ' + (contentMoving ? 'playing after ' + elapsed + ' s' :
                        'not playing after 45 s') + ' video=' + videoId);
                    clearInterval(watchTimer);
                }
            }, 1000);
        }

        function pruneText(text) {
            try {
                return JSON.stringify(prune(JSON.parse(text)));
            } catch (e) {
                return text;
            }
        }

        // 1. Dati iniziali scritti nell'HTML della pagina (var ytInitialPlayerResponse = {...})
        let initialPlayerResponse;
        try {
            Object.defineProperty(window, 'ytInitialPlayerResponse', {
                configurable: true,
                get: function() { return initialPlayerResponse; },
                set: function(value) { initialPlayerResponse = prune(value); }
            });
        } catch (e) {}

        const PLAYER_URL = /\/youtubei\/v1\/(player|get_watch)\b|[?&]pbj=1/;

        // 2. fetch: la risposta del player si legge, si ripulisce e si ricrea uguale
        const originalFetch = window.fetch;
        if (typeof originalFetch === 'function') {
            window.fetch = function(input) {
                const url = typeof input === 'string' ? input : ((input && input.url) || String(input || ''));
                const pending = originalFetch.apply(this, arguments);
                if (!PLAYER_URL.test(url)) return pending;
                return pending.then(function(response) {
                    const type = response.headers.get('content-type') || '';
                    if (!response.ok || type.indexOf('json') === -1) return response;
                    return response.clone().text().then(function(text) {
                        const headers = new Headers(response.headers);
                        headers.delete('content-length');
                        headers.delete('content-encoding');
                        const cleaned = new Response(pruneText(text), {
                            status: response.status,
                            statusText: response.statusText,
                            headers: headers
                        });
                        Object.defineProperty(cleaned, 'url', { value: response.url });
                        return cleaned;
                    }, function() { return response; });
                });
            };
        }

        // 3. Le stesse richieste fatte con XMLHttpRequest
        const xhrProto = XMLHttpRequest.prototype;
        const originalOpen = xhrProto.open;
        xhrProto.open = function(method, url) {
            this.__aabPrune = PLAYER_URL.test(String(url || ''));
            return originalOpen.apply(this, arguments);
        };

        function cleanedText(xhr, raw) {
            if (!xhr.__aabPrune || xhr.readyState !== 4 || typeof raw !== 'string') return raw;
            if (xhr.__aabRaw !== raw) {
                xhr.__aabRaw = raw;
                xhr.__aabCleaned = pruneText(raw);
            }
            return xhr.__aabCleaned;
        }

        const textGetter = (Object.getOwnPropertyDescriptor(xhrProto, 'responseText') || {}).get;
        if (textGetter) {
            Object.defineProperty(xhrProto, 'responseText', {
                configurable: true,
                get: function() { return cleanedText(this, textGetter.call(this)); }
            });
        }
        const responseGetter = (Object.getOwnPropertyDescriptor(xhrProto, 'response') || {}).get;
        if (responseGetter) {
            Object.defineProperty(xhrProto, 'response', {
                configurable: true,
                get: function() {
                    const raw = responseGetter.call(this);
                    if (!this.__aabPrune || this.readyState !== 4) return raw;
                    if (typeof raw === 'string') return cleanedText(this, raw);
                    if (raw && typeof raw === 'object' && this.responseType === 'json') return prune(raw);
                    return raw;
                }
            });
        }

        console.log('AABrowser: YouTube ad data pruning active');
    })();
    """.trimIndent()

    fun getYouTubeAdBlockScript(): String {
        return """
    (function() {
        if (window.aabIntervals) {
            window.aabIntervals.forEach(clearInterval);
        }
        window.aabIntervals = [];
        // Lo script si inietta a ogni pagina finita: via l'observer e gli eventi messi la volta prima
        if (window.aabAdObserver) window.aabAdObserver.disconnect();
        window.aabAdObserver = null;
        if (window.aabAdNavHandler) {
            ['yt-navigate-finish', 'spfdone', 'popstate'].forEach(name => {
                window.removeEventListener(name, window.aabAdNavHandler);
                document.removeEventListener(name, window.aabAdNavHandler);
            });
        }

        console.log("AABrowser: YouTube AdBlock Active (v13)");

        // Non offsetParent: è null anche dentro i contenitori "fixed", come il player di m.youtube.com,
        // e il tasto "Salta" non veniva mai trovato
        const isVisible = (el) => {
            if (!el || !el.isConnected) return false;
            const rect = el.getBoundingClientRect();
            if (rect.width <= 0 || rect.height <= 0) return false;
            const style = getComputedStyle(el);
            return style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0';
        };

        // Un semplice click() a volte viene ignorato: sequenza completa come un tocco vero
        function pressButton(el) {
            try {
                const rect = el.getBoundingClientRect();
                const init = {
                    bubbles: true, cancelable: true, view: window,
                    clientX: rect.left + rect.width / 2, clientY: rect.top + rect.height / 2
                };
                ['pointerdown', 'mousedown', 'pointerup', 'mouseup'].forEach(type => {
                    const EventType = type.indexOf('pointer') === 0 && window.PointerEvent ? PointerEvent : MouseEvent;
                    el.dispatchEvent(new EventType(type, init));
                });
                el.click();
            } catch (e) {}
        }

        // Il <video> del player: sulla home di m.youtube.com prima nel DOM ci possono essere le anteprime
        function getMainVideo(player) {
            return (player && player.querySelector('video')) || document.querySelector('video');
        }

        // --- Velocità: pubblicità 16x, musica 1x fissa, gli altri video la velocità scelta dall'utente ---
        const MIN_USER_SPEED = 0.25;
        const MAX_USER_SPEED = 4.0;

        const getPlayer = () => document.querySelector('#movie_player') || document.querySelector('.html5-video-player');

        function isMusicContent() {
            if (window.location.host.includes('music.youtube.com')) return true;
            if ((document.title || '').toLowerCase().includes('official music video')) return true;
            // Categoria del video: non dipende dalla lingua del titolo
            try {
                const player = getPlayer();
                const response = player && typeof player.getPlayerResponse === 'function' ? player.getPlayerResponse() : null;
                const micro = response && response.microformat && response.microformat.playerMicroformatRenderer;
                if (micro && micro.category === 'Music') return true;
            } catch (e) {}
            return false;
        }

        function getSavedSpeed() {
            const saved = parseFloat(localStorage.getItem('yt-custom-speed') || '1.0');
            return isFinite(saved) && saved >= MIN_USER_SPEED && saved <= MAX_USER_SPEED ? saved : 1.0;
        }

        function saveSpeed(rate) {
            if (Math.abs(getSavedSpeed() - rate) < 0.01) return;
            localStorage.setItem('yt-custom-speed', rate.toString());
            console.log('AABrowser AdBlock: saved yt-custom-speed = ' + rate);
            // Persistenza globale lato Android (sopravvive alla chiusura dell'app)
            if (window.AndroidBridge && typeof window.AndroidBridge.saveYouTubeSpeed === 'function') {
                window.AndroidBridge.saveYouTubeSpeed(rate);
            }
        }

        // Velocità impostata da noi: il ratechange che segue non è una scelta dell'utente.
        // Passa anche dall'API del player, così il menu di YouTube resta allineato e non la riporta a 1x
        function applyRate(video, rate) {
            window.aabExpectedRate = rate;
            window.aabExpectedRateTime = Date.now();
            const player = getPlayer();
            if (rate <= 2.0 && player && typeof player.setPlaybackRate === 'function') {
                try { player.setPlaybackRate(rate); } catch (e) {}
            }
            if (Math.abs(video.playbackRate - rate) > 0.01) video.playbackRate = rate;
        }

        // YouTube riusa lo stesso <video> per tutti i video: id e sorgente indicano quale video sta suonando
        function videoKey(video) {
            const id = new URLSearchParams(window.location.search).get('v') || '';
            return window.location.pathname + '|' + id + '|' + (video.currentSrc || '');
        }

        function trackUserSpeed(video) {
            if (video.dataset.aabRateListener) return;
            video.dataset.aabRateListener = 'true';
            video.addEventListener('ratechange', () => {
                const rate = video.playbackRate;
                const ours = Math.abs(rate - (window.aabExpectedRate || 0)) < 0.01 &&
                    Date.now() - (window.aabExpectedRateTime || 0) < 1500;
                // Non sono scelte dell'utente: i nostri cambi, pubblicità e musica (velocità fisse)
                // e il reset a 1x che YouTube fa su un video nuovo (non ancora inizializzato da noi)
                if (ours || window.aabIsAdPlaying || isMusicContent()) return;
                if (video.dataset.aabSpeedKey !== videoKey(video)) return;
                if (rate < MIN_USER_SPEED || rate > MAX_USER_SPEED) return;
                saveSpeed(rate);
            });
        }

        // Controllo della pubblicità: lo chiamano l'observer (subito), gli eventi di navigazione e il giro ogni 500 ms
        function checkAds() {
            if (!window.location.host.includes('youtube.com') && !window.location.host.includes('youtu.be') && !window.location.host.includes('youtubekids.com')) return;
            const isMusic = window.location.host.includes('music.youtube.com');
            const player = document.querySelector('#movie_player') || document.querySelector('.html5-video-player') || document.querySelector('ytmusic-player');
            const video = getMainVideo(player);
            if (!video) return;

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

            // Durante la pubblicità il tasto vale anche se risulta nascosto: sta dentro .video-ads / .ytp-ad-module,
            // che nascondiamo noi più sotto (hideSelectors), e così non veniva mai premuto. click() funziona lo stesso
            if (!skipBtn && adShowing) {
                skipBtn = skipBtnSelectors.map(s => document.querySelector(s)).find(Boolean);
            }

            // Ricerca per testo (più pesante: legge tutti i pulsanti) solo durante una pubblicità
            if (!skipBtn && adShowing) {
                const buttons = document.querySelectorAll('button, [role="button"], .ytmusic-skip-ad-button');
                for (const btn of buttons) {
                    const t = (btn.innerText || "").toLowerCase();
                    const aria = (btn.getAttribute('aria-label') || "").toLowerCase();
                    const skipWords = ["skip", "salta", "saltar", "passer", "überspringen", "pular", "пропустить", "overslaan", "pomiń"];
                    if (skipWords.some(w => t.includes(w) || aria.includes(w)) && isVisible(btn)) {
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
                if (!video.muted) {
                    video.muted = true;
                    video.dataset.aabAdMuted = 'true';
                }
                video.playbackRate = 16.0;

                // Pubblicità lunghe non saltabili: anche a 16x durano minuti, o si fermano a caricare perché il
                // buffer non tiene il passo. Il video della pubblicità va alla fine e YouTube passa a quello vero.
                // Solo se lo dice il player stesso (classe letta adesso), mai su un video che non è pubblicità
                const classAd = player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'));
                const adDuration = video.duration;
                if (classAd && isFinite(adDuration) && adDuration > 1 && video.currentTime < adDuration - 1) {
                    try { video.currentTime = adDuration - 0.1; } catch (e) {}
                }

                if (skipBtn) {
                    const isDisabled = skipBtn.disabled || skipBtn.getAttribute('aria-disabled') === 'true';
                    // Al massimo ogni 300 ms: l'observer richiama il controllo a ogni modifica del player
                    if (!isDisabled && Date.now() - (window.aabLastSkipPress || 0) > 300) {
                        window.aabLastSkipPress = Date.now();
                        pressButton(skipBtn);
                        // Trovato il contenitore: va premuto anche il pulsante vero e proprio
                        const inner = skipBtn.querySelector('button');
                        if (inner && inner !== skipBtn) pressButton(inner);
                    }
                }

                if (video.paused) video.play().catch(() => {});
            } else {
                if (window.aabIsAdPlaying) {
                    window.aabIsAdPlaying = false;

                    // Ritardo di assestamento per permettere a YouTube di ripristinare il timing del video principale
                    // ed evitare il salto a inizio video o il disalignamento della seekbar.
                    setTimeout(() => {
                        if (!window.aabIsAdPlaying && video) {
                            applyRate(video, isMusicContent() ? 1.0 : getSavedSpeed());
                            if (window.AndroidBridge) {
                                AndroidBridge.onMediaStatusChanged(!video.paused, video.currentTime, video.playbackRate);
                            }
                        }
                    }, 350);
                }
                // Audio di nuovo attivo se l'avevamo tolto noi per la pubblicità
                if (video.dataset.aabAdMuted === 'true') {
                    video.muted = false;
                    delete video.dataset.aabAdMuted;
                } else if (!video.paused && window.location.pathname === '/watch' &&
                    video.dataset.aabStartAudioKey !== videoKey(video)) {
                    // YouTube fa partire in muto il video aperto senza un tocco (ricaricamento, comando vocale): si
                    // controlla una volta sola per video, all'inizio e solo nella pagina del video. Mai le anteprime
                    // mute della home (prenderebbero il player dell'auto) e mai un muto messo dall'utente dopo
                    video.dataset.aabStartAudioKey = videoKey(video);
                    if (video.muted && video.currentTime < 5) {
                        if (player && typeof player.unMute === 'function') player.unMute();
                        video.muted = false;
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
        }

        // Reazione immediata: il player riceve la classe "ad-showing" appena parte una pubblicità e il tasto
        // "Salta" compare nel suo modulo. Col solo giro ogni 500 ms i bumper da 5 s partivano con l'audio
        let checkScheduled = false;
        function scheduleCheck() {
            if (checkScheduled) return;
            checkScheduled = true;
            // Una volta sola per gruppo di modifiche
            Promise.resolve().then(() => {
                checkScheduled = false;
                checkAds();
            });
        }

        let observedPlayer = null;
        function attachObserver() {
            const player = document.querySelector('#movie_player') || document.querySelector('.html5-video-player') || document.querySelector('ytmusic-player');
            if (!player || player === observedPlayer) return;
            if (window.aabAdObserver) window.aabAdObserver.disconnect();
            observedPlayer = player;
            window.aabAdObserver = new MutationObserver(scheduleCheck);
            window.aabAdObserver.observe(player, {
                attributes: true, attributeFilter: ['class'], childList: true, subtree: true
            });
            scheduleCheck();
        }

        // YouTube cambia video senza ricaricare la pagina (SPA): il player può essere un elemento nuovo
        window.aabAdNavHandler = () => {
            attachObserver();
            scheduleCheck();
        };
        ['yt-navigate-finish', 'spfdone', 'popstate'].forEach(name => {
            window.addEventListener(name, window.aabAdNavHandler);
            document.addEventListener(name, window.aabAdNavHandler);
        });
        attachObserver();

        // Riserva: ricollega l'observer se il player è cambiato e controlla comunque
        const mainLoop = setInterval(() => {
            attachObserver();
            checkAds();
        }, 500);
        window.aabIntervals.push(mainLoop);

        const slowLoop = setInterval(() => {
            if (!window.location.host.includes('youtube.com') && !window.location.host.includes('youtu.be')) return;
            const video = getMainVideo(getPlayer());
            if (video && !window.aabIsAdPlaying) {
                trackUserSpeed(video);
                const key = videoKey(video);
                if (isMusicContent()) {
                    if (Math.abs(video.playbackRate - 1.0) > 0.01) applyRate(video, 1.0);
                    video.dataset.aabSpeedKey = key;
                } else if (video.dataset.aabSpeedKey !== key && !video.paused && video.currentTime > 0.5) {
                    // Video nuovo (YouTube lo fa ripartire da 1x): appena parte riceve la velocità salvata.
                    // Se è già diversa da 1x l'ha scelta l'utente (o YouTube la ricordava): la rispettiamo
                    video.dataset.aabSpeedKey = key;
                    const savedSpeed = getSavedSpeed();
                    if (Math.abs(video.playbackRate - 1.0) < 0.01 && Math.abs(savedSpeed - 1.0) > 0.01) {
                        applyRate(video, savedSpeed);
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