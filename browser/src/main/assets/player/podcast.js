(function () {
    'use strict';

    const config = window.AAB_PODCAST || { token: '', sources: [], strings: {} };
    const S = config.strings || {};
    const Progress = window.AABPodcastProgress;
    const STORE_KEY = 'aabPodcastState';
    const ROW = 56;
    const SAVE_PROGRESS_MS = 5000;
    const SAVE_STATE_MS = 1000;
    const SEARCH_WAIT_MS = 600;
    const COVER_REQUESTS = 3;
    const MAX_COVERS = 600;
    const JUMP_BACK = 15;
    const JUMP_FORWARD = 30;
    const byId = (id) => document.getElementById(id);
    const media = byId('media');
    const channelsBox = byId('channels');
    const space = byId('channelsSpace');
    const seek = byId('seek');
    const rows = new Map();
    const dates = new Intl.DateTimeFormat(config.locale || undefined, { day: 'numeric', month: 'short', year: 'numeric' });
    const minutes = new Intl.NumberFormat(config.locale || undefined, { style: 'unit', unit: 'minute', unitDisplay: 'short' });

    let shows = [];             // la lista scelta nel menu
    let found = null;           // risultati della ricerca; null = nessuna ricerca
    let showFollowed = false;
    let openShow = null;        // il podcast di cui si vedono gli episodi
    let episodes = [];
    let visible = [];
    let showsScroll = 0;
    let current = null;         // { show, episode }: in ascolto, o l'ultimo ascoltato
    let audioReady = false;     // l'audio dell'episodio è stato chiesto
    let pendingSeek = 0;
    let isDragging = false;
    let lastProgressSave = 0;
    let requestToken = 0;
    let listToken = 0;          // l'elenco arrivato dopo aver scelto un'altra lista si scarta
    let searchToken = 0;
    let searchTimer = 0;
    let saveTimer = 0;
    let drawPending = false;
    let coverRequests = 0;
    const coverQueue = [];
    const coverAsked = new Set();
    let state = readState();

    // [known]: i dati dei podcast che servono anche fuori dalla loro lista (seguiti, ultimo ascoltato)
    function readState() {
        const empty = { source: '', following: [], known: {}, covers: {}, progress: {}, last: null };
        try {
            return Object.assign(empty, JSON.parse(localStorage.getItem(STORE_KEY)));
        } catch (_) {
            return empty;
        }
    }

    function saveState() {
        clearTimeout(saveTimer);
        saveTimer = 0;
        try {
            localStorage.setItem(STORE_KEY, JSON.stringify(state));
        } catch (_) {}
    }

    function saveStateSoon() {
        if (!saveTimer) saveTimer = setTimeout(saveState, SAVE_STATE_MS);
    }

    function withToken(path, params) {
        const query = new URLSearchParams(Object.assign({}, params || {}, { t: config.token }));
        return path + '?' + query;
    }

    async function api(path, params) {
        const response = await fetch(withToken(path, params), { cache: 'no-store' });
        if (response.status === 403) {
            location.reload();
            throw new Error('token');
        }
        if (!response.ok) throw new Error(String(response.status));
        return response.json();
    }

    function message(value) {
        const box = byId('message');
        box.textContent = value || '';
        box.hidden = !value;
    }

    function coverOf(show) {
        return show.image || state.covers[show.id] || '';
    }

    function isFollowed(show) {
        return state.following.includes(show.id);
    }

    function summary(show) {
        return { id: show.id, title: show.title, author: show.author || '', image: coverOf(show) };
    }

    function keepKnownShows() {
        const kept = {};
        const ids = state.following.concat(state.last ? state.last.show.id : []);
        ids.forEach((id) => {
            if (state.known[id]) kept[id] = state.known[id];
        });
        state.known = kept;
    }

    // ------------------------------------------------------------------
    // Elenco: podcast o episodi di un podcast
    // ------------------------------------------------------------------

    function listedShows() {
        if (found) return showFollowed ? found.filter(isFollowed) : found;
        if (showFollowed) return state.following.map((id) => state.known[id]).filter(Boolean);
        return shows;
    }

    function updateTools() {
        const inShow = !!openShow;
        byId('allShows').hidden = inShow;
        byId('followedShows').hidden = inShow;
        byId('closeShow').hidden = !inShow;
        byId('followShow').hidden = !inShow;
        byId('allShows').classList.toggle('active', !showFollowed);
        byId('followedShows').classList.toggle('active', showFollowed);
        if (inShow) {
            const followed = isFollowed(openShow);
            byId('closeShow').textContent = '‹ ' + openShow.title;
            byId('followShow').textContent = followed ? '★ ' + (S.following || 'Following') : '☆ ' + (S.follow || 'Follow');
            byId('followShow').classList.toggle('active', followed);
        }
    }

    function render() {
        visible = openShow ? episodes : listedShows();
        rows.forEach((row) => row.remove());
        rows.clear();
        space.style.height = (visible.length * ROW) + 'px';
        drawRows();
        updateTools();
    }

    function baseRow(index, isCurrent) {
        const row = document.createElement('div');
        row.className = 'channel' + (isCurrent ? ' current' : '');
        row.style.top = (index * ROW) + 'px';
        row.dataset.index = String(index);
        return row;
    }

    function details(title, meta) {
        const box = document.createElement('div');
        box.className = 'details';
        const name = document.createElement('span');
        name.className = 'name';
        name.textContent = title;
        box.appendChild(name);
        const line = document.createElement('span');
        line.className = 'meta';
        line.textContent = meta;
        box.appendChild(line);
        return box;
    }

    function letterFor(show) {
        const letter = document.createElement('span');
        letter.className = 'letter';
        letter.textContent = (show.title || '?').trim().charAt(0).toLocaleUpperCase();
        return letter;
    }

    function artFor(show) {
        const cover = coverOf(show);
        if (!cover) {
            askCover(show);
            return letterFor(show);
        }
        const image = document.createElement('img');
        image.loading = 'lazy';
        image.referrerPolicy = 'no-referrer';
        image.src = cover;
        image.addEventListener('error', () => image.replaceWith(letterFor(show)));
        return image;
    }

    function makeShowRow(show, index) {
        const row = baseRow(index, !!current && current.show.id === show.id);
        row.appendChild(artFor(show));
        row.appendChild(details(show.title, show.author || ''));
        if (isFollowed(show)) {
            const star = document.createElement('span');
            star.className = 'star';
            star.textContent = '★';
            row.appendChild(star);
        }
        return row;
    }

    function episodeMeta(episode, saved) {
        const parts = [];
        if (episode.date > 0) parts.push(dates.format(new Date(episode.date)));
        const left = Progress.left(saved);
        if (Progress.isDone(saved)) parts.push(S.listened || 'Played');
        else if (left > 0) parts.push((S.left || '%1$s left').replace('%1$s', minutes.format(Math.max(1, Math.round(left / 60)))));
        else if (episode.duration > 0) parts.push(minutes.format(Math.max(1, Math.round(episode.duration / 60))));
        return parts.join(' · ');
    }

    function makeEpisodeRow(episode, index) {
        const saved = state.progress[episode.id];
        const row = baseRow(index, !!current && current.episode.id === episode.id);
        if (Progress.isDone(saved)) row.classList.add('done');
        row.appendChild(details(episode.title, episodeMeta(episode, saved)));
        const share = Progress.share(saved);
        if (share > 0 && share < 1) {
            const heard = document.createElement('span');
            heard.className = 'heard';
            heard.style.width = (share * 100) + '%';
            row.appendChild(heard);
        }
        return row;
    }

    function makeRow(index) {
        return openShow ? makeEpisodeRow(visible[index], index) : makeShowRow(visible[index], index);
    }

    function drawRows() {
        const top = channelsBox.scrollTop;
        const height = channelsBox.clientHeight || 600;
        const first = Math.max(0, Math.floor(top / ROW) - 8);
        const last = Math.min(visible.length, Math.ceil((top + height) / ROW) + 8);
        rows.forEach((row, index) => {
            if (index < first || index >= last) {
                row.remove();
                rows.delete(index);
            }
        });
        for (let index = first; index < last; index++) {
            if (!rows.has(index)) {
                const row = makeRow(index);
                rows.set(index, row);
                space.appendChild(row);
            }
        }
    }

    function redrawRow(index) {
        const old = rows.get(index);
        if (!old) return;
        const row = makeRow(index);
        rows.set(index, row);
        old.replaceWith(row);
    }

    // ------------------------------------------------------------------
    // Copertine: le legge l'app dall'inizio del feed, solo per le righe in vista
    // ------------------------------------------------------------------

    function askCover(show) {
        if (coverAsked.has(show.id)) return;
        coverAsked.add(show.id);
        coverQueue.push(show);
        nextCovers();
    }

    function nextCovers() {
        while (coverRequests < COVER_REQUESTS && coverQueue.length) {
            const show = coverQueue.pop();
            if (!visible.includes(show)) {
                coverAsked.delete(show.id);
                continue;
            }
            coverRequests++;
            api('/api/podcast/cover', { show: show.id })
                .then((data) => {
                    if (data.image) setCover(show, data.image);
                })
                .catch(() => {})
                .finally(() => {
                    coverRequests--;
                    nextCovers();
                });
        }
    }

    function setCover(show, image) {
        show.image = image;
        if (Object.keys(state.covers).length >= MAX_COVERS) state.covers = {};
        state.covers[show.id] = image;
        saveStateSoon();
        if (!openShow) redrawRow(visible.indexOf(show));
    }

    // ------------------------------------------------------------------
    // Liste, ricerca, episodi
    // ------------------------------------------------------------------

    const picker = window.createSourcePicker(byId('source'), {
        section: 'podcast',
        sources: config.sources,
        addLabel: S.addList,
        phoneOnlyText: S.addOnPhone,
        messageBox: byId('message'),
        onChange: selectSource
    });

    // Avvisata dall'app quando le liste cambiano nelle Impostazioni: l'episodio in ascolto continua
    window.aabSourcesChanged = function () {
        api('/api/podcast/sources').then((data) => picker.update(data.sources)).catch(() => {});
    };

    function setFollowedView(on) {
        showFollowed = on;
    }

    function leaveShow() {
        openShow = null;
        episodes = [];
    }

    function selectSource(id) {
        state.source = id;
        saveState();
        picker.select(id);
        found = null;
        byId('search').value = '';
        if (openShow) history.back();
        leaveShow();
        setFollowedView(false);
        channelsBox.scrollTop = 0;
        shows = [];
        render();
        return loadShows(false);
    }

    async function loadShows(refresh) {
        const token = ++listToken;
        message(S.loading || 'Loading…');
        try {
            const data = await api('/api/podcast/shows', Object.assign({ source: state.source }, refresh ? { refresh: '1' } : {}));
            if (token !== listToken) return;
            shows = data.shows || [];
            render();
            message(shows.length ? '' : (S.noResults || 'No podcasts found'));
        } catch (_) {
            if (token === listToken) message(S.error || 'Could not load the podcasts');
        }
    }

    async function runSearch(text) {
        const token = ++searchToken;
        message(S.loading || 'Loading…');
        try {
            const data = await api('/api/podcast/search', { q: text, source: state.source });
            if (token !== searchToken) return;
            found = data.shows || [];
            if (openShow) history.back();
            leaveShow();
            setFollowedView(false);
            channelsBox.scrollTop = 0;
            render();
            message(found.length ? '' : (S.noResults || 'No podcasts found'));
        } catch (_) {
            if (token === searchToken) message(S.error || 'Could not load the podcasts');
        }
    }

    // Una voce nella cronologia: il tasto Indietro torna ai podcast prima di lasciare la pagina
    async function openEpisodes(show, refresh) {
        const token = ++listToken;
        message(S.loading || 'Loading…');
        try {
            const data = await api('/api/podcast/episodes', Object.assign({ show: show.id }, refresh ? { refresh: '1' } : {}));
            if (token !== listToken) return;
            if (!openShow) {
                showsScroll = channelsBox.scrollTop;
                history.pushState({ podcast: show.id }, '');
            }
            openShow = Object.assign({}, show, data.show);
            if (data.show.image) show.image = data.show.image;
            episodes = data.episodes || [];
            channelsBox.scrollTop = 0;
            render();
            message(episodes.length ? '' : (S.noResults || 'No podcasts found'));
        } catch (_) {
            if (token === listToken) message(S.error || 'Could not load the podcasts');
        }
    }

    function backToShows() {
        leaveShow();
        render();
        channelsBox.scrollTop = showsScroll;
        drawRows();
        message('');
    }

    function toggleFollow(show) {
        state.following = isFollowed(show)
            ? state.following.filter((id) => id !== show.id)
            : state.following.concat(show.id);
        state.known[show.id] = summary(show);
        keepKnownShows();
        saveState();
        render();
    }

    // ------------------------------------------------------------------
    // Ascolto e punto d'ascolto
    // ------------------------------------------------------------------

    function showNow() {
        const art = byId('art');
        const image = byId('artImage');
        byId('title').textContent = current ? current.episode.title : (S.title || 'Podcast');
        byId('subtitle').textContent = current ? current.show.title : '';
        const cover = current && (current.episode.image || coverOf(current.show));
        if (cover) {
            image.src = cover;
            art.classList.add('has-art');
        } else {
            image.removeAttribute('src');
            art.classList.remove('has-art');
        }
    }

    function updateMediaSession() {
        if (!current || !('mediaSession' in navigator) || typeof MediaMetadata !== 'function') return;
        const cover = current.episode.image || coverOf(current.show);
        try {
            navigator.mediaSession.metadata = new MediaMetadata({
                title: current.episode.title,
                artist: current.show.title,
                album: S.title || 'Podcast',
                artwork: cover ? [{ src: cover, sizes: '512x512' }] : []
            });
        } catch (_) {}
    }

    function saveProgress() {
        if (!current || !audioReady || pendingSeek > 0) return;
        Progress.update(state.progress, current.episode.id, media.currentTime, media.duration, Date.now());
        lastProgressSave = Date.now();
        saveStateSoon();
        if (openShow) redrawRow(visible.indexOf(current.episode));
    }

    function stopAudio() {
        audioReady = false;
        pendingSeek = 0;
        media.pause();
        media.removeAttribute('src');
        media.load();
        document.body.classList.remove('is-playing');
    }

    function showTimes() {
        const duration = media.duration;
        byId('currentTime').textContent = Progress.clock(media.currentTime);
        byId('totalTime').textContent = Progress.clock(duration);
        if (!isDragging) seek.value = isFinite(duration) && duration > 0 ? String((media.currentTime / duration) * 1000) : '0';
    }

    async function playEpisode(show, episode) {
        saveProgress();
        const token = ++requestToken;
        stopAudio();
        current = { show, episode };
        state.last = { show: summary(show), episode };
        state.known[show.id] = summary(show);
        keepKnownShows();
        saveState();
        showNow();
        updateMediaSession();
        showTimes();
        render();
        message(S.loading || 'Loading…');
        try {
            const info = await api('/api/podcast/play', { show: show.id, episode: episode.id });
            if (token !== requestToken) return;
            pendingSeek = Progress.resumeAt(state.progress, episode.id);
            media.src = info.url;
            audioReady = true;
            const attempt = media.play();
            if (attempt && attempt.catch) attempt.catch(() => {});
        } catch (_) {
            if (token === requestToken) message(S.unavailable || 'Episode not available');
        }
    }

    function resume() {
        if (audioReady) {
            media.play().catch(() => {});
        } else if (current) {
            playEpisode(current.show, current.episode);
        }
    }

    function jump(seconds) {
        if (!audioReady) return;
        const end = isFinite(media.duration) ? media.duration : Infinity;
        media.currentTime = Math.min(end, Math.max(0, media.currentTime + seconds));
        saveProgress();
    }

    media.addEventListener('loadedmetadata', () => {
        if (pendingSeek > 0 && (!isFinite(media.duration) || pendingSeek < media.duration)) media.currentTime = pendingSeek;
        pendingSeek = 0;
        showTimes();
    });
    media.addEventListener('timeupdate', () => {
        showTimes();
        if (Date.now() - lastProgressSave > SAVE_PROGRESS_MS) saveProgress();
    });
    media.addEventListener('playing', () => {
        document.body.classList.add('is-playing');
        message('');
    });
    media.addEventListener('pause', () => {
        document.body.classList.remove('is-playing');
        saveProgress();
    });
    media.addEventListener('ended', () => {
        document.body.classList.remove('is-playing');
        saveProgress();
    });
    media.addEventListener('error', () => {
        if (audioReady) message(S.unavailable || 'Episode not available');
    });
    window.addEventListener('pagehide', () => {
        saveProgress();
        saveState();
    });

    seek.addEventListener('input', () => {
        isDragging = true;
        if (isFinite(media.duration)) byId('currentTime').textContent = Progress.clock((Number(seek.value) / 1000) * media.duration);
    });
    seek.addEventListener('change', () => {
        if (isFinite(media.duration)) media.currentTime = (Number(seek.value) / 1000) * media.duration;
        isDragging = false;
        saveProgress();
    });

    byId('playToggle').addEventListener('click', () => {
        if (audioReady && !media.paused) media.pause();
        else resume();
    });
    byId('back').addEventListener('click', () => jump(-JUMP_BACK));
    byId('forward').addEventListener('click', () => jump(JUMP_FORWARD));

    channelsBox.addEventListener('click', (event) => {
        const row = event.target.closest('.channel');
        const item = row && visible[Number(row.dataset.index)];
        if (!item) return;
        if (openShow) playEpisode(openShow, item);
        else openEpisodes(item, false);
    });
    channelsBox.addEventListener('scroll', () => {
        if (drawPending) return;
        drawPending = true;
        requestAnimationFrame(() => {
            drawPending = false;
            drawRows();
        });
    });
    window.addEventListener('resize', drawRows);
    window.addEventListener('popstate', () => {
        if (openShow) backToShows();
    });

    byId('search').placeholder = S.search || 'Search podcasts';
    byId('search').addEventListener('input', () => {
        clearTimeout(searchTimer);
        const text = byId('search').value.trim();
        if (text.length < 2) {
            searchToken++;
            if (found) {
                found = null;
                channelsBox.scrollTop = 0;
                render();
                message('');
            }
            return;
        }
        searchTimer = setTimeout(() => runSearch(text), SEARCH_WAIT_MS);
    });

    byId('allShows').textContent = S.all || 'All';
    byId('followedShows').textContent = S.following || 'Following';
    byId('allShows').addEventListener('click', () => {
        setFollowedView(false);
        channelsBox.scrollTop = 0;
        render();
    });
    byId('followedShows').addEventListener('click', () => {
        setFollowedView(true);
        channelsBox.scrollTop = 0;
        render();
    });
    byId('closeShow').addEventListener('click', () => history.back());
    byId('followShow').addEventListener('click', () => {
        if (openShow) toggleFollow(openShow);
    });
    byId('refresh').addEventListener('click', () => {
        if (openShow) openEpisodes(openShow, true);
        else if (found === null) loadShows(true);
    });

    byId('artImage').addEventListener('error', () => byId('art').classList.remove('has-art'));

    // Senza nexttrack e previoustrack: Android Auto mostra i suoi -10/+10 s, e i tasti al volante fanno lo stesso
    if ('mediaSession' in navigator) {
        [['play', resume], ['pause', () => media.pause()]].forEach(([action, handler]) => {
            try {
                navigator.mediaSession.setActionHandler(action, handler);
            } catch (_) {}
        });
    }

    // L'ultimo ascoltato resta pronto: ▶ riprende da dove era rimasto
    if (state.last && state.last.show && state.last.episode) {
        current = state.last;
        showNow();
        updateMediaSession();
    }

    // La lista automatica cambia con la lingua dell'app: una lista che non c'è più torna alla prima
    state.source = picker.has(state.source) ? state.source : ((picker.list()[0] || {}).id || '');
    picker.select(state.source);
    loadShows(false);
})();
