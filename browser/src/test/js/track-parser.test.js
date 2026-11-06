// Testi veri mandati dalle stazioni (metadati ICY campionati da Radio Browser il 06/11/2026).
// node --test browser/src/test/js/track-parser.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
require('../../main/assets/player/track-parser.js');

const { parse, fromTags, matches } = globalThis.AABTrackParser;

function song(raw, station, artist, title) {
    const track = parse(raw, station);
    assert.equal(track.isSong, true, raw);
    assert.equal(track.artist, artist, raw);
    assert.equal(track.title, title, raw);
    return track;
}

function notSong(raw, station, display) {
    const track = parse(raw, station);
    assert.equal(track.isSong, false, raw);
    if (display !== undefined) assert.equal(track.display, display, raw);
}

test('brano in onda e prossimo brano', () => {
    const current = 'Brano attuale: Vasco Rossi - Albachiara, Brano successivo: Ligabue - Certe notti';
    assert.equal(song(current, 'Radio X', 'Vasco Rossi', 'Albachiara').display, 'Vasco Rossi - Albachiara');
    song('BRANO ATTUALE VASCO ROSSI - ALBACHIARA BRANO SUCCESSIVO LIGABUE - CERTE NOTTI', '', 'VASCO ROSSI', 'ALBACHIARA');
    song('Brano successivo: Ligabue - Certe notti | Brano attuale: Vasco Rossi - Albachiara', '', 'Vasco Rossi', 'Albachiara');
    song('Vasco Rossi - Albachiara - A seguire: Ligabue - Certe notti', '', 'Vasco Rossi', 'Albachiara');
    song('Vasco Rossi - Albachiara, a seguire Ligabue - Certe notti', '', 'Vasco Rossi', 'Albachiara');
    song('Ora in Onda: BONNIE TYLER - IT\'S A HEARTACHE', 'Radio Cafè', 'BONNIE TYLER', 'IT\'S A HEARTACHE');
    song('Now Playing: Queen - Radio Ga Ga | Next: Blur - Song 2', '', 'Queen', 'Radio Ga Ga');
    song('Jetzt läuft: Nena - 99 Luftballons, Als nächstes: Falco - Rock Me Amadeus', '', 'Nena', '99 Luftballons');
    song('Сейчас играет: Кино - Группа крови', '', 'Кино', 'Группа крови');
    song('正在播放: 周杰伦 - 稻香', '', '周杰伦', '稻香');
    // Il brano in onda senza trattino: resta una frase, da cercare così com'è
    const phrase = parse('brano attuale vasco rossi albachiara, brano successivo ligabue certe notti', '');
    assert.equal(phrase.phrase, true);
    assert.equal(phrase.title, 'vasco rossi albachiara');
    // Il brano in onda c'è solo a metà
    const half = parse('ALOE - seguirà: CARTOONS - WITCH DOCTOR', 'Radio Manila');
    assert.equal(half.display, 'ALOE');
    // Solo il prossimo: del brano in onda non si sa niente
    notSong('Next: Blur - Song 2', '');
});

test('le stesse parole dentro un titolo non sono etichette', () => {
    song('Odyssey - Now That We Found Love', '', 'Odyssey', 'Now That We Found Love');
    song('Emeli Sandé - Next to Me', '', 'Emeli Sandé', 'Next to Me');
    song('Ron Sexsmith - Before the light is gone', '', 'Ron Sexsmith', 'Before the light is gone');
    song('Paul McCartney - Coming Up', '', 'Paul McCartney', 'Coming Up');
    song('Le Vibrazioni - Dopo la pioggia', '', 'Le Vibrazioni', 'Dopo la pioggia');
    song('Pink Floyd - Not Now John', '', 'Pink Floyd', 'Not Now John');
});

