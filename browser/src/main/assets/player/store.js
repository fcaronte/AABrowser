/*
 * Stato delle pagine di TV, Radio e Podcast (preferiti, seguiti, punto d'ascolto): nel localStorage della pagina e, con
 * un po' di ritardo, nell'app (MediaPages), che lo mette nei backup. All'apertura vale la copia dell'app, se c'è: dopo
 * un ripristino il localStorage è vuoto o vecchio. Senza copia (prima apertura dopo l'aggiornamento) vale il localStorage
 * e l'app ne riceve una.
 */
(function (root) {
    'use strict';

    const PUSH_MS = 10000;

    function parse(text) {
        try {
            const value = JSON.parse(text);
            return value && typeof value === 'object' && !Array.isArray(value) ? value : null;
        } catch (_) {
            return null;
        }
    }

    /**
     * [key]: nome nell'app ('tv', 'radio', 'podcast'); [storeKey]: chiave nel localStorage; [config]: quella della pagina
     * (token, saved); [localOnly]: campi che restano solo nel localStorage (le copertine si ritrovano da sole).
     */
    root.AABPageStore = function (key, storeKey, config, localOnly) {
        const skip = localOnly || [];
        let timer = 0;
        let pending = null;

        function readLocal() {
            try {
                return parse(root.localStorage.getItem(storeKey));
            } catch (_) {
                return null;
            }
        }

        function read() {
            const local = readLocal();
            const saved = typeof config.saved === 'string' ? parse(config.saved) : null;
            if (!saved) {
                if (local) push(local);
                return local;
            }
            if (local) skip.forEach((field) => {
                if (field in local) saved[field] = local[field];
            });
            return saved;
        }

        function withoutLocalOnly(state) {
            const copy = Object.assign({}, state);
            skip.forEach((field) => delete copy[field]);
            return copy;
        }

        function send() {
            clearTimeout(timer);
            timer = 0;
            if (!pending) return;
            const query = new URLSearchParams({ key: key, v: JSON.stringify(withoutLocalOnly(pending)), t: config.token || '' });
            pending = null;
            try {
                root.fetch('/api/pages/save?' + query, { cache: 'no-store', keepalive: true }).catch(() => {});
            } catch (_) {}
        }

        function push(state) {
            pending = state;
            if (!timer) timer = setTimeout(send, PUSH_MS);
        }

        function write(state) {
            try {
                root.localStorage.setItem(storeKey, JSON.stringify(state));
            } catch (_) {}
            push(state);
        }

        // Pagina che si chiude o va dietro: la copia dell'app non aspetta
        if (typeof root.addEventListener === 'function') root.addEventListener('pagehide', send);
        if (root.document) root.document.addEventListener('visibilitychange', () => {
            if (root.document.visibilityState === 'hidden') send();
        });

        return { read: read, write: write, flush: send };
    };
})(typeof window !== 'undefined' ? window : globalThis);
