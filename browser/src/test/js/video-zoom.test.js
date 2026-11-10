// Zoom dei video di CarWeb Media (assets/player/video-zoom.js): dimensioni nei tre modi e passi dello zoom.
// node --test browser/src/test/js/video-zoom.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
require('../../main/assets/player/video-zoom.js');

const Zoom = globalThis.AABVideoZoom;

test('Adatta: il video intero nel riquadro, con le bande', () => {
    assert.deepEqual(Zoom.contentSize('fit', 1920, 1080, 400, 300), [400, 225]);
    assert.deepEqual(Zoom.contentSize('fit', 1080, 1920, 400, 300), [168.75, 300]);
});

test('Riempi: il riquadro pieno, il video tagliato ai lati', () => {
    const [width, height] = Zoom.contentSize('fill', 1920, 1080, 400, 300);
    assert.equal(height, 300);
    assert.ok(Math.abs(width - 533.33) < 0.01);
});

test('Allunga, o video senza dimensioni ancora: quanto il riquadro', () => {
    assert.deepEqual(Zoom.contentSize('stretch', 1920, 1080, 400, 300), [400, 300]);
    assert.deepEqual(Zoom.contentSize('fit', 0, 0, 400, 300), [400, 300]);
});

test('passi dello zoom tra 1× e 6×', () => {
    assert.equal(Zoom.nextStep(1, 1), 1.5);
    assert.equal(Zoom.nextStep(4, 1), 6);
    assert.equal(Zoom.nextStep(6, 1), 6);
    assert.equal(Zoom.nextStep(2, -1), 1.5);
    assert.equal(Zoom.nextStep(1, -1), 1);
});
