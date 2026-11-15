/*
 * Le impostazioni di CarWeb come pagina (SettingsApi, SettingsSchema): la stessa per la Main, che la mostra in una WebView tutta
 * sua, e per la Wide. La pagina non conosce nessuna voce: riceve da /api/settings/get le sezioni (testi già nella lingua
 * dell'app), i valori di ora e gli elenchi, e disegna. Ogni scelta va a /api/settings/set, ogni pulsante a /api/settings/do, e
 * la risposta è lo stato com'è dopo: quello che l'app rifiuta non cambia niente. Quello che solo il telefono può fare (selettori
 * di file, finestre per indirizzi e password, riavvio, aprire un link) lo chiede al ponte della WebView (AndroidBridge.run) e,
 * dove non c'è (la Wide, l'auto), lo dice. Grande da toccare in auto: righe alte, niente cursori (− e + al loro posto).
 */
(function (root) {
    'use strict';

    // ------------------------------------------------------------------
    // Funzioni pure (provate con node --test)
    // ------------------------------------------------------------------

    /** Come String.format di Android: %d, %s, %1$d, %2$s e %% (i testi arrivano come sono nelle risorse). */
    function fmt(template, ...args) {
        let next = 0;
        return String(template).replace(/%(?:(\d+)\$)?([ds%])/g, (match, index, type) => {
            if (type === '%') return '%';
            const value = index ? args[Number(index) - 1] : args[next++];
            return value === undefined ? match : String(value);
        });
    }

    /** Una condizione di una riga: {k, eq} o {k, ne} sui valori di ora. */
    function holds(cond, values) {
        const value = values[cond.k];
        if (cond.eq !== undefined && value !== cond.eq) return false;
        if (cond.ne !== undefined && value === cond.ne) return false;
        return true;
    }

    /** Tutte le condizioni valgono (nessuna = sempre). */
    function visible(conds, values) {
        return !conds || conds.every((cond) => holds(cond, values));
    }

    /** Il numero dopo un tocco su − o +: resta dentro i limiti e sui passi che partono dal minimo. */
    function stepped(value, dir, min, max, step) {
        const next = value + dir * step;
        if (next < min || next > max) return value;
        return next;
    }

    /** Le voci di una scelta: quelle scritte nello schema o quelle che manda l'app. */
    function optionsOf(row, data) {
        return row.options || (data && data[row.optionsData]) || [];
    }

    /** Il testo della voce scelta (la prima, se il valore non è tra le voci). */
    function selectedLabel(options, value) {
        const found = options.find((option) => option[0] === value) || options[0];
        return found ? found[1] : '';
    }

    // --- Elenco dei tasti (barra delle schede, sidebar): [{ id, on, essential }], prima quelli mostrati, poi gli altri ---

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

    root.AABSettings = {
        fmt: fmt, holds: holds, visible: visible, stepped: stepped, optionsOf: optionsOf, selectedLabel: selectedLabel,
        shownIds: shownIds, toggled: toggled, moved: moved,
    };

    if (typeof document === 'undefined') return;

    // ------------------------------------------------------------------
    // La pagina
    // ------------------------------------------------------------------

    const config = root.AAB_SETTINGS || { token: '', strings: {} };
    let S = config.strings || {};
    const byId = (id) => document.getElementById(id);

    // Il ponte della WebView, se c'è: sulla Main `isPhone` dice se c'è il telefono (selettori, finestre) o solo l'auto
    const bridge = root.AndroidBridge;
    const hasNative = !!(bridge && typeof bridge.run === 'function');
    let phone = false;
    try {
        phone = hasNative && typeof bridge.isPhone === 'function' && !!bridge.isPhone();
    } catch (_) {
        phone = false;
    }

    function native(action) {
        try {
            return hasNative && !!bridge.run(action);
        } catch (_) {
            return false;
        }
    }

    const state = {
        snap: null,
        values: {},
        data: {},
        cacheLabel: null,
        openSections: new Set(),
        openGroups: new Set(),
        openLists: new Set(),
        checks: new Set(),
        armed: null,
    };

    try {
        const saved = JSON.parse(sessionStorage.getItem('aabSettingsOpen') || '{}');
        (saved.sections || []).forEach((id) => state.openSections.add(id));
        (saved.groups || []).forEach((id) => state.openGroups.add(id));
    } catch (_) { /* senza sessionStorage la pagina parte chiusa */ }

    function remember() {
        try {
            sessionStorage.setItem('aabSettingsOpen', JSON.stringify({
                sections: Array.from(state.openSections), groups: Array.from(state.openGroups),
            }));
        } catch (_) { /* niente */ }
    }

    function element(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined && text !== null) node.textContent = text;
        return node;
    }

    const SVG_NS = 'http://www.w3.org/2000/svg';

    function icon(path, className, viewBox) {
        const svg = document.createElementNS(SVG_NS, 'svg');
        svg.setAttribute('viewBox', viewBox || '0 0 24 24');
        if (className) svg.setAttribute('class', className);
        svg.setAttribute('aria-hidden', 'true');
        const shape = document.createElementNS(SVG_NS, 'path');
        shape.setAttribute('d', path);
        svg.appendChild(shape);
        return svg;
    }

    const PATHS = {
        chevron: 'M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6z',
        refresh: 'M17.65 6.35A7.96 7.96 0 0 0 12 4a8 8 0 1 0 7.73 10h-2.08A5.99 5.99 0 0 1 12 18a6 6 0 1 1 0-12c1.66 0 3.14.69 4.22 1.78L13 11h7V4z',
        remove: 'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z',
        close: 'M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
        mic: 'M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 3-2.54 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z',
        location: 'M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5A2.5 2.5 0 1 1 12 6.5a2.5 2.5 0 0 1 0 5z',
        files: 'M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z',
    };

    const PERMISSION_ICONS = { mic: PATHS.mic, location: PATHS.location, files: PATHS.files };

    // I loghi del piè di pagina (stesso disegno delle icone dell'app)
    const GITHUB_MARK = 'M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0 0 16 8c0-4.42-3.58-8-8-8z';

    // ------------------------------------------------------------------
    // Avvisi, finestre
    // ------------------------------------------------------------------

    let noticeTimer = 0;

    function notice(text) {
        if (!text) return;
        const box = byId('notice');
        box.textContent = text;
        box.hidden = false;
        clearTimeout(noticeTimer);
        // I messaggi lunghi restano il tempo di leggerli
        noticeTimer = setTimeout(() => { box.hidden = true; }, text.length > 60 ? 6000 : 3000);
    }

    function closeModal() {
        const box = byId('modal');
        const was = !box.hidden;
        box.hidden = true;
        box.textContent = '';
        return was;
    }

    /** Un riquadro al centro, sopra tutto, che si vede anche sullo schermo dell'auto (niente finestre di sistema). */
    function openModal(title, bodyNode, buttons) {
        const box = byId('modal');
        box.textContent = '';
        const card = element('div', 'modal-card');
        card.addEventListener('click', (event) => event.stopPropagation());
        if (title) card.appendChild(element('h2', 'modal-title', title));
        const body = element('div', 'modal-body');
        body.appendChild(bodyNode);
        card.appendChild(body);
        const actions = element('div', 'modal-actions');
        buttons.forEach((button) => {
            const node = element('button', button.primary ? 'btn' : 'btn text', button.text);
            node.addEventListener('click', () => {
                closeModal();
                if (button.run) button.run();
            });
            actions.appendChild(node);
        });
        card.appendChild(actions);
        box.appendChild(card);
        // Un tocco fuori annulla, come Indietro
        box.onclick = () => closeModal();
        box.hidden = false;
    }

    function paragraphs(text) {
        const body = element('div', 'paragraphs');
        String(text).split('\n').forEach((line) => {
            body.appendChild(line.trim() ? element('p', null, line) : element('div', 'gap'));
        });
        return body;
    }

    function confirmModal(confirm, onAccept) {
        openModal(confirm.title, paragraphs(confirm.message), [
            { text: confirm.cancel },
            { text: confirm.ok, primary: true, run: onAccept },
        ]);
    }

    function qrModal(link) {
        const image = element('img', 'qr');
        image.alt = S.qrLabel || '';
        image.src = '/api/settings/qr?' + new URLSearchParams({ link: link, t: config.token });
        openModal(S.qrTitle || '', image, [{ text: S.close || 'OK', primary: true }]);
    }

    /** Indietro del telefono: prima chiude il riquadro aperto; true = l'ha chiuso, la pagina resta. */
    root.aabSettingsBack = function () {
        return closeModal();
    };

    // ------------------------------------------------------------------
    // Dati dall'app
    // ------------------------------------------------------------------

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

    function showError(text) {
        const box = byId('error');
        box.textContent = text;
        box.hidden = !text;
    }

    /** Lo stato mandato dall'app diventa quello della pagina (anche i colori e la lingua, se sono cambiati). */
    function adopt(data) {
        if (data.strings) S = Object.assign({}, S, data.strings);
        state.snap = data;
        state.values = data.values || {};
        state.data = data.data || {};
        // La dimensione della cache si legge dal disco: l'app la manda solo quando serve, la pagina tiene l'ultima
        const cache = (state.data.clear_options || []).find((option) => option.id === 'cache');
        if (cache && cache.label) state.cacheLabel = cache.label;
        if (data.result && data.result.cacheLabel) state.cacheLabel = data.result.cacheLabel;
        if (data.theme) byId('theme').textContent = data.theme;
        byId('title').textContent = S.title || 'Settings';
        byId('back').setAttribute('aria-label', S.back || 'Back');
        render();
    }

    // Le richieste partono una dopo l'altra: due tocchi su + di fila non si sorpassano, e la pagina mostra lo stato dell'app
    // solo quando non ne aspetta altre (altrimenti il numero tornerebbe indietro per un attimo)
    let chain = Promise.resolve();
    let pending = 0;

    function request(path, params, after) {
        pending++;
        chain = chain.then(async () => {
            try {
                const data = await api(path, params);
                pending--;
                if (pending === 0) adopt(data);
                showError('');
                if (data.result) after(data.result);
            } catch (_) {
                pending--;
                notice(S.error);
                if (pending === 0) load();
            }
        });
        return chain;
    }

    function load() {
        return api('/api/settings/get').then((data) => {
            showError('');
            adopt(data);
            focusRequested();
        }).catch(() => showError(S.error || 'Could not load'));
    }
    root.aabSettingsRefresh = function () {
        // Una finestra del telefono ha aggiunto una lista, un file è stato letto: si rilegge tutto
        load();
    };

    /** Cosa chiedeva la scelta salvata: riavviare l'app, un permesso del telefono, un avviso, un riquadro. */
    function afterResult(result) {
        if (result.cacheLabel) {
            state.cacheLabel = result.cacheLabel;
            render();
        }
        if (result.notice) notice(result.notice);
        if (result.modal) openModal(result.modal.title, paragraphs(result.modal.text), [{ text: S.ok || 'OK', primary: true }]);
        if (result.permission === 'weather') {
            // Il permesso lo chiede il telefono; in auto lo si dice (l'auto non può mostrare la finestra di Android)
            if (!(phone && native('weather_permission'))) notice(S.weatherPermission);
        }
        if (result.restart) {
            if (!native('restart')) notice(S.restartNeeded);
        }
    }

    function set(key, value) {
        // Subito sullo schermo, poi si conferma con l'app
        state.values[key] = value;
        render();
        return request('/api/settings/set', { key: key, value: value }, afterResult);
    }

    function act(name, arg) {
        return request('/api/settings/do', { act: name, arg: arg || '' }, afterResult);
    }

    // ------------------------------------------------------------------
    // Righe
    // ------------------------------------------------------------------

    function labelBlock(label, desc) {
        const text = element('div', 'text');
        if (label) text.appendChild(element('div', 'label', label));
        if (desc) text.appendChild(element('div', 'desc', desc));
        return text;
    }

    function switchNode(on, label, disabled, onClick) {
        const toggle = element('button', 'switch' + (on ? ' on' : ''));
        toggle.setAttribute('role', 'switch');
        toggle.setAttribute('aria-checked', on ? 'true' : 'false');
        toggle.setAttribute('aria-label', label);
        toggle.disabled = !!disabled;
        toggle.appendChild(element('span', 'knob'));
        toggle.addEventListener('click', onClick);
        return toggle;
    }

    function switchRow(row) {
        const blocked = row.blocked && holds(row.blocked, state.values);
        const on = state.values[row.key] === '1' && !blocked;
        let desc = row.desc;
        if (row.descOn || row.descOff) desc = on ? row.descOn : row.descOff;
        if (blocked && row.blockedDesc) desc = row.blockedDesc;
        const node = element('div', 'row');
        node.appendChild(labelBlock(row.label, desc));
        node.appendChild(switchNode(on, row.label, false, () => {
            if (blocked) return notice(row.blockedDesc);
            const next = on ? '0' : '1';
            if (next === '1' && row.confirm) return confirmModal(row.confirm, () => set(row.key, '1'));
            set(row.key, next);
        }));
        return node;
    }

    function pick(row, value) {
        const ask = row.ask;
        // Una lista a piacere vuole prima il suo indirizzo: lo si scrive nella finestra del telefono, non qui
        if (ask && value === ask.value && state.values[ask.unless] !== '1') {
            if (phone) native(ask.nat);
            else notice(ask.notPhone);
            return;
        }
        if (value !== state.values[row.key]) set(row.key, value);
    }

    function choiceRow(row) {
        const options = optionsOf(row, state.data);
        const current = state.values[row.key];
        const node = element('div', 'row column');
        if (row.buttons) {
            node.appendChild(labelBlock(row.label, row.desc));
            const choices = element('div', 'choices');
            options.forEach(([value, label]) => {
                const button = element('button', 'choice' + (value === current ? ' active' : ''), label);
                button.addEventListener('click', () => pick(row, value));
                choices.appendChild(button);
            });
            node.appendChild(choices);
            return node;
        }
        if (row.desc) node.appendChild(labelBlock(null, row.desc));
        const id = 'choice:' + row.key;
        const open = state.openLists.has(id);
        const head = element('button', 'pick');
        head.setAttribute('aria-expanded', open ? 'true' : 'false');
        const text = element('div', 'text');
        text.appendChild(element('div', 'pick-title', row.label));
        text.appendChild(element('div', 'pick-value', selectedLabel(options, current)));
        head.appendChild(text);
        head.appendChild(icon(PATHS.chevron, 'chev'));
        head.addEventListener('click', () => {
            if (open) state.openLists.delete(id); else state.openLists.add(id);
            render();
        });
        node.appendChild(head);
        if (open) {
            const list = element('div', 'options');
            options.forEach(([value, label]) => {
                const option = element('button', 'option' + (value === current ? ' selected' : ''));
                option.appendChild(element('span', 'radio'));
                option.appendChild(element('span', 'option-text', label));
                option.addEventListener('click', () => {
                    state.openLists.delete(id);
                    pick(row, value);
                    render();
                });
                list.appendChild(option);
            });
            node.appendChild(list);
        }
        return node;
    }

    function stepperRow(row) {
        const value = Number(state.values[row.key]);
        const max = row.maxKey ? Number(state.values[row.maxKey]) : row.max;
        const shown = row.format ? fmt(row.format, value) : value + (row.unit || '');
        const node = element('div', 'row');
        // Con un titolo il numero sta tra − e +; senza, il numero è il testo della riga (es. "Schede da precaricare: 4")
        node.appendChild(row.label ? labelBlock(row.label, row.desc) : labelBlock(shown, row.desc));
        const control = element('div', 'stepper');
        [['−', -1], ['+', 1]].forEach(([glyph, dir], index) => {
            const button = element('button', 'step', glyph);
            button.disabled = stepped(value, dir, row.min, max, row.step) === value;
            button.setAttribute('aria-label', glyph);
            button.addEventListener('click', () => set(row.key, String(stepped(value, dir, row.min, max, row.step))));
            if (index === 1 && row.label) control.appendChild(element('div', 'stepper-value', shown));
            control.appendChild(button);
        });
        node.appendChild(control);
        return node;
    }

    function pressButton(button, id) {
        if (button.confirmTap && state.armed !== id) {
            // Cambia tutto: un secondo tocco di conferma, entro qualche secondo
            state.armed = id;
            render();
            setTimeout(() => {
                if (state.armed === id) {
                    state.armed = null;
                    render();
                }
            }, 4000);
            return;
        }
        state.armed = null;
        if (button.link) {
            if (!(phone && native('open_link:' + button.link))) qrModal(button.link);
        } else if (button.nat) {
            if (!phone) notice(button.notPhone);
            else native(button.nat);
        } else if (button.act) {
            act(button.act, '');
        }
        render();
    }

    function buttonsRow(row) {
        // I pulsanti del telefono spariscono dove non c'è il telefono, salvo quelli che dicono perché
        const shown = row.buttons.filter((button) => !button.nat || phone || button.notPhone);
        if (row.hideWithoutPhone && shown.length === 0) return null;
        const node = element('div', 'row' + (shown.length > 1 || (!shown.length && row.phoneNote) ? ' column' : ''));
        const desc = row.descKey ? state.values[row.descKey] : row.desc;
        if (row.label || desc) node.appendChild(labelBlock(row.label, desc));
        if (!shown.length) {
            if (row.phoneNote) node.appendChild(element('div', 'desc', row.phoneNote));
            return node;
        }
        const group = element('div', shown.length > 1 ? 'buttons' : 'buttons single');
        shown.forEach((button) => {
            const id = button.act || button.nat || button.link;
            const armed = state.armed === id;
            const label = armed ? (S.areYouSure || '?') : button.text;
            const control = element('button', 'btn' + (button.outline ? ' outline' : '') + (armed ? ' armed' : ''), label);
            control.addEventListener('click', () => pressButton(button, id));
            group.appendChild(control);
        });
        node.appendChild(group);
        return node;
    }

    function headingRow(row) {
        const node = element('div', 'row heading');
        node.appendChild(labelBlock(row.label, row.desc));
        return node;
    }

    function actionsRow(row) {
        const list = state.data[row.key] || [];
        const node = element('div', 'actions');
        const shown = list.filter((entry) => entry.on);
        list.forEach((entry) => {
            const line = element('div', 'action' + (entry.on ? '' : ' off'));
            const name = element('div', 'action-name');
            name.appendChild(element('span', null, entry.label));
            if (entry.mainOnly) name.appendChild(element('small', null, S.mainOnly || ''));
            line.appendChild(name);
            const change = (next) => {
                state.data[row.key] = next;
                set(row.key, shownIds(next).join(','));
            };
            if (entry.on) {
                const index = shown.findIndex((other) => other.id === entry.id);
                [['▲', -1, S.up], ['▼', 1, S.down]].forEach(([glyph, by, label]) => {
                    const move = element('button', 'step', glyph);
                    move.setAttribute('aria-label', label || '');
                    move.disabled = index + by < 0 || index + by >= shown.length;
                    move.addEventListener('click', () => change(moved(list, entry.id, by)));
                    line.appendChild(move);
                });
            }
            line.appendChild(switchNode(entry.on, entry.label, entry.essential, () => change(toggled(list, entry.id))));
            node.appendChild(line);
        });
        return node;
    }

    function groupRow(row) {
        const open = state.openGroups.has(row.id);
        const node = element('div', 'group');
        const head = element('button', 'group-head');
        head.setAttribute('aria-expanded', open ? 'true' : 'false');
        // Chiuso: in una riga il riassunto (quanti tasti); aperto: la spiegazione
        const summary = open ? row.desc : fmt(row.summary, state.values[row.summaryKey]);
        head.appendChild(labelBlock(row.label, summary));
        head.appendChild(icon(PATHS.chevron, 'chev'));
        head.addEventListener('click', () => {
            if (open) state.openGroups.delete(row.id); else state.openGroups.add(row.id);
            remember();
            render();
        });
        node.appendChild(head);
        if (open) {
            const body = element('div', 'group-body');
            row.rows.forEach((child) => {
                const built = buildRow(child);
                if (built) body.appendChild(built);
            });
            node.appendChild(body);
        }
        return node;
    }

    function itemsRow(row) {
        const items = state.data[row.data] || [];
        const node = element('div', 'items');
        const removable = (item) => item.removable !== false;
        if (!row.listLabel) {
            items.forEach((item) => {
                const line = element('div', 'item');
                line.appendChild(labelBlock(item.title, item.sub));
                if (removable(item) && !item.children) {
                    const remove = element('button', 'icon-btn');
                    remove.setAttribute('aria-label', S.remove || '');
                    remove.appendChild(icon(PATHS.remove));
                    remove.addEventListener('click', () => act(row.act, (row.scope || '') + '|' + item.id));
                    line.appendChild(remove);
                }
                node.appendChild(line);
            });
            return node;
        }
        return null;
    }

    // Siti con una scelta: chiusa mostra quanti sono, aperta un sito per riga con le icone dei suoi permessi; toccando un sito
    // si vedono i permessi uno per uno, ognuno da togliere (il sito chiederà di nuovo)
    function sitesRow(row) {
        const sites = state.data[row.data] || [];
        const id = 'sites:' + row.data;
        const open = state.openLists.has(id) && sites.length > 0;
        const node = element('div', 'sites');
        const head = element('button', 'pick');
        head.disabled = sites.length === 0;
        const text = element('div', 'text');
        text.appendChild(element('div', 'pick-value', sites.length ? fmt(row.listLabel, sites.length) : (row.empty || '')));
        head.appendChild(text);
        if (sites.length) head.appendChild(icon(PATHS.chevron, 'chev'));
        head.addEventListener('click', () => {
            if (open) state.openLists.delete(id); else state.openLists.add(id);
            render();
        });
        node.appendChild(head);
        if (!open) return node;
        const list = element('div', 'site-list');
        sites.forEach((site) => {
            const siteId = 'site:' + site.id;
            const siteOpen = state.openLists.has(siteId);
            const card = element('div', 'site');
            const top = element('button', 'site-head');
            top.appendChild(element('span', 'site-name', site.title));
            (site.children || []).forEach((child) => {
                const mark = icon(PERMISSION_ICONS[child.id] || PATHS.files, 'perm ' + (child.allowed ? 'allowed' : 'blocked'));
                top.appendChild(mark);
            });
            top.appendChild(icon(PATHS.chevron, 'chev small'));
            top.setAttribute('aria-expanded', siteOpen ? 'true' : 'false');
            top.addEventListener('click', () => {
                if (siteOpen) state.openLists.delete(siteId); else state.openLists.add(siteId);
                render();
            });
            card.appendChild(top);
            if (siteOpen) {
                (site.children || []).forEach((child) => {
                    const line = element('div', 'perm-row');
                    line.appendChild(icon(PERMISSION_ICONS[child.id] || PATHS.files, 'perm ' + (child.allowed ? 'allowed' : 'blocked')));
                    line.appendChild(element('span', 'perm-text', fmt(S.siteState || '%1$s: %2$s', child.label, child.allowed ? S.allowed : S.blocked)));
                    const remove = element('button', 'icon-btn');
                    remove.setAttribute('aria-label', S.siteRemove || '');
                    remove.appendChild(icon(PATHS.close));
                    remove.addEventListener('click', () => act(row.act, site.id + '|' + child.id));
                    line.appendChild(remove);
                    card.appendChild(line);
                });
            }
            list.appendChild(card);
        });
        node.appendChild(list);
        return node;
    }

    function checksRow(row) {
        const options = state.data[row.data] || [];
        const node = element('div', 'row column');
        const top = element('div', 'check-top');
        top.appendChild(labelBlock(row.label, row.desc));
        const refresh = element('button', 'icon-btn');
        refresh.setAttribute('aria-label', row.refresh);
        refresh.appendChild(icon(PATHS.refresh));
        refresh.addEventListener('click', () => act('cache_size', ''));
        top.appendChild(refresh);
        node.appendChild(top);
        options.forEach((option) => {
            const label = element('label', 'check');
            const box = element('input');
            box.type = 'checkbox';
            box.checked = state.checks.has(option.id);
            box.addEventListener('change', () => {
                if (box.checked) state.checks.add(option.id); else state.checks.delete(option.id);
                render();
            });
            label.appendChild(box);
            label.appendChild(element('span', null, option.id === 'cache' ? (state.cacheLabel || option.label || '…') : option.label));
            node.appendChild(label);
        });
        const confirm = element('button', 'btn danger', row.button);
        confirm.disabled = state.checks.size === 0;
        confirm.addEventListener('click', () => {
            const chosen = Array.from(state.checks).join(',');
            state.checks.clear();
            act(row.act, chosen);
            render();
        });
        node.appendChild(confirm);
        return node;
    }

    function buildRow(row) {
        if (!visible(row.showIf, state.values)) return null;
        switch (row.type) {
            case 'switch': return switchRow(row);
            case 'choice': return choiceRow(row);
            case 'stepper': return stepperRow(row);
            case 'buttons': return buttonsRow(row);
            case 'heading': return headingRow(row);
            case 'group': return groupRow(row);
            case 'actions': return actionsRow(row);
            case 'items': return row.listLabel ? sitesRow(row) : itemsRow(row);
            case 'checks': return checksRow(row);
            default: return null;
        }
    }

    // ------------------------------------------------------------------
    // Sezioni e piè di pagina
    // ------------------------------------------------------------------

    function cardNode(card) {
        if (!visible(card.showIf, state.values)) return null;
        const node = element('div', 'card');
        if (card.id) node.setAttribute('data-card', card.id);
        let rows = 0;
        card.rows.forEach((row) => {
            const built = buildRow(row);
            if (built) {
                node.appendChild(built);
                rows++;
            }
        });
        return rows ? node : null;
    }

    function sectionNode(section) {
        const wrap = element('section', 'section');
        wrap.setAttribute('data-section', section.id);
        const cards = section.cards.map(cardNode).filter(Boolean);
        if (section.plain) {
            const body = element('div', 'sec-body');
            cards.forEach((card) => body.appendChild(card));
            wrap.appendChild(body);
            return wrap;
        }
        const open = state.openSections.has(section.id);
        const head = element('button', 'sec-head');
        head.setAttribute('aria-expanded', open ? 'true' : 'false');
        head.appendChild(element('span', 'sec-title', section.title));
        head.appendChild(icon(PATHS.chevron, 'chev'));
        head.addEventListener('click', () => {
            if (open) state.openSections.delete(section.id); else state.openSections.add(section.id);
            remember();
            render();
        });
        wrap.appendChild(head);
        if (open) {
            const body = element('div', 'sec-body');
            cards.forEach((card) => body.appendChild(card));
            wrap.appendChild(body);
        }
        return wrap;
    }

    function logoButton(path, viewBox, label, onClick, extra) {
        const button = element('button', 'logo' + (extra ? ' ' + extra : ''));
        button.setAttribute('aria-label', label);
        button.appendChild(icon(path, null, viewBox));
        if (onClick) button.addEventListener('click', onClick);
        return button;
    }

    function footerNode(footer) {
        const node = element('div', 'footer-row');
        const left = element('div', 'footer-side');
        // Tocco = la pagina del progetto; pressione lunga = il log dell'app da condividere (solo dal telefono: dall'auto la
        // scelta dell'app si aprirebbe sul telefono, fuori vista)
        const github = logoButton(GITHUB_MARK, '0 0 16 16', footer.githubLabel, null, 'github');
        let timer = 0;
        let held = false;
        github.addEventListener('pointerdown', () => {
            held = false;
            timer = setTimeout(() => {
                held = true;
                if (phone) native('share_logs'); else notice(S.logsPhoneOnly);
            }, 700);
        });
        ['pointerup', 'pointerleave', 'pointercancel'].forEach((name) => github.addEventListener(name, () => clearTimeout(timer)));
        github.addEventListener('click', () => {
            if (!held) openLink('github');
        });
        left.appendChild(github);
        left.appendChild(element('div', 'footer-caption', footer.github));
        node.appendChild(left);

        const right = element('div', 'footer-donate');
        right.appendChild(element('div', 'footer-text', footer.donation));
        const logos = element('div', 'footer-logos');
        [['paypal', footer.paypal], ['coffee', footer.coffee]].forEach(([id, label]) => {
            const cell = element('div', 'footer-side');
            const logo = element('button', 'logo ' + id);
            logo.setAttribute('aria-label', label);
            const image = element('img');
            image.src = id === 'paypal' ? '/settings-paypal.svg' : '/settings-coffee.svg';
            image.alt = '';
            logo.appendChild(image);
            logo.addEventListener('click', () => openLink(id));
            cell.appendChild(logo);
            cell.appendChild(element('div', 'footer-caption', label));
            logos.appendChild(cell);
        });
        right.appendChild(logos);
        node.appendChild(right);
        return node;
    }

    function openLink(id) {
        if (!(phone && native('open_link:' + id))) qrModal(id);
    }

    function render() {
        const box = byId('sections');
        const top = window.scrollY;
        box.textContent = '';
        state.snap.sections.forEach((section) => box.appendChild(sectionNode(section)));
        const footer = byId('footer');
        footer.textContent = '';
        if (state.snap.footer) footer.appendChild(footerNode(state.snap.footer));
        // La pagina si ricostruisce: la posizione resta dov'era
        window.scrollTo(0, top);
    }

    // Aperta da una pagina di TV, Radio o Podcast ("Aggiungi una lista…"): la sezione delle liste aperta e quella lista in vista
    let focusDone = false;

    function focusRequested() {
        if (focusDone) return;
        focusDone = true;
        const wanted = new URLSearchParams(location.search).get('focus');
        if (!wanted) return;
        state.openSections.add('media');
        render();
        const card = document.querySelector('[data-card="' + wanted.replace(/[^a-z]/g, '') + '"]');
        if (card && card.scrollIntoView) card.scrollIntoView({ block: 'center' });
    }

    byId('back').addEventListener('click', () => {
        // Nella Main la pagina sta in una schermata sua: si chiude; nella Wide si torna alla pagina di prima
        if (!native('close')) history.back();
    });
    byId('title').textContent = S.title || 'Settings';
    byId('back').setAttribute('aria-label', S.back || 'Back');
    load();
})(typeof window !== 'undefined' ? window : globalThis);
