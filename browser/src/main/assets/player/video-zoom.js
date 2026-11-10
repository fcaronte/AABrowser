/*
 * Zoom dei video di CarWeb Media (TV e file locali): − 1× + per ingrandire (la targa nel video della dashcam), con
 * l'immagine ingrandita che si sposta trascinando (telefono e Main in auto) o con lo scorrimento di Android Auto (nella
 * Wide la pagina chiede che si sposti solo il video: window.__aabScrollOnly); il modo dell'immagine Adatta (intera),
 * Riempi (tagliata ai bordi) o Allunga (deformata); lo schermo intero, del riquadro con la sua barra. La barra compare
 * toccando il video e sparisce dopo qualche secondo.
 */
(function (root) {
    'use strict';

    const STEPS = [1, 1.5, 2, 3, 4, 6];
    const MODES = ['fit', 'fill', 'stretch'];
    const OBJECT_FIT = { fit: 'contain', fill: 'cover', stretch: 'fill' };
    const MODE_KEY = 'aabVideoMode';
    const HIDE_MS = 4000;

    /** Dimensioni del video nel riquadro, prima dello zoom: intero, a riempire (tagliato) o deformato. */
    function contentSize(mode, videoWidth, videoHeight, boxWidth, boxHeight) {
        if (mode === 'stretch' || !videoWidth || !videoHeight) return [boxWidth, boxHeight];
        const fit = mode === 'fill' ? Math.max : Math.min;
        const scale = fit(boxWidth / videoWidth, boxHeight / videoHeight);
        return [videoWidth * scale, videoHeight * scale];
    }

    function nextStep(zoom, direction) {
        if (direction > 0) return STEPS.find((step) => step > zoom + 0.01) || STEPS[STEPS.length - 1];
        return STEPS.slice().reverse().find((step) => step < zoom - 0.01) || STEPS[0];
    }

    function readMode() {
        try {
            const mode = root.localStorage.getItem(MODE_KEY);
            return MODES.includes(mode) ? mode : 'fit';
        } catch (_) {
            return 'fit';
        }
    }

    /**
     * [stage]: il riquadro del video; [video]: l'elemento video, che finisce in un contenitore che scorre; [strings]:
     * fit, fill, stretch, fullscreen; [isVideo]: c'è un video da mostrare (la barra non compare con l'audio).
     */
    function attach(options) {
        const stage = options.stage;
        const video = options.video;
        const S = options.strings || {};
        const isVideo = options.isVideo || (() => true);
        const doc = stage.ownerDocument;

        const box = doc.createElement('div');
        box.className = 'zoom-box';
        video.parentNode.insertBefore(box, video);
        box.appendChild(video);

        const bar = doc.createElement('div');
        bar.className = 'zoom-bar';
        bar.hidden = true;
        bar.innerHTML =
            '<button class="zoom-button" data-zoom="out">−</button>' +
            '<button class="zoom-button" data-zoom="reset"></button>' +
            '<button class="zoom-button" data-zoom="in">+</button>' +
            '<button class="zoom-button zoom-mode" data-zoom="mode"></button>' +
            '<button class="zoom-button" data-zoom="full">' +
            '<svg class="icon-enter" viewBox="0 0 24 24"><path d="M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z"/></svg>' +
            '<svg class="icon-exit" viewBox="0 0 24 24"><path d="M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z"/></svg>' +
            '</button>';
        stage.appendChild(bar);
        const button = (name) => bar.querySelector('[data-zoom="' + name + '"]');
        button('full').setAttribute('aria-label', S.fullscreen || 'Fullscreen');

        let zoom = 1;
        let mode = readMode();
        let size = null;        // dimensioni del video all'ultimo calcolo, per tenere fermo il centro
        let hideTimer = 0;

        const offset = (boxSide, side) => Math.max(0, (boxSide - side) / 2);

        function layout() {
            const boxWidth = box.clientWidth;
            const boxHeight = box.clientHeight;
            if (!boxWidth || !boxHeight) return;
            const base = contentSize(mode, video.videoWidth, video.videoHeight, boxWidth, boxHeight);
            const width = base[0] * zoom;
            const height = base[1] * zoom;
            // Il punto al centro della vista resta al centro
            let centerX = 0.5;
            let centerY = 0.5;
            if (size) {
                centerX = (box.scrollLeft + boxWidth / 2 - offset(boxWidth, size[0])) / size[0];
                centerY = (box.scrollTop + boxHeight / 2 - offset(boxHeight, size[1])) / size[1];
            }
            size = [width, height];
            Object.assign(video.style, {
                width: width + 'px',
                height: height + 'px',
                left: offset(boxWidth, width) + 'px',
                top: offset(boxHeight, height) + 'px',
                // Il rettangolo è già quello del modo; senza le dimensioni del video (anteprima) decide object-fit
                objectFit: video.videoWidth ? 'fill' : OBJECT_FIT[mode],
            });
            const movable = width > boxWidth + 1 || height > boxHeight + 1;
            box.classList.toggle('movable', movable);
            box.scrollLeft = centerX * width - boxWidth / 2;
            box.scrollTop = centerY * height - boxHeight / 2;
            if (movable) root.__aabScrollOnly = box;
            else if (root.__aabScrollOnly === box) root.__aabScrollOnly = null;
            updateBar();
        }

        function updateBar() {
            const reset = button('reset');
            reset.textContent = (Math.round(zoom * 10) / 10) + '×';
            button('out').disabled = zoom <= STEPS[0];
            button('in').disabled = zoom >= STEPS[STEPS.length - 1];
            button('mode').textContent = S[mode] || mode;
            bar.classList.toggle('in-fullscreen', doc.fullscreenElement === stage);
        }

        function setZoom(value) {
            zoom = value;
            layout();
        }

        function showBar() {
            if (!isVideo()) return;
            bar.hidden = false;
            updateBar();
            clearTimeout(hideTimer);
            hideTimer = setTimeout(() => {
                bar.hidden = true;
            }, HIDE_MS);
        }

        // Tocco sul video: la barra compare o sparisce (lo schermo intero ora ha il suo tasto, come su YouTube)
        box.addEventListener('click', () => {
            if (!bar.hidden) {
                clearTimeout(hideTimer);
                bar.hidden = true;
            } else {
                showBar();
            }
        });

        bar.addEventListener('click', (event) => {
            const target = event.target.closest('[data-zoom]');
            if (!target) return;
            event.stopPropagation();
            switch (target.dataset.zoom) {
                case 'in':
                    setZoom(nextStep(zoom, 1));
                    break;
                case 'out':
                    setZoom(nextStep(zoom, -1));
                    break;
                case 'reset':
                    setZoom(1);
                    break;
                case 'mode':
                    mode = MODES[(MODES.indexOf(mode) + 1) % MODES.length];
                    try {
                        root.localStorage.setItem(MODE_KEY, mode);
                    } catch (_) {}
                    size = null;
                    layout();
                    break;
                case 'full':
                    if (doc.fullscreenElement) {
                        doc.exitFullscreen().catch(() => {});
                    } else if (stage.requestFullscreen) {
                        stage.requestFullscreen().catch(() => {});
                    }
                    break;
            }
            showBar();
        });

        // Video nuovo (altro canale, altro file): di nuovo a 1×, il modo resta
        video.addEventListener('loadstart', () => {
            zoom = 1;
            size = null;
            layout();
        });
        video.addEventListener('loadedmetadata', layout);
        video.addEventListener('resize', layout);
        doc.addEventListener('fullscreenchange', () => {
            updateBar();
            layout();
        });
        if (typeof root.ResizeObserver === 'function') new root.ResizeObserver(layout).observe(box);
        layout();

        return { reset: () => setZoom(1) };
    }

    root.AABVideoZoom = { attach: attach, contentSize: contentSize, nextStep: nextStep, STEPS: STEPS };
})(typeof window !== 'undefined' ? window : globalThis);
