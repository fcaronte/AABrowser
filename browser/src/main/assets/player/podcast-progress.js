/*
 * Punto d'ascolto degli episodi dei podcast: da dove riprendere e quali sono finiti. Gli episodi sono chiavi di un oggetto
 * salvato dalla pagina: { t: secondi ascoltati, d: durata, done: finito, at: quando }.
 */
(function (root) {
    'use strict';

    const MAX_EPISODES = 500;
    const DONE_MARGIN = 30;     // gli ultimi secondi di solito sono sigla o saluti: l'episodio conta come finito
    const MIN_RESUME = 15;      // da così vicino all'inizio tanto vale ripartire da capo

    function prune(progress) {
        const ids = Object.keys(progress);
        if (ids.length <= MAX_EPISODES) return;
        ids.sort((a, b) => progress[a].at - progress[b].at)
            .slice(0, ids.length - MAX_EPISODES)
            .forEach((id) => delete progress[id]);
    }

    root.AABPodcastProgress = {
        update(progress, id, time, duration, now) {
            if (!id || !(time >= 1)) return;
            const length = isFinite(duration) && duration > 0 ? duration : 0;
            progress[id] = {
                t: Math.floor(time),
                d: Math.floor(length),
                done: length > 0 && time >= length - Math.min(DONE_MARGIN, length * 0.05),
                at: now
            };
            prune(progress);
        },

        /** Da dove ripartire, in secondi; 0 = da capo (episodio finito o appena iniziato). */
        resumeAt(progress, id) {
            const entry = progress[id];
            return entry && !entry.done && entry.t >= MIN_RESUME ? entry.t : 0;
        },

        isDone(entry) {
            return !!(entry && entry.done);
        },

        /** Secondi che mancano alla fine; 0 = non si sa o non è iniziato. */
        left(entry) {
            return entry && !entry.done && entry.d > 0 ? Math.max(0, entry.d - entry.t) : 0;
        },

        /** Quanto dell'episodio è stato ascoltato, da 0 a 1. */
        share(entry) {
            if (!entry) return 0;
            if (entry.done) return 1;
            return entry.d > 0 ? Math.min(1, entry.t / entry.d) : 0;
        },

        clock(seconds) {
            if (!isFinite(seconds) || seconds < 0) return '0:00';
            const total = Math.floor(seconds);
            const hours = Math.floor(total / 3600);
            const minutes = Math.floor((total % 3600) / 60);
            const secs = String(total % 60).padStart(2, '0');
            return hours > 0 ? hours + ':' + String(minutes).padStart(2, '0') + ':' + secs : minutes + ':' + secs;
        }
    };
})(typeof window !== 'undefined' ? window : globalThis);
