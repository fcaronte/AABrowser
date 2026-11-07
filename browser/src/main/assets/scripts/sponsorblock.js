// SponsorBlock su YouTube e YouTube Music (CarWeb e CarWebWide, all'inizio delle pagine di YouTube): salta da solo
// le parti segnate dagli utenti di SponsorBlock (sponsor.ajay.app) nelle categorie scelte nelle Impostazioni.
// A SponsorBlock arrivano solo le prime 4 cifre dell'impronta SHA-256 dell'id del video: risponde con i video che
// iniziano così e la pagina tiene quello giusto. Mai durante le pubblicità; ogni parte una volta sola, così se
// l'utente torna indietro la riguarda. Le categorie e il messaggio li dà l'app (AndroidBridge).
// Test: node --test browser/src/test/js/
(function (root) {
    'use strict';

    const API = 'https://sponsor.ajay.app/api/skipSegments/';
    // Parti più corte non si saltano; il salto si ferma un poco prima della fine della parte
    const MIN_LENGTH = 1;
    const END_MARGIN = 0.3;

    function videoIdOf(href) {
        let url;
        try {
            url = new URL(href);
        } catch (e) {
            return null;
        }
        if (url.hostname === 'youtu.be') return url.pathname.slice(1, 12) || null;
        const v = url.searchParams.get('v');
        if (v) return v;
        const match = url.pathname.match(/^\/(?:shorts|live|embed)\/([A-Za-z0-9_-]{11})/);
        return match ? match[1] : null;
    }

    // Dalla risposta (tutti i video con lo stesso inizio d'impronta): le parti da saltare di videoId, in ordine
    function segmentsFor(entries, videoId, categories) {
        const entry = Array.isArray(entries) ? entries.find((e) => e && e.videoID === videoId) : null;
        if (!entry || !Array.isArray(entry.segments)) return [];
        return entry.segments
            .filter((s) => s && s.actionType === 'skip' && categories.includes(s.category) && Array.isArray(s.segment))
            .map((s) => ({ start: Number(s.segment[0]), end: Number(s.segment[1]), category: s.category, id: String(s.UUID) }))
            .filter((s) => Number.isFinite(s.start) && Number.isFinite(s.end) && s.end - s.start >= MIN_LENGTH)
            .sort((a, b) => a.start - b.start);
    }

    // La parte in cui si trova time e non ancora saltata
    function segmentAt(segments, time, skipped) {
        return segments.find((s) => time >= s.start - 0.05 && time < s.end - END_MARGIN && !skipped.has(s.id)) || null;
    }

    async function sha256Prefix(text) {
        const digest = await root.crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
        return Array.from(new Uint8Array(digest).slice(0, 2), (b) => b.toString(16).padStart(2, '0')).join('');
    }

    root.AABSponsorBlock = { videoIdOf, segmentsFor, segmentAt, sha256Prefix };

    const bridge = root.AndroidBridge;
    if (!root.document || !bridge || typeof bridge.sponsorBlockCategories !== 'function' || root.__aabSponsorBlockOn) return;
    let categories;
    try {
        categories = JSON.parse(bridge.sponsorBlockCategories() || '[]');
    } catch (e) {
        categories = [];
    }
    if (!Array.isArray(categories) || categories.length === 0) return;
    root.__aabSponsorBlockOn = true;
    const message = typeof bridge.sponsorBlockMessage === 'function' ? String(bridge.sponsorBlockMessage()) : 'SponsorBlock';

    let currentId = null;
    let segments = [];
    let skipped = new Set();

    async function load(id) {
        currentId = id;
        segments = [];
        skipped = new Set();
        if (!id) return;
        try {
            const prefix = await sha256Prefix(id);
            const url = API + prefix + '?categories=' + encodeURIComponent(JSON.stringify(categories));
            const response = await fetch(url, { credentials: 'omit' });
            // 404 = nessun video con quell'inizio ha parti segnate
            if (!response.ok) return;
            const entries = await response.json();
            if (currentId === id) segments = segmentsFor(entries, id, categories);
        } catch (e) {}
    }

    function toast(text) {
        try {
            let el = root.document.getElementById('aab-sponsorblock');
            if (!el) {
                el = root.document.createElement('div');
                el.id = 'aab-sponsorblock';
                el.style.cssText = 'position:fixed;left:50%;bottom:72px;transform:translateX(-50%);z-index:2147483647;' +
                    'background:rgba(0,0,0,.8);color:#fff;padding:8px 16px;border-radius:16px;font:14px/1.3 sans-serif;' +
                    'pointer-events:none;transition:opacity .3s';
            }
            // A schermo intero si vede solo quello che sta dentro l'elemento a schermo intero
            const parent = root.document.fullscreenElement || root.document.body || root.document.documentElement;
            if (el.parentNode !== parent) parent.appendChild(el);
            el.textContent = text;
            el.style.opacity = '1';
            clearTimeout(el.aabTimer);
            el.aabTimer = setTimeout(() => { el.style.opacity = '0'; }, 2000);
        } catch (e) {}
    }

    function check() {
        const id = videoIdOf(root.location.href);
        if (id !== currentId) load(id);
        if (segments.length === 0) return;
        const player = root.document.getElementById('movie_player');
        if (player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'))) return;
        const video = (player && player.querySelector('video')) || root.document.querySelector('video');
        if (!video || video.readyState === 0) return;
        const segment = segmentAt(segments, video.currentTime, skipped);
        if (!segment) return;
        skipped.add(segment.id);
        const end = Number.isFinite(video.duration) ? Math.min(segment.end, video.duration - 0.25) : segment.end;
        video.currentTime = end;
        toast(message);
    }

    // timeupdate non risale, ma passa dal document in cattura; il controllo periodico vede i cambi di video
    root.document.addEventListener('timeupdate', check, true);
    setInterval(check, 1000);
})(typeof window !== 'undefined' ? window : globalThis);
