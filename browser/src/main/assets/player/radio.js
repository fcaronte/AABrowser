(function () {
    'use strict';

    const config = window.AAB_RADIO || { token: '', sources: [], strings: {} };
    const S = config.strings || {};
    const STORE_KEY = 'aabRadioState';
    const ROW = 56;
    const byId = (id) => document.getElementById(id);
    const media = byId('media');
    const channelsBox = byId('channels');
    const space = byId('channelsSpace');
    const rows = new Map();
    let stations = [];
    let visibleStations = [];
    let current = null;
    let currentRawTitle = '';   // il testo come lo manda la stazione
    let currentTrack = null;    // quello che ne capisce AABTrackParser
    let currentTrackTitle = '';
    let lyricsLookupTimer = null;
    let lyricsController = null;
    let currentUrl = '';
    let currentIsHls = false;
    let hls = null;
    let metadataTimer = null;
    let metadataRequestPending = null;
    let icyMetadataSupported = null;
    let showFavorites = false;
    let drawPending = false;
    let requestToken = 0;
    let listToken = 0;          // l'elenco arrivato dopo aver scelto un'altra lista si scarta
    // Risultato di una ricerca a voce: può contenere stazioni fuori dalla lista e nomi scritti diversamente
    let foundStations = null;
    let foundQuery = '';
    let state = readState();

    // [extra]: i dati delle stazioni che servono anche fuori dalla loro lista (l'ultima ascoltata, i preferiti)
    function readState() {
        const empty = { source: '', favorites: [], lastUuid: '', extra: {} };
        try {
            return Object.assign(empty, JSON.parse(localStorage.getItem(STORE_KEY)));
        } catch (_) {
            return empty;
        }
    }

    function saveState() {
        try {
            localStorage.setItem(STORE_KEY, JSON.stringify(state));
        } catch (_) {}
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

    function favorite(station) {
        return state.favorites.includes(station.uuid);
    }

    function setFavoriteButton() {
        byId('favorite').classList.toggle('active', !!current && favorite(current));
    }

    function showStation(station) {
        byId('title').textContent = station ? (currentTrackTitle || station.name) : (S.title || 'Radio');
        byId('lyricsButton').hidden = !station;
        byId('subtitle').textContent = station
            ? [station.name, station.country, station.codec].filter(Boolean).join(' · ')
            : '';
        const art = byId('art');
        const image = byId('artImage');
        if (station && station.favicon) {
            image.src = station.favicon;
            art.classList.add('has-art');
        } else {
            image.removeAttribute('src');
            art.classList.remove('has-art');
        }
    }

    function stationByUuid(uuid) {
        return stations.find((station) => station.uuid === uuid) || state.extra[uuid];
    }

    function filteredStations() {
        const query = byId('search').value.trim().toLocaleLowerCase();
        if (foundStations && byId('search').value === foundQuery) {
            return foundStations.filter((station) => !showFavorites || favorite(station));
        }
        // I preferiti valgono per tutte le liste
        const pool = showFavorites ? state.favorites.map(stationByUuid).filter(Boolean) : stations;
        return pool.filter((station) => !query || station.name.toLocaleLowerCase().includes(query) ||
            station.country.toLocaleLowerCase().includes(query));
    }

    function render() {
        visibleStations = filteredStations();
        rows.forEach((row) => row.remove());
        rows.clear();
        space.style.height = (visibleStations.length * ROW) + 'px';
        drawRows();
        setFavoriteButton();
    }

    function makeRow(station, index) {
        const row = document.createElement('div');
        row.className = 'channel' + (current && current.uuid === station.uuid ? ' current' : '');
        row.style.top = (index * ROW) + 'px';
        row.dataset.index = String(index);

        if (station.favicon) {
            const image = document.createElement('img');
            image.loading = 'lazy';
            image.referrerPolicy = 'no-referrer';
            image.src = station.favicon;
            image.addEventListener('error', () => image.remove());
            row.appendChild(image);
        }
        const details = document.createElement('div');
        details.className = 'details';
        const name = document.createElement('span');
        name.className = 'name';
        name.textContent = station.name;
        details.appendChild(name);
        const meta = document.createElement('span');
        meta.className = 'meta';
        meta.textContent = [station.country, station.codec].filter(Boolean).join(' · ');
        details.appendChild(meta);
        row.appendChild(details);
        if (favorite(station)) {
            const star = document.createElement('span');
            star.className = 'star';
            star.textContent = '★';
            row.appendChild(star);
        }
        return row;
    }

    function drawRows() {
        const top = channelsBox.scrollTop;
        const height = channelsBox.clientHeight || 600;
        const first = Math.max(0, Math.floor(top / ROW) - 8);
        const last = Math.min(visibleStations.length, Math.ceil((top + height) / ROW) + 8);
        rows.forEach((row, index) => {
            if (index < first || index >= last) {
                row.remove();
                rows.delete(index);
            }
        });
        for (let index = first; index < last; index++) {
            if (!rows.has(index)) {
                const row = makeRow(visibleStations[index], index);
                rows.set(index, row);
                space.appendChild(row);
            }
        }
    }

    function updateMediaSession(station) {
        if (!('mediaSession' in navigator) || typeof MediaMetadata !== 'function') return;
        try {
            navigator.mediaSession.metadata = new MediaMetadata({
                title: currentTrackTitle || station.name,
                artist: station.name,
                album: S.title || 'Radio',
                artwork: station.favicon ? [{ src: station.favicon, sizes: '512x512' }] : []
            });
        } catch (_) {}
    }

    function setTrackTitle(value) {
        const raw = String(value || '').trim();
        if (!current || !raw || raw === currentRawTitle) return;
        currentRawTitle = raw;
        currentTrack = window.AABTrackParser.parse(raw, current.name);
        currentTrackTitle = currentTrack.display;
        showStation(current);
        updateMediaSession(current);
        clearTimeout(lyricsLookupTimer);
        const track = getLyricsTrack();
        lyricsLookupTimer = setTimeout(() => {
            lyricsController?.autoLookup(track);
        }, 800);
    }

    function getLyricsTrack() {
        if (!current) return null;
        if (!currentTrack || !currentTrack.isSong) {
            return { artist: '', title: '', display: currentTrackTitle || current.name, sync: false };
        }
        return Object.assign({}, currentTrack, { sync: false });
    }

    function resetLyricsLookup() {
        clearTimeout(lyricsLookupTimer);
        lyricsLookupTimer = null;
        lyricsController?.autoLookup(getLyricsTrack());
    }

    function decodeId3Text(bytes, offset, end) {
        if (offset >= end) return '';
        const encoding = bytes[offset++];
        let decoder;
        if (encoding === 1) decoder = new TextDecoder('utf-16');
        else if (encoding === 2) decoder = new TextDecoder('utf-16be');
        else if (encoding === 3) decoder = new TextDecoder('utf-8');
        else decoder = new TextDecoder('windows-1252');
        return decoder.decode(bytes.subarray(offset, end)).replace(/\0+$/g, '').trim();
    }

    function id3TextFrame(bytes, frameId, offset, end) {
        if (frameId !== 'TIT2' && frameId !== 'TXXX') return null;
        if (offset >= end) return null;
        if (frameId === 'TIT2') return decodeId3Text(bytes, offset, end) || null;

        const encoding = bytes[offset++];
        const terminatorWidth = encoding === 1 || encoding === 2 ? 2 : 1;
        let valueStart = offset;
        while (valueStart + terminatorWidth <= end) {
            if (bytes[valueStart] === 0 &&
                (terminatorWidth === 1 || bytes[valueStart + 1] === 0)) {
                valueStart += terminatorWidth;
                break;
            }
            valueStart += terminatorWidth;
        }
        const description = decodeId3Text(bytes, offset, valueStart - terminatorWidth);
        if (description.toLowerCase() !== 'streamtitle') return null;
        return decodeId3Text(bytes, valueStart, end) || null;
    }

    function id3Title(value) {
        const bytes = value instanceof Uint8Array
            ? value
            : value instanceof ArrayBuffer ? new Uint8Array(value) : null;
        if (!bytes || bytes.length < 10 || bytes[0] !== 0x49 || bytes[1] !== 0x44 || bytes[2] !== 0x33) return null;

        const version = bytes[3];
        const tagSize = ((bytes[6] & 0x7f) << 21) | ((bytes[7] & 0x7f) << 14) |
            ((bytes[8] & 0x7f) << 7) | (bytes[9] & 0x7f);
        const tagEnd = Math.min(bytes.length, 10 + tagSize);
        let offset = 10;
        if (bytes[5] & 0x40) {
            if (offset + 4 > tagEnd) return null;
            const extendedSize = version >= 4
                ? ((bytes[offset] & 0x7f) << 21) | ((bytes[offset + 1] & 0x7f) << 14) |
                    ((bytes[offset + 2] & 0x7f) << 7) | (bytes[offset + 3] & 0x7f)
                : (bytes[offset] << 24) | (bytes[offset + 1] << 16) |
                    (bytes[offset + 2] << 8) | bytes[offset + 3];
            offset += 4 + extendedSize;
        }
        while (offset + 10 <= tagEnd) {
            const frameId = String.fromCharCode(...bytes.subarray(offset, offset + 4));
            if (!/^[A-Z0-9]{4}$/.test(frameId)) break;
            const frameSize = version >= 4
                ? ((bytes[offset + 4] & 0x7f) << 21) | ((bytes[offset + 5] & 0x7f) << 14) |
                    ((bytes[offset + 6] & 0x7f) << 7) | (bytes[offset + 7] & 0x7f)
                : (bytes[offset + 4] << 24) | (bytes[offset + 5] << 16) |
                    (bytes[offset + 6] << 8) | bytes[offset + 7];
            const frameStart = offset + 10;
            const frameEnd = Math.min(tagEnd, frameStart + frameSize);
            const title = id3TextFrame(bytes, frameId, frameStart, frameEnd);
            if (title) return title;
            offset = frameEnd;
        }
        return null;
    }

    function stopMetadataPolling() {
        if (metadataTimer !== null) {
            clearInterval(metadataTimer);
            metadataTimer = null;
        }
        metadataRequestPending = null;
        icyMetadataSupported = null;
    }

    async function pollIcyMetadata() {
        if (!current || media.paused || icyMetadataSupported === false) return;
        const stationUuid = current.uuid;
        const playToken = requestToken;
        if (metadataRequestPending === playToken) return;
        metadataRequestPending = playToken;
        try {
            const result = await api('/api/radio/metadata', { uuid: stationUuid, source: current.s || '' });
            if (!current || current.uuid !== stationUuid || requestToken !== playToken) return;
            if (result.supported === false) {
                icyMetadataSupported = false;
                stopMetadataPolling();
                return;
            }
            icyMetadataSupported = result.supported === true;
            if (result.title) setTrackTitle(result.title);
        } catch (_) {
            // Metadata is optional; keep playback running and retry on the next interval.
        } finally {
            if (metadataRequestPending === playToken) metadataRequestPending = null;
        }
    }

    function startMetadataPolling() {
        stopMetadataPolling();
        if (!currentUrl || currentIsHls) return;
        pollIcyMetadata();
        metadataTimer = setInterval(pollIcyMetadata, 15_000);
    }

    function stopStream() {
        stopMetadataPolling();
        if (hls) {
            hls.destroy();
            hls = null;
        }
        media.pause();
        media.removeAttribute('src');
        media.load();
        currentUrl = '';
        currentIsHls = false;
        document.body.classList.remove('is-playing');
    }

    async function play(station) {
        current = station;
        currentRawTitle = '';
        currentTrack = null;
        currentTrackTitle = '';
        resetLyricsLookup();
        state.lastUuid = station.uuid;
        keepExtraStations();
        saveState();
        showStation(station);
        updateMediaSession(station);
        render();
        message(S.loading || 'Loading…');
        const token = ++requestToken;
        stopStream();
        try {
            const info = await api('/api/radio/play', { uuid: station.uuid, source: station.s || '' });
            if (token !== requestToken) return;
            currentUrl = info.url;
            currentIsHls = info.kind === 'hls';
            if (currentIsHls && window.Hls && Hls.isSupported()) {
                hls = new Hls({ backBufferLength: 30, maxBufferLength: 30 });
                hls.on(Hls.Events.ERROR, (_, data) => {
                    if (data.fatal) message(S.unavailable || 'Station unavailable');
                });
                if (Hls.Events.FRAG_PARSING_METADATA) {
                    hls.on(Hls.Events.FRAG_PARSING_METADATA, (_, data) => {
                        (data.samples || []).forEach((sample) => {
                            const title = id3Title(sample.data);
                            if (title) setTrackTitle(title);
                        });
                    });
                }
                hls.loadSource(info.url);
                hls.attachMedia(media);
            } else {
                media.src = info.url;
            }
            const attempt = media.play();
            if (attempt && attempt.catch) attempt.catch(() => {});
        } catch (_) {
            if (token === requestToken) message(S.unavailable || 'Station unavailable');
        }
    }

    function step(offset) {
        if (!visibleStations.length) return;
        const currentIndex = current ? visibleStations.findIndex((station) => station.uuid === current.uuid) : -1;
        const target = currentIndex < 0
            ? (offset < 0 ? visibleStations.length - 1 : 0)
            : (currentIndex + offset + visibleStations.length) % visibleStations.length;
        play(visibleStations[target]);
        const top = target * ROW;
        if (top < channelsBox.scrollTop || top + ROW > channelsBox.scrollTop + channelsBox.clientHeight) {
            channelsBox.scrollTop = Math.max(0, top - ROW);
        }
    }

    // L'ultima ascoltata e i preferiti restano disponibili anche scegliendo un'altra lista
    function keepExtraStations() {
        const kept = {};
        const known = new Map(Object.values(state.extra).concat(stations).map((station) => [station.uuid, station]));
        state.favorites.concat(state.lastUuid).forEach((uuid) => {
            const station = known.get(uuid);
            if (station) kept[uuid] = station;
        });
        state.extra = kept;
    }

    function setFavoritesView(on) {
        showFavorites = on;
        byId('favoriteStations').classList.toggle('active', on);
        byId('allStations').classList.toggle('active', !on);
    }

    const picker = window.createSourcePicker(byId('source'), {
        section: 'radio',
        sources: config.sources,
        addLabel: S.addList,
        phoneOnlyText: S.addOnPhone,
        messageBox: byId('message'),
        onChange: selectSource
    });

    // Avvisata dall'app quando le liste cambiano nelle Impostazioni: la stazione in onda continua
    window.aabSourcesChanged = function () {
        api('/api/radio/sources').then((data) => picker.update(data.sources)).catch(() => {});
    };

    function selectSource(id) {
        state.source = id;
        saveState();
        picker.select(id);
        foundStations = null;
        byId('search').value = '';
        setFavoritesView(false);
        channelsBox.scrollTop = 0;
        stations = [];
        render();
        return loadStations(false);
    }

    async function loadStations(refresh) {
        const token = ++listToken;
        message(S.loading || 'Loading…');
        try {
            const data = await api('/api/radio/stations', Object.assign({ source: state.source }, refresh ? { refresh: '1' } : {}));
            if (token !== listToken) return;
            stations = data.stations || [];
            keepExtraStations();
            saveState();
            byId('allStations').textContent = S.allStations || 'Stations';
            byId('favoriteStations').textContent = S.favorites || 'Favorites';
            const previous = stationByUuid(state.lastUuid);
            // Aggiornando l'elenco mentre suona non si perde quello che si sta ascoltando
            if (currentUrl && current) {
                current = previous || current;
            } else if (previous) {
                current = previous;
                currentRawTitle = '';
                currentTrack = null;
                currentTrackTitle = '';
                showStation(previous);
                updateMediaSession(previous);
            } else {
                current = null;
                showStation(null);
            }
            render();
            message(stations.length ? '' : (S.noResults || 'No stations found'));
        } catch (_) {
            if (token === listToken) message(S.error || 'Could not load radio stations');
        }
    }

    // ------------------------------------------------------------------
    // Comandi vocali: la richiesta la tiene l'app (MediaVoice)
    // ------------------------------------------------------------------

    function resumePlayback() {
        if (!current) {
            if (visibleStations.length) play(visibleStations[0]);
        } else if (currentUrl) {
            media.play().catch(() => {});
        } else {
            play(current);
        }
    }

    async function runVoiceSearch(request) {
        message(S.loading || 'Loading…');
        let data;
        try {
            data = await api('/api/radio/find', { q: request.query, source: state.source });
        } catch (_) {
            message(S.error || 'Could not load radio stations');
            return;
        }
        const results = data.stations || [];
        const confident = results.filter((station) => station.m >= data.confident);
        if (!confident.length && request.fallback) {
            message('');
            api('/api/voice/fallback').catch(() => {});
            return;
        }
        // Le stazioni nuove entrano nell'elenco, così i tasti e i preferiti funzionano anche per loro
        const known = new Map(stations.map((station) => [station.uuid, station]));
        const found = results.map((station) => {
            if (known.has(station.uuid)) return known.get(station.uuid);
            stations.push(station);
            known.set(station.uuid, station);
            return station;
        });
        foundStations = found;
        foundQuery = request.query;
        byId('search').value = foundQuery;
        setFavoritesView(false);
        channelsBox.scrollTop = 0;
        render();
        if (!found.length) {
            message(S.noResults || 'No stations found');
            return;
        }
        message('');
        if (request.play && confident.length) {
            const uuids = new Set(confident.map((station) => station.uuid));
            const candidates = found.filter((station) => uuids.has(station.uuid));
            play(candidates.find(favorite) || candidates[0]);
        }
    }

    async function runVoice(request) {
        if (request.action === 'pause') media.pause();
        else if (request.action === 'next') step(1);
        else if (request.action === 'previous') step(-1);
        else if (request.query) await runVoiceSearch(request);
        else if ((request.play || request.action === 'play') && media.paused) resumePlayback();
    }

    window.aabVoice = async function () {
        await stationsReady;
        try {
            const request = await api('/api/voice', { section: 'radio' });
            if (request.query || request.play || request.action) await runVoice(request);
        } catch (_) {
            // Il comando resta senza effetto: la pagina continua come prima
        }
    };

    byId('artImage').addEventListener('error', () => byId('art').classList.remove('has-art'));
    byId('channels').addEventListener('click', (event) => {
        const row = event.target.closest('.channel');
        const station = row && visibleStations[Number(row.dataset.index)];
        if (station) play(station);
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
    byId('search').placeholder = S.search || 'Search stations';
    byId('search').addEventListener('input', () => {
        foundStations = null;
        channelsBox.scrollTop = 0;
        render();
    });
    byId('allStations').addEventListener('click', () => {
        setFavoritesView(false);
        render();
    });
    byId('favoriteStations').addEventListener('click', () => {
        setFavoritesView(true);
        render();
    });
    byId('refresh').addEventListener('click', () => loadStations(true));
    byId('favorite').addEventListener('click', () => {
        if (!current) return;
        state.favorites = favorite(current)
            ? state.favorites.filter((uuid) => uuid !== current.uuid)
            : state.favorites.concat(current.uuid);
        keepExtraStations();
        saveState();
        render();
    });
    byId('playToggle').addEventListener('click', () => {
        if (current && !media.paused) media.pause();
        else resumePlayback();
    });
    byId('next').addEventListener('click', () => step(1));
    byId('previous').addEventListener('click', () => step(-1));
    media.addEventListener('playing', () => {
        document.body.classList.add('is-playing');
        message('');
        startMetadataPolling();
    });
    media.addEventListener('pause', () => document.body.classList.remove('is-playing'));
    media.addEventListener('error', () => {
        if (current) message(S.unavailable || 'Station unavailable');
    });

    lyricsController = window.createLyricsController(
        byId('lyricsButton'),
        media,
        getLyricsTrack,
        S,
        config.client || 'CarWeb (https://github.com/fcaronte/AABrowser)'
    );

    if ('mediaSession' in navigator) {
        [['play', () => current && (currentUrl ? media.play() : play(current))],
            ['pause', () => media.pause()],
            ['nexttrack', () => step(1)],
            ['previoustrack', () => step(-1)]].forEach(([action, handler]) => {
            try {
                navigator.mediaSession.setActionHandler(action, handler);
            } catch (_) {}
        });
    }

    // La lista automatica cambia con la lingua dell'app: una lista che non c'è più torna alla prima
    state.source = picker.has(state.source) ? state.source : ((picker.list()[0] || {}).id || '');
    picker.select(state.source);
    const stationsReady = loadStations(false);
    window.aabVoice();
})();
