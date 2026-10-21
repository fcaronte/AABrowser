package com.fcaronte.aabrowser.utils

object AdBlockJavascript {

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
                // Audio di nuovo attivo solo se l'avevamo tolto noi per la pubblicità: non i video che l'utente
                // ha messo in muto e non le anteprime mute (si sentirebbero e prenderebbero il player dell'auto)
                if (video.dataset.aabAdMuted === 'true') {
                    video.muted = false;
                    delete video.dataset.aabAdMuted;
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