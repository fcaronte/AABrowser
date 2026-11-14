/*
 * Effetti animati sulla copertina di CarWeb Media (musica) e della Radio: barre, onda, cerchio, disegnati su un canvas con
 * i dati di un AnalyserNode di Web Audio. La pagina crea l'AudioContext (con un tocco dell'utente: senza gesto resta
 * sospeso e l'audio sarebbe muto) e dice quando disegnare; qui c'è solo il disegno.
 */
(function (root) {
    'use strict';

    const EFFECTS = ['off', 'bars', 'wave', 'circle'];
    // 30 fotogrammi al secondo bastano e pesano la metà
    const FRAME_MS = 33;

    /**
     * Bande logaritmiche, come gli analizzatori dei vecchi lettori: tante per i bassi, poche per gli acuti.
     * [frequencyData]: i byte dell'analizzatore (getByteFrequencyData), [sampleRate]: quello dell'AudioContext.
     */
    function bandLevels(frequencyData, sampleRate, count) {
        const nyquist = sampleRate / 2;
        const binWidth = nyquist / frequencyData.length;
        const minHz = 40;
        const maxHz = Math.min(16000, nyquist);
        const levels = new Array(count);
        for (let i = 0; i < count; i++) {
            const from = Math.floor(minHz * Math.pow(maxHz / minHz, i / count) / binWidth);
            const to = Math.max(from + 1, Math.ceil(minHz * Math.pow(maxHz / minHz, (i + 1) / count) / binWidth));
            let sum = 0;
            let bins = 0;
            for (let bin = from; bin < to && bin < frequencyData.length; bin++) {
                sum += frequencyData[bin];
                bins++;
            }
            levels[i] = bins ? sum / bins / 255 : 0;
        }
        return levels;
    }

    /**
     * Collega gli effetti a un canvas. Opzioni:
     * [canvas]: il canvas sopra la copertina (nascosto quando non si disegna);
     * [effect]: () => 'bars' | 'wave' | 'circle' | 'off', la scelta dell'utente;
     * [active]: () => bool, serve disegnare ora? (effetto scelto, suono in riproduzione, pagina visibile);
     * [source]: () => { analyser, sampleRate } | null, l'analizzatore (null = niente da disegnare).
     * Restituisce { update, reset }: update() quando cambia una delle condizioni di [active], reset() cambiando effetto.
     */
    function attach(options) {
        const canvas = options.canvas;
        const painter = canvas.getContext('2d');
        let frequencyData = null;
        let waveData = null;
        let peaks = [];
        let handle = 0;
        let lastFrame = 0;
        let accentColor = '';

        function update() {
            const active = options.active();
            canvas.hidden = !active;
            if (active && !handle) handle = requestAnimationFrame(frame);
        }

        function reset() {
            peaks = [];
        }

        function frame(now) {
            handle = 0;
            if (!options.active()) {
                canvas.hidden = true;
                return;
            }
            handle = requestAnimationFrame(frame);
            if (now - lastFrame < FRAME_MS) return;
            lastFrame = now;
            const source = options.source();
            if (!source) return;
            const analyser = source.analyser;
            if (!frequencyData || frequencyData.length !== analyser.frequencyBinCount) {
                frequencyData = new Uint8Array(analyser.frequencyBinCount);
            }
            if (!waveData || waveData.length !== analyser.fftSize) waveData = new Uint8Array(analyser.fftSize);

            const ratio = root.devicePixelRatio || 1;
            const width = Math.max(1, Math.round(canvas.clientWidth * ratio));
            const height = Math.max(1, Math.round(canvas.clientHeight * ratio));
            if (canvas.width !== width || canvas.height !== height) {
                canvas.width = width;
                canvas.height = height;
            }
            if (!accentColor) {
                accentColor = root.getComputedStyle(root.document.documentElement).getPropertyValue('--accent').trim() || '#00bcd4';
            }
            painter.clearRect(0, 0, width, height);
            // Velo scuro: l'effetto si vede su qualunque copertina
            painter.fillStyle = 'rgba(0, 0, 0, 0.35)';
            painter.fillRect(0, 0, width, height);
            const effect = options.effect();
            if (effect === 'wave') drawWave(analyser, width, height);
            else if (effect === 'circle') drawCircle(analyser, source.sampleRate, width, height, now);
            else drawBars(analyser, source.sampleRate, width, height);
        }

        function levelsOf(analyser, sampleRate, count) {
            analyser.getByteFrequencyData(frequencyData);
            return bandLevels(frequencyData, sampleRate, count);
        }

        // Barre con il "cappello" che ricade piano, come in Winamp
        function drawBars(analyser, sampleRate, width, height) {
            const count = 32;
            const levels = levelsOf(analyser, sampleRate, count);
            const slot = width / count;
            const barWidth = slot * 0.72;
            const capHeight = Math.max(2, height * 0.014);
            const gradient = painter.createLinearGradient(0, height, 0, 0);
            gradient.addColorStop(0, accentColor);
            gradient.addColorStop(1, '#ffffff');
            for (let i = 0; i < count; i++) {
                const barHeight = Math.max(capHeight, levels[i] * height * 0.85);
                const x = i * slot + (slot - barWidth) / 2;
                painter.fillStyle = gradient;
                painter.fillRect(x, height - barHeight, barWidth, barHeight);
                const peak = Math.max((peaks[i] || 0) - height * 0.01, barHeight);
                peaks[i] = peak;
                painter.fillStyle = '#ffffff';
                painter.fillRect(x, height - peak - capHeight * 1.5, barWidth, capHeight);
            }
        }

        // Oscilloscopio
        function drawWave(analyser, width, height) {
            analyser.getByteTimeDomainData(waveData);
            painter.lineWidth = Math.max(2, height * 0.012);
            painter.lineJoin = 'round';
            painter.strokeStyle = accentColor;
            painter.shadowColor = accentColor;
            painter.shadowBlur = height * 0.05;
            painter.beginPath();
            const points = Math.min(waveData.length, Math.round(width / 3));
            for (let i = 0; i < points; i++) {
                const value = waveData[Math.floor(i * waveData.length / points)] / 128 - 1;
                const x = i * width / (points - 1);
                const y = height / 2 + value * height * 0.42;
                if (i === 0) painter.moveTo(x, y);
                else painter.lineTo(x, y);
            }
            painter.stroke();
            painter.shadowBlur = 0;
        }

        // Raggi intorno al centro che ruotano piano, con un alone che pulsa sui bassi
        function drawCircle(analyser, sampleRate, width, height, now) {
            const count = 64;
            const levels = levelsOf(analyser, sampleRate, count);
            const centerX = width / 2;
            const centerY = height / 2;
            const radius = Math.min(width, height) * 0.2;
            const bass = (levels[0] + levels[1] + levels[2] + levels[3]) / 4;
            const rotation = now / 6000;

            painter.fillStyle = accentColor;
            painter.globalAlpha = 0.12 + bass * 0.4;
            painter.beginPath();
            painter.arc(centerX, centerY, radius * (0.85 + bass * 0.3), 0, Math.PI * 2);
            painter.fill();
            painter.globalAlpha = 1;

            painter.strokeStyle = accentColor;
            painter.lineCap = 'round';
            painter.lineWidth = Math.max(2, (Math.PI * 2 * radius / count) * 0.5);
            for (let i = 0; i < count; i++) {
                const angle = rotation + (i / count) * Math.PI * 2;
                const length = radius * (0.12 + levels[i] * 1.1);
                const cos = Math.cos(angle);
                const sin = Math.sin(angle);
                painter.beginPath();
                painter.moveTo(centerX + cos * radius, centerY + sin * radius);
                painter.lineTo(centerX + cos * (radius + length), centerY + sin * (radius + length));
                painter.stroke();
            }
        }

        return { update: update, reset: reset };
    }

    root.AABEffects = { attach: attach, bandLevels: bandLevels, EFFECTS: EFFECTS };
})(typeof window !== 'undefined' ? window : globalThis);
