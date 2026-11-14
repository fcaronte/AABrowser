/*
 * Impostazioni di CarWeb come pagina (SettingsApi): interruttori, scelte e l'elenco dei tasti della sidebar (quali, in che
 * ordine). La pagina non conosce le voci: le riceve da /api/settings/get (testi già nella lingua dell'app) e rimanda la
 * scelta a /api/settings/set, che risponde con lo stato com'è dopo. Grande da toccare in auto: righe alte, niente cursori.
 */
(function (root) {
    'use strict';

    // --- Elenco dei tasti della sidebar: [{ id, on, essential }], prima quelli mostrati (nel loro ordine), poi gli altri ---

    function shownIds(list) {
        return list.filter((item) => item.on).map((item) => item.id);
    }

    /** Spegne o accende un tasto: spento va tra i nascosti, acceso in fondo a quelli mostrati. Un essenziale (Home) non si tocca. */
    function toggled(list, id) {
        const item = list.find((entry) => entry.id === id);
        if (!item || item.essential) return list;
        const shown = list.filter((entry) => entry.on && entry.id !== id);
        const hidden = list.filter((entry) => !entry.on && entry.id !== id);
        const next = Object.assign({}, item, { on: !item.on });
        return item.on ? shown.concat(hidden, [next]) : shown.concat([next], hidden);
    }

    /** Sposta un tasto mostrato di [by] posti (-1 su, +1 giù); fuori dai limiti, o se è nascosto, non cambia. */
    function moved(list, id, by) {
        const shown = list.filter((entry) => entry.on);
        const hidden = list.filter((entry) => !entry.on);
        const index = shown.findIndex((entry) => entry.id === id);
        const target = index + by;
        if (index < 0 || target < 0 || target >= shown.length) return list;
        const [item] = shown.splice(index, 1);
        shown.splice(target, 0, item);
        return shown.concat(hidden);
    }

    root.AABSettings = { shownIds: shownIds, toggled: toggled, moved: moved };

    if (typeof document === 'undefined') return;

    const config = root.AAB_SETTINGS || { token: '', strings: {} };
    const S = config.strings || {};
    const byId = (id) => document.getElementById(id);

    function element(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined && text !== null) node.textContent = text;
        return node;
    }

    async function api(path, params) {
        const query = new URLSearchParams(Object.assign({}, params || {}, { t: config.token }));
        const response = await fetch(path + '?' + query, { cache: 'no-store' });
        // App riavviata: la pagina si ricarica per avere il token nuovo
        if (response.status === 403) {
            location.reload();
            throw new Error('token');
        }
        if (!response.ok) throw new Error(String(response.status));
        return response.json();
    }

    async function set(key, value) {
        try {
            render(await api('/api/settings/set', { key: key, value: value }));
            byId('error').hidden = true;
        } catch (_) {
            const box = byId('error');
            box.textContent = S.error || 'Could not save';
            box.hidden = false;
        }
    }

    function switchRow(item) {
        const row = element('div', 'row');
        const text = element('div', 'text');
        text.appendChild(element('div', 'label', item.label));
        if (item.desc) text.appendChild(element('div', 'desc', item.desc));
        row.appendChild(text);
        const toggle = element('button', 'switch' + (item.value ? ' on' : ''));
        toggle.setAttribute('role', 'switch');
        toggle.setAttribute('aria-checked', item.value ? 'true' : 'false');
        toggle.setAttribute('aria-label', item.label);
        toggle.appendChild(element('span', 'knob'));
        toggle.addEventListener('click', () => set(item.key, item.value ? '0' : '1'));
        row.appendChild(toggle);
        return row;
    }

    function choiceRow(item) {
        const row = element('div', 'row column');
        row.appendChild(element('div', 'label', item.label));
        const choices = element('div', 'choices');
        item.options.forEach(([value, label]) => {
            const button = element('button', 'choice' + (value === item.value ? ' active' : ''), label);
            button.addEventListener('click', () => {
                if (value !== item.value) set(item.key, value);
            });
            choices.appendChild(button);
        });
        row.appendChild(choices);
        return row;
    }

    function actionsRow(item) {
        const row = element('div', 'row column');
        row.appendChild(element('div', 'label', item.label));
        if (item.desc) row.appendChild(element('div', 'desc', item.desc));
        const list = item.value;
        const shown = list.filter((entry) => entry.on);
        const rows = element('div', 'actions');
        list.forEach((entry) => {
            const line = element('div', 'action' + (entry.on ? '' : ' off'));
            const name = element('div', 'action-name');
            name.appendChild(element('span', null, entry.label));
            if (entry.mainOnly) name.appendChild(element('small', null, S.mainOnly || ''));
            line.appendChild(name);
            if (entry.on) {
                const index = shown.findIndex((other) => other.id === entry.id);
                [['▲', -1, S.up], ['▼', 1, S.down]].forEach(([glyph, by, label]) => {
                    const move = element('button', 'step', glyph);
                    move.setAttribute('aria-label', label || '');
                    move.disabled = index + by < 0 || index + by >= shown.length;
                    move.addEventListener('click', () => set(item.key, shownIds(moved(list, entry.id, by)).join(',')));
                    line.appendChild(move);
                });
            }
            const toggle = element('button', 'switch' + (entry.on ? ' on' : ''));
            toggle.setAttribute('role', 'switch');
            toggle.setAttribute('aria-checked', entry.on ? 'true' : 'false');
            toggle.setAttribute('aria-label', entry.label);
            toggle.disabled = !!entry.essential;
            toggle.appendChild(element('span', 'knob'));
            toggle.addEventListener('click', () => set(item.key, shownIds(toggled(list, entry.id)).join(',')));
            line.appendChild(toggle);
            rows.appendChild(line);
        });
        row.appendChild(rows);
        return row;
    }

    function render(data) {
        const box = byId('sections');
        box.textContent = '';
        data.sections.forEach((section) => {
            const group = element('section', 'group');
            group.appendChild(element('h2', 'group-title', section.title));
            section.items.forEach((item) => {
                if (item.type === 'switch') group.appendChild(switchRow(item));
                else if (item.type === 'choice') group.appendChild(choiceRow(item));
                else if (item.type === 'actions') group.appendChild(actionsRow(item));
            });
            box.appendChild(group);
        });
    }

    byId('title').textContent = S.title || 'Settings';
    byId('back').setAttribute('aria-label', S.back || 'Back');
    byId('back').addEventListener('click', () => history.back());
    byId('note').textContent = S.phoneNote || '';
    api('/api/settings/get').then(render).catch(() => {
        const box = byId('error');
        box.textContent = S.error || 'Could not load';
        box.hidden = false;
    });
})(typeof window !== 'undefined' ? window : globalThis);
