/*
 * Dal testo che manda una stazione (metadati ICY e ID3) o dai tag di un file al brano da cercare su LRCLIB.
 *
 * Le stazioni scrivono di tutto: "Ora in Onda: Artista - Titolo", "Brano attuale: A - B, Brano successivo: C - D",
 * "Titolo~Artista~~1972~~268~2026-11-06T15:09:53~…", "Song*TITOLO*ARTISTA*1973*…", "Artista - text=\"Titolo\" song_spot=\"M\" …",
 * il nome della stazione tra le parti, pubblicità, numeri di telefono, indirizzi web. Qui si tiene solo il brano in
 * onda, con artista e titolo separati quando si riesce a capirlo. L'ordine non è sicuro ("Radio Gaga - Queen"):
 * per questo la ricerca del testo prova anche al contrario (matches).
 */
(function (root) {
    'use strict';

    const BEFORE = '(?<![\\p{L}\\p{N}])';
    const AFTER = '(?![\\p{L}\\p{N}])';

    // Etichette del brano in onda, del prossimo e del precedente nelle lingue dell'app. Quelle forti sono così lunghe
    // che non possono far parte di un titolo: valgono anche senza i due punti ("brano attuale Vasco Rossi - Albachiara")
    const STRONG_LABELS = {
        current: [
            'brano attuale', 'brano corrente', 'brano in onda', 'canzone attuale', 'canzone in onda', 'traccia attuale',
            'titolo attuale', 'ora in onda', 'adesso in onda', 'in onda ora', 'in onda adesso', 'ora in riproduzione',
            'in riproduzione', 'stai ascoltando', 'state ascoltando', 'sta suonando', 'ora suona',
            'now playing', 'currently playing', 'playing now', 'now on air', 'on air now', 'current track',
            'current song', 'you are listening to', "you[’']re listening to",
            'sonando ahora', 'ahora suena', 'ahora sonando', 'suena ahora', 'canci[oó]n actual', 'tema actual',
            'est[aá]s escuchando',
            'titre en cours', 'morceau en cours', 'en ce moment', 'vous [eé]coutez',
            'jetzt l[aä]uft', 'l[aä]uft gerade', 'gerade l[aä]uft', 'aktueller titel', 'aktueller song', 'sie h[oö]ren',
            'du h[oö]rst',
            'tocando agora', 'agora tocando', 'a tocar agora', 'm[uú]sica atual', 'faixa atual',
            'voc[eê] est[aá] ouvindo',
            'сейчас играет', 'сейчас в эфире', 'текущий трек',
            'most sz[oó]l', 'most j[aá]tsszuk', '[eé]ppen sz[oó]l',
            'يعرض الآن', 'تستمع الآن', 'المقطع الحالي', 'الأغنية الحالية',
            'अभी बज रहा है', 'अब बज रहा है',
            '正在播放', '当前播放', '现在播放', '当前歌曲'
        ],
        next: [
            'brano successivo', 'prossimo brano', 'brano seguente', 'prossima canzone', 'canzone successiva',
            'traccia successiva', 'titolo successivo',
            'next song', 'next track', 'next up', 'up next', 'coming up next', 'followed by',
            'siguiente canci[oó]n', 'pr[oó]xima canci[oó]n', 'pr[oó]ximo tema', 'a continuaci[oó]n',
            '[aà] suivre', 'titre suivant', 'prochain titre', 'morceau suivant',
            'als n[aä]chstes', 'n[aä]chster titel', 'n[aä]chster song', 'im anschluss',
            'pr[oó]xima m[uú]sica', 'pr[oó]xima faixa',
            'следующий трек', 'следующая песня',
            'المقطع التالي', 'الأغنية التالية', 'अगला गाना', '下一首', '下一曲', '接下来', '即将播放'
        ],
        previous: [
            'brano precedente', 'canzone precedente', 'titolo precedente', 'appena ascoltato',
            'previous song', 'previous track', 'last played', 'last song', 'just played',
            'canci[oó]n anterior', 'titre pr[eé]c[eé]dent', 'vorheriger titel', 'm[uú]sica anterior',
            'предыдущий трек', 'предыдущая песня', 'الأغنية السابقة', 'पिछला गाना', '上一首'
        ]
    };

    // Queste possono stare anche in un titolo ("Now That We Found Love", "Next to Me", "Dopo la pioggia"): valgono solo
    // con i due punti. "A seguire" e simili anche dopo una separazione ("Artista - Titolo, a seguire …")
    const WEAK_LABELS = {
        current: [
            'ora', 'adesso', 'in onda', 'on air', 'on-air', 'now', 'current', 'playing', 'ahora', 'sonando',
            'en el aire', 'al aire', 'reproduciendo', 'actualmente', 'en cours', 'actuellement', 'maintenant',
            '[aà] l.antenne', 'jetzt', 'aktuell', 'agora', 'tocando', 'no ar', 'сейчас', 'в эфире', 'играет', 'most',
            'jelenleg', 'الآن', 'अभी', '现在'
        ],
        next: [
            'a seguire', 'a seguir', 'seguir[aà]', 'segue', 'successivo', 'successiva', 'prossimo', 'prossima', 'dopo',
            'next', 'coming up', 'then', 'siguiente', 'despu[eé]s', 'suivant', 'suivante', 'ensuite', 'danach',
            'n[aä]chster', 'n[aä]chste', 'gleich', 'depois', 'pr[oó]xim[oa]', 'далее', 'следующий', 'следующая', 'затем',
            'потом', 'k[oö]vetkez[oő]', 'ut[aá]na', 'التالي', 'अगला', '下一个'
        ],
        previous: [
            'precedente', 'prima', 'previous', 'before', 'anterior', 'pr[eé]c[eé]dent', 'zuvor', 'davor', 'vorher',
            'предыдущий', 'предыдущая', 'el[oő]z[oő]', 'السابق', 'पिछला'
        ]
    };
    const AFTER_BREAK_LABELS = ['a seguire', 'a seguir', 'coming up'];

    function labelGroup(words) {
        return words.slice().sort((a, b) => b.length - a.length).join('|').replace(/ /g, '\\s+');
    }

    function labelPattern(labels) {
        return '(?:(' + labelGroup(labels.current) + ')|(' + labelGroup(labels.next) + ')|(' +
            labelGroup(labels.previous) + '))';
    }

    const STRONG = new RegExp(BEFORE + labelPattern(STRONG_LABELS) + AFTER + '\\s*[:：=>»\\-–—.]*\\s*', 'giu');
    const WEAK = new RegExp(BEFORE + labelPattern(WEAK_LABELS) + '\\s*[:：=>»]+\\s*', 'giu');
    const AFTER_BREAK = new RegExp(
        '(?<=^|[,;|/\\-–—.(]\\s*)(' + labelGroup(AFTER_BREAK_LABELS) + ')' + AFTER + '\\s*', 'giu');

    // Parti che dicono che non è una canzone. Intere, perché "Bad News" e "Huey Lewis & The News" lo sono
    const NOT_MUSIC_PARTS = new Set([
        'news', 'notizie', 'notiziario', 'giornale radio', 'gr', 'meteo', 'weather', 'traffic', 'viabilita',
        'nachrichten', 'noticias', 'informativo', 'новости', 'hirek', 'أخبار', 'समाचार', '新闻',
        'ad', 'ads', 'adv', 'advertising', 'advertisement', 'commercial', 'commercial break', 'spot', 'jingle',
        'publicidad', 'publicite', 'werbung', 'publicidade', 'реклама', 'reklam',
        'on air', 'onair', 'live', 'diretta', 'test', 'radio', 'music', 'musica', 'no title', 'untitled', 'null',
        'none', 'undefined', 'n a', 'podcast', 'adbreak', 'ad break', 'adbreak end', 'simulcast'
    ]);
    // Parti che non vogliono dire niente: si tolgono e si guarda il resto
    const EMPTY_PARTS = new Set([
        'unknown', 'unknown artist', 'sconosciuto', 'artista sconosciuto', 'va', 'various', 'various artists', 'n d'
    ]);
    const NOT_MUSIC_TEXT = new RegExp(
        'pubblicit|publicit|advert|commercial break|ad ?break|this station will|right back|whats ?app|hotline|' +
        'telefono|\\bmhz\\b|\\bkhz\\b|scrivici|chiamaci|prenota|\\bsms\\b|e-?mail|\\bwerbung\\b|\\bpublicidad\\b',
        'iu');
    // Testi di prova dei programmi di trasmissione: da non mostrare nemmeno
    const PLACEHOLDER_TEXT = /info goes here|azuracast|recaster|adwtag|adcontext/iu;
    const PHONE_NUMBER = /\d[\d .\-/]{6,}\d/g;
    const WEB_ADDRESS = /(?:https?:\/\/|www\.)\S+|[\p{L}\p{N}\-]+\.(?:com|it|net|org|fm|eu|de|fr|es|ru|hu|info|tv|radio|co\.uk)(?![\p{L}\p{N}])/iu;

    // Versioni e note tra parentesi: non fanno parte del titolo con cui il brano è su LRCLIB
    const BRACKETS = /\s*(?:\([^()]*\)|\[[^\]]*]|\{[^}]*})/g;
    const OPEN_BRACKET_END = /\s*[(\[{][^)\]}]*$/;
    // Nei titoli "with" e "con" fanno parte del titolo ("Portami con te", "Here with Me")
    const FEATURING_IN_TITLE = /\s+(?:feat\.?|ft\.?|featuring)\s+.*$/i;
    const FEATURING_IN_ARTIST = /\s+(?:feat\.?|ft\.?|featuring|with|con|vs\.?)\s+.*$/i;
    const VERSION_PART = /^(?:\d{4}\s+)?(?:re-?master(?:ed)?|radio edit|radio version|single version|album version|live|mono|stereo|bonus track|acoustic|extended|remix|deluxe|explicit|clean|karaoke|instrumental|edit|original mix)\b/i;
    const YEAR_OR_TIME = /^(?:\(?\d{4}\)?|\d{1,2}:\d{2}(?::\d{2})?)$/;
    const ARTIST_JOIN = /\s*(?:,|&|\+|\/|;|\s(?:x|e|and|y|et|und|feat\.?|ft\.?|featuring|with|con|vs\.?)\s)\s*/i;
    const GENERIC_STATION_WORDS = new Set([
        'radio', 'radios', 'rádio', 'rádió', 'радио', 'web', 'webradio', 'fm', 'am', 'tv', 'network', 'live', 'online',
        'stream', 'streaming', 'hd', 'hq', 'the', 'la', 'il', 'official'
    ]);

    function simplify(text) {
        return String(text || '').normalize('NFKD').toLocaleLowerCase()
            .replace(/\p{M}+/gu, '')
            .replace(/[^\p{L}\p{N}]+/gu, ' ')
            .trim();
    }

    function compact(text) {
        return simplify(text).replace(/ /g, '');
    }

    function decodeEntities(text) {
        return text
            .replace(/&amp;/gi, '&')
            .replace(/&quot;/gi, '"')
            .replace(/&#0*39;|&apos;/gi, "'")
            .replace(/&#(\d{2,5});/g, (_, code) => String.fromCodePoint(Number(code)));
    }

    function tidy(raw) {
        return decodeEntities(String(raw || ''))
            .replace(/[​-‍⁠﻿]/g, '')
            .replace(/\s+/g, ' ')
            .trim();
    }

    // Separazioni e simboli ai bordi: "- Radio Bruno", "Artista - Titolo,", "*WWW.GAMMARADIO.IT*", "_"
    function trimEdges(text) {
        return text.replace(/^[\s\-–—_*~|•·,;:./\\]+|[\s\-–—_*~|•·,;:/\\]+$/g, '').trim();
    }

    // ------------------------------------------------------------------
    // Brano in onda, prossimo e precedente
    // ------------------------------------------------------------------

    function findMarks(text) {
        const marks = [];
        const collect = (regex, kindOf) => {
            for (const match of text.matchAll(regex)) {
                // Un'etichetta in fondo è un titolo: "Paul McCartney - Coming Up"
                if (!text.slice(match.index + match[0].length).trim()) continue;
                marks.push({ start: match.index, end: match.index + match[0].length, kind: kindOf(match) });
            }
        };
        const kindOf = (match) => (match[1] ? 'current' : match[2] ? 'next' : 'previous');
        collect(STRONG, kindOf);
        collect(WEAK, kindOf);
        collect(AFTER_BREAK, () => 'next');
        marks.sort((a, b) => a.start - b.start || b.end - a.end);
        return marks.filter((mark, index) => index === 0 || mark.start >= marks[index - 1].end);
    }

    /** Il testo del brano in onda: dopo la sua etichetta, altrimenti quello prima delle altre. */
    function currentSection(text) {
        const marks = findMarks(text);
        if (!marks.length) return text;
        const sectionOf = (index) => trimEdges(
            text.slice(marks[index].end, index + 1 < marks.length ? marks[index + 1].start : text.length));
        for (let index = 0; index < marks.length; index++) {
            if (marks[index].kind === 'current') {
                const section = sectionOf(index);
                if (section) return section;
            }
        }
        return trimEdges(text.slice(0, marks[0].start));
    }

    // ------------------------------------------------------------------
    // Formati con i campi
    // ------------------------------------------------------------------

    // iHeart e simili: Artista - text="Titolo" song_spot="M" …  oppure  title="Titolo",artist="Artista",url="…"
    function parseAttributes(text) {
        const attribute = (name) => new RegExp('(?:^|[\\s,])' + name + '="([^"]*)"').exec(text);
        const textAttribute = attribute('text');
        const titleAttribute = attribute('title');
        const found = titleAttribute || textAttribute;
        if (!found) return /^adContext=/i.test(text) ? { display: '', isSong: false } : null;
        const spot = /song_spot="([^"]*)"/.exec(text);
        const artistAttribute = attribute('artist');
        const artist = artistAttribute ? artistAttribute[1] : trimEdges(text.slice(0, found.index));
        const title = tidy(found[1]);
        // M e F sono i brani; T annunci e trasmissioni
        if (spot && !/^[MF]$/.test(spot[1])) return { display: title, isSong: false };
        return songOf(tidy(artist), title, false);
    }

    // Titolo~Artista~Album~Anno~ISRC~Durata~Inizio~Adesso~Stazione~…  oppure  Artista~Titolo~Album~Anno.
    // In maiuscolo arrivano nell'ordine opposto (Radio 105, Virgin Radio): comunque si provano tutti e due
    function parseTildes(text, station) {
        const fields = text.split('~').map((field) => field.trim());
        if (fields.length < 2) return null;
        const [first, second] = fields;
        if (!second) return { display: isStationName(first, station) ? '' : first, isSong: false };
        const duration = Number(fields[5]);
        if (Number.isFinite(duration) && duration > 1200) return { display: first, isSong: false };
        const isLongForm = fields.length >= 6;
        const titleFirst = isLongForm && first !== first.toLocaleUpperCase();
        const artist = titleFirst ? second : first;
        const title = titleFirst ? first : second;
        const album = (fields[2] || '').replace(/\s*-\s*(?:single|ep)$/i, '');
        return songOf(artist, title, true, album);
    }

    // Song*TITOLO*ARTISTA*1973*…  e  Spot*…
    function parseStars(text) {
        const match = /^([\p{L}]+)\*(.*)$/u.exec(text);
        if (!match) return null;
        if (!/^(?:song|music|musica|track|brano|canzone)$/i.test(match[1])) return { display: '', isSong: false };
        const fields = match[2].split('*').map((field) => field.trim());
        return fields[0] && fields[1] ? songOf(fields[1], fields[0], true) : null;
    }

    // Artista: X Titolo: Y, in un ordine o nell'altro
    function parseKeys(text) {
        const artistKey = '(?:artist[ae]?|interprete|autore|author|cantante)';
        const titleKey = '(?:title|titolo|brano|canzone|song|track|traccia|titel|t[ií]tulo|titre)';
        const artistFirst = new RegExp('^' + artistKey + '\\s*[:=]\\s*(.+?)\\s*[-–—,;|/]?\\s*' + titleKey + '\\s*[:=]\\s*(.+)$', 'iu').exec(text);
        if (artistFirst) return songOf(artistFirst[1], artistFirst[2], false);
        const titleFirst = new RegExp('^' + titleKey + '\\s*[:=]\\s*(.+?)\\s*[-–—,;|/]?\\s*' + artistKey + '\\s*[:=]\\s*(.+)$', 'iu').exec(text);
        if (titleFirst) return songOf(titleFirst[2], titleFirst[1], false);
        return null;
    }

    // ------------------------------------------------------------------
    // Testo libero
    // ------------------------------------------------------------------

    function isStationName(part, station) {
        if (!station) return false;
        const whole = compact(part);
        const name = compact(station);
        if (!whole || whole === name) return true;
        const core = (text) => simplify(text).split(' ').filter((word) => !GENERIC_STATION_WORDS.has(word));
        const partWords = core(part);
        const stationWords = core(station);
        // Solo "radio", "fm": non è niente
        if (!partWords.length) return true;
        if (!stationWords.length) return false;
        const partCore = partWords.join('');
        const stationCore = stationWords.join('');
        if (partCore === stationCore) return true;
        // "m2o" per "m2o originals radio", "MARILU" per "Radio Marilù", "Radio 105" per "Radio 105 Network"
        if (partCore.length >= 3 && stationCore.startsWith(partCore)) return true;
        // Il nome della stazione col suo slogan: "LatteMiele SCRIVICI…", "Radio Bruno Top 40"
        return name.length >= 5 && whole.startsWith(name);
    }

    // Separatori fuori dalle parentesi: tra parentesi i trattini non dividono artista e titolo
    function splitParts(text) {
        const parts = [];
        let depth = 0;
        let start = 0;
        const separator = /\s+[-–—|•·]+\s*|[-–—|•·]+\s+|[–—|•·]+|\s+\/\/?\s+|\s+::\s+/gu;
        for (let index = 0; index < text.length;) {
            const char = text[index];
            if ('([{'.includes(char)) depth++;
            else if (')]}'.includes(char)) depth = Math.max(0, depth - 1);
            separator.lastIndex = index;
            const match = depth === 0 ? separator.exec(text) : null;
            if (match && match.index === index && match[0]) {
                parts.push(text.slice(start, index));
                index += match[0].length;
                start = index;
            } else {
                index++;
            }
        }
        parts.push(text.slice(start));
        return parts.map(trimEdges).filter(Boolean);
    }

    // "CLAUDIO BAGLIONI-STRADA FACENDO": un trattino solo tra parole. Non "Anne-Marie" né "Jay-Z"
    function splitTightDash(text) {
        const hyphens = text.match(/-/g);
        if (!hyphens || hyphens.length !== 1 || !/[\s.]/.test(text)) return null;
        const [left, right] = text.split('-').map((part) => part.trim());
        const hasWord = (part) => /\p{L}{2}/u.test(part);
        return hasWord(left) && hasWord(right) ? [left, right] : null;
    }

    // Tra parentesi ci sono gli anni ("Bottesini (1821-1889)"); un telefono ha almeno 8 cifre ("867-5309" è un titolo)
    function hasPhoneNumber(text) {
        const numbers = text.replace(BRACKETS, ' ').match(PHONE_NUMBER) || [];
        return numbers.some((number) => number.replace(/\D/g, '').length >= 8);
    }

    function parseFree(text, station) {
        const notSong = { display: '', isSong: false };
        const asIs = (display) => ({ display, isSong: false });
        // "*** Nachrichten kompakt": le trasmissioni di alcune emittenti pubbliche
        if (/^\*{2,}/.test(text)) return asIs(trimEdges(text));
        const cleaned = trimEdges(text);
        if (!cleaned || !/[\p{L}\p{N}]/u.test(cleaned) || PLACEHOLDER_TEXT.test(cleaned)) return notSong;
        if (NOT_MUSIC_TEXT.test(cleaned) || hasPhoneNumber(cleaned)) return asIs(cleaned);

        let parts = splitParts(cleaned);
        if (parts.length === 1 && isStationName(parts[0], station)) return notSong;
        if (parts.some((part) => NOT_MUSIC_PARTS.has(simplify(part)))) return asIs(cleaned);
        // Tra parentesi non conta: "Concerto {+info: veniceclassicradio.eu}" è un titolo
        const withoutWeb = parts.filter((part) => !WEB_ADDRESS.test(part.replace(BRACKETS, ' ')));
        // Solo l'indirizzo della stazione ("www.ladyradio.it")
        if (!withoutWeb.length) return notSong;
        const hadWeb = withoutWeb.length !== parts.length;
        parts = withoutWeb.filter((part) => !EMPTY_PARTS.has(simplify(part)) && !/^\d+$/.test(part.trim()));
        const hadUnknown = parts.length !== withoutWeb.length;
        const withoutStation = parts.filter((part) => !isStationName(part, station));
        const hadStation = withoutStation.length !== parts.length;
        parts = withoutStation.filter((part) => !YEAR_OR_TIME.test(part) && !VERSION_PART.test(part));
        if (!parts.length) return notSong;

        if (parts.length === 1) {
            const single = parts[0];
            const byArtist = /^(.+?)\s+by\s+(.+)$/i.exec(single);
            if (byArtist) return songOf(byArtist[2], byArtist[1], true);
            const tight = !hadStation && !hadWeb && !hadUnknown ? splitTightDash(single) : null;
            if (tight) return songOf(tight[0], tight[1], true);
            // Accanto al nome della stazione è il suo slogan più spesso che un titolo: non si cerca da solo
            return phraseOf(single, !hadStation && !hadWeb);
        }
        const song = songOf(parts[0], parts[1], true);
        if (parts.length > 2) {
            // Artista - Titolo - Album, Artista - Opera - Movimento, Album - Artista - Titolo
            song.alternatives.push(
                { artist: parts[0], title: parts[1] + ' - ' + parts[2] },
                { artist: parts[1], title: parts[2] }
            );
        }
        return song;
    }

    // ------------------------------------------------------------------
    // Risultato
    // ------------------------------------------------------------------

    function coreTitle(title) {
        const withoutBrackets = String(title || '').replace(BRACKETS, '').replace(OPEN_BRACKET_END, '');
        const result = withoutBrackets.replace(FEATURING_IN_TITLE, '').trim();
        return trimEdges(result) || trimEdges(String(title || ''));
    }

    function coreArtist(artist) {
        const withoutBrackets = String(artist || '').replace(BRACKETS, '').replace(FEATURING_IN_ARTIST, '').trim();
        return trimEdges(withoutBrackets) || trimEdges(String(artist || ''));
    }

    function mainArtist(artist) {
        const first = coreArtist(artist).split(ARTIST_JOIN)[0];
        return (first || '').trim() || coreArtist(artist);
    }

    function uniqueQueries(list) {
        const seen = new Set();
        return list.filter((query) => {
            const key = simplify(query);
            if (!key || seen.has(key)) return false;
            seen.add(key);
            return true;
        });
    }

    function songOf(artist, title, canSwap, album) {
        const cleanArtist = trimEdges(artist);
        const cleanTitle = trimEdges(title);
        if (!cleanTitle) return cleanArtist ? phraseOf(cleanArtist, false) : { display: '', isSong: false };
        if (!cleanArtist) return phraseOf(cleanTitle, false);
        const titleCore = coreTitle(cleanTitle);
        const artistCore = coreArtist(cleanArtist);
        return {
            display: cleanArtist + ' - ' + cleanTitle,
            isSong: true,
            artist: artistCore,
            title: titleCore,
            album: album || '',
            alternatives: canSwap ? [{ artist: coreArtist(cleanTitle), title: coreTitle(cleanArtist) }] : [],
            queries: uniqueQueries([mainArtist(cleanArtist) + ' ' + titleCore, titleCore])
        };
    }

    // Una frase sola: forse solo il titolo, forse artista e titolo senza separazione ("Vasco Rossi Albachiara").
    // La ricerca la accetta solo se un brano la copre tutta. [searchable] false = solo a richiesta (pulsante Testi)
    function phraseOf(text, searchable) {
        const core = coreTitle(text);
        return {
            display: text,
            isSong: true,
            artist: '',
            title: core,
            album: '',
            alternatives: [],
            queries: uniqueQueries([core]),
            phrase: true,
            searchable: searchable
        };
    }

    /**
     * Cosa dice il testo di una stazione. [station] è il suo nome. Sempre un oggetto: `display` è il testo pulito da
     * mostrare (vuoto se non dice niente), `isSong` false per pubblicità, trasmissioni e slogan.
     */
    function parse(raw, station) {
        const text = tidy(raw);
        if (!text) return { display: '', isSong: false };
        const attributes = parseAttributes(text);
        if (attributes) return attributes;
        const section = currentSection(text);
        if (!section) return { display: '', isSong: false };
        return parseTildes(section, station) || parseStars(section) || parseKeys(section) || parseFree(section, station);
    }

    /** I tag di un file (o solo il suo nome, nel titolo): con l'artista si fida, altrimenti lo cerca nel nome. */
    function fromTags(tags) {
        const title = tidy(tags.title);
        const artist = tidy(tags.artist);
        if (artist && title) {
            const song = songOf(artist, title, false, tidy(tags.album));
            song.display = title + ' — ' + artist;
            return song;
        }
        const fileName = title.replace(/^\d{1,3}\s*[-._)]+\s*|^0\d\s+/, '').replace(/_/g, ' ');
        const parsed = parseFree(fileName, '');
        return parsed.isSong ? parsed : phraseOf(fileName, true);
    }

    // ------------------------------------------------------------------
    // Confronto con i risultati di LRCLIB
    // ------------------------------------------------------------------

    function sameTitle(a, b) {
        const left = simplify(coreTitle(a));
        const right = simplify(coreTitle(b));
        if (!left || !right) return false;
        if (left === right || left.replace(/ /g, '') === right.replace(/ /g, '')) return true;
        // Titoli tagliati dalla stazione: "Can't Help Falling in Love" per "… with You"
        const [short, long] = left.length <= right.length ? [left, right] : [right, left];
        return short.split(' ').length >= 3 && short.length >= long.length * 0.6 && long.startsWith(short + ' ');
    }

    function sameArtist(a, b) {
        const left = simplify(coreArtist(a));
        const right = simplify(coreArtist(b));
        if (!left || !right) return false;
        const withoutThe = (text) => text.replace(/^the /, '').replace(/ /g, '');
        if (withoutThe(left) === withoutThe(right)) return true;
        // Uno degli artisti è nell'altro nome: "Usher, Pitbull" e "Usher"; "Kehlani ft Missy Elliott" e "Kehlani"
        const names = (text) => coreArtist(text).split(ARTIST_JOIN).map(simplify).map(withoutThe).filter(Boolean);
        const leftNames = names(a);
        const rightNames = names(b);
        return leftNames.some((name) => name.length >= 2 && rightNames.includes(name));
    }

    /** Il brano [trackName] di [artistName] è quello di [track] (ottenuto da parse o fromTags)? In un ordine o nell'altro. */
    function matches(trackName, artistName, track) {
        if (track.phrase) {
            // Tutte le parole della frase e nessun'altra, in qualunque ordine
            const words = (text) => simplify(text).split(' ').filter(Boolean).sort().join(' ');
            return words(artistName + ' ' + coreTitle(trackName)) === words(track.title);
        }
        const pairs = [{ artist: track.artist, title: track.title }].concat(track.alternatives || []);
        return pairs.some((pair) => sameTitle(trackName, pair.title) && sameArtist(artistName, pair.artist));
    }

    root.AABTrackParser = { parse, fromTags, matches, coreTitle, coreArtist };
})(typeof window !== 'undefined' ? window : globalThis);
