// Punto d'ascolto degli episodi dei podcast (assets/player/podcast-progress.js).
// node --test browser/src/test/js/podcast-progress.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
require('../../main/assets/player/podcast-progress.js');

const Progress = globalThis.AABPodcastProgress;

test('riprende dal punto salvato', () => {
    const progress = {};
    Progress.update(progress, 'a', 750.4, 3600, 1);
    assert.equal(Progress.resumeAt(progress, 'a'), 750);
    assert.equal(Progress.left(progress.a), 2850);
    assert.equal(Progress.isDone(progress.a), false);
});

test('un episodio appena iniziato o mai aperto riparte da capo', () => {
    const progress = {};
    assert.equal(Progress.resumeAt(progress, 'a'), 0);
    Progress.update(progress, 'a', 10, 3600, 1);
    assert.equal(Progress.resumeAt(progress, 'a'), 0);
});

test('non cancella il punto salvato prima che l\'audio sia pronto', () => {
    const progress = {};
    Progress.update(progress, 'a', 750, 3600, 1);
    Progress.update(progress, 'a', 0, NaN, 2);
    assert.equal(Progress.resumeAt(progress, 'a'), 750);
});

test('negli ultimi secondi l\'episodio è finito e si riascolta da capo', () => {
    const progress = {};
    Progress.update(progress, 'a', 3580, 3600, 1);
    assert.equal(Progress.isDone(progress.a), true);
    assert.equal(Progress.resumeAt(progress, 'a'), 0);
    assert.equal(Progress.left(progress.a), 0);
    assert.equal(Progress.share(progress.a), 1);
});

test('negli episodi brevi il margine è più stretto', () => {
    const progress = {};
    Progress.update(progress, 'a', 100, 120, 1);
    assert.equal(Progress.isDone(progress.a), false);
    Progress.update(progress, 'a', 115, 120, 2);
    assert.equal(Progress.isDone(progress.a), true);
});

test('senza durata riprende e non risulta mai finito', () => {
    const progress = {};
    Progress.update(progress, 'a', 700, Infinity, 1);
    assert.equal(Progress.resumeAt(progress, 'a'), 700);
    assert.equal(Progress.isDone(progress.a), false);
    assert.equal(Progress.left(progress.a), 0);
    assert.equal(Progress.share(progress.a), 0);
});

test('riascoltando un episodio finito torna in corso', () => {
    const progress = {};
    Progress.update(progress, 'a', 3590, 3600, 1);
    Progress.update(progress, 'a', 60, 3600, 2);
    assert.equal(Progress.isDone(progress.a), false);
    assert.equal(Progress.resumeAt(progress, 'a'), 60);
});

test('tiene solo gli episodi più recenti', () => {
    const progress = {};
    for (let i = 0; i < 510; i++) Progress.update(progress, 'e' + i, 60, 3600, i);
    assert.equal(Object.keys(progress).length, 500);
    assert.equal(progress.e0, undefined);
    assert.equal(progress.e9, undefined);
    assert.ok(progress.e10);
    assert.ok(progress.e509);
});

test('orologio', () => {
    assert.equal(Progress.clock(0), '0:00');
    assert.equal(Progress.clock(65), '1:05');
    assert.equal(Progress.clock(3723), '1:02:03');
    assert.equal(Progress.clock(NaN), '0:00');
});
