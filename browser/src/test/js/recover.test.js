// Ripresa automatica della TV (assets/player/recover.js): quando riprova da sola e quando mai.
// node --test browser/src/test/js/recover.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
require('../../main/assets/player/recover.js');

const { create } = globalThis.AABRecover;

/** Scorre il tempo a passi di un secondo dando [state(t)] a ogni passo; ritorna le azioni con il loro istante. */
function run(recover, from, to, state) {
    const actions = [];
    for (let t = from; t <= to; t += 1000) {
        const action = recover.tick(t, state(t));
        if (action) actions.push([t, action]);
    }
    return actions;
}

const playing = (t) => ({ paused: false, time: t / 1000, otherAudio: false });
const paused = (time = 5) => () => ({ paused: true, time, otherAudio: false });
const frozen = (time = 5) => () => ({ paused: false, time, otherAudio: false });

test('un canale che suona non fa niente', () => {
    const recover = create();
    recover.begin(false);
    assert.deepEqual(run(recover, 0, 60000, playing), []);
});

test('fermo senza che l\'utente l\'abbia chiesto: prima un play(), poi ancora, poi si riavvia il flusso', () => {
    const recover = create();
    recover.begin(false);
    run(recover, 0, 5000, playing);
    const actions = run(recover, 6000, 40000, paused());
    const names = actions.map(([, name]) => name);
    assert.equal(names[0], 'play', 'il primo tentativo è un play() semplice');
    assert.ok(actions[0][0] - 6000 <= 3000, 'dopo un attimo');
    assert.ok(names.filter((name) => name === 'play').length >= 2, 'e riprova ogni tanto');
    const restart = actions.find(([, name]) => name === 'restart');
    assert.ok(restart, 'se resta fermo ricarica il flusso');
    assert.ok(restart[0] - 6000 >= 10000, 'non prima di dieci secondi di fermo');
});

test('una pausa voluta (pagina, Android Auto, volante, voce, altra riproduzione) non si riprende mai', () => {
    const recover = create();
    recover.begin(false);
    run(recover, 0, 5000, playing);
    recover.userPause();
    assert.deepEqual(run(recover, 6000, 300000, paused()), []);
    assert.equal(recover.wanted, false);
});

test('dopo una pausa voluta il play dell\'utente riattiva la ripresa automatica', () => {
    const recover = create();
    recover.begin(false);
    run(recover, 0, 3000, playing);
    recover.userPause();
    recover.userPlay();
    assert.equal(recover.wanted, true);
    const names = run(recover, 4000, 20000, paused()).map(([, name]) => name);
    assert.ok(names.includes('play'));
});

test('un play() duplicato non azzera quello che la ripresa sta facendo', () => {
    const recover = create();
    recover.begin(false);
    run(recover, 0, 3000, playing);
    run(recover, 4000, 8000, paused());
    recover.userPlay();   // già voluto: niente cambia
    const names = run(recover, 9000, 30000, paused()).map(([, name]) => name);
    assert.ok(names.includes('restart'));
});

test('se un\'altra app sta suonando non si prende l\'audio a nessuno, e riprende quando smette', () => {
    const recover = create();
    recover.begin(false);
    run(recover, 0, 3000, playing);
    const other = () => ({ paused: true, time: 3, otherAudio: true });
    assert.deepEqual(run(recover, 4000, 120000, other), [], 'aspetta, senza tentativi né riavvii');
    // L'altra app smette
    const actions = run(recover, 121000, 130000, paused(3));
    assert.equal(actions[0][1], 'play');
    assert.ok(actions[0][0] - 121000 <= 3000);
});

test('in riproduzione ma fermo (il segnale è sparito): si riavvia dopo qualche secondo', () => {
    const recover = create();
    recover.begin(false);
    run(recover, 0, 5000, playing);
    const actions = run(recover, 6000, 30000, frozen());
    const restart = actions.find(([, name]) => name === 'restart');
    assert.ok(restart);
    assert.ok(restart[0] - 6000 >= 10000 && restart[0] - 6000 <= 16000, 'dopo ' + (restart[0] - 6000));
});

test('un canale lento ad avviarsi ha più tempo, ma non per sempre', () => {
    const recover = create();
    recover.begin(false);
    // Fermo a 0 e mai partito: niente riavvio nei primi 20 secondi
    assert.deepEqual(run(recover, 0, 20000, frozen(0)), []);
    const later = run(recover, 21000, 40000, frozen(0));
    assert.ok(later.some(([, name]) => name === 'restart'), 'dopo 25 secondi sì');
});

test('un errore del flusso riavvia dopo l\'attesa, e le attese crescono finché non torna a suonare', () => {
    const recover = create();
    recover.begin(false);
    run(recover, 0, 3000, playing);
    const gaps = [];
    let now = 4000;
    for (let attempt = 0; attempt < 5; attempt++) {
        recover.error(now);
        const started = now;
        let fired = null;
        while (fired === null) {
            now += 500;
            if (recover.tick(now, paused()()) === 'restart') fired = now;
            assert.ok(now - started < 60000, 'riavvio mai arrivato');
        }
        gaps.push(fired - started);
        recover.begin(true);   // la pagina riavvia il flusso: i tentativi continuano
    }
    assert.ok(gaps[0] <= 2000, 'la prima attesa è breve: ' + gaps);
    for (let i = 1; i < gaps.length; i++) assert.ok(gaps[i] >= gaps[i - 1], 'crescono: ' + gaps);
    assert.ok(gaps[4] >= 12000, 'e diventano lunghe: ' + gaps);
});

test('dopo qualche secondo di riproduzione vera i tentativi ricominciano da capo', () => {
    const recover = create();
    recover.begin(false);
    recover.error(0);
    recover.tick(2000, paused()());
    assert.equal(recover.attempts, 1);
    recover.begin(true);
    run(recover, 3000, 8000, playing);
    assert.equal(recover.attempts, 0);
});

test('scegliere un altro canale annulla un riavvio in attesa', () => {
    const recover = create();
    recover.begin(false);
    recover.error(0);
    recover.begin(false);   // l'utente cambia canale
    assert.deepEqual(run(recover, 1000, 5000, playing), []);
});

test('un canale tolto non si riprende', () => {
    const recover = create();
    recover.begin(false);
    recover.stop();
    assert.deepEqual(run(recover, 0, 60000, paused()), []);
    recover.error(0);
    assert.deepEqual(run(recover, 0, 60000, paused()), []);
});

test('un canale che si ferma e riparte da solo non fa niente di inutile', () => {
    const recover = create();
    recover.begin(false);
    run(recover, 0, 3000, playing);
    // Fermo un secondo (focus perso per un attimo) e poi riprende
    assert.deepEqual(run(recover, 4000, 4000, paused(3)), []);
    assert.deepEqual(run(recover, 5000, 30000, (t) => ({ paused: false, time: 3 + (t - 5000) / 1000, otherAudio: false })), []);
});
