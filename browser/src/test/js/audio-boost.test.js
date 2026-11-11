'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(
    path.join(__dirname, '../../main/assets/scripts/audio-boost.js'),
    'utf8'
);

function createPage(db, media) {
    const listeners = {};
    let contextsCreated = 0;
    const audioContext = {
        state: 'running',
        currentTime: 1,
        destination: {},
        resume: () => Promise.resolve(),
        createMediaElementSource: () => ({ connect() {} }),
        createGain() {
            this.lastGain = {
                gain: {
                    value: 1,
                    setTargetAtTime(value) {
                        this.value = value;
                    }
                },
                connect() {}
            };
            return this.lastGain;
        }
    };
    class FakeAudioContext {
        constructor() {
            contextsCreated++;
            return audioContext;
        }
    }

    const document = {
        addEventListener(name, callback) {
            listeners[name] = callback;
        },
        querySelectorAll: () => [media]
    };
    const window = {
        AndroidBridge: { getAudioBoostDb: () => db.value },
        AudioContext: FakeAudioContext,
        location: { href: 'https://m.youtube.com/watch?v=test', origin: 'https://m.youtube.com' },
        addEventListener() {},
        setInterval() {}
    };
    vm.runInNewContext(source, { window, document, URL, Promise, console });

    return {
        listeners,
        media,
        state: window.__aabAudioBoost,
        get contextsCreated() { return contextsCreated; },
        audioContext
    };
}

async function flushPromises() {
    await new Promise(resolve => setImmediate(resolve));
}

test('0 dB leaves native playback untouched; +15 dB maps to linear gain', () => {
    const db = { value: 0 };
    const page = createPage(db, { currentSrc: 'blob:https://m.youtube.com/video', paused: false, ended: false });

    page.listeners.play({ target: page.media });
    assert.equal(page.contextsCreated, 0);
    assert.equal(page.state.gainForDb(0), 1);
    assert.ok(Math.abs(page.state.gainForDb(15) - Math.pow(10, 0.75)) < 0.0001);
    assert.equal(page.state.gainForDb(20), page.state.gainForDb(15));
});

test('YouTube MediaSource playback gets a live, adjustable gain', async () => {
    const db = { value: 15 };
    const page = createPage(db, { currentSrc: 'blob:https://m.youtube.com/video', paused: false, ended: false });

    page.listeners.play({ target: page.media });
    await flushPromises();
    assert.equal(page.state.status(page.media), 'boosted');
    assert.ok(Math.abs(page.audioContext.lastGain.gain.value - Math.pow(10, 0.75)) < 0.0001);

    db.value = 5;
    page.state.refresh();
    await flushPromises();
    assert.ok(Math.abs(page.audioContext.lastGain.gain.value - Math.pow(10, 0.25)) < 0.0001);
});

test('protected and non-CORS cross-origin media are not captured', () => {
    const db = { value: 10 };
    const protectedMedia = {
        currentSrc: 'blob:https://m.youtube.com/video',
        mediaKeys: {},
        paused: false,
        ended: false
    };
    const page = createPage(db, protectedMedia);

    page.listeners.play({ target: protectedMedia });
    assert.equal(page.state.status(protectedMedia), 'protected');
    assert.equal(page.contextsCreated, 0);

    const crossOriginPage = createPage(db, {
        currentSrc: 'https://media.example/video.mp4',
        crossOrigin: null,
        paused: false,
        ended: false
    });
    crossOriginPage.listeners.play({ target: crossOriginPage.media });
    assert.equal(crossOriginPage.state.status(crossOriginPage.media), 'cross-origin');
    assert.equal(crossOriginPage.contextsCreated, 0);
});
