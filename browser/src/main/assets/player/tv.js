/*
 * TV di CarWeb Media: liste M3U e account Xtream aggiunti nelle Impostazioni dell'app. L'app legge le liste e fa da
 * tramite per gli stream (IptvApi, IptvProxy): qui arrivano solo nomi, gruppi, loghi e indirizzi del tramite.
 * Gruppi, ricerca, preferiti di tutte le liste insieme e ultimo canale (salvati nella pagina); HLS con hls.js, MPEG-TS con mpegts.js, il resto
 * direttamente nel video. Sul player di Android Auto ⏮ ⏭ cambiano canale (Media Session).
 */
(function () {
    'use strict';

    const config = window.AAB_TV || { token: '', strings: {}, sources: [] };
    const S = config.strings;
    const STORE_KEY = 'aabTvState';
    const ROW = 56; // altezza di una riga della lista (tv.css)

    const byId = (id) => document.getElementById(id);
    const media = byId('media');
    const searchInput = byId('search');
    const groupsBox = byId('groups');
    const channelsBox = byId('channels');
    const space = byId('channelsSpace');
    const message = byId('message');

    // { source, group (indice o 'fav'), last: canale con la sua lista (s), favs: [canali, ognuno con la sua lista (s)] }
    const state = readState();
    // Fino alla 2.6 i preferiti erano per lista ({ lista: [canali] }): ora sono insieme, ognuno con la sua lista
    if (state.favorites && !state.favs) {
        state.favs = [];
        Object.keys(state.favorites).forEach((source) => {
            (state.favorites[source] || []).forEach((channel) => {
                if (!state.favs.some((favorite) => favorite.s === source && favorite.k === channel.k)) {
                    state.favs.push(Object.assign({}, channel, { s: source }));
                }
            });
        });
        delete state.favorites;
        saveState();
    }
    let groups = [];
    let items = []; // canali mostrati: un gruppo, la ricerca o i preferiti
    let current = null; // { s, k, n, l, g }
    let hls = null;
    let ts = null;
    let retried = false;
    let loadToken = 0; // le risposte arrivate dopo un altro clic si scartano
    let playToken = 0;
    const rows = new Map();

    function readState() {
        try {
            return JSON.parse(localStorage.getItem(STORE_KEY)) || {};
        } catch (_) {
            return {};
        }
    }

    function saveState() {
        try {
            localStorage.setItem(STORE_KEY, JSON.stringify(state));
        } catch (_) {
        }
    }

    function request(path, params) {
        const query = new URLSearchParams(Object.assign({}, params, { t: config.token }));
        return fetch(path + '?' + query).then((response) => {
            // App riavviata: la pagina si ricarica per avere il token nuovo
            if (response.status === 403) {
                location.reload();
                throw new Error('token');
            }
            return response.ok ? response.json() : Promise.reject(new Error(String(response.status)));
        });
    }

    function api(path, params) {
        return request('/api/tv/' + path, Object.assign({ source: state.source || '' }, params));
    }

    function showMessage(text) {
        message.textContent = text || '';
        message.hidden = !text;
    }

    // ------------------------------------------------------------------
    // Liste e gruppi
    // ------------------------------------------------------------------

    const picker = window.createSourcePicker(byId('source'), {
        section: 'tv',
        sources: config.sources,
        addLabel: S.addList,
        phoneOnlyText: S.addOnPhone,
        messageBox: message,
        onChange: (id) => {
            state.group = undefined;
            selectSource(id, false);
        }
    });

    // Avvisata dall'app quando le liste cambiano nelle Impostazioni: il canale in onda continua
    window.aabSourcesChanged = function () {
        request('/api/tv/sources').then((data) => picker.update(data.sources)).catch(() => {});
    };

    function selectSource(id, refresh) {
        state.source = id;
        saveState();
        picker.select(id);
        groupsBox.textContent = '';
        setItems([]);
        showMessage(S.loading);
        const token = ++loadToken;
        return api('groups', refresh ? { refresh: '1' } : {}).then((data) => {
            if (token !== loadToken) return null;
            groups = data.groups;
            showMessage('');
            renderGroups();
            if (state.group === 'fav' && favorites().length) {
                showFavorites();
                return null;
            }
            const known = groups.some((group) => group.i === state.group);
            return showGroup(known ? state.group : (groups.length ? groups[0].i : null));
        }).catch(() => {
            if (token === loadToken) showMessage(S.sourceError);
        });
    }

    function renderGroups() {
        groupsBox.textContent = '';
        addGroupChip('fav', '★ ' + S.favorites, showFavorites);
        groups.forEach((group) => {
            addGroupChip(group.i, (group.name || S.other) + ' · ' + group.count, () => showGroup(group.i));
        });
        markGroup();
    }

    function addGroupChip(key, label, onSelect) {
        const chip = document.createElement('button');
        chip.className = 'group';
        chip.dataset.key = String(key);
        chip.textContent = label;
        chip.addEventListener('click', () => {
            searchInput.value = '';
            onSelect();
        });
        groupsBox.appendChild(chip);
    }

    function markGroup() {
        groupsBox.querySelectorAll('.group').forEach((chip) => {
            chip.classList.toggle('active', chip.dataset.key === String(state.group));
        });
        const active = groupsBox.querySelector('.group.active');
        if (active) active.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    }

    function showGroup(index) {
        if (index === null || index === undefined) {
            setItems([]);
            return null;
        }
        state.group = index;
        saveState();
        markGroup();
        showMessage(S.loading);
        const token = ++loadToken;
        return api('channels', { group: String(index) }).then((data) => {
            if (token !== loadToken) return;
            showMessage('');
            setItems(data.channels);
        }).catch(() => {
            if (token === loadToken) showMessage(S.sourceError);
        });
    }

    function showFavorites() {
        ++loadToken;
        state.group = 'fav';
        saveState();
        markGroup();
        showMessage('');
        setItems(favorites());
    }

    let searchTimer = 0;
    searchInput.addEventListener('input', () => {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(() => {
            const query = searchInput.value.trim();
            if (query.length < 2) {
                if (state.group === 'fav') showFavorites();
                else showGroup(state.group);
                return;
            }
            const token = ++loadToken;
            api('channels', { q: query }).then((data) => {
                if (token !== loadToken) return;
                setItems(data.channels);
                showMessage(data.channels.length ? '' : S.noResults);
            }).catch(() => {
                if (token === loadToken) showMessage(S.sourceError);
            });
        }, 350);
    });

    // ------------------------------------------------------------------
    // Lista dei canali: disegna solo le righe visibili
    // ------------------------------------------------------------------

    function setItems(list) {
        items = list || [];
        rows.forEach((row) => row.remove());
        rows.clear();
        space.style.height = (items.length * ROW) + 'px';
        channelsBox.scrollTop = 0;
        drawRows();
    }

    function drawRows() {
        const top = channelsBox.scrollTop;
        const height = channelsBox.clientHeight || 600;
        const first = Math.max(0, Math.floor(top / ROW) - 8);
        const last = Math.min(items.length, Math.ceil((top + height) / ROW) + 8);
        rows.forEach((row, index) => {
            if (index < first || index >= last) {
                row.remove();
                rows.delete(index);
            }
        });
        for (let index = first; index < last; index++) {
            if (!rows.has(index)) {
                const row = makeRow(items[index], index);
                rows.set(index, row);
                space.appendChild(row);
            }
        }
    }

    function redrawRows() {
        rows.forEach((row) => row.remove());
        rows.clear();
        drawRows();
    }

    function makeRow(channel, index) {
        const row = document.createElement('div');
        row.className = 'channel';
        row.style.top = (index * ROW) + 'px';
        row.dataset.index = String(index);
        if (sameChannel(current, channel)) row.classList.add('current');
        if (channel.l) {
            const logo = document.createElement('img');
            logo.loading = 'lazy';
            logo.referrerPolicy = 'no-referrer';
            logo.addEventListener('error', () => logo.remove());
            logo.src = channel.l;
            row.appendChild(logo);
        }
        const name = document.createElement('span');
        name.className = 'name';
        name.textContent = channel.n;
        row.appendChild(name);
        if (isFavorite(channel)) {
            const star = document.createElement('span');
            star.className = 'star';
            star.textContent = '★';
            row.appendChild(star);
        }
        return row;
    }

    let drawPending = false;
    channelsBox.addEventListener('scroll', () => {
        if (drawPending) return;
        drawPending = true;
        requestAnimationFrame(() => {
            drawPending = false;
            drawRows();
        });
    });
    window.addEventListener('resize', drawRows);

    channelsBox.addEventListener('click', (event) => {
        const row = event.target.closest('.channel');
        const channel = row && items[Number(row.dataset.index)];
        if (channel) play(channel);
    });

    // ------------------------------------------------------------------
    // Preferiti: di tutte le liste insieme, ognuno con la sua lista
    // ------------------------------------------------------------------

    // Un canale è della lista da cui viene (s); quelli dell'elenco mostrato sono della lista scelta
    function sourceOf(channel) {
        return channel.s || state.source;
    }

    function sameChannel(a, b) {
        return !!a && !!b && a.k === b.k && sourceOf(a) === sourceOf(b);
    }

    // Quelli di una lista tolta non si vedono ma restano: tornano se torna la lista (o la lingua dell'app)
    function favorites() {
        return (state.favs || []).filter((favorite) => picker.has(favorite.s));
    }

    function isFavorite(channel) {
        return (state.favs || []).some((favorite) => sameChannel(favorite, channel));
    }

    function updateFavoriteButton() {
        byId('favorite').classList.toggle('active', !!current && isFavorite(current));
    }

    byId('favorite').addEventListener('click', () => {
        if (!current) return;
        const all = state.favs || [];
        state.favs = isFavorite(current)
            ? all.filter((favorite) => !sameChannel(favorite, current))
            : all.concat([{ s: sourceOf(current), k: current.k, n: current.n, l: current.l, g: current.g }]);
        saveState();
        updateFavoriteButton();
        if (state.group === 'fav' && !searchInput.value.trim()) setItems(favorites());
        else redrawRows();
    });

    // ------------------------------------------------------------------
    // Riproduzione
    // ------------------------------------------------------------------

    function showChannel(channel) {
        byId('title').textContent = channel.n;
        byId('subtitle').textContent = channel.g || '';
        const art = byId('art');
        const image = byId('artImage');
        if (channel.l) {
            image.src = channel.l;
            art.classList.add('has-art');
        } else {
            image.removeAttribute('src');
            art.classList.remove('has-art');
        }
        updateFavoriteButton();
        // Nome, gruppo e logo anche sul player di Android Auto
        if ('mediaSession' in navigator && typeof MediaMetadata === 'function') {
            try {
                navigator.mediaSession.metadata = new MediaMetadata({
                    title: channel.n,
                    artist: channel.g || 'TV',
                    album: 'CarWeb TV',
                    artwork: channel.l ? [{ src: channel.l, sizes: '512x512' }] : []
                });
            } catch (_) {
            }
        }
    }

    byId('artImage').addEventListener('error', () => byId('art').classList.remove('has-art'));

    function stopPlayer() {
        if (hls) {
            try {
                hls.destroy();
            } catch (_) {
            }
            hls = null;
        }
        if (ts) {
            try {
                ts.pause();
                ts.unload();
                ts.detachMediaElement();
                ts.destroy();
            } catch (_) {
            }
            ts = null;
        }
        media.removeAttribute('src');
        try {
            media.load();
        } catch (_) {
        }
        document.body.classList.remove('has-video');
    }

    // Anche un preferito di un'altra lista: parte dalla sua, senza cambiare quella scelta
    function play(channel) {
        current = Object.assign({}, channel, { s: sourceOf(channel) });
        retried = false;
        state.last = current;
        saveState();
        showChannel(current);
        redrawRows();
        showMessage(S.loading);
        const token = ++playToken;
        api('play', { ch: current.k, source: current.s }).then((info) => {
            if (token !== playToken) return;
            stopPlayer();
            start(info);
        }).catch(() => {
            if (token === playToken) showMessage(S.unavailable);
        });
    }

    function start(info) {
        if (info.kind === 'hls' && window.Hls && Hls.isSupported()) {
            hls = new Hls({ backBufferLength: 30, maxBufferLength: 30 });
            hls.on(Hls.Events.ERROR, (event, data) => {
                if (!data.fatal) return;
                // Un tentativo di ripresa, poi il messaggio
                if (!retried && data.type === Hls.ErrorTypes.NETWORK_ERROR) {
                    retried = true;
                    hls.startLoad();
                } else if (!retried && data.type === Hls.ErrorTypes.MEDIA_ERROR) {
                    retried = true;
                    hls.recoverMediaError();
                } else {
                    showMessage(S.unavailable);
                }
            });
            hls.loadSource(info.url);
            hls.attachMedia(media);
        } else if ((info.kind === 'ts' || info.kind === 'flv') && window.mpegts && mpegts.getFeatureList().mseLivePlayback) {
            ts = mpegts.createPlayer(
                { type: info.kind === 'flv' ? 'flv' : 'mpegts', isLive: true, url: info.url },
                { enableWorker: false, lazyLoad: false, autoCleanupSourceBuffer: true }
            );
            ts.on(mpegts.Events.ERROR, () => showMessage(S.unavailable));
            ts.attachMediaElement(media);
            ts.load();
        } else {
            media.src = info.url;
        }
        const attempt = media.play();
        if (attempt && attempt.catch) attempt.catch(() => {});
    }

    // Play: l'ultimo canale, mostrato all'apertura, parte al primo tocco; altrimenti riprende
    function resume() {
        if (current && !hls && !ts && !media.getAttribute('src')) {
            play(current);
            return;
        }
        const attempt = media.play();
        if (attempt && attempt.catch) attempt.catch(() => {});
    }

    function step(offset) {
        if (!items.length) return;
        const index = current ? items.findIndex((channel) => sameChannel(channel, current)) : -1;
        const target = index < 0 ? 0 : (index + offset + items.length) % items.length;
        play(items[target]);
        // La riga del canale resta in vista
        const top = target * ROW;
        if (top < channelsBox.scrollTop || top + ROW > channelsBox.scrollTop + channelsBox.clientHeight) {
            channelsBox.scrollTop = Math.max(0, top - ROW);
        }
    }

    media.addEventListener('playing', () => {
        showMessage('');
        document.body.classList.add('is-playing');
    });
    media.addEventListener('pause', () => document.body.classList.remove('is-playing'));
    media.addEventListener('loadedmetadata', () => {
        document.body.classList.toggle('has-video', media.videoWidth > 0);
    });
    media.addEventListener('error', () => {
        if (current && media.getAttribute('src')) showMessage(S.unavailable);
    });

    // Tocco sul video: schermo intero (anche in auto, dove il video riempie lo spazio dell'app)
    media.addEventListener('click', () => {
        if (document.fullscreenElement) {
            document.exitFullscreen().catch(() => {});
        } else if (media.requestFullscreen && document.body.classList.contains('has-video')) {
            media.requestFullscreen().catch(() => {});
        }
    });

    byId('playToggle').addEventListener('click', () => {
        if (!current) {
            if (items.length) play(items[0]);
        } else if (media.paused) {
            resume();
        } else {
            media.pause();
        }
    });
    byId('next').addEventListener('click', () => step(1));
    byId('previous').addEventListener('click', () => step(-1));

    if ('mediaSession' in navigator) {
        const handlers = {
            play: () => resume(),
            pause: () => media.pause(),
            nexttrack: () => step(1),
            previoustrack: () => step(-1)
        };
        Object.keys(handlers).forEach((action) => {
            try {
                navigator.mediaSession.setActionHandler(action, handlers[action]);
            } catch (_) {
            }
        });
    }

    // ------------------------------------------------------------------
    // Comandi vocali: la richiesta la tiene l'app (MediaVoice)
    // ------------------------------------------------------------------

    let ready = Promise.resolve();

    // Prima nella lista scelta, poi nelle altre: i risultati sicuri vincono, altrimenti i primi trovati
    async function findChannels(query) {
        let first = null;
        const others = picker.list().map((source) => source.id).filter((id) => id !== state.source);
        for (const id of [state.source].concat(others)) {
            let data;
            try {
                data = await api('channels', { q: query, source: id });
            } catch (_) {
                continue;
            }
            if (data.channels.some((channel) => channel.m >= data.confident)) return { id, data };
            if (!first && data.channels.length) first = { id, data };
        }
        return first;
    }

    async function runVoiceSearch(voice) {
        const token = ++loadToken;
        clearTimeout(searchTimer);
        showMessage(S.loading);
        const found = await findChannels(voice.query);
        if (token !== loadToken) return;
        const channels = found ? found.data.channels : [];
        const confident = found ? channels.filter((channel) => channel.m >= found.data.confident) : [];
        if (!confident.length && voice.fallback) {
            showMessage('');
            request('/api/voice/fallback').catch(() => {});
            return;
        }
        if (found && found.id !== state.source) await selectSource(found.id, false);
        ++loadToken;
        searchInput.value = voice.query;
        setItems(channels);
        showMessage(channels.length ? '' : S.noResults);
        if (voice.play && confident.length) play(confident.find(isFavorite) || confident[0]);
    }

    async function runVoice(voice) {
        if (voice.action === 'pause') {
            media.pause();
        } else if (voice.action === 'next') {
            step(1);
        } else if (voice.action === 'previous') {
            step(-1);
        } else if (voice.query) {
            await runVoiceSearch(voice);
        } else if ((voice.play || voice.action === 'play') && media.paused) {
            if (current) resume();
            else if (items.length) play(items[0]);
        }
    }

    window.aabVoice = async function () {
        await ready;
        try {
            const voice = await request('/api/voice', { section: 'tv' });
            if (voice.query || voice.play || voice.action) await runVoice(voice);
        } catch (_) {
            // Il comando resta senza effetto: la pagina continua come prima
        }
    };

    // ------------------------------------------------------------------
    // Avvio
    // ------------------------------------------------------------------

    searchInput.placeholder = S.search || '';
    byId('refresh').addEventListener('click', () => {
        if (state.source) selectSource(state.source, true);
    });

    if (!config.sources.length) {
        byId('source').hidden = true;
        byId('refresh').hidden = true;
        showMessage(S.noSources);
        return;
    }
    const sameLocale = state.locale === config.locale;
    const source = sameLocale && picker.has(state.source)
        ? state.source
        : config.sources[0].id;
    state.locale = config.locale;
    // L'ultimo canale, anche di un'altra lista: ▶ lo fa ripartire dalla sua
    if (state.last && state.last.s && picker.has(state.last.s)) {
        current = state.last;
        showChannel(current);
    }
    ready = selectSource(source, false);
    window.aabVoice();
})();