test('formati con i campi', () => {
    song('Rocket Man~Elton John~~1972~~268~2026-11-06T15:09:53~2026-11-06T15:12:55~Virgin Radio Classic Rock~182.98~a67f',
        'Virgin Radio Classic Rock', 'Elton John', 'Rocket Man');
    const upper = song('TANANAI~BUONANOTTE ANCHE STANOTTE~~2026~~210~2026-11-06T16:13:42~2026-11-06T16:13:42~Radio 105',
        'Radio 105', 'TANANAI', 'BUONANOTTE ANCHE STANOTTE');
    assert.deepEqual(upper.alternatives[0], { artist: 'BUONANOTTE ANCHE STANOTTE', title: 'TANANAI' });
    assert.equal(song('PLACEBO~HELEN KELLER~HELEN KELLER - SINGLE~2026~~281~x~y~Virgin Radio', '', 'PLACEBO', 'HELEN KELLER').album, 'HELEN KELLER');
    song('Oi Va Voi~D\'Ror Ykra~Laughter Through Tears~2003', '', 'Oi Va Voi', 'D\'Ror Ykra');
    notSong('R101 La Banda di R101~~~~~329~2026-11-06T15:14:10~2026-11-06T15:14:13~R101 La Banda di R101~3.33~b282', 'R101 La Banda di R101', '');
    notSong('Rave The World Radio~Alok~~~~3600~2026-11-06T15:00:12~2026-11-06T15:12:54~Radio 105 InDaKlubb~762.73~b3a1', 'Radio 105');
    song('Song*KILLING ME SOFTLY*ROBERTA FLACK*1973*d1cbc746-0033-4b7a-90a5-449e38f92e70', '', 'ROBERTA FLACK', 'KILLING ME SOFTLY');
    song('Nirvana - text="Come As You Are" song_spot="M" MediaBaseId="1135223" itunesTrackId="0" length="00:03:35"', '', 'Nirvana', 'Come As You Are');
    song('title="Rude Boy",artist="RIHANNA",url="song_spot="F" MediaBaseId="0" length="00:03:42"', '', 'RIHANNA', 'Rude Boy');
    notSong('- text="THE JUBAL SHOW FRI H4S2 [GEN]" song_spot="T" spotInstanceId="-1" length="00:07:48"', '', 'THE JUBAL SHOW FRI H4S2 [GEN]');
    notSong('adContext="aHR0cHM6Ly9uMThiLWUy"', '', '');
    song('Artista: Vasco Rossi Titolo: Albachiara', '', 'Vasco Rossi', 'Albachiara');
    song('Title: Albachiara - Artist: Vasco Rossi', '', 'Vasco Rossi', 'Albachiara');
});

test('nome della stazione, indirizzi e note', () => {
    song('Dj Tatanka - m2o - Zero Db', 'm2o originals radio', 'Dj Tatanka', 'Zero Db');
    song('Radio 105 - Vasco Rossi - Albachiara', 'Radio 105 Network', 'Vasco Rossi', 'Albachiara');
    song('Sinead O\'connor - This Is A Rebel Song - TISF This Is Sherwood Festival - sherwood.it', 'Radio Sherwood', 'Sinead O\'connor', 'This Is A Rebel Song');
    song('T. Rex - 20th Century Boy (Remastered)', '', 'T. Rex', '20th Century Boy');
    song('Lucio Battisti - La canzone del sole - 1971', '', 'Lucio Battisti', 'La canzone del sole');
    song('Nick Leng - Inside Your Mind (Feat. Carmody) - 0:00', '', 'Nick Leng', 'Inside Your Mind');
    song('Queen - Bohemian Rhapsody - Remastered 2011', '', 'Queen', 'Bohemian Rhapsody');
    song('Daniele Luppi - Free Love Sequence (Lp Version', '', 'Daniele Luppi', 'Free Love Sequence');
    song('Atlanta Rhythm Section - A Livin\' Lovin\' Wreck {Back Up Against The Wall - 1973}', '', 'Atlanta Rhythm Section', 'A Livin\' Lovin\' Wreck');
    song('Giovanni Bottesini (1821-1889) - Concerto in fa diesis minore (Furtok, contrabbasso - Orchester Frankfurt) {+info: veniceclassicradio.eu}',
        'Venice Classic Radio', 'Giovanni Bottesini', 'Concerto in fa diesis minore');
    song('KEHLANI FT MISSY ELLIOTT - BACK AND FORTH', '', 'KEHLANI', 'BACK AND FORTH');
    song('Greensleeves by Rosemary Breland - Christmas Vinyl on walmradio.com', 'Christmas Vinyl HD', 'Rosemary Breland', 'Greensleeves');
    // "with" e "con" dei titoli restano
    song('Gigi D\'Alessio - Portami con te', '', 'Gigi D\'Alessio', 'Portami con te');
    song('Dido - Here with Me', '', 'Dido', 'Here with Me');
    // Trattino nel nome: non divide
    song('Jay-Z - Empire State of Mind', '', 'Jay-Z', 'Empire State of Mind');
    song('Taylor Swift - Anti-Hero', '', 'Taylor Swift', 'Anti-Hero');
    assert.equal(parse('Anne-Marie', '').phrase, true);
    // Trattino attaccato tra parole
    song('CLAUDIO BAGLIONI-STRADA FACENDO', '', 'CLAUDIO BAGLIONI', 'STRADA FACENDO');
    // Spazi, BOM, entità
    song('\n  \n    Andy Gibb - Shadow dancing\n  \n', '', 'Andy Gibb', 'Shadow dancing');
    song('﻿Simon &amp; Garfunkel - The Boxer', '', 'Simon & Garfunkel', 'The Boxer');
});

