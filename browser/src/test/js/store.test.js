// Stato delle pagine di TV, Radio e Podcast, nel localStorage e nell'app (assets/player/store.js).
// node --test browser/src/test/js/store.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');

const storage = new Map();
const sent = [];
globalThis.localStorage = {
    getItem: (key) => (storage.has(key) ? storage.get(key) : null),
    setItem: (key, value) => storage.set(key, String(value)),
};
globalThis.fetch = (url) => {
    sent.push(new URL(url, 'https://player.local'));
    return Promise.resolve({ status: 204 });
};
require('../../main/assets/player/store.js');

function reset() {
    storage.clear();
    sent.length = 0;
}

test('senza copia nell\'app vale il localStorage, e l\'app ne riceve una', () => {
    reset();
    storage.set('aabTvState', JSON.stringify({ favs: [{ s: 'a', k: '1' }] }));
    const store = globalThis.AABPageStore('tv', 'aabTvState', { token: 'T' });
    assert.deepEqual(store.read(), { favs: [{ s: 'a', k: '1' }] });
    assert.equal(sent.length, 0, 'non subito: con un po\' di ritardo');
    store.flush();
    assert.equal(sent.length, 1);
    assert.equal(sent[0].pathname, '/api/pages/save');
    assert.equal(sent[0].searchParams.get('key'), 'tv');
    assert.equal(sent[0].searchParams.get('t'), 'T');
    assert.deepEqual(JSON.parse(sent[0].searchParams.get('v')), { favs: [{ s: 'a', k: '1' }] });
});

test('dopo un ripristino vale la copia dell\'app, non il localStorage vecchio', () => {
    reset();
    storage.set('aabRadioState', JSON.stringify({ favorites: ['vecchio'] }));
    const store = globalThis.AABPageStore('radio', 'aabRadioState', { token: 'T', saved: JSON.stringify({ favorites: ['nuovo'] }) });
    assert.deepEqual(store.read(), { favorites: ['nuovo'] });
    store.flush();
    assert.equal(sent.length, 0, 'l\'app ha già quello che ha dato');
});

test('i campi solo locali restano nella pagina e non vanno all\'app', () => {
    reset();
    storage.set('aabPodcastState', JSON.stringify({ following: ['vecchio'], covers: { a1: 'https://x/c.jpg' } }));
    const store = globalThis.AABPageStore('podcast', 'aabPodcastState',
        { token: 'T', saved: JSON.stringify({ following: ['a1'] }) }, ['covers']);
    const state = store.read();
    assert.deepEqual(state, { following: ['a1'], covers: { a1: 'https://x/c.jpg' } });
    store.write(state);
    assert.deepEqual(JSON.parse(storage.get('aabPodcastState')), state);
    store.flush();
    assert.deepEqual(JSON.parse(sent[0].searchParams.get('v')), { following: ['a1'] });
});

test('più salvataggi ravvicinati: all\'app arriva solo l\'ultimo', () => {
    reset();
    const store = globalThis.AABPageStore('tv', 'aabTvState', { token: 'T' });
    const state = store.read() || {};
    state.group = 1;
    store.write(state);
    state.group = 2;
    store.write(state);
    store.flush();
    store.flush();
    assert.equal(sent.length, 1);
    assert.deepEqual(JSON.parse(sent[0].searchParams.get('v')), { group: 2 });
});

test('una copia rovinata non conta', () => {
    reset();
    storage.set('aabTvState', '{rotto');
    const store = globalThis.AABPageStore('tv', 'aabTvState', { token: 'T', saved: '[1,2]' });
    assert.equal(store.read(), null);
    store.flush();
    assert.equal(sent.length, 0);
});
