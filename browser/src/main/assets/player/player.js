/*
 * AAB Media, il lettore locale di AABrowser.
 *
 * I file arrivano dall'app (LocalPlayer su https://player.local/): cartella scelta sul telefono, richieste Range
 * per spostarsi nel brano, tag e copertina. La Media Session (titolo, copertina, avanti/indietro) collega la pagina
 * al player di Android Auto tramite gli script che l'app inietta in ogni pagina.
 *
 * Audio e video hanno code separate (schede): avanti, indietro, casuale e ripeti restano sempre nella coda del
 * file in riproduzione, così dopo una canzone non parte un video e viceversa.
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
    const state = Object.assign({ shuffle: false, repeat: 'all', lastId: null, lastTime: 0 }, readState());
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

    function select(index, startTime, autoplay) {
        const track = lists[queueKind][index];
        if (!track) return;
        current = track;
        state.lastId = track.id;
        state.lastTime = startTime || 0;
        saveState();

        pendingSeek = startTime > 0 ? startTime : 0;
        document.body.classList.toggle('video-mode', kindOf(track) === 'video');
        media.src = withToken('/media/' + track.id);
        seek.value = '0';
        byId('currentTime').textContent = formatTime(pendingSeek);
        byId('totalTime').textContent = '0:00';
        showTrack(track, null);
        loadTags(track);
        highlight();
        if (autoplay) start();
    }

    function start() {
        if (!current) return;
        const attempt = media.play();
        if (attempt && attempt.catch) attempt.catch(() => {});
    }

    function next(automatic) {
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
        select(order[position], 0, true);
    }

    function previous() {
        if (order.length === 0) return;
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
            try {
                navigator.mediaSession.metadata = new MediaMetadata({
                    title: title,
                    artist: artist || album,
                    album: album,
                    artwork: artUrl ? [{ src: artUrl, sizes: '512x512' }] : []
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
    });

    media.addEventListener('play', () => document.body.classList.add('is-playing'));

    media.addEventListener('playing', () => {
        consecutiveErrors = 0;
    });

    media.addEventListener('pause', () => {
        document.body.classList.remove('is-playing');
        state.lastTime = media.currentTime;
        saveState();
    });

    media.addEventListener('ended', () => next(true));

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