test('non sono canzoni', () => {
    notSong('Radio Tirol', 'Radio Tirol', '');
    notSong('MARILU', 'Radio Marilù', '');
    notSong('ONEDANCE', 'One Dance', '');
    notSong('www.ladyradio.it', 'Lady Radio', '');
    notSong('*WWW.GAMMARADIO.IT*', 'Gamma Radio - Golden Hits', '');
    notSong('-', 'Radio Stella', '');
    notSong('_', 'Times Radio', '');
    notSong('Now Playing info goes here', 'Radio Stereocittà', '');
    notSong('On Air - Pubblicita\'', 'Radio Marte');
    notSong('PUBBLICITÀ - Radio Babboleo', 'Radio Babboleo');
    notSong('AD', 'Dj Osso Radio');
    notSong('ADBREAK_END', 'MC2');
    notSong('ADWTAG_122000 - THIS STATION WILL CONTINUE AFTER THIS BREAK', '#1 HITS 80s', '');
    notSong('Big R Radio - We’ll Be Right Back After This Message', 'Big R Radio');
    notSong('Sport Club con Tommaso Lavizzari - WhatsApp 334.11.11.622', 'Giornale Radio');
    notSong('WDR 5 Hotline: 0221-56789 555', 'WDR 5');
    notSong('*** Nachrichten kompakt', 'hr-info', 'Nachrichten kompakt');
    // Lo slogan della stazione si mostra ma non si cerca da solo
    const slogan = parse('Radio Kiss Kiss - Play everywhere!', 'Radio Kiss Kiss');
    assert.equal(slogan.display, 'Play everywhere!');
    assert.equal(slogan.searchable, false);
    // Un numero di 7 cifre è un titolo
    song('Tommy Tutone - 867-5309', '', 'Tommy Tutone', '867-5309');
});

test('confronto con i risultati', () => {
    const swapped = parse('Radio Gaga - Queen', 'Absolute Radio');
    // Al contrario, e con le parole staccate diversamente
    assert.equal(matches('Radio Ga Ga', 'Queen', swapped), true);
    assert.equal(matches('Radio Ga Ga', 'Tribute Band', swapped), false);
    const track = parse('THE WEEKND - CAN\'T FEEL MY FACE', '');
    assert.equal(matches('Can\'t Feel My Face', 'The Weeknd', track), true);
    assert.equal(matches('Can\'t Feel My Face (Remix)', 'The Weeknd, Foo', track), true);
    assert.equal(matches('Can\'t Feel My Face', 'Tribute Band', track), false);
    assert.equal(matches('Blinding Lights', 'The Weeknd', track), false);
    assert.equal(matches('Domino Dancing', 'Pet Shop Boys', parse('PET SHOP BOYS - DOMINO DANCING', '')), true);
    assert.equal(matches('Everybody Hurts', 'R.E.M.', parse('Rem - Everybody hurts', '')), true);
    assert.equal(matches('Vento d\'estate', 'Max Gazzè', parse('Vento D\'estate~Max Gazzè & Niccolò Fabi~~1998~X~194~a~b~c', '')), true);
    assert.equal(matches('Can\'t Help Falling in Love', 'Elvis Presley', parse('Elvis Presley - Can\'t Help Falling in Love with You', '')), true);
    // Una frase vale solo se il brano la copre tutta
    const phrase = parse('vasco rossi albachiara', '');
    assert.equal(matches('Albachiara', 'Vasco Rossi', phrase), true);
    assert.equal(matches('Albachiara', 'Mònica Serra', phrase), false);
    assert.equal(matches('No More Games', 'New Kids', parse('No More Games', '')), false);
});

test('file senza tag', () => {
    const file = fromTags({ title: '01 - Vasco Rossi - Albachiara (Official Video)', artist: '', album: '' });
    assert.equal(file.artist, 'Vasco Rossi');
    assert.equal(file.title, 'Albachiara');
    const plain = fromTags({ title: 'Albachiara', artist: '', album: '' });
    assert.equal(plain.phrase, true);
    assert.equal(plain.searchable, true);
    const tagged = fromTags({ title: 'Bohemian Rhapsody (Remastered 2011)', artist: 'Queen', album: 'Greatest Hits' });
    assert.equal(tagged.artist, 'Queen');
    assert.equal(tagged.title, 'Bohemian Rhapsody');
    assert.equal(tagged.album, 'Greatest Hits');
    assert.equal(fromTags({ title: '99 Problems', artist: '', album: '' }).title, '99 Problems');
});
