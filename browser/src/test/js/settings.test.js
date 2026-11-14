// Impostazioni come pagina (assets/player/settings.js): l'elenco dei tasti della sidebar, cosa cambia toccandone uno.
// node --test browser/src/test/js/settings.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
require('../../main/assets/player/settings.js');

const { shownIds, toggled, moved } = globalThis.AABSettings;

const list = () => [
    { id: 'home', on: true, essential: true },
    { id: 'back', on: true },
    { id: 'reload', on: true },
    { id: 'share', on: false },
    { id: 'exit', on: false }
];

test('i tasti mostrati, nell\'ordine in cui compaiono', () => {
    assert.deepEqual(shownIds(list()), ['home', 'back', 'reload']);
});

test('spegnere un tasto lo manda tra i nascosti, e gli altri restano come stavano', () => {
    const next = toggled(list(), 'back');
    assert.deepEqual(shownIds(next), ['home', 'reload']);
    assert.equal(next.find((item) => item.id === 'back').on, false);
    assert.equal(next.length, 5);
});

test('accendere un tasto lo mette in fondo a quelli mostrati', () => {
    const next = toggled(list(), 'exit');
    assert.deepEqual(shownIds(next), ['home', 'back', 'reload', 'exit']);
});

test('Home non si spegne', () => {
    const before = list();
    assert.deepEqual(toggled(before, 'home'), before);
    assert.deepEqual(shownIds(toggled(before, 'home')), ['home', 'back', 'reload']);
});

test('un tasto che non c\'è non cambia niente', () => {
    const before = list();
    assert.deepEqual(toggled(before, 'sconosciuto'), before);
});

test('spostare scambia con il vicino e si ferma ai bordi', () => {
    assert.deepEqual(shownIds(moved(list(), 'back', 1)), ['home', 'reload', 'back']);
    assert.deepEqual(shownIds(moved(list(), 'reload', -1)), ['home', 'reload', 'back']);
    assert.deepEqual(shownIds(moved(list(), 'home', -1)), ['home', 'back', 'reload']);
    assert.deepEqual(shownIds(moved(list(), 'reload', 1)), ['home', 'back', 'reload']);
});

test('un tasto nascosto non si sposta, e gli altri nascosti restano in fondo', () => {
    const before = list();
    assert.deepEqual(moved(before, 'share', -1), before);
    const next = moved(before, 'back', 1);
    assert.deepEqual(next.map((item) => item.id), ['home', 'reload', 'back', 'share', 'exit']);
});
