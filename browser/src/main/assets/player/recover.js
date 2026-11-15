/*
 * Ripresa automatica di un canale o di una stazione che si ferma da solo (TV). Un flusso dal vivo può perdere il segnale o il
 * focus per un attimo e restare fermo: cambiando canale e rimettendolo torna a funzionare, quindi la pagina lo fa da sola. Vale
 * una regola: se l'utente non ha messo in pausa (dalla pagina, dal player di Android Auto, dal volante, a voce, o perché un'altra
 * riproduzione ha preso il player), la pagina riprova sempre.
 *
 * Qui c'è solo la decisione, senza DOM (si prova con node --test): ogni secondo la pagina dice com'è il video e il modulo
 * risponde null, 'play' (un play() semplice) o 'restart' (ricarica il flusso, come cambiare canale e rimetterlo), con attese
 * crescenti tra un riavvio e l'altro (1,5 s, 3, 6, 12, 20, poi ogni 30 s: un canale morto non consuma niente di rilevante).
 *  - in pausa senza averla chiesta: dopo un attimo un play(), poi ancora ogni qualche secondo; se resta fermo si riavvia. Se
 *    un'altra app sta suonando (otherAudio) non si prende l'audio a nessuno: si aspetta che smetta;
 *  - in riproduzione ma fermo (il tempo non avanza): si riavvia dopo qualche secondo, più pazienti all'avvio (un canale lento);
 *  - un errore del flusso (error): riavvio con l'attesa.
 * I tentativi ricominciano da capo dopo qualche secondo di riproduzione vera.
 */
(function (root) {
    'use strict';

    const NEVER = -1;
    const DEFAULTS = {
        pauseGraceMs: 1500,      // fermo senza averlo chiesto: prima un attimo, poi un play()
        retryPlayMs: 3000,       // un altro play() ogni tanto finché resta fermo
        stallMs: 10000,          // fermo così a lungo: si riavvia il flusso
        startMs: 25000,          // all'avvio un canale lento ha più tempo
        movingToReset: 3,        // secondi di riproduzione vera dopo i quali i tentativi ricominciano da capo
        delays: [1500, 3000, 6000, 12000, 20000, 30000]
    };

    function create(options) {
        const o = Object.assign({}, DEFAULTS, options || {});
        let wanted = false;
        let attempts = 0;
        let restartAt = NEVER;
        let lastTime = null;
        let idleSince = NEVER;
        let everMoved = false;
        let movingTicks = 0;
        let lastPlayAt = NEVER;

        function delay() {
            return o.delays[Math.min(attempts, o.delays.length - 1)];
        }

        function schedule(now) {
            if (restartAt === NEVER) restartAt = now + delay();
            idleSince = NEVER;
        }

        return {
            /** Un canale parte. [auto]: lo riavvia la pagina stessa (i tentativi continuano); altrimenti l'ha scelto l'utente. */
            begin(auto) {
                wanted = true;
                if (!auto) attempts = 0;
                restartAt = NEVER;
                lastTime = null;
                idleSince = NEVER;
                everMoved = false;
                movingTicks = 0;
                lastPlayAt = NEVER;
            },

            /** Pausa voluta (pagina, Android Auto, volante, voce, altra riproduzione): niente da riprendere. */
            userPause() {
                wanted = false;
                restartAt = NEVER;
                idleSince = NEVER;
            },

            /** Play voluto, dopo una pausa. */
            userPlay() {
                if (wanted) return;
                wanted = true;
                restartAt = NEVER;
                idleSince = NEVER;
                lastPlayAt = NEVER;
            },

            /** Il canale è stato tolto (nessun flusso): niente da riprendere. */
            stop() {
                wanted = false;
                restartAt = NEVER;
                idleSince = NEVER;
            },

            /** Errore del flusso (hls.js, mpegts.js, elemento video): si riavvia dopo l'attesa. */
            error(now) {
                if (wanted) schedule(now);
            },

            get wanted() {
                return wanted;
            },

            get attempts() {
                return attempts;
            },

            /**
             * Da chiamare ogni secondo. [state]: { paused, time, otherAudio }. Risponde null, 'play' o 'restart'.
             */
            tick(now, state) {
                if (!wanted) {
                    idleSince = NEVER;
                    return null;
                }
                if (restartAt !== NEVER) {
                    if (now < restartAt) return null;
                    restartAt = NEVER;
                    attempts++;
                    return 'restart';
                }
                // La prima occhiata dopo la partenza fissa solo il tempo di partenza: non è ancora una riproduzione che avanza
                const moving = lastTime !== null && !state.paused && state.time !== lastTime;
                lastTime = state.time;
                if (moving) {
                    everMoved = true;
                    idleSince = NEVER;
                    if (++movingTicks >= o.movingToReset) attempts = 0;
                    return null;
                }
                movingTicks = 0;
                if (idleSince === NEVER) {
                    idleSince = now;
                    return null;
                }
                const idle = now - idleSince;
                if (state.paused) {
                    // Un'altra app sta suonando: non si toglie l'audio a nessuno, si aspetta che smetta
                    if (state.otherAudio) {
                        idleSince = now;
                        return null;
                    }
                    if (idle >= o.stallMs) {
                        schedule(now);
                        return null;
                    }
                    if (idle >= o.pauseGraceMs && (lastPlayAt === NEVER || now - lastPlayAt >= o.retryPlayMs)) {
                        lastPlayAt = now;
                        return 'play';
                    }
                    return null;
                }
                // In riproduzione ma il tempo non avanza: carica, o il flusso si è bloccato
                if (idle >= (everMoved ? o.stallMs : o.startMs)) schedule(now);
                return null;
            }
        };
    }

    root.AABRecover = { create: create, DEFAULTS: DEFAULTS };
})(typeof window !== 'undefined' ? window : globalThis);
