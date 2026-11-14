// Le icone che portano alle quattro pagine di CarWeb Media (Media, TV, Radio, Podcast), nella barra in alto di ognuna:
// stesso ordine, stesso posto, stesse icone; la pagina aperta è segnata e non è un collegamento.
// node --test browser/src/test/js/pages-nav.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ASSETS = path.join(__dirname, '../../main/assets/player');
const ORDER = ['media', 'tv', 'radio', 'podcast'];
const HREFS = { media: '/', tv: '/tv', radio: '/radio', podcast: '/podcast' };
const FILES = { media: 'index.html', tv: 'tv.html', radio: 'radio.html', podcast: 'podcast.html' };

// Le voci della <nav class="pages"> di una pagina: a (collegamento) o span (pagina aperta), con icona e testo per chi non vede
function items(page) {
    const html = fs.readFileSync(path.join(ASSETS, FILES[page]), 'utf8');
    const nav = html.match(/<nav class="pages">([\s\S]*?)<\/nav>/);
    assert.ok(nav, `${FILES[page]}: manca <nav class="pages">`);
    const found = [];
    const item = /<(a|span) class="icon-button( current)?" data-page="(\w+)"((?: href="[^"]*")?)((?: aria-current="page")?) aria-label="([^"]*)">\s*(<svg[\s\S]*?<\/svg>)\s*<\/\1>/g;
    let match;
    while ((match = item.exec(nav[1])) !== null) {
        found.push({
            tag: match[1],
            current: !!match[2],
            page: match[3],
            href: (match[4].match(/href="([^"]*)"/) || [])[1],
            ariaCurrent: !!match[5],
            label: match[6],
            svg: match[7]
        });
    }
    // Niente altro dentro la barra delle pagine: ogni <a> o <span> deve essere stato letto
    const declared = (nav[1].match(/<(a|span)\b/g) || []).length;
    assert.equal(found.length, declared, `${FILES[page]}: una voce della barra delle pagine non ha la forma attesa`);
    return found;
}

test('ogni pagina mostra le quattro icone nello stesso ordine', () => {
    for (const page of ORDER) {
        assert.deepEqual(items(page).map((entry) => entry.page), ORDER, FILES[page]);
    }
});

test('le icone, i testi e gli indirizzi sono gli stessi in tutte le pagine', () => {
    const reference = items('media');
    for (const page of ORDER) {
        const own = items(page);
        for (let i = 0; i < ORDER.length; i++) {
            assert.equal(own[i].svg, reference[i].svg, `${FILES[page]}: icona di ${ORDER[i]} diversa da quella di Media`);
            assert.equal(own[i].label, reference[i].label, `${FILES[page]}: testo di ${ORDER[i]} diverso`);
            if (own[i].href !== undefined) assert.equal(own[i].href, HREFS[ORDER[i]], `${FILES[page]}: indirizzo di ${ORDER[i]}`);
        }
    }
});

test('solo la pagina aperta è segnata, e non è un collegamento', () => {
    for (const page of ORDER) {
        const entries = items(page);
        const current = entries.filter((entry) => entry.current);
        assert.deepEqual(current.map((entry) => entry.page), [page], `${FILES[page]}: la pagina segnata`);
        assert.equal(current[0].tag, 'span', `${FILES[page]}: la pagina aperta non deve essere un collegamento`);
        assert.equal(current[0].href, undefined);
        assert.equal(entries.filter((entry) => entry.ariaCurrent).length, 1, `${FILES[page]}: aria-current`);
        assert.ok(current[0].ariaCurrent, `${FILES[page]}: la voce segnata ha aria-current`);
        for (const other of entries.filter((entry) => !entry.current)) {
            assert.equal(other.tag, 'a', `${FILES[page]}: ${other.page} deve essere un collegamento`);
            assert.equal(other.href, HREFS[other.page], `${FILES[page]}: ${other.page}`);
        }
    }
});

test('ogni icona ha un testo per chi non la vede e nessuno è "Play" o "Pausa" (gli script di Android Auto cliccano per etichetta)', () => {
    for (const page of ORDER) {
        for (const entry of items(page)) {
            assert.ok(entry.label.length > 0, `${FILES[page]}: ${entry.page} senza testo`);
            assert.doesNotMatch(entry.label, /play|pausa|pause|riproduci|resume|riprendi/i, `${FILES[page]}: ${entry.page}`);
        }
    }
});

test('la barra delle quattro pagine ha la sua classe e gli stili che servono', () => {
    const css = fs.readFileSync(path.join(ASSETS, 'player.css'), 'utf8');
    assert.match(css, /\.pages\s*\{/);
    assert.match(css, /\.icon-button\.current\s*\{/);
    assert.match(css, /@media \(max-width: 520px\)/);
    for (const page of ORDER) {
        const html = fs.readFileSync(path.join(ASSETS, FILES[page]), 'utf8');
        assert.match(html, /<header class="bar has-pages">/, FILES[page]);
    }
});
