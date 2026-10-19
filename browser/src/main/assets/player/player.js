/*
 * AAB Media, il lettore locale di AABrowser.
 *
 * I file arrivano dall'app (LocalPlayer su https://player.local/): cartella scelta sul telefono, richieste Range
 * per spostarsi nel brano, tag e copertina. La Media Session (titolo, copertina, avanti/indietro) collega la pagina
 * al player di Android Auto tramite gli script che l'app inietta in ogni pagina.
 *
 * Audio e video hanno code separate (schede): avanti, indietro, casuale e ripeti restano sempre nella coda del
 * file in riproduzione, così dopo una canzone non parte un video e viceversa.
 *
 * Opzioni (salvate nella pagina): dissolvenza tra i brani audio ed effetti animati sulla copertina.
 */
(function () {
    'use strict';

    const config = window.AAB_PLAYER || { token: '', strings: {} };
    const text = (key, fallback) => (config.strings && config.strings[key]) || fallback;
    const withToken = (path) => path + (path.indexOf('?') >= 0 ? '&' : '?') + 't=' + encodeURIComponent(config.token);

    const byId = (id) => document.getElementById(id);
    const media = byId('media');
    const seek = byId('seek');
    const list = byId('tracks');

    const STATE_KEY = 'aabPlayerState';
    const CROSSFADE_CHOICES = [0, 3, 6, 10];
    const EFFECT_CHOICES = ['off', 'bars', 'wave', 'circle'];
    const state = Object.assign(
        { shuffle: false, repeat: 'all', lastId: null, lastTime: 0, crossfade: 0, effect: 'off' },
        readState());
    const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });

    let lists = { audio: [], video: [] };   // file della cartella, già ordinati
    let viewKind = 'audio';                 // scheda mostrata
    let queueKind = 'audio';                // scheda della coda in riproduzione
    let order = [];      // indici di lists[queueKind] nell'ordine di riproduzione
    let position = -1;   // posizione in order del brano corrente
    let current = null;
    let pendingSeek = 0;
    let isSeeking = false;
    let lastStateSave = 0;
    let consecutiveErrors = 0;
    let lastSourceChange = 0;

    function readState() {
        try {
            return JSON.parse(localStorage.getItem(STATE_KEY)) || {};
        } catch (e) {
            return {};
        }
    }

    function saveState() {
        try {
            localStorage.setItem(STATE_KEY, JSON.stringify(state));
        } catch (e) {}
    }

    function formatTime(seconds) {
        if (!isFinite(seconds) || seconds < 0) return '0:00';
        const total = Math.floor(seconds);
        const hours = Math.floor(total / 3600);
        const minutes = Math.floor((total % 3600) / 60);
        const secs = String(total % 60).padStart(2, '0');
        return hours > 0 ? hours + ':' + String(minutes).padStart(2, '0') + ':' + secs : minutes + ':' + secs;
    }

    function showMessage(message) {
        const box = byId('message');
        box.textContent = message || '';
        box.hidden = !message;
    }

    // --- Cartella e coda ---

    async function loadLibrary(keepCurrent) {
        showMessage(text('loading', 'Loading…'));
        let data;
        try {
            const response = await fetch(withToken('/api/library'), { cache: 'no-store' });
            // Token di un avvio precedente dell'app: ricaricando la pagina ne arriva uno nuovo
            if (response.status === 403) {
                location.reload();
                return;
            }
            data = await response.json();
        } catch (e) {
            showMessage(text('emptyFolder', 'No audio or video files in this folder.'));
            return;
        }

        byId('folderName').textContent = data.folder || '';
        const previousId = keepCurrent && current ? current.id : state.lastId;
        const all = (data.tracks || []).sort((a, b) =>
            collator.compare(a.folder, b.folder) || collator.compare(a.name, b.name));
        lists = {
            audio: all.filter((track) => kindOf(track) === 'audio'),
            video: all.filter((track) => kindOf(track) === 'video')
        };

        // Si riparte dalla coda dell'ultimo file (all'avvio anche dalla sua scheda); se non c'è più, da una non vuota
        const previous = all.find((track) => track.id === previousId) || null;
        if (previous) {
            queueKind = kindOf(previous);
            if (!keepCurrent) viewKind = queueKind;
        } else {
            queueKind = lists[viewKind].length > 0 ? viewKind : otherKind(viewKind);
            viewKind = queueKind;
        }
        if (lists[viewKind].length === 0) viewKind = otherKind(viewKind);
        renderTabs();
        renderList();

        if (!data.folder) {
            showMessage(text('noFolder', 'No folder yet: choose one on the phone.'));
            return;
        }
        if (all.length === 0) {
            showMessage(text('emptyFolder', 'No audio or video files in this folder.'));
            return;
        }
        showMessage('');

        const index = previous ? lists[queueKind].indexOf(previous) : -1;
        // Cartella riletta mentre suona un brano che c'è ancora: si continua senza interruzioni
        if (keepCurrent && current && index >= 0) {
            current = previous;
            buildOrder(index);
            highlight();
            return;
        }
        buildOrder(index >= 0 ? index : 0);
        select(order[position], index >= 0 ? state.lastTime : 0, false);
    }

    function kindOf(track) {
        return track && track.kind === 'video' ? 'video' : 'audio';
    }

    function otherKind(kind) {
        return kind === 'video' ? 'audio' : 'video';
    }

    function buildOrder(startIndex) {
        const start = Math.max(0, startIndex);
        order = lists[queueKind].map((_, index) => index);
        if (state.shuffle && order.length > 1) {
            for (let i = order.length - 1; i > 0; i--) {
                const j = Math.floor(Math.random() * (i + 1));
                const swap = order[i];
                order[i] = order[j];
                order[j] = swap;
            }
            // Il brano di partenza resta il primo della coda casuale
            const at = order.indexOf(start);
            if (at > 0) {
                order.splice(at, 1);
                order.unshift(start);
            }
            position = 0;
        } else {
            position = start;
        }
    }

    function renderTabs() {
        byId('tabs').hidden = !(lists.audio.length > 0 && lists.video.length > 0);
        [['audio', byId('tabAudio'), 'Audio'], ['video', byId('tabVideo'), 'Video']].forEach(([kind, tab, fallback]) => {
            tab.textContent = text(kind, fallback);
            tab.dataset.count = String(lists[kind].length);
            tab.classList.toggle('active', kind === viewKind);
        });
    }

    // Cambiare scheda mostra solo l'altro elenco: la coda in riproduzione cambia toccando un file
    function showKind(kind) {
        if (kind === viewKind) return;
        viewKind = kind;
        renderTabs();
        renderList();
        byId('listPanel').scrollTop = 0;
        highlight();
    }

    function renderList() {
        list.textContent = '';
        const fragment = document.createDocumentFragment();
        lists[viewKind].forEach((track, index) => {
            const item = document.createElement('li');
            item.dataset.index = String(index);
            const name = document.createElement('div');
            name.className = 'track-name';
            name.textContent = track.name;
            item.appendChild(name);
            if (track.folder) {
                const folder = document.createElement('div');
                folder.className = 'track-folder';
                folder.textContent = track.folder;
                item.appendChild(folder);
            }
            fragment.appendChild(item);
        });
        list.appendChild(fragment);
    }

    function highlight() {
        list.querySelectorAll('li.current').forEach((item) => item.classList.remove('current'));
        const index = current ? lists[viewKind].indexOf(current) : -1;
        if (index < 0) return;
        const item = list.querySelector('li[data-index="' + index + '"]');
        if (item) {
            item.classList.add('current');
            item.scrollIntoView({ block: 'nearest' });
        }
    }

    // --- Riproduzione ---

    // fadingIn = brano che entra in dissolvenza: il precedente sta ancora sfumando sull'altro lettore
    function select(index, startTime, autoplay, fadingIn) {
        const track = lists[queueKind][index];
        if (!track) return;
        if (!fadingIn) stopFade();
        current = track;
        state.lastId = track.id;
        state.lastTime = startTime || 0;
        saveState();

        pendingSeek = startTime > 0 ? startTime : 0;
        fadePrepared = false;
        lastSourceChange = Date.now();
        document.body.classList.toggle('video-mode', kindOf(track) === 'video');
        media.src = withToken('/media/' + track.id);
        seek.value = '0';
        byId('currentTime').textContent = formatTime(pendingSeek);
        byId('totalTime').textContent = '0:00';
        showTrack(track, null);
        loadTags(track);
        highlight();
        updateEffects();
        if (autoplay) start();
    }

    function start() {
        if (!current) return;
        const attempt = media.play();
        if (attempt && attempt.catch) attempt.catch(() => {});
    }

    function next(automatic, fadingIn) {
        if (order.length === 0) return;
        if (automatic && state.repeat === 'one') {
            media.currentTime = 0;
            start();
            return;
        }
        let nextPosition = position + 1;
        if (nextPosition >= order.length) {
            // Fine della coda: si ricomincia solo con "ripeti tutto" (o se è l'utente a chiedere il successivo)
            if (automatic && state.repeat === 'off') {
                media.pause();
                return;
            }
            nextPosition = 0;
        }
        position = nextPosition;
        select(order[position], 0, true, fadingIn);
    }

    function previous() {
        if (order.length === 0) return;
        stopFade();
        // Come i lettori musicali: dopo i primi secondi "indietro" riparte dall'inizio del brano
        if (media.currentTime > 3) {
            media.currentTime = 0;
            return;
        }
        position = position > 0 ? position - 1 : order.length - 1;
        select(order[position], 0, true);
    }

    async function loadTags(track) {
        let tags = {};
        try {
            const response = await fetch(withToken('/api/tags/' + track.id), { cache: 'no-store' });
            if (response.ok) tags = await response.json();
        } catch (e) {}
        // Nel frattempo è iniziato un altro brano
        if (current !== track) return;
        showTrack(track, tags);
    }

    function showTrack(track, tags) {
        const title = (tags && tags.title) || track.name;
        const artist = (tags && tags.artist) || '';
        const album = (tags && tags.album) || track.folder || byId('folderName').textContent;
        const artUrl = tags && tags.hasArt ? withToken('/api/art/' + track.id) : '';

        byId('title').textContent = title;
        byId('subtitle').textContent = [artist, album].filter(Boolean).join(' • ');
        document.title = title;

        const art = byId('art');
        const image = byId('artImage');
        if (artUrl) {
            image.src = artUrl;
            art.classList.add('has-art');
        } else {
            image.removeAttribute('src');
            art.classList.remove('has-art');
        }
        // Video: anteprima finché non parte
        if (artUrl && kindOf(track) === 'video') media.poster = artUrl;
        else media.removeAttribute('poster');

        if ('mediaSession' in navigator && typeof MediaMetadata === 'function') {
            // Senza copertina, letti i tag, Android Auto mostra la nota di AAB Media (cover.png la disegna l'app)
            const artwork = artUrl ? [{ src: artUrl, sizes: '512x512' }]
                : tags ? [{ src: 'cover.png', sizes: '512x512' }] : [];
            try {
                navigator.mediaSession.metadata = new MediaMetadata({
                    title: title,
                    artist: artist || album,
                    album: album,
                    artwork: artwork
                });
            } catch (e) {}
        }
    }

    function updateToggles() {
        byId('shuffle').classList.toggle('active', state.shuffle);
        const repeat = byId('repeat');
        repeat.classList.toggle('active', state.repeat !== 'off');
        repeat.classList.toggle('one', state.repeat === 'one');
    }

    // --- Dissolvenza tra i brani audio ---
    //
    // Negli ultimi secondi il brano che finisce passa a un secondo lettore, fuori dalla pagina, e sfuma; #media
    // parte subito col successivo e cresce. Gli script dell'app e Android Auto vedono solo #media: per loro è un
    // normale cambio brano. Il secondo lettore va "sbloccato" con un tocco (la WebView fa partire l'audio solo dopo
    // un gesto dell'utente), poi può partire da solo.

    const FADE_MIN_VOLUME = 0.02;   // mai 0: un media muto non conta come riproduzione per gli script dell'app
    const fadeDeck = new Audio();
    fadeDeck.preload = 'auto';
    let fadeDeckUnlocked = false;
    let fadePrepared = false;
    let fadeFailedSrc = '';
    let fading = null;

    function crossfadeSeconds() {
        if (!state.crossfade || kindOf(current) !== 'audio' || state.repeat === 'one') return 0;
        // Brani brevi: niente dissolvenza, ne resterebbe poco
        if (!isFinite(media.duration) || media.duration < state.crossfade * 3) return 0;
        return state.crossfade;
    }

    function hasAutomaticNext() {
        return order.length > 1 && (position + 1 < order.length || state.repeat === 'all');
    }

    function setMainVolume(value) {
        if (gainNode) {
            gainNode.gain.value = value;
            media.volume = 1;
        } else {
            media.volume = value;
        }
    }

    // Qualche secondo prima: il secondo lettore carica lo stesso brano, così poi parte subito
    function prepareFade() {
        if (fadePrepared || !media.currentSrc) return;
        fadePrepared = true;
        fadeDeck.src = media.currentSrc;
        fadeDeck.currentTime = media.currentTime;
    }

    function startFade(seconds) {
        if (fading || !fadeDeckUnlocked || !media.currentSrc) return;
        // Già fallita per questo brano: niente nuovi tentativi a ogni timeupdate (riaprirebbero il file)
        if (fadeFailedSrc === media.currentSrc) return;
        if (fadeDeck.currentSrc !== media.currentSrc) fadeDeck.src = media.currentSrc;
        fadeDeck.currentTime = media.currentTime;
        fadeDeck.volume = 1;
        const fade = { duration: seconds * 1000, startedAt: 0, timer: 0 };
        fading = fade;
        const begin = () => {
            if (fading !== fade) return;
            // Partito un po' in ritardo (caricamento): si riallinea, il tratto è già in memoria
            if (media.currentTime - fadeDeck.currentTime > 0.15) fadeDeck.currentTime = media.currentTime;
            fade.startedAt = performance.now();
            fade.timer = setInterval(stepFade, 50);
            setMainVolume(FADE_MIN_VOLUME);
            next(true, true);
        };
        const failedSrc = media.currentSrc;
        const attempt = fadeDeck.play();
        if (attempt && attempt.then) {
            attempt.then(begin, () => {
                if (fading !== fade) return;
                // Il brano finisce senza dissolvenza, come se fosse spenta
                fading = null;
                fadeFailedSrc = failedSrc;
            });
        } else {
            begin();
        }
    }

    // Potenza costante: il volume complessivo non cala a metà dissolvenza
    function stepFade() {
        if (!fading || !fading.startedAt) return;
        const progress = Math.min(1, (performance.now() - fading.startedAt) / fading.duration);
        fadeDeck.volume = Math.cos(progress * Math.PI / 2);
        setMainVolume(Math.max(FADE_MIN_VOLUME, Math.sin(progress * Math.PI / 2)));
        if (progress >= 1) stopFade();
    }

    function stopFade() {
        if (fading) {
            clearInterval(fading.timer);
            fading = null;
        }
        if (fadeDeck.getAttribute('src')) {
            fadeDeck.pause();
            fadeDeck.removeAttribute('src');
            fadeDeck.load();
        }
        fadePrepared = false;
        setMainVolume(1);
    }

    fadeDeck.addEventListener('ended', stopFade);
    fadeDeck.addEventListener('error', () => {
        if (fading) stopFade();
    });

    // --- Effetti animati sulla copertina (Web Audio) ---
    //
    // Il suono di #media passa da un AudioContext: analizzatore (per gli effetti) e guadagno (per la dissolvenza).
    // Va creato con un tocco dell'utente: un AudioContext creato senza gesto resta sospeso e #media sarebbe muto.

    const canvas = byId('visual');
    const painter = canvas.getContext('2d');
    let audioContext = null;
    let gainNode = null;
    let analyser = null;
    let frequencyData = null;
    let waveData = null;
    let peaks = [];
    let drawHandle = 0;
    let lastFrame = 0;
    let accentColor = '';

    function ensureAudioGraph() {
        if (analyser) return true;
        const Context = window.AudioContext || window.webkitAudioContext;
        if (!Context) return false;
        try {
            const context = new Context();
            const source = context.createMediaElementSource(media);
            const nextAnalyser = context.createAnalyser();
            nextAnalyser.fftSize = 2048;
            nextAnalyser.smoothingTimeConstant = 0.78;
            const gain = context.createGain();
            // Il volume attuale (es. a metà dissolvenza) passa al guadagno
            gain.gain.value = media.volume;
            media.volume = 1;
            source.connect(nextAnalyser);
            nextAnalyser.connect(gain);
            gain.connect(context.destination);
            audioContext = context;
            analyser = nextAnalyser;
            gainNode = gain;
            frequencyData = new Uint8Array(analyser.frequencyBinCount);
            waveData = new Uint8Array(analyser.fftSize);
            return true;
        } catch (e) {
            return false;
        }
    }

    function effectsActive() {
        return state.effect !== 'off' && !!analyser && kindOf(current) === 'audio' && !media.paused &&
            document.visibilityState === 'visible';
    }

    function updateEffects() {
        const active = effectsActive();
        canvas.hidden = !active;
        if (active && !drawHandle) drawHandle = requestAnimationFrame(drawFrame);
    }

    function drawFrame(now) {
        drawHandle = 0;
        if (!effectsActive()) {
            canvas.hidden = true;
            return;
        }
        drawHandle = requestAnimationFrame(drawFrame);
        // 30 fotogrammi al secondo bastano e pesano la metà
        if (now - lastFrame < 33) return;
        lastFrame = now;

        const ratio = window.devicePixelRatio || 1;
        const width = Math.max(1, Math.round(canvas.clientWidth * ratio));
        const height = Math.max(1, Math.round(canvas.clientHeight * ratio));
        if (canvas.width !== width || canvas.height !== height) {
            canvas.width = width;
            canvas.height = height;
        }
        if (!accentColor) {
            accentColor = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim() || '#00bcd4';
        }
        painter.clearRect(0, 0, width, height);
        // Velo scuro: l'effetto si vede su qualunque copertina
        painter.fillStyle = 'rgba(0, 0, 0, 0.35)';
        painter.fillRect(0, 0, width, height);
        if (state.effect === 'wave') drawWave(width, height);
        else if (state.effect === 'circle') drawCircle(width, height, now);
        else drawBars(width, height);
    }

    // Bande logaritmiche, come gli analizzatori dei vecchi lettori: tante per i bassi, poche per gli acuti
    function bandLevels(count) {
        analyser.getByteFrequencyData(frequencyData);
        const nyquist = audioContext.sampleRate / 2;
        const binWidth = nyquist / frequencyData.length;
        const minHz = 40;
        const maxHz = Math.min(16000, nyquist);
        const levels = new Array(count);
        for (let i = 0; i < count; i++) {
            const from = Math.floor(minHz * Math.pow(maxHz / minHz, i / count) / binWidth);
            const to = Math.max(from + 1, Math.ceil(minHz * Math.pow(maxHz / minHz, (i + 1) / count) / binWidth));
            let sum = 0;
            let bins = 0;
            for (let bin = from; bin < to && bin < frequencyData.length; bin++) {
                sum += frequencyData[bin];
                bins++;
            }
            levels[i] = bins ? sum / bins / 255 : 0;
        }
        return levels;
    }

    // Barre con il "cappello" che ricade piano, come in Winamp
    function drawBars(width, height) {
        const count = 32;
        const levels = bandLevels(count);
        const slot = width / count;
        const barWidth = slot * 0.72;
        const capHeight = Math.max(2, height * 0.014);
        const gradient = painter.createLinearGradient(0, height, 0, 0);
        gradient.addColorStop(0, accentColor);
        gradient.addColorStop(1, '#ffffff');
        for (let i = 0; i < count; i++) {
            const barHeight = Math.max(capHeight, levels[i] * height * 0.85);
            const x = i * slot + (slot - barWidth) / 2;
            painter.fillStyle = gradient;
            painter.fillRect(x, height - barHeight, barWidth, barHeight);
            const peak = Math.max((peaks[i] || 0) - height * 0.01, barHeight);
            peaks[i] = peak;
            painter.fillStyle = '#ffffff';
            painter.fillRect(x, height - peak - capHeight * 1.5, barWidth, capHeight);
        }
    }

    // Oscilloscopio
    function drawWave(width, height) {
        analyser.getByteTimeDomainData(waveData);
        painter.lineWidth = Math.max(2, height * 0.012);
        painter.lineJoin = 'round';
        painter.strokeStyle = accentColor;
        painter.shadowColor = accentColor;
        painter.shadowBlur = height * 0.05;
        painter.beginPath();
        const points = Math.min(waveData.length, Math.round(width / 3));
        for (let i = 0; i < points; i++) {
            const value = waveData[Math.floor(i * waveData.length / points)] / 128 - 1;
            const x = i * width / (points - 1);
            const y = height / 2 + value * height * 0.42;
            if (i === 0) painter.moveTo(x, y);
            else painter.lineTo(x, y);
        }
        painter.stroke();
        painter.shadowBlur = 0;
    }

    // Raggi intorno al centro che ruotano piano, con un alone che pulsa sui bassi
    function drawCircle(width, height, now) {
        const count = 64;
        const levels = bandLevels(count);
        const centerX = width / 2;
        const centerY = height / 2;
        const radius = Math.min(width, height) * 0.2;
        const bass = (levels[0] + levels[1] + levels[2] + levels[3]) / 4;
        const rotation = now / 6000;

        painter.fillStyle = accentColor;
        painter.globalAlpha = 0.12 + bass * 0.4;
        painter.beginPath();
        painter.arc(centerX, centerY, radius * (0.85 + bass * 0.3), 0, Math.PI * 2);
        painter.fill();
        painter.globalAlpha = 1;

        painter.strokeStyle = accentColor;
        painter.lineCap = 'round';
        painter.lineWidth = Math.max(2, (Math.PI * 2 * radius / count) * 0.5);
        for (let i = 0; i < count; i++) {
            const angle = rotation + (i / count) * Math.PI * 2;
            const length = radius * (0.12 + levels[i] * 1.1);
            const cos = Math.cos(angle);
            const sin = Math.sin(angle);
            painter.beginPath();
            painter.moveTo(centerX + cos * radius, centerY + sin * radius);
            painter.lineTo(centerX + cos * (radius + length), centerY + sin * (radius + length));
            painter.stroke();
        }
    }

    // --- Opzioni ---

    function renderOptions() {
        byId('optionsTitle').textContent = text('options', 'Options');
        byId('crossfadeLabel').textContent = text('crossfade', 'Crossfade');
        byId('effectsLabel').textContent = text('effects', 'Visual effects');
        renderChoices(byId('crossfadeChoices'),
            CROSSFADE_CHOICES.map((seconds) => [seconds, seconds ? seconds + ' s' : text('off', 'Off')]),
            state.crossfade,
            (seconds) => {
                state.crossfade = seconds;
                if (!seconds) stopFade();
            });
        renderChoices(byId('effectsChoices'),
            EFFECT_CHOICES.map((effect) => [effect, text(effect, effect)]),
            state.effect,
            (effect) => {
                state.effect = effect;
                peaks = [];
                if (effect !== 'off') ensureAudioGraph();
                updateEffects();
            });
    }

    function renderChoices(box, choices, selected, onSelect) {
        box.textContent = '';
        choices.forEach(([value, label]) => {
            const button = document.createElement('button');
            button.className = 'choice' + (value === selected ? ' active' : '');
            button.textContent = label;
            button.addEventListener('click', () => {
                onSelect(value);
                saveState();
                renderOptions();
            });
            box.appendChild(button);
        });
    }

    const optionsSheet = byId('optionsSheet');
    byId('optionsButton').addEventListener('click', () => {
        renderOptions();
        optionsSheet.hidden = false;
    });
    // Tocco fuori dal riquadro: chiude
    optionsSheet.addEventListener('click', (event) => {
        if (event.target === optionsSheet) optionsSheet.hidden = true;
    });

    // Ogni tocco: sblocca il secondo lettore e, se servono gli effetti, crea o riattiva l'AudioContext
    document.addEventListener('click', () => {
        if (!fadeDeckUnlocked) {
            fadeDeckUnlocked = true;
            const attempt = fadeDeck.play();
            if (attempt && attempt.catch) attempt.catch(() => {});
            fadeDeck.pause();
        }
        if (state.effect !== 'off') ensureAudioGraph();
        if (audioContext && audioContext.state === 'suspended') audioContext.resume().catch(() => {});
    }, true);

    document.addEventListener('visibilitychange', updateEffects);

    // --- Eventi del media ---

    media.addEventListener('loadedmetadata', () => {
        if (pendingSeek > 0 && isFinite(media.duration) && pendingSeek < media.duration - 1) {
            media.currentTime = pendingSeek;
        }
        pendingSeek = 0;
        byId('totalTime').textContent = formatTime(media.duration);
    });

    media.addEventListener('timeupdate', () => {
        if (!isSeeking && isFinite(media.duration) && media.duration > 0) {
            seek.value = String(Math.round((media.currentTime / media.duration) * 1000));
            byId('currentTime').textContent = formatTime(media.currentTime);
        }
        const now = Date.now();
        if (now - lastStateSave > 5000) {
            lastStateSave = now;
            state.lastTime = media.currentTime;
            saveState();
        }

        // Dissolvenza: anche da qui, perché con lo schermo spento i timer della pagina rallentano
        stepFade();
        const seconds = crossfadeSeconds();
        if (seconds && !fading && !media.paused && hasAutomaticNext()) {
            const left = media.duration - media.currentTime;
            if (left <= seconds + 3) prepareFade();
            if (left <= seconds) startFade(seconds);
        }
    });

    media.addEventListener('play', () => {
        document.body.classList.add('is-playing');
        updateEffects();
    });

    media.addEventListener('playing', () => {
        consecutiveErrors = 0;
    });

    media.addEventListener('pause', () => {
        document.body.classList.remove('is-playing');
        state.lastTime = media.currentTime;
        saveState();
        // Pausa dell'utente (o di Android Auto) durante la dissolvenza: si ferma anche il brano che sfumava
        if (fading && Date.now() - lastSourceChange > 500) stopFade();
        updateEffects();
    });

    media.addEventListener('ended', () => {
        // Finito prima che la dissolvenza partisse davvero: cambio brano normale
        if (fading && !fading.startedAt) stopFade();
        next(true);
    });

    media.addEventListener('error', () => {
        if (!current || !media.getAttribute('src')) return;
        consecutiveErrors++;
        showMessage(text('unplayable', 'This file cannot be played.'));
        // Si passa al successivo, ma non all'infinito se nessun file della coda si riesce a riprodurre
        if (consecutiveErrors < lists[queueKind].length) {
            setTimeout(() => {
                showMessage('');
                next(false);
            }, 1500);
        }
    });

    // Tocco sul video: schermo intero
    media.addEventListener('click', () => {
        if (kindOf(current) === 'video' && media.requestFullscreen) {
            media.requestFullscreen().catch(() => {});
        }
    });

    // --- Comandi ---

    seek.addEventListener('input', () => {
        isSeeking = true;
        if (isFinite(media.duration)) {
            byId('currentTime').textContent = formatTime((Number(seek.value) / 1000) * media.duration);
        }
    });

    seek.addEventListener('change', () => {
        stopFade();
        if (isFinite(media.duration)) media.currentTime = (Number(seek.value) / 1000) * media.duration;
        isSeeking = false;
    });

    byId('playToggle').addEventListener('click', () => {
        if (!current) return;
        if (media.paused) start();
        else media.pause();
    });

    byId('previous').addEventListener('click', previous);
    byId('next').addEventListener('click', () => next(false));

    byId('shuffle').addEventListener('click', () => {
        state.shuffle = !state.shuffle;
        saveState();
        buildOrder(current ? lists[queueKind].indexOf(current) : 0);
        updateToggles();
    });

    byId('repeat').addEventListener('click', () => {
        state.repeat = state.repeat === 'all' ? 'one' : state.repeat === 'one' ? 'off' : 'all';
        saveState();
        updateToggles();
    });

    list.addEventListener('click', (event) => {
        const item = event.target.closest('li');
        if (!item) return;
        const index = Number(item.dataset.index);
        if (viewKind !== queueKind) {
            // File dell'altra scheda: la coda diventa quella (la precedente si ferma qui)
            queueKind = viewKind;
            buildOrder(index);
        } else {
            const inOrder = order.indexOf(index);
            position = inOrder >= 0 ? inOrder : 0;
        }
        select(index, 0, true);
    });

    byId('tabAudio').addEventListener('click', () => showKind('audio'));
    byId('tabVideo').addEventListener('click', () => showKind('video'));

    const pickFolder = byId('pickFolder');
    const folderInput = byId('folderInput');
    pickFolder.textContent = text('chooseFolder', 'Choose folder');
    pickFolder.addEventListener('click', () => folderInput.click());
    // Sul telefono l'app apre il selettore e ricorda la cartella; in auto risponde con l'ultima scelta fatta sul telefono
    folderInput.addEventListener('change', () => {
        folderInput.value = '';
        loadLibrary(true);
    });

    // --- Media Session: comandi del player di Android Auto e delle notifiche ---

    if ('mediaSession' in navigator) {
        const handle = (action, handler) => {
            try {
                navigator.mediaSession.setActionHandler(action, handler);
            } catch (e) {}
        };
        handle('play', start);
        handle('pause', () => media.pause());
        handle('previoustrack', previous);
        handle('nexttrack', () => next(false));
        handle('seekto', (details) => {
            if (details && typeof details.seekTime === 'number') media.currentTime = details.seekTime;
        });
    }

    updateToggles();
    loadLibrary(false);
})();
