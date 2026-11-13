/*
 * Altre App: le app del telefono da aprire sullo schermo virtuale (Shizuku). In alto lo stato di Shizuku (verde: si può,
 * rosso: manca qualcosa) e la ricerca; sotto tutte le app, ognuna con la stella per tenerla fra i preferiti (compare nella
 * home di CarWeb). Un tocco su un'app va a "app://pacchetto": l'app di CarWeb la apre in una scheda (Main) o sullo schermo
 * della Wide. Nessun dato esce dal telefono: elenco, icone e preferiti arrivano da CarWeb con il token di questa pagina.
 */
(function (root) {
    'use strict';

    // Per cercare e confrontare: minuscole e senza accenti ("è" e "e" sono la stessa lettera)
    function normalize(text) {
        return String(text || '')
            .normalize('NFD')
            .replace(/[̀-ͯ]/g, '')
            .toLowerCase()
            .trim();
    }

    // Le app il cui nome (o pacchetto) contiene la ricerca; senza ricerca tutte
    function filterApps(apps, query) {
        const q = normalize(query);
        if (!q) return apps;
        return apps.filter((app) => normalize(app.n).includes(q) || app.p.toLowerCase().includes(q));
    }

    // Colore e testo dello stato di Shizuku
    function statusKind(status) {
        return status === 'ready' ? 'ready' : 'problem';
    }

    function statusText(strings, status) {
        switch (status) {
            case 'ready':
                return strings.ready;
            case 'missing':
                return strings.missing;
            case 'stopped':
                return strings.stopped;
            case 'permission':
                return strings.permission;
            default:
                return strings.loading;
        }
    }

    root.AABApps = { normalize, filterApps, statusKind, statusText };

    if (typeof document === 'undefined' || !document.getElementById('grid')) return;

    const config = root.AAB_APPS || { token: '', strings: {} };
    const strings = config.strings || {};
    const byId = (id) => document.getElementById(id);
    const grid = byId('grid');
    const message = byId('message');
    const hint = byId('hint');
    const shizuku = byId('shizuku');
    const shizukuText = byId('shizukuText');
    const authorize = byId('authorize');
    const searchBar = byId('searchBar');
    const search = byId('search');

    let apps = [];
    let query = '';
    let status = 'loading';

    const STAR = 'M12 17.27 18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z';
    const STAR_OUTLINE = 'M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z';

    function api(path, params) {
        const url = new URL(path, location.href);
        url.searchParams.set('t', config.token);
        Object.keys(params || {}).forEach((key) => url.searchParams.set(key, params[key]));
        return url.toString();
    }

    function icon(pkg) {
        return api('/api/app/icon', { pkg });
    }

    function starSvg(on) {
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('viewBox', '0 0 24 24');
        const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        path.setAttribute('d', on ? STAR : STAR_OUTLINE);
        svg.appendChild(path);
        return svg;
    }

    function open(pkg) {
        // L'app di CarWeb prende questo indirizzo: Main = nuova scheda, Wide = schermo virtuale sopra la pagina
        location.href = 'app://' + pkg;
    }

    function tile(app) {
        const el = document.createElement('div');
        el.className = 'app' + (app.f ? ' favorite' : '');
        el.setAttribute('role', 'button');
        el.dataset.pkg = app.p;

        const img = document.createElement('img');
        img.alt = '';
        img.loading = 'lazy';
        img.src = icon(app.p);
        el.appendChild(img);

        const name = document.createElement('div');
        name.className = 'name';
        name.textContent = app.n;
        el.appendChild(name);

        const star = document.createElement('button');
        star.className = 'star';
        star.setAttribute('aria-label', app.f ? strings.favoriteRemove : strings.favoriteAdd);
        star.appendChild(starSvg(app.f));
        star.addEventListener('click', (event) => {
            event.stopPropagation();
            toggleFavorite(app, el, star);
        });
        el.appendChild(star);

        el.addEventListener('click', () => open(app.p));
        return el;
    }

    function toggleFavorite(app, el, star) {
        app.f = !app.f;
        el.classList.toggle('favorite', app.f);
        star.setAttribute('aria-label', app.f ? strings.favoriteRemove : strings.favoriteAdd);
        star.replaceChildren(starSvg(app.f));
        fetch(api('/api/apps/favorite', { pkg: app.p, on: app.f ? '1' : '0' })).catch(() => {});
    }

    function render() {
        const shown = filterApps(apps, query);
        grid.replaceChildren(...shown.map(tile));
        message.hidden = shown.length > 0;
        message.textContent = shown.length === 0 ? strings.empty : '';
    }

    function showStatus() {
        shizuku.className = 'shizuku ' + statusKind(status);
        shizukuText.textContent = statusText(strings, status);
        authorize.hidden = status !== 'permission';
        // Shizuku si accende e si autorizza dal telefono: in auto si dice dove
        hint.hidden = status === 'ready' || status === 'loading';
        hint.textContent = strings.hint || '';
    }

    function refreshStatus() {
        if (document.visibilityState === 'hidden') return;
        fetch(api('/api/apps/status'))
            .then((response) => response.json())
            .then((data) => {
                if (data.status !== status) {
                    status = data.status;
                    showStatus();
                }
            })
            .catch(() => {});
    }

    function load() {
        fetch(api('/api/apps/list'))
            .then((response) => response.json())
            .then((data) => {
                apps = data.apps || [];
                render();
            })
            .catch(() => {
                message.hidden = false;
                message.textContent = strings.empty;
            });
    }

    byId('searchToggle').addEventListener('click', () => {
        searchBar.hidden = !searchBar.hidden;
        if (!searchBar.hidden) search.focus();
        else {
            search.value = '';
            query = '';
            render();
        }
    });
    search.addEventListener('input', () => {
        query = search.value;
        render();
    });
    authorize.addEventListener('click', () => {
        fetch(api('/api/apps/authorize')).catch(() => {});
    });

    byId('searchToggle').setAttribute('aria-label', strings.search || 'Search');
    search.placeholder = strings.search || '';
    authorize.textContent = strings.authorize || '';
    document.title = strings.title || document.title;
    showStatus();
    message.hidden = false;
    message.textContent = strings.loading || '';
    load();
    refreshStatus();
    // Shizuku può accendersi o essere autorizzato mentre la pagina è aperta: lo stato si rilegge ogni poco
    setInterval(refreshStatus, 3000);
    document.addEventListener('visibilitychange', refreshStatus);
})(typeof window !== 'undefined' ? window : globalThis);
