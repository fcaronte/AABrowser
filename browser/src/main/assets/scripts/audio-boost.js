(function() {
    'use strict';

    if (window.__aabAudioBoostInstalled) return;
    window.__aabAudioBoostInstalled = true;

    const records = new WeakMap();
    const pending = new WeakSet();
    const failed = new WeakSet();
    const statuses = new WeakMap();
    let audioContext = null;

    function currentDb() {
        try {
            const value = Number(window.AndroidBridge && window.AndroidBridge.getAudioBoostDb());
            return Number.isFinite(value) ? Math.min(15, Math.max(0, value)) : 0;
        } catch (_) {
            return 0;
        }
    }

    function gainForDb(db) {
        return Math.pow(10, Math.min(15, Math.max(0, db)) / 20);
    }

    function isCaptureSafe(media) {
        if (media.mediaKeys) return false;
        const source = media.currentSrc || media.src;
        if (!source || source.startsWith('blob:') || source.startsWith('data:')) return true;
        try {
            const origin = new URL(source, window.location.href).origin;
            return origin === window.location.origin ||
                media.crossOrigin === 'anonymous' ||
                media.crossOrigin === 'use-credentials';
        } catch (_) {
            return false;
        }
    }

    function setGain(record, db) {
        if (!record || record.context.state === 'closed') return;
        const value = gainForDb(db);
        const param = record.gain.gain;
        if (typeof param.setTargetAtTime === 'function') {
            param.setTargetAtTime(value, record.context.currentTime, 0.03);
        } else {
            param.value = value;
        }
    }

    function attach(media) {
        const db = currentDb();
        if (db <= 0 || !media || failed.has(media)) return;
        if (records.has(media)) {
            setGain(records.get(media), db);
            return;
        }
        if (pending.has(media)) return;
        if (!isCaptureSafe(media)) {
            statuses.set(media, media.mediaKeys ? 'protected' : 'cross-origin');
            return;
        }

        const AudioContext = window.AudioContext || window.webkitAudioContext;
        if (!AudioContext) {
            failed.add(media);
            statuses.set(media, 'unsupported');
            return;
        }

        try {
            audioContext = audioContext || new AudioContext();
            const context = audioContext;
            pending.add(media);
            statuses.set(media, 'waiting-for-audio-context');
            const resumed = context.state === 'running' ? Promise.resolve() : context.resume();
            Promise.resolve(resumed).then(function() {
                pending.delete(media);
                if (context.state !== 'running' || currentDb() <= 0 || media.mediaKeys) {
                    statuses.set(media, media.mediaKeys ? 'protected' : 'not-started');
                    return;
                }
                const source = context.createMediaElementSource(media);
                const gain = context.createGain();
                gain.gain.value = gainForDb(currentDb());
                source.connect(gain);
                gain.connect(context.destination);
                const record = { context: context, gain: gain };
                records.set(media, record);
                statuses.set(media, 'boosted');
            }).catch(function(error) {
                pending.delete(media);
                failed.add(media);
                statuses.set(media, error && error.name === 'NotSupportedError' ? 'unsupported' : 'unavailable');
                console.warn('[CarWeb] Audio boost unavailable:', error && error.name || 'Error');
            });
        } catch (error) {
            pending.delete(media);
            failed.add(media);
            statuses.set(media, 'unavailable');
            console.warn('[CarWeb] Audio boost unavailable:', error && error.name || 'Error');
        }
    }

    function refresh() {
        const db = currentDb();
        document.querySelectorAll('audio, video').forEach(function(media) {
            const record = records.get(media);
            if (record) {
                setGain(record, db);
            } else if (db > 0 && !media.paused && !media.ended) {
                attach(media);
            }
        });
    }

    document.addEventListener('play', function(event) {
        attach(event.target);
    }, true);
    document.addEventListener('playing', function(event) {
        attach(event.target);
    }, true);
    window.addEventListener('pointerdown', function() {
        if (audioContext && audioContext.state === 'suspended') {
            audioContext.resume().catch(function() {});
        }
    }, true);
    window.addEventListener('keydown', function() {
        if (audioContext && audioContext.state === 'suspended') {
            audioContext.resume().catch(function() {});
        }
    }, true);
    document.addEventListener('visibilitychange', refresh);
    window.setInterval(refresh, 1000);

    window.__aabAudioBoost = {
        gainForDb: gainForDb,
        status: function(media) { return statuses.get(media) || 'idle'; },
        refresh: refresh
    };
})();
