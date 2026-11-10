/*
 * Video dei siti a schermo intero: Adatta (come lo mette il sito), Riempi (tagliato ai bordi) o Allunga (deformato).
 * Toccando lo schermo compare in alto al centro una scritta con il modo, toccandola si passa al successivo; sparisce
 * dopo qualche secondo. Il modo resta per il sito (localStorage del sito, aabVideoFit) e vale a ogni schermo intero;
 * fuori dallo schermo intero la pagina resta com'è. Le pagine di CarWeb Media hanno la loro barra (video-zoom.js).
 * Regole e scritta in fogli di stile costruiti (niente innerHTML né <style>: Trusted Types e CSP dei siti), la scritta
 * in uno shadow DOM dentro l'elemento a schermo intero: il resto della pagina Chromium lo rende intoccabile. Quando è a
 * schermo intero il solo <video> (comandi standard del browser) la scritta non c'è: i suoi figli non si vedono.
 * I testi li mette l'app (VideoFit).
 */
(function (labels) {
    'use strict';
    if (window.__aabVideoFit || location.hostname === 'player.local' || !labels) return;
    window.__aabVideoFit = true;

    const MODES = ['fit', 'fill', 'stretch'];
    const KEY = 'aabVideoFit';
    const HIDE_MS = 4000;
    const rules = new CSSStyleSheet();
    let mode = readMode();
    let target = null;          // il video a schermo intero
    let host = null;            // la scritta
    let button = null;
    let hideTimer = 0;
    let resizeObserver = null;

    function readMode() {
        try {
            const value = localStorage.getItem(KEY);
            return MODES.includes(value) ? value : 'fit';
        } catch (_) {
            return 'fit';
        }
    }

    function saveMode() {
        try {
            localStorage.setItem(KEY, mode);
        } catch (_) {}
    }

    function useRules() {
        if (!document.adoptedStyleSheets.includes(rules)) {
            document.adoptedStyleSheets = document.adoptedStyleSheets.concat(rules);
        }
    }

    function largestVideo(root) {
        if (root.tagName === 'VIDEO') return root;
        let best = null;
        let bestArea = 0;
        root.querySelectorAll('video').forEach((video) => {
            const rect = video.getBoundingClientRect();
            if (rect.width * rect.height > bestArea) {
                best = video;
                bestArea = rect.width * rect.height;
            }
        });
        return best;
    }

    function clear(video) {
        rules.replaceSync('');
        if (!video) return;
        video.removeAttribute('data-aab-fit');
        video.removeAttribute('data-aab-fit-box');
    }

    // Con !important: il sito che riscrive lo stile del video (YouTube a ogni ridimensionamento) non le toglie. Un video
    // grande quanto il filmato e non quanto lo schermo (YouTube) viene allargato a tutto lo schermo intero
    function apply() {
        const fullscreen = document.fullscreenElement;
        if (!fullscreen || !target) return;
        clear(target);
        if (mode === 'fit') return;
        useRules();
        let css = 'video[data-aab-fit="fill"]{object-fit:cover!important}' +
            'video[data-aab-fit="stretch"]{object-fit:fill!important}';
        const box = fullscreen.getBoundingClientRect();
        const rect = target.getBoundingClientRect();
        const covers = rect.left <= box.left + 2 && rect.top <= box.top + 2 &&
            rect.right >= box.right - 2 && rect.bottom >= box.bottom - 2;
        const position = getComputedStyle(target).position;
        if (!covers && (position === 'absolute' || position === 'fixed')) {
            const parent = position === 'fixed' ? null : target.offsetParent;
            const base = parent ? parent.getBoundingClientRect() : { left: 0, top: 0 };
            const left = box.left - base.left - (parent ? parent.clientLeft : 0);
            const top = box.top - base.top - (parent ? parent.clientTop : 0);
            css += 'video[data-aab-fit][data-aab-fit-box]{left:' + left + 'px!important;top:' + top + 'px!important;' +
                'width:' + box.width + 'px!important;height:' + box.height + 'px!important;' +
                'max-width:none!important;max-height:none!important;transform:none!important}';
            target.setAttribute('data-aab-fit-box', '');
        }
        rules.replaceSync(css);
        target.setAttribute('data-aab-fit', mode);
    }

    function makeLabel() {
        host = document.createElement('div');
        // Fuori dai fogli di stile del sito: posizione e aspetto tutti qui
        host.style.cssText = 'position:fixed;top:12px;left:50%;transform:translateX(-50%);margin:0;padding:0;' +
            'border:none;background:transparent;z-index:2147483647;pointer-events:auto';
        const shadow = host.attachShadow({ mode: 'closed' });
        const look = new CSSStyleSheet();
        look.replaceSync('button{font:600 16px/1 system-ui,sans-serif;color:#fff;background:rgba(0,0,0,.62);border:none;' +
            'border-radius:22px;min-width:44px;height:44px;padding:0 18px;cursor:pointer}' +
            'button:active{background:rgba(70,70,70,.85)}');
        shadow.adoptedStyleSheets = [look];
        button = document.createElement('button');
        button.type = 'button';
        // Il tocco sulla scritta non arriva al lettore del sito (che altrimenti mostrerebbe o toglierebbe i suoi comandi)
        ['pointerdown', 'pointerup', 'touchstart', 'touchend', 'mousedown', 'mouseup', 'dblclick'].forEach((type) => {
            button.addEventListener(type, (event) => event.stopPropagation());
        });
        button.addEventListener('click', (event) => {
            event.stopPropagation();
            event.preventDefault();
            mode = MODES[(MODES.indexOf(mode) + 1) % MODES.length];
            saveMode();
            apply();
            show();
        });
        shadow.appendChild(button);
    }

    function show() {
        const fullscreen = document.fullscreenElement;
        if (!fullscreen || !target || fullscreen.tagName === 'VIDEO') return;
        if (!host) makeLabel();
        // Di nuovo dentro, se il lettore del sito ha rifatto i suoi elementi
        if (host.parentNode !== fullscreen) fullscreen.appendChild(host);
        button.textContent = labels[mode] || mode;
        clearTimeout(hideTimer);
        hideTimer = setTimeout(hide, HIDE_MS);
    }

    function hide() {
        clearTimeout(hideTimer);
        if (host) host.remove();
    }

    document.addEventListener('fullscreenchange', () => {
        if (resizeObserver) resizeObserver.disconnect();
        resizeObserver = null;
        clear(target);
        target = null;
        const fullscreen = document.fullscreenElement;
        // Nella pagina che contiene il riquadro a schermo intero (iframe del video) qui non c'è un video: fa lo script
        // dentro il riquadro
        target = fullscreen ? largestVideo(fullscreen) : null;
        if (!target) {
            hide();
            return;
        }
        apply();
        if (window.ResizeObserver) {
            resizeObserver = new ResizeObserver(() => apply());
            resizeObserver.observe(fullscreen);
        }
        show();
    }, true);

    // Un tocco a schermo intero mostra la scritta, insieme ai comandi del sito
    document.addEventListener('pointerdown', () => {
        if (document.fullscreenElement && target) show();
    }, { capture: true, passive: true });

    // Filmato nuovo o con altre dimensioni nello stesso video
    ['loadedmetadata', 'resize'].forEach((type) => {
        document.addEventListener(type, (event) => {
            if (event.target === target) apply();
        }, true);
    });
})(/*AAB_LABELS*/null);
