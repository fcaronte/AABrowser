/*
 * La home della Wide (HomeApi): i preferiti come schede, con matita, ricerca e microfono in alto e Impostazioni in basso, come la
 * home della Main. Riceve nell'HTML i testi (già nella lingua dell'app) e i dati di ora; le modifiche ai preferiti vanno a
 * /api/home/fav (la stessa lista della Main, che si rilegge) e la risposta è lo stato com'è dopo. I campi di testo del modulo
 * sono campi normali: nella Wide li scrive la tastiera di Android Auto (o quella passeggero) con il suo popup, come in ogni pagina.
 */
(function (root) {
    'use strict';

    // ------------------------------------------------------------------
    // Funzioni pure (provate con node --test)
    // ------------------------------------------------------------------

    const ZOOM_MIN = 0.25;
    const ZOOM_MAX = 1.5;
    const ZOOM_STEP = 0.05;

    /** Come String.format di Android: %d, %s, %1$d, %2$s e %% (i testi arrivano come sono nelle risorse). */
    function fmt(template, ...args) {
        let next = 0;
        return String(template).replace(/%(?:(\d+)\$)?([ds%])/g, (match, index, type) => {
            if (type === '%') return '%';
            const value = index ? args[Number(index) - 1] : args[next++];
            return value === undefined ? match : String(value);
        });
    }

    /** Lo zoom dopo un tocco su − o +: passi del 5% dentro i limiti. */
    function steppedZoom(value, dir) {
        const next = Math.round((value + dir * ZOOM_STEP) * 100) / 100;
        return Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, next));
    }

    function zoomPercent(value) {
        return Math.round(value * 100);
    }

    /** Un colore #rgb o #rrggbb come [r, g, b], o null. */
    function parseColor(text) {
        const match = /^#?([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(String(text || '').trim());
        if (!match) return null;
        let hex = match[1];
        if (hex.length === 3) hex = hex.split('').map((c) => c + c).join('');
        return [parseInt(hex.slice(0, 2), 16), parseInt(hex.slice(2, 4), 16), parseInt(hex.slice(4, 6), 16)];
    }

    /** Quanto è chiaro un colore, da 0 a 1 (nero o bianco). */
    function luminance(rgb) {
        return rgb ? (0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]) / 255 : 0;
    }

    /** La lettera che sta al posto dell'icona (la prima del nome, maiuscola). */
    function initialOf(name) {
        const first = Array.from(String(name || '').trim())[0];
        return first ? first.toLocaleUpperCase() : '?';
    }

    /** Lo zoom che mostra il modulo: quello scelto per il sito, altrimenti quello di base (mobile o desktop). */
    function shownZoom(form, base) {
        return form.desktop === true
            ? (form.desktopZoom !== null ? form.desktopZoom : base.desktop)
            : (form.mobileZoom !== null ? form.mobileZoom : base.mobile);
    }

    /** Il modulo come lo vuole l'API: vuoto dove non c'è una scelta (valgono quelle di base). */
    function formParams(form) {
        return {
            name: String(form.name).trim(),
            url: String(form.url).trim(),
            color: String(form.color).replace('#', ''),
            icon: String(form.icon).trim(),
            desktop: form.desktop === null ? '' : (form.desktop ? '1' : '0'),
            mz: form.mobileZoom === null ? '' : String(form.mobileZoom),
            dz: form.desktopZoom === null ? '' : String(form.desktopZoom)
        };
    }

    const api = { fmt, steppedZoom, zoomPercent, parseColor, luminance, initialOf, shownZoom, formParams, ZOOM_MIN, ZOOM_MAX };
    root.AABHome = api;
    if (typeof document === 'undefined') return;

    // ------------------------------------------------------------------
    // Pagina
    // ------------------------------------------------------------------

    const config = root.AAB_HOME || { token: '', rtl: false, strings: {}, snapshot: { favorites: [], apps: [], zoom: { mobile: 1, desktop: 1 } } };
    const S = config.strings || {};
    const byId = (id) => document.getElementById(id);
    const SVG_NS = 'http://www.w3.org/2000/svg';

    // I tasti dei preferiti e del modulo (tracciati Material)
    const ICON = {
        back: 'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z',
        forward: 'M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8z',
        edit: 'M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z',
        trash: 'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z',
        add: 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
        close: 'M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
        check: 'M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z'
    };

    // Il colore di default di un preferito nuovo e le altre scelte (come nel modulo della Main)
    const COLORS = ['accent', '#FF0000', '#34A853', '#FBBC05', '#24292E'];

    let snap = config.snapshot;
    let editing = false;
    let form = null;
    let busy = false;
    let noticeTimer = 0;

    function el(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined) node.textContent = text;
        return node;
    }

    function svg(path) {
        const node = document.createElementNS(SVG_NS, 'svg');
        node.setAttribute('viewBox', '0 0 24 24');
        node.setAttribute('aria-hidden', 'true');
        const shape = document.createElementNS(SVG_NS, 'path');
        shape.setAttribute('d', path);
        node.appendChild(shape);
        return node;
    }

    function notice(text) {
        const box = byId('notice');
        box.textContent = text;
        box.hidden = false;
        clearTimeout(noticeTimer);
        noticeTimer = setTimeout(() => { box.hidden = true; }, 3000);
    }

    function accentHex() {
        const value = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim();
        return parseColor(value) ? value : '#8AB4F8';
    }

    // ------------------------------------------------------------------
    // Le schede
    // ------------------------------------------------------------------

    /** L'icona del sito: le icone da provare in ordine, e se nessuna c'è (o resta quella da 16 px del globo) la lettera. */
    function iconNode(icons, color, name, className) {
        const box = el('div', 'ico' + (className ? ' ' + className : ''));
        box.style.setProperty('--c', color || 'var(--accent)');
        const letter = el('span', 'letter', initialOf(name));
        if (!icons || !icons.length) {
            box.appendChild(letter);
            return box;
        }
        const image = document.createElement('img');
        image.referrerPolicy = 'no-referrer';
        image.alt = '';
        let index = 0;
        const next = () => {
            index++;
            if (index < icons.length) {
                image.src = icons[index];
            } else {
                image.remove();
                box.appendChild(letter);
            }
        };
        image.addEventListener('error', next);
        image.addEventListener('load', () => {
            if (image.naturalWidth <= 16 && index + 1 < icons.length) next();
        });
        image.src = icons[0];
        box.appendChild(image);
        return box;
    }

    function labelNode(name, second) {
        const label = el('div', 'label');
        label.appendChild(el('div', 'name', name));
        label.appendChild(el('div', 'domain', second));
        return label;
    }

    function control(path, title, className, onClick) {
        const button = el('button', 'ctl' + (className ? ' ' + className : ''));
        button.type = 'button';
        button.title = title;
        button.setAttribute('aria-label', title);
        button.appendChild(svg(path));
        button.addEventListener('click', (event) => {
            event.preventDefault();
            event.stopPropagation();
            onClick();
        });
        return button;
    }

    function favoriteTile(fav) {
        if (!editing) {
            const tile = el('a', 'tile');
            tile.href = fav.url;
            tile.appendChild(iconNode(fav.icons, fav.color, fav.name));
            tile.appendChild(labelNode(fav.name, fav.domain));
            return tile;
        }
        const tile = el('div', 'tile editing');
        tile.appendChild(iconNode(fav.icons, fav.color, fav.name));
        tile.appendChild(labelNode(fav.name, fav.domain));
        const controls = el('div', 'controls');
        controls.appendChild(control(ICON.back, S.moveBack, 'flip', () => move(fav, -1)));
        controls.appendChild(control(ICON.edit, S.editFavorite, '', () => openForm(fav)));
        // Le pagine di CarWeb di serie si cambiano ma non si eliminano
        if (!fav.fixed) controls.appendChild(control(ICON.trash, S.delete, 'danger', () => remove(fav)));
        controls.appendChild(control(ICON.forward, S.moveForward, 'flip', () => move(fav, 1)));
        tile.appendChild(controls);
        return tile;
    }

    function appTile(app) {
        // Le app del telefono si gestiscono dalla pagina Altre App: in modifica restano com'erano
        const tile = el(editing ? 'div' : 'a', 'tile' + (editing ? ' inert' : ''));
        if (!editing) tile.href = app.url;
        const icon = el('div', 'ico app');
        const image = document.createElement('img');
        image.alt = '';
        image.addEventListener('error', () => {
            image.remove();
            icon.appendChild(el('span', 'letter', initialOf(app.name)));
        });
        image.src = app.icon;
        icon.appendChild(image);
        tile.appendChild(icon);
        tile.appendChild(labelNode(app.name, S.appTile));
        return tile;
    }

    function addTile() {
        const tile = el('div', 'tile add');
        tile.setAttribute('role', 'button');
        tile.setAttribute('aria-label', S.addFavorite);
        tile.appendChild(svg(ICON.add));
        tile.addEventListener('click', () => openForm(null));
        return tile;
    }

    function render() {
        byId('brand').textContent = S.title || 'CarWeb';
        byId('version').textContent = snap.version ? 'v' + snap.version : '';
        byId('subtitle').textContent = S.subtitle || '';
        byId('openMainLabel').textContent = S.openMain || '';
        byId('voice').setAttribute('aria-label', S.voice || '');
        byId('search').setAttribute('aria-label', S.search || '');
        byId('edit').setAttribute('aria-label', S.edit || '');
        byId('edit').classList.toggle('on', editing);
        byId('edit').setAttribute('aria-pressed', editing ? 'true' : 'false');
        byId('settingsLabel').textContent = S.settings || '';
        byId('settings').href = snap.settingsUrl || 'https://player.local/settings';

        const resume = byId('resume');
        resume.hidden = !snap.resume;
        if (snap.resume) {
            resume.href = snap.resume;
            byId('resumeLabel').textContent = S.resume || '';
        }

        const update = byId('update');
        update.hidden = !snap.update;
        if (snap.update) byId('updateText').textContent = snap.update;

        const grid = byId('grid');
        grid.textContent = '';
        snap.favorites.forEach((fav) => grid.appendChild(favoriteTile(fav)));
        (snap.apps || []).forEach((app) => grid.appendChild(appTile(app)));
        if (editing) grid.appendChild(addTile());
        const empty = byId('empty');
        const nothing = !snap.favorites.length && !(snap.apps || []).length;
        empty.hidden = !nothing || editing;
        empty.textContent = nothing ? (S.noFavorites || '') : '';
    }

    // ------------------------------------------------------------------
    // Richieste
    // ------------------------------------------------------------------

    /** Una modifica ai preferiti: la risposta è lo stato com'è dopo e l'esito (ok, not_allowed, not_found, invalid). */
    async function call(op, params) {
        if (busy) return null;
        busy = true;
        try {
            const query = new URLSearchParams(Object.assign({ op, t: config.token }, params || {}));
            const response = await fetch('/api/home/fav?' + query);
            // App riavviata: la pagina si ricarica per avere il token nuovo
            if (response.status === 403) {
                location.reload();
                return null;
            }
            if (!response.ok) throw new Error(String(response.status));
            const data = await response.json();
            snap = data;
            render();
            return data.result;
        } catch (_) {
            notice(S.error || 'Error');
            return 'error';
        } finally {
            busy = false;
        }
    }

    /** I dati di ora (la Main o le Impostazioni possono aver cambiato i preferiti mentre la home era aperta). */
    async function load() {
        try {
            const response = await fetch('/api/home/get?' + new URLSearchParams({ t: config.token }));
            if (response.status === 403) {
                location.reload();
                return;
            }
            if (!response.ok) return;
            snap = await response.json();
            // Con il modulo aperto non si ridisegna sotto le dita
            if (!form) render();
        } catch (_) {
        }
    }

    async function move(fav, direction) {
        await call('move', { id: fav.id, dir: String(direction) });
    }

    async function remove(fav) {
        await call('delete', { id: fav.id });
    }

    // ------------------------------------------------------------------
    // Il modulo
    // ------------------------------------------------------------------

    function openForm(fav) {
        form = {
            id: fav ? fav.id : null,
            fixed: !!(fav && fav.fixed),
            name: fav ? fav.name : '',
            url: fav ? fav.url : '',
            icon: fav ? fav.favicon : '',
            color: fav ? fav.color : accentHex(),
            desktop: fav ? fav.desktop : null,
            mobileZoom: fav ? fav.mobileZoom : null,
            desktopZoom: fav ? fav.desktopZoom : null
        };
        renderForm();
        byId('modal').hidden = false;
    }

    function closeForm() {
        form = null;
        byId('modal').hidden = true;
        byId('modal').textContent = '';
        // Di nuovo scorre la pagina (la griglia)
        root.__aabScrollOnly = null;
    }

    function field(id, label, value, options) {
        const wrap = el('label', 'field' + (options && options.wide ? ' wide' : ''));
        wrap.appendChild(el('span', 'field-label', label));
        const input = document.createElement('input');
        input.type = 'text';
        input.id = id;
        input.value = value;
        input.autocomplete = 'off';
        input.autocapitalize = 'off';
        input.spellcheck = false;
        if (options && options.disabled) input.disabled = true;
        wrap.appendChild(input);
        return wrap;
    }

    function swatch(color) {
        const value = color === 'accent' ? accentHex() : color;
        const button = el('button', 'swatch');
        button.type = 'button';
        button.style.background = value;
        button.setAttribute('aria-label', value);
        if (String(form.color).toLowerCase() === value.toLowerCase()) {
            button.classList.add('sel');
            button.appendChild(svg(ICON.check));
        }
        button.addEventListener('click', () => {
            readForm();
            form.color = value;
            renderForm();
        });
        return button;
    }

    function zoomText() {
        const percent = zoomPercent(shownZoom(form, snap.zoom));
        return fmt(form.desktop === true ? S.desktopZoom : S.mobileZoom, percent);
    }

    function stepZoom(dir) {
        readForm();
        const current = shownZoom(form, snap.zoom);
        const next = steppedZoom(current, dir);
        if (form.desktop === true) form.desktopZoom = next;
        else form.mobileZoom = next;
        byId('zoomValue').textContent = zoomText();
    }

    function renderForm() {
        const modal = byId('modal');
        modal.textContent = '';
        const sheet = el('div', 'sheet');

        const head = el('div', 'sheet-head');
        head.appendChild(el('div', 'sheet-title', form.id ? S.editFavorite : S.addFavorite));
        const close = el('button', 'icon-btn');
        close.type = 'button';
        close.setAttribute('aria-label', S.close || '');
        close.appendChild(svg(ICON.close));
        close.addEventListener('click', closeForm);
        head.appendChild(close);
        sheet.appendChild(head);

        const body = el('div', 'sheet-body');
        body.appendChild(field('fName', S.name, form.name));
        body.appendChild(field('fUrl', S.url, form.url, { disabled: form.fixed }));
        body.appendChild(field('fIcon', S.favicon, form.icon));

        const colors = el('div', 'field');
        colors.appendChild(el('span', 'field-label', S.color));
        const swatches = el('div', 'swatches');
        COLORS.forEach((color) => swatches.appendChild(swatch(color)));
        colors.appendChild(swatches);
        body.appendChild(colors);

        // Desktop o mobile per questo sito, e lo zoom della modalità scelta (− e +: nella Wide non si trascina)
        const desktop = el('div', 'row');
        const text = el('div', 'row-text');
        text.appendChild(el('div', 'row-title', S.desktop));
        text.appendChild(el('div', 'row-desc', S.desktopDesc));
        desktop.appendChild(text);
        const toggle = el('button', 'switch' + (form.desktop === true ? ' on' : ''));
        toggle.type = 'button';
        toggle.setAttribute('role', 'switch');
        toggle.setAttribute('aria-checked', form.desktop === true ? 'true' : 'false');
        toggle.appendChild(el('span', 'knob'));
        toggle.addEventListener('click', () => {
            readForm();
            form.desktop = !(form.desktop === true);
            renderForm();
        });
        desktop.appendChild(toggle);
        body.appendChild(desktop);

        const zoom = el('div', 'row');
        const minus = el('button', null, '−');
        minus.type = 'button';
        minus.setAttribute('aria-label', '−');
        minus.addEventListener('click', () => stepZoom(-1));
        const value = el('div', 'stepper-value', zoomText());
        value.id = 'zoomValue';
        const plus = el('button', null, '+');
        plus.type = 'button';
        plus.setAttribute('aria-label', '+');
        plus.addEventListener('click', () => stepZoom(1));
        const stepper = el('div', 'stepper');
        stepper.appendChild(minus);
        stepper.appendChild(value);
        stepper.appendChild(plus);
        zoom.appendChild(stepper);
        body.appendChild(zoom);
        sheet.appendChild(body);
        // Nella Wide lo scorrimento dell'host muove i contenitori della pagina: con il modulo aperto solo il suo, non anche la
        // griglia che sta dietro (lo script di scorrimento della Wide lo prevede per le pagine che lo chiedono)
        root.__aabScrollOnly = body;

        const foot = el('div', 'sheet-foot');
        const cancel = el('button', 'btn', S.cancel);
        cancel.type = 'button';
        cancel.addEventListener('click', closeForm);
        const confirm = el('button', 'btn primary', S.confirm);
        confirm.type = 'button';
        confirm.addEventListener('click', submit);
        foot.appendChild(cancel);
        foot.appendChild(confirm);
        sheet.appendChild(foot);

        modal.appendChild(sheet);
    }

    // Quello che c'è nei campi adesso (il testo arriva anche dalla tastiera di Android Auto, che scrive nel campo da fuori)
    function readForm() {
        const name = byId('fName');
        const url = byId('fUrl');
        const icon = byId('fIcon');
        if (name) form.name = name.value;
        if (url && !url.disabled) form.url = url.value;
        if (icon) form.icon = icon.value;
    }

    async function submit() {
        readForm();
        const params = formParams(form);
        const wasEdit = !!form.id;
        if (wasEdit) params.id = form.id;
        const result = await call(wasEdit ? 'update' : 'add', params);
        if (result === 'ok') {
            closeForm();
            notice(wasEdit ? S.updated : S.added);
        } else if (result === 'not_allowed') {
            notice(S.notAllowed || S.error);
        } else if (result && result !== 'error') {
            notice(S.error || 'Error');
        }
    }

    // ------------------------------------------------------------------
    // Avvio
    // ------------------------------------------------------------------

    byId('edit').addEventListener('click', () => {
        editing = !editing;
        render();
    });

    document.documentElement.dir = config.rtl ? 'rtl' : 'ltr';
    // Lo sfondo sfumato solo con il tema scuro, come nella Main
    document.body.classList.toggle(
        'dark',
        luminance(parseColor(getComputedStyle(document.documentElement).getPropertyValue('--bg').trim())) < 0.5
    );

    // Chiamata dalla Wide quando i preferiti cambiano altrove (Impostazioni, la stella)
    root.aabRefresh = load;
    render();
})(typeof window !== 'undefined' ? window : globalThis);
