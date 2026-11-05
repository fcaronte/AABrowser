(function () {
    'use strict';

    const config = window.AAB_RADIO || { token: '', country: 'US', strings: {} };
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
    let currentUrl = '';
    let hls = null;
    let showFavorites = false;
    let drawPending = false;
    let requestToken = 0;
    let state = readState();

    function readState() {
        try {
            return JSON.parse(localStorage.getItem(STORE_KEY)) || { favorites: [], lastUuid: '' };
        } catch (_) {
            return { favorites: [], lastUuid: '' };
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
        byId('title').textContent = station ? station.name : (S.title || 'Radio');
        byId('subtitle').textContent = station
            ? [station.country, station.codec].filter(Boolean).join(' · ')
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

    function filteredStations() {
        const query = byId('search').value.trim().toLocaleLowerCase();
        return stations.filter((station) => {
            if (showFavorites && !favorite(station)) return false;
            return !query || station.name.toLocaleLowerCase().includes(query) ||
                station.country.toLocaleLowerCase().includes(query);
        });
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
                title: station.name,
                artist: station.country || S.title || 'Radio',
                album: S.title || 'Radio',
                artwork: station.favicon ? [{ src: station.favicon, sizes: '512x512' }] : []
            });
        } catch (_) {}
    }

    function stopStream() {
        if (hls) {
            hls.destroy();
            hls = null;
        }
        media.pause();
        media.removeAttribute('src');
        media.load();
        currentUrl = '';
        document.body.classList.remove('is-playing');
    }

    async function play(station) {
        current = station;
        state.lastUuid = station.uuid;
        saveState();
        showStation(station);
        updateMediaSession(station);
        render();
        message(S.loading || 'Loading…');
        const token = ++requestToken;
        stopStream();
        try {
            const info = await api('/api/radio/play', { uuid: station.uuid });
            if (token !== requestToken) return;
            currentUrl = info.url;
            if (/\.m3u8(?:$|[?#])/i.test(info.url) && window.Hls && Hls.isSupported()) {
                hls = new Hls({ backBufferLength: 30, maxBufferLength: 30 });
                hls.on(Hls.Events.ERROR, (_, data) => {
                    if (data.fatal) message(S.unavailable || 'Station unavailable');
                });
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

    async function loadStations(refresh) {
        message(S.loading || 'Loading…');
        try {
            const data = await api('/api/radio/stations', refresh ? { refresh: '1' } : {});
            stations = data.stations || [];
            byId('country').textContent = S.inCountry || S.title || 'Radio';
            byId('allStations').textContent = S.allStations || 'Stations';
            byId('favoriteStations').textContent = S.favorites || 'Favorites';
            const previous = stations.find((station) => station.uuid === state.lastUuid);
            if (previous) {
                current = previous;
                showStation(previous);
                updateMediaSession(previous);
            } else {
                current = null;
                showStation(null);
            }
            render();
            message(stations.length ? '' : (S.noResults || 'No stations found'));
        } catch (_) {
            message(S.error || 'Could not load radio stations');
        }
    }

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
        channelsBox.scrollTop = 0;
        render();
    });
    byId('allStations').addEventListener('click', () => {
        showFavorites = false;
        byId('allStations').classList.add('active');
        byId('favoriteStations').classList.remove('active');
        render();
    });
    byId('favoriteStations').addEventListener('click', () => {
        showFavorites = true;
        byId('favoriteStations').classList.add('active');
        byId('allStations').classList.remove('active');
        render();
    });
    byId('refresh').addEventListener('click', () => loadStations(true));
    byId('favorite').addEventListener('click', () => {
        if (!current) return;
        state.favorites = favorite(current)
            ? state.favorites.filter((uuid) => uuid !== current.uuid)
            : state.favorites.concat(current.uuid);
        saveState();
        render();
    });
    byId('playToggle').addEventListener('click', () => {
        if (!current) {
            if (visibleStations.length) play(visibleStations[0]);
        } else if (media.paused) {
            if (currentUrl) media.play().catch(() => {});
            else play(current);
        } else {
            media.pause();
        }
    });
    byId('next').addEventListener('click', () => step(1));
    byId('previous').addEventListener('click', () => step(-1));
    media.addEventListener('playing', () => {
        document.body.classList.add('is-playing');
        message('');
    });
    media.addEventListener('pause', () => document.body.classList.remove('is-playing'));
    media.addEventListener('error', () => {
        if (current) message(S.unavailable || 'Station unavailable');
    });

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

    loadStations(false);
})();
