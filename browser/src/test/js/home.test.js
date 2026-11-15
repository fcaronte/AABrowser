// La home della Wide (assets/home/home.js): lo zoom con i passi di − e +, i colori, la lettera dell'icona e il modulo come lo
// vuole l'API.
// node --test browser/src/test/js/home.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
require('../../main/assets/home/home.js');

const { fmt, steppedZoom, zoomPercent, parseColor, luminance, initialOf, shownZoom, formParams, ZOOM_MIN, ZOOM_MAX } = globalThis.AABHome;

test('i testi con i numeri come nelle risorse di Android', () => {
    assert.equal(fmt('Zoom Mobile: %d%%', 90), 'Zoom Mobile: 90%');
    assert.equal(fmt('%1$s su %2$s', 'a', 'b'), 'a su b');
    assert.equal(fmt('Nuovo aggiornamento: %s', '2.8'), 'Nuovo aggiornamento: 2.8');
});

test('lo zoom va a passi del 5% e si ferma ai limiti', () => {
    assert.equal(steppedZoom(1, 1), 1.05);
    assert.equal(steppedZoom(1, -1), 0.95);
    assert.equal(steppedZoom(0.9, 1), 0.95);
    assert.equal(steppedZoom(ZOOM_MAX, 1), ZOOM_MAX);
    assert.equal(steppedZoom(ZOOM_MIN, -1), ZOOM_MIN);
    assert.equal(steppedZoom(1.48, 1), ZOOM_MAX);
});

test('lo zoom a più passi non perde decimali', () => {
    let value = 1;
    for (let i = 0; i < 7; i++) value = steppedZoom(value, -1);
    assert.equal(value, 0.65);
    assert.equal(zoomPercent(value), 65);
    assert.equal(zoomPercent(0.9), 90);
});

test('i colori #rgb e #rrggbb, e quanto sono chiari', () => {
    assert.deepEqual(parseColor('#FF0000'), [255, 0, 0]);
    assert.deepEqual(parseColor('0af'), [0, 170, 255]);
    assert.equal(parseColor('rosso'), null);
    assert.equal(parseColor(''), null);
    assert.ok(luminance(parseColor('#101114')) < 0.1);
    assert.ok(luminance(parseColor('#FFFBFE')) > 0.9);
    assert.equal(luminance(null), 0);
});

test('la lettera dell\'icona è la prima del nome', () => {
    assert.equal(initialOf('youtube'), 'Y');
    assert.equal(initialOf('  radio'), 'R');
    assert.equal(initialOf(''), '?');
    assert.equal(initialOf(undefined), '?');
    assert.equal(initialOf('🙂 sito'), '🙂');
});

test('lo zoom del modulo è quello scelto per il sito, o quello di base della modalità', () => {
    const base = { mobile: 1, desktop: 0.8 };
    assert.equal(shownZoom({ desktop: null, mobileZoom: null, desktopZoom: null }, base), 1);
    assert.equal(shownZoom({ desktop: false, mobileZoom: 0.9, desktopZoom: null }, base), 0.9);
    assert.equal(shownZoom({ desktop: true, mobileZoom: 0.9, desktopZoom: null }, base), 0.8);
    assert.equal(shownZoom({ desktop: true, mobileZoom: 0.9, desktopZoom: 1.2 }, base), 1.2);
});

test('il modulo per l\'API: vuoto dove non c\'è una scelta', () => {
    assert.deepEqual(
        formParams({ name: ' Sito ', url: ' example.com ', color: '#8AB4F8', icon: '', desktop: null, mobileZoom: null, desktopZoom: null }),
        { name: 'Sito', url: 'example.com', color: '8AB4F8', icon: '', desktop: '', mz: '', dz: '' }
    );
    assert.deepEqual(
        formParams({ name: 'A', url: 'https://a.b', color: '#FF0000', icon: ' https://i/x.png ', desktop: true, mobileZoom: 0.9, desktopZoom: 1.25 }),
        { name: 'A', url: 'https://a.b', color: 'FF0000', icon: 'https://i/x.png', desktop: '1', mz: '0.9', dz: '1.25' }
    );
    assert.equal(formParams({ name: '', url: '', color: '#000', icon: '', desktop: false, mobileZoom: null, desktopZoom: null }).desktop, '0');
});
