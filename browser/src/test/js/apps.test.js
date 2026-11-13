// Altre App (assets/player/apps.js): ricerca senza accenti e stato di Shizuku.
// node --test browser/src/test/js/apps.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
require('../../main/assets/player/apps.js');

const { normalize, filterApps, statusKind, statusText } = globalThis.AABApps;

const apps = [
    { p: 'com.google.android.youtube', n: 'YouTube', f: false },
    { p: 'com.whatsapp', n: 'WhatsApp', f: true },
    { p: 'org.videolan.vlc', n: 'VLC', f: false },
    { p: 'it.rai.raiplay', n: 'RaiPlay', f: false },
    { p: 'com.example.cafe', n: 'Caffè Aperto', f: false },
];

test('la ricerca ignora maiuscole e accenti', () => {
    assert.equal(normalize('  Caffè  '), 'caffe');
    assert.deepEqual(filterApps(apps, 'caffe').map((a) => a.p), ['com.example.cafe']);
    assert.deepEqual(filterApps(apps, 'WHATS').map((a) => a.p), ['com.whatsapp']);
});

test('la ricerca guarda anche il nome del pacchetto', () => {
    assert.deepEqual(filterApps(apps, 'videolan').map((a) => a.n), ['VLC']);
});

test('senza ricerca ci sono tutte le app, e senza risultati nessuna', () => {
    assert.equal(filterApps(apps, '').length, apps.length);
    assert.equal(filterApps(apps, '   ').length, apps.length);
    assert.deepEqual(filterApps(apps, 'zzz'), []);
});

test('Shizuku è verde solo se pronto, rosso in ogni altro caso', () => {
    assert.equal(statusKind('ready'), 'ready');
    for (const status of ['missing', 'stopped', 'permission', 'loading', 'altro']) {
        assert.equal(statusKind(status), 'problem');
    }
});

test('ogni stato ha il suo testo', () => {
    const strings = { ready: 'a', missing: 'b', stopped: 'c', permission: 'd', loading: 'e' };
    assert.equal(statusText(strings, 'ready'), 'a');
    assert.equal(statusText(strings, 'missing'), 'b');
    assert.equal(statusText(strings, 'stopped'), 'c');
    assert.equal(statusText(strings, 'permission'), 'd');
    assert.equal(statusText(strings, 'boh'), 'e');
});
