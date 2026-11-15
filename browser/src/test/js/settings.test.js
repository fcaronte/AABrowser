// Impostazioni come pagina (assets/player/settings.js): i testi con i numeri, le condizioni delle righe, i passi di - e +,
// le voci delle scelte e l'elenco dei tasti della sidebar (cosa cambia toccandone uno).
// node --test browser/src/test/js/settings.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
require('../../main/assets/player/settings.js');

const { fmt, holds, visible, stepped, optionsOf, selectedLabel, shownIds, toggled, moved } = globalThis.AABSettings;

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

// --- Testi con i numeri: arrivano come sono nelle risorse di Android ---

test('i testi con il numero: %d, %1$d e il % scritto %%', () => {
    assert.equal(fmt('Schede da precaricare: %d', 4), 'Schede da precaricare: 4');
    assert.equal(fmt('%1$d dB', 12), '12 dB');
    assert.equal(fmt('Zoom: %d%%', 75), 'Zoom: 75%');
    assert.equal(fmt('%1$s: %2$s', 'Microfono', 'Consentito'), 'Microfono: Consentito');
    assert.equal(fmt('%2$s - %1$s', 'a', 'b'), 'b - a');
});

test('un segnaposto senza valore resta com\'è, e un testo senza segnaposto non cambia', () => {
    assert.equal(fmt('Cache (%s)'), 'Cache (%s)');
    assert.equal(fmt('Solo testo', 1, 2), 'Solo testo');
});

// --- Condizioni delle righe ---

test('una condizione vale se il valore è quello (o non è quello)', () => {
    const values = { force_theme: '1', adblock_list: 'custom' };
    assert.equal(holds({ k: 'force_theme', eq: '1' }, values), true);
    assert.equal(holds({ k: 'force_theme', eq: '0' }, values), false);
    assert.equal(holds({ k: 'adblock_list', ne: 'custom' }, values), false);
    assert.equal(holds({ k: 'adblock_list', ne: 'altra' }, values), true);
    // Un valore che non c'è non è mai quello cercato
    assert.equal(holds({ k: 'assente', eq: '1' }, values), false);
    assert.equal(holds({ k: 'assente', ne: '1' }, values), true);
});

test('una riga si mostra solo se tutte le sue condizioni valgono', () => {
    const values = { adblock: '1', adblock_list: 'custom' };
    assert.equal(visible(undefined, values), true);
    assert.equal(visible([], values), true);
    assert.equal(visible([{ k: 'adblock', eq: '1' }, { k: 'adblock_list', eq: 'custom' }], values), true);
    assert.equal(visible([{ k: 'adblock', eq: '1' }, { k: 'adblock_list', eq: 'altra' }], values), false);
});

// --- − e + ---

test('un passo di - o + resta dentro i limiti', () => {
    assert.equal(stepped(50, 1, 25, 150, 5), 55);
    assert.equal(stepped(50, -1, 25, 150, 5), 45);
    assert.equal(stepped(25, -1, 25, 150, 5), 25);
    assert.equal(stepped(150, 1, 25, 150, 5), 150);
    assert.equal(stepped(0, 1, 0, 15, 1), 1);
    // Il massimo lo può dare l'app (i tasti mostrati): 3 tasti, al massimo 3 visibili
    assert.equal(stepped(3, 1, 1, 3, 1), 3);
});

// --- Voci delle scelte ---

test('le voci di una scelta: quelle dello schema o quelle dell\'app', () => {
    const fixed = { options: [['a', 'Uno'], ['b', 'Due']] };
    const dynamic = { optionsData: 'lista' };
    const data = { lista: [['x', 'Ics']] };
    assert.deepEqual(optionsOf(fixed, data), [['a', 'Uno'], ['b', 'Due']]);
    assert.deepEqual(optionsOf(dynamic, data), [['x', 'Ics']]);
    assert.deepEqual(optionsOf(dynamic, {}), []);
});

test('il testo della voce scelta, o la prima se il valore non c\'è più', () => {
    const options = [['a', 'Uno'], ['b', 'Due']];
    assert.equal(selectedLabel(options, 'b'), 'Due');
    assert.equal(selectedLabel(options, 'zzz'), 'Uno');
    assert.equal(selectedLabel([], 'a'), '');
});
