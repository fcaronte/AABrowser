// Effetti animati di CarWeb Media e della Radio (assets/player/effects.js): bande dell'analizzatore e disegno su un canvas finto.
// node --test browser/src/test/js/effects.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');

// Un DOM finto quanto basta: coda dei fotogrammi, stili calcolati, un canvas e un pennello che registra le chiamate
const frames = [];
globalThis.requestAnimationFrame = (callback) => frames.push(callback);
globalThis.getComputedStyle = () => ({ getPropertyValue: () => ' #00bcd4 ' });
globalThis.document = { documentElement: {} };
require('../../main/assets/player/effects.js');

const Effects = globalThis.AABEffects;

function fakePainter(calls) {
    return new Proxy({}, {
        get(target, name) {
            if (name === 'createLinearGradient') return () => ({ addColorStop() {} });
            if (name in target) return target[name];
            return (...args) => calls.push(name + '(' + args.length + ')');
        },
        set(target, name, value) {
            target[name] = value;
            return true;
        }
    });
}

function fakeCanvas(calls) {
    const painter = fakePainter(calls);
    return { hidden: true, clientWidth: 320, clientHeight: 180, width: 0, height: 0, getContext: () => painter };
}

function fakeAnalyser(level) {
    return {
        frequencyBinCount: 1024,
        fftSize: 2048,
        getByteFrequencyData(data) { data.fill(level); },
        getByteTimeDomainData(data) { data.fill(128); }
    };
}

test('le scelte sono spento, barre, onda e cerchio', () => {
    assert.deepEqual(Effects.EFFECTS, ['off', 'bars', 'wave', 'circle']);
});

test('bande: tutte a zero senza suono, tutte al massimo con tutto il rumore', () => {
    const silent = Effects.bandLevels(new Uint8Array(1024), 48000, 32);
    assert.equal(silent.length, 32);
    assert.ok(silent.every((level) => level === 0));
    const noise = Effects.bandLevels(new Uint8Array(1024).fill(255), 48000, 32);
    assert.ok(noise.every((level) => Math.abs(level - 1) < 1e-9));
});

test('bande: i bassi accendono le prime, gli acuti le ultime', () => {
    const data = new Uint8Array(1024);
    // Fino a circa 1 kHz (bin da 23,4 Hz)
    for (let bin = 0; bin < 43; bin++) data[bin] = 255;
    const low = Effects.bandLevels(data, 48000, 32);
    assert.ok(low[0] > 0 && low[5] > 0);
    assert.ok(low.slice(20).every((level) => level === 0));

    const high = new Uint8Array(1024);
    // Oltre 4 kHz
    for (let bin = 180; bin < 1024; bin++) high[bin] = 255;
    const bright = Effects.bandLevels(high, 48000, 32);
    assert.ok(bright.slice(0, 15).every((level) => level === 0));
    assert.ok(bright[31] > 0);
});

test('bande: un tono si ritrova nella banda della sua frequenza', () => {
    const data = new Uint8Array(1024);
    // 440 Hz con 48 kHz e 1024 bin: bin 18-19
    data[18] = 255;
    data[19] = 255;
    const levels = Effects.bandLevels(data, 48000, 32);
    const loudest = levels.indexOf(Math.max(...levels));
    // La banda i va da 40 Hz × 400^(i/32): 440 Hz cade fra la 12 e la 13
    assert.ok(loudest === 12 || loudest === 13, 'banda ' + loudest);
    assert.ok(levels.slice(0, 10).every((level) => level === 0));
    assert.ok(levels.slice(16).every((level) => level === 0));
});

test('il canvas si mostra solo quando serve e si disegna a ogni fotogramma', () => {
    frames.length = 0;
    const calls = [];
    const canvas = fakeCanvas(calls);
    let active = true;
    const visual = Effects.attach({
        canvas,
        effect: () => 'bars',
        active: () => active,
        source: () => ({ analyser: fakeAnalyser(200), sampleRate: 48000 })
    });

    visual.update();
    assert.equal(canvas.hidden, false);
    assert.equal(frames.length, 1);
    frames.shift()(100);
    assert.equal(canvas.width, 320);
    assert.equal(canvas.height, 180);
    // Velo scuro e barre
    assert.ok(calls.includes('clearRect(4)'));
    assert.ok(calls.filter((call) => call === 'fillRect(4)').length > 32);
    // Il fotogramma ne prepara un altro
    assert.equal(frames.length, 1);

    // Troppo presto (meno di 33 ms): niente di nuovo da disegnare
    const before = calls.length;
    frames.shift()(110);
    assert.equal(calls.length, before);

    // Non serve più: il canvas sparisce e i fotogrammi finiscono
    active = false;
    frames.shift()(200);
    assert.equal(canvas.hidden, true);
    assert.equal(frames.length, 0);
});

test('onda e cerchio usano il tratto, e senza analizzatore non si disegna niente', () => {
    for (const [effect, mark] of [['wave', 'stroke(0)'], ['circle', 'arc(5)']]) {
        frames.length = 0;
        const calls = [];
        const visual = Effects.attach({
            canvas: fakeCanvas(calls),
            effect: () => effect,
            active: () => true,
            source: () => ({ analyser: fakeAnalyser(120), sampleRate: 44100 })
        });
        visual.update();
        frames.shift()(100);
        assert.ok(calls.includes(mark), effect + ': ' + calls.join(','));
        assert.ok(calls.includes('fillRect(4)'), 'velo scuro');
    }

    frames.length = 0;
    const calls = [];
    const visual = Effects.attach({ canvas: fakeCanvas(calls), effect: () => 'bars', active: () => true, source: () => null });
    visual.update();
    frames.shift()(100);
    assert.equal(calls.length, 0);
});

test('reset azzera i cappelli delle barre', () => {
    frames.length = 0;
    const calls = [];
    const visual = Effects.attach({
        canvas: fakeCanvas(calls),
        effect: () => 'bars',
        active: () => true,
        source: () => ({ analyser: fakeAnalyser(255), sampleRate: 48000 })
    });
    visual.update();
    frames.shift()(100);
    assert.doesNotThrow(() => visual.reset());
    frames.shift()(200);
    assert.ok(calls.length > 0);
});
