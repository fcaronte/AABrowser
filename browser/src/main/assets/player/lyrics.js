(function () {
    'use strict';

    const CACHE_KEY = 'aabLyricsCache';
    const CACHE_TTL = 30 * 24 * 60 * 60 * 1000;
    const NEGATIVE_TTL = 6 * 60 * 60 * 1000;
    let retryAfterUntil = 0;

    function createController(button, media, getTrack, strings, client) {
        const text = (key, fallback) => strings[key] || fallback;
        const overlay = document.createElement('div');
        overlay.className = 'lyrics-overlay';
        overlay.hidden = true;
        const card = document.createElement('section');
        card.className = 'lyrics-card';
        card.setAttribute('role', 'dialog');
        card.setAttribute('aria-modal', 'true');
        const header = document.createElement('header');
        header.className = 'lyrics-header';
        const headingElement = document.createElement('div');
        headingElement.className = 'lyrics-heading';
        const closeButton = document.createElement('button');
        closeButton.className = 'lyrics-close';
        closeButton.type = 'button';
        closeButton.textContent = '×';
        header.append(headingElement, closeButton);
        const searchForm = document.createElement('form');
        searchForm.className = 'lyrics-search';
        const searchInput = document.createElement('input');
        searchInput.type = 'search';
        searchInput.autocomplete = 'off';
        const searchButton = document.createElement('button');
        searchButton.type = 'submit';
        searchForm.append(searchInput, searchButton);
        const content = document.createElement('div');
        content.className = 'lyrics-content';
        card.append(header, searchForm, content);
        overlay.appendChild(card);
        document.body.appendChild(overlay);

        const heading = headingElement;
        let activeLines = [];
        let currentActiveLine = null;
        let syncEnabled = false;
        let lookupVersion = 0;

        button.textContent = text('lyricsButton', 'Lyrics');
        closeButton.setAttribute('aria-label', text('lyricsClose', 'Close'));
        searchInput.placeholder = text('lyricsSearchHint', 'Search artist or title');
        searchButton.textContent = text('lyricsSearch', 'Search');

        function close() {
            lookupVersion++;
            overlay.hidden = true;
            activeLines = [];
            currentActiveLine = null;
        }

        function status(message) {
            content.textContent = message;
            content.className = 'lyrics-content lyrics-status';
            activeLines = [];
        }

        function readCache() {
            try {
                return JSON.parse(localStorage.getItem(CACHE_KEY) || '{}');
            } catch (_) {
                return {};
            }
        }

        function cached(key) {
            const entry = readCache()[key];
            if (!entry || entry.expiresAt <= Date.now()) return null;
            return entry;
        }

        function store(key, value, negative) {
            try {
                const cache = readCache();
                cache[key] = {
                    value: value,
                    expiresAt: Date.now() + (negative ? NEGATIVE_TTL : CACHE_TTL)
                };
                const entries = Object.entries(cache)
                    .filter((entry) => entry[1] && entry[1].expiresAt > Date.now())
                    .slice(-80);
                localStorage.setItem(CACHE_KEY, JSON.stringify(Object.fromEntries(entries)));
            } catch (_) {
                // Lyrics remain usable when browser storage is unavailable or full.
            }
        }

        function keyFor(track) {
            return [track.artist, track.title, track.album]
                .map((part) => String(part || '').trim().toLocaleLowerCase())
                .join('|');
        }

        async function requestJson(url) {
            if (Date.now() < retryAfterUntil) throw new Error('rate-limit');
            const controller = new AbortController();
            const timeout = setTimeout(() => controller.abort(), 12000);
            try {
                const response = await fetch(url, {
                    headers: { 'X-User-Agent': client },
                    signal: controller.signal,
                    cache: 'no-store'
                });
                if (response.status === 429 || response.status === 503) {
                    const retryAfter = response.headers.get('Retry-After') || '';
                    const seconds = Number(retryAfter);
                    const retryAt = Number.isFinite(seconds) && seconds > 0
                        ? Date.now() + seconds * 1000
                        : Date.parse(retryAfter);
                    retryAfterUntil = Number.isFinite(retryAt) && retryAt > Date.now()
                        ? retryAt
                        : Date.now() + 60_000;
                }
                if (!response.ok) return { status: response.status, data: null };
                return { status: response.status, data: await response.json() };
            } finally {
                clearTimeout(timeout);
            }
        }

        function buildGetUrl(track) {
            const params = new URLSearchParams({
                track_name: track.title,
                artist_name: track.artist
            });
            if (track.album) params.set('album_name', track.album);
            const duration = Math.floor(Number(track.duration));
            if (duration >= 1 && duration <= 3600) params.set('duration', String(duration));
            return 'https://lrclib.net/api/get?' + params.toString();
        }

        async function getRecord(track, id) {
            const url = id
                ? 'https://lrclib.net/api/get/' + encodeURIComponent(id)
                : buildGetUrl(track);
            const response = await requestJson(url);
            if (response.status === 200 && response.data) return response.data;
            if (response.status === 404) return null;
            if (response.status === 429 || response.status === 503) throw new Error('rate-limit');
            throw new Error('HTTP ' + response.status);
        }

        function searchUrl(track, query) {
            const params = new URLSearchParams();
            if (query) {
                params.set('q', query);
            } else if (track.artist) {
                params.set('track_name', track.title);
                params.set('artist_name', track.artist);
                if (track.album) params.set('album_name', track.album);
            } else {
                params.set('q', track.title);
            }
            return 'https://lrclib.net/api/search?' + params.toString();
        }

        function parseLrc(value) {
            const lines = [];
            String(value || '').split(/\r?\n/).forEach((line) => {
                const times = [...line.matchAll(/\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\]/g)];
                const lyric = line.replace(/\[[^\]]+\]/g, '').trim();
                if (!lyric) return;
                if (!times.length) {
                    lines.push({ time: null, text: lyric });
                    return;
                }
                times.forEach((match) => {
                    const fraction = Number('0.' + (match[3] || '0'));
                    lines.push({
                        time: Number(match[1]) * 60 + Number(match[2]) + fraction,
                        text: lyric
                    });
                });
            });
            return lines.sort((a, b) => (a.time || 0) - (b.time || 0));
        }

        function showRecord(record, track) {
            heading.textContent = [record.trackName || track.title, record.artistName || track.artist]
                .filter(Boolean).join(' — ');
            content.className = 'lyrics-content';
            content.textContent = '';
            activeLines = [];
            currentActiveLine = null;
            syncEnabled = track.sync === true && Number.isFinite(media.duration);

            if (record.instrumental) {
                status(text('lyricsInstrumental', 'This track is instrumental.'));
                return;
            }
            const source = record.syncedLyrics || record.plainLyrics || '';
            const lines = record.syncedLyrics ? parseLrc(source) : [];
            if (lines.length) {
                const fragment = document.createDocumentFragment();
                lines.forEach((line) => {
                    const element = document.createElement('div');
                    element.className = 'lyrics-line';
                    element.textContent = line.text;
                    element.dataset.time = line.time === null ? '' : String(line.time);
                    fragment.appendChild(element);
                    if (line.time !== null) activeLines.push(element);
                });
                content.appendChild(fragment);
                updateActiveLine();
            } else if (record.plainLyrics || source) {
                content.textContent = record.plainLyrics || source;
            } else {
                status(text('lyricsNotFound', 'Lyrics not found.'));
            }
        }

        function updateActiveLine() {
            if (!syncEnabled || !activeLines.length || !overlay || overlay.hidden) return;
            const currentTime = media.currentTime || 0;
            let active = null;
            for (const line of activeLines) {
                const time = Number(line.dataset.time);
                if (time > currentTime) break;
                active = line;
            }
            if (currentActiveLine !== active) {
                if (currentActiveLine) currentActiveLine.classList.remove('active');
                currentActiveLine = active;
                if (active) active.classList.add('active');
            }
            if (active && currentActiveLine === active) {
                const contentRect = content.getBoundingClientRect();
                const lineRect = active.getBoundingClientRect();
                if (active.dataset.wasVisible !== 'true' ||
                    lineRect.top < contentRect.top || lineRect.bottom > contentRect.bottom) {
                    active.scrollIntoView({ block: 'center', behavior: 'smooth' });
                    active.dataset.wasVisible = 'true';
                }
            }
        }

        function failure(error) {
            status(text('lyricsError', 'Could not load lyrics.'));
        }

        async function showSearchResults(track, records, version) {
            if (version !== lookupVersion) return;
            const candidates = Array.isArray(records)
                ? records.filter((record) => record && (record.plainLyrics || record.syncedLyrics))
                : [];
            if (!candidates.length) {
                store(keyFor(track), null, true);
                status(text('lyricsNotFound', 'Lyrics not found.'));
                return;
            }
            if (candidates.length === 1) {
                const record = await getRecord(track, candidates[0].id);
                if (version !== lookupVersion) return;
                if (!record) {
                    status(text('lyricsNotFound', 'Lyrics not found.'));
                    return;
                }
                store(keyFor(track), record, false);
                showRecord(record, track);
                return;
            }
            heading.textContent = text('lyricsChooseMatch', 'Choose a matching track');
            content.className = 'lyrics-content';
            content.textContent = '';
            candidates.slice(0, 10).forEach((record) => {
                const choice = document.createElement('button');
                choice.type = 'button';
                choice.className = 'lyrics-result';
                const title = document.createElement('span');
                title.className = 'lyrics-result-title';
                title.textContent = record.trackName || record.name || '';
                const artist = document.createElement('span');
                artist.className = 'lyrics-result-artist';
                artist.textContent = [record.artistName, record.albumName].filter(Boolean).join(' · ');
                choice.append(title, artist);
                choice.addEventListener('click', async () => {
                    const selectionVersion = ++lookupVersion;
                    status(text('lyricsLoading', 'Loading lyrics…'));
                    try {
                        const selected = await getRecord(track, record.id);
                        if (selectionVersion !== lookupVersion) return;
                        if (!selected) {
                            status(text('lyricsNotFound', 'Lyrics not found.'));
                            return;
                        }
                        store(keyFor(track), selected, false);
                        showRecord(selected, track);
                    } catch (error) {
                        if (selectionVersion === lookupVersion) failure(error);
                    }
                });
                content.appendChild(choice);
            });
        }

        async function search(track, query, version) {
            status(text('lyricsLoading', 'Loading lyrics…'));
            try {
                const response = await requestJson(searchUrl(track, query));
                if (version !== lookupVersion) return;
                if (response.status === 429 || response.status === 503) throw new Error('rate-limit');
                if (response.status !== 200 || !Array.isArray(response.data)) {
                    throw new Error('HTTP ' + response.status);
                }
                await showSearchResults(track, response.data, version);
            } catch (error) {
                if (version === lookupVersion) failure(error);
            }
        }

        async function load(track, version) {
            const cacheKey = keyFor(track);
            const entry = cached(cacheKey);
            if (entry) {
                if (entry.value) showRecord(entry.value, track);
                else status(text('lyricsNotFound', 'Lyrics not found.'));
                return;
            }
            status(text('lyricsLoading', 'Loading lyrics…'));
            try {
                if (track.artist) {
                    const direct = await getRecord(track);
                    if (version !== lookupVersion) return;
                    if (direct) {
                        store(cacheKey, direct, false);
                        showRecord(direct, track);
                        return;
                    }
                }
                await search(track, null, version);
            } catch (error) {
                if (version === lookupVersion) failure(error);
            }
        }

        function normalized(value) {
            return String(value || '').normalize('NFKC').toLocaleLowerCase()
                .replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
        }

        async function autoLookup(track) {
            const version = ++lookupVersion;
            if (!track) return;
            if (!track.title) {
                if (!overlay.hidden) {
                    heading.textContent = track.display || text('lyricsButton', 'Lyrics');
                    status(text('lyricsSearchHint', 'Search artist or title'));
                }
                return;
            }
            const cacheKey = keyFor(track);
            const entry = cached(cacheKey);
            if (overlay.hidden === false) {
                heading.textContent = track.display || [track.title, track.artist].filter(Boolean).join(' — ');
                status(entry
                    ? (entry.value ? '' : text('lyricsNotFound', 'Lyrics not found.'))
                    : text('lyricsLoading', 'Loading lyrics…'));
                if (entry && entry.value) showRecord(entry.value, track);
            }
            if (entry) return;

            try {
                let record = track.artist ? await getRecord(track) : null;
                if (version !== lookupVersion) return;
                if (!record) {
                    const response = await requestJson(searchUrl(track, null));
                    if (version !== lookupVersion) return;
                    if (response.status === 429 || response.status === 503) throw new Error('rate-limit');
                    if (response.status !== 200 || !Array.isArray(response.data)) {
                        throw new Error('HTTP ' + response.status);
                    }
                    const titleKey = normalized(track.title);
                    const artistKey = normalized(track.artist);
                    record = response.data.find((candidate) =>
                        candidate && (candidate.plainLyrics || candidate.syncedLyrics) &&
                        normalized(candidate.trackName) === titleKey &&
                        (!artistKey || normalized(candidate.artistName) === artistKey)
                    ) || null;
                }
                if (version !== lookupVersion) return;
                store(cacheKey, record, !record);
                if (!overlay.hidden) {
                    if (record) showRecord(record, track);
                    else status(text('lyricsNotFound', 'Lyrics not found.'));
                }
            } catch (error) {
                if (version === lookupVersion && !overlay.hidden) failure(error);
            }
        }

        button.addEventListener('click', () => {
            const track = getTrack();
            if (!track) return;
            const version = ++lookupVersion;
            overlay.hidden = false;
            heading.textContent = track.display || [track.title, track.artist].filter(Boolean).join(' — ')
                || text('lyricsButton', 'Lyrics');
            searchInput.value = [track.artist, track.title].filter(Boolean).join(' - ');
            if (track.title) load(track, version);
            else status(text('lyricsSearchHint', 'Search artist or title'));
        });
        searchForm.addEventListener('submit', (event) => {
            event.preventDefault();
            const query = searchInput.value.trim();
            if (!query) return;
            const parts = query.match(/^(.+?)\s*[-–—]\s*(.+)$/);
            const track = parts
                ? { artist: parts[1].trim(), title: parts[2].trim(), sync: false }
                : { artist: '', title: query, sync: false };
            search(track, query, ++lookupVersion);
        });
        closeButton.addEventListener('click', close);
        overlay.addEventListener('click', (event) => {
            if (event.target === overlay) close();
        });
        document.addEventListener('keydown', (event) => {
            if (event.key === 'Escape' && !overlay.hidden) close();
        });
        media.addEventListener('timeupdate', updateActiveLine);

        return { update: updateActiveLine, autoLookup };
    }

    window.createLyricsController = createController;
})();
