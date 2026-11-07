// SponsorBlock: risposta vera dell'API (07/11/2026) e indirizzi di YouTube.
// node --test browser/src/test/js/sponsorblock.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
require('../../main/assets/scripts/sponsorblock.js');

const { videoIdOf, segmentsFor, segmentAt, sha256Prefix } = globalThis.AABSponsorBlock;

const entries = [
    {
        videoID: 'eXjGWlJOhWg',
        segments: [
            { category: 'intro', actionType: 'skip', segment: [0, 14.827], UUID: 'a1', videoDuration: 3238.835 },
            { category: 'sponsor', actionType: 'skip', segment: [120.5, 180], UUID: 'b2' },
            { category: 'sponsor', actionType: 'mute', segment: [300, 310], UUID: 'c3' },
            { category: 'selfpromo', actionType: 'skip', segment: [500, 500.4], UUID: 'd4' },
            { category: 'sponsor', actionType: 'full', segment: [0, 0], UUID: 'e5' },
        ],
    },
    { videoID: 'other123456', segments: [{ category: 'sponsor', actionType: 'skip', segment: [10, 20], UUID: 'x' }] },
];

test('video id from YouTube addresses', () => {
    assert.equal(videoIdOf('https://m.youtube.com/watch?v=eXjGWlJOhWg&t=42'), 'eXjGWlJOhWg');
    assert.equal(videoIdOf('https://music.youtube.com/watch?v=abcdefghijk&list=RD1'), 'abcdefghijk');
    assert.equal(videoIdOf('https://youtu.be/abcdefghijk?si=x'), 'abcdefghijk');
    assert.equal(videoIdOf('https://m.youtube.com/shorts/abcdefghijk'), 'abcdefghijk');
    assert.equal(videoIdOf('https://www.youtube.com/embed/abcdefghijk?autoplay=1'), 'abcdefghijk');
    assert.equal(videoIdOf('https://m.youtube.com/'), null);
    assert.equal(videoIdOf('https://m.youtube.com/results?search_query=gatti'), null);
    assert.equal(videoIdOf('not a url'), null);
});

test('only the parts of the video to skip, in the chosen categories', () => {
    assert.deepEqual(segmentsFor(entries, 'eXjGWlJOhWg', ['sponsor', 'intro']).map((s) => s.id), ['a1', 'b2']);
    assert.deepEqual(segmentsFor(entries, 'eXjGWlJOhWg', ['sponsor']).map((s) => s.id), ['b2']);
    // Parte di meno di un secondo, muto e video intero: no
    assert.deepEqual(segmentsFor(entries, 'eXjGWlJOhWg', ['selfpromo']), []);
    assert.deepEqual(segmentsFor(entries, 'missing0000', ['sponsor']), []);
    assert.deepEqual(segmentsFor(null, 'eXjGWlJOhWg', ['sponsor']), []);
});

test('the part under the playback position, once', () => {
    const segments = segmentsFor(entries, 'eXjGWlJOhWg', ['sponsor', 'intro']);
    const skipped = new Set();
    assert.equal(segmentAt(segments, 0, skipped).id, 'a1');
    assert.equal(segmentAt(segments, 60, skipped), null);
    assert.equal(segmentAt(segments, 121, skipped).id, 'b2');
    // Quasi alla fine: non vale la pena
    assert.equal(segmentAt(segments, 179.8, skipped), null);
    skipped.add('b2');
    assert.equal(segmentAt(segments, 150, skipped), null);
});

test('hash prefix sent to SponsorBlock', async () => {
    // sha256("dQw4w9WgXcQ") inizia con 5f6b (controllato con sha256sum)
    assert.equal(await sha256Prefix('dQw4w9WgXcQ'), '5f6b');
});
