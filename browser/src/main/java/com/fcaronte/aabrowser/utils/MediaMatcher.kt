package com.fcaronte.aabrowser.utils

import java.text.Normalizer

/**
 * Trova per nome canali, stazioni e file di CarWeb Media, da una frase detta a voce o scritta nella ricerca.
 * Il riconoscimento vocale scrive i nomi a modo suo ("rai uno" per "Rai 1 HD", "la sette" per "La7", "rtl 102 e 5"
 * per "RTL 102.5") e le liste aggiungono del loro ("IT: RAI 1 (1080p) [Geo-blocked]"): si confrontano parole pulite,
 * con i numeri in cifre, invece del testo così com'è.
 */
object MediaMatcher {
    /** Da qui in su il risultato è abbastanza sicuro da partire da solo con "metti …". */
    const val CONFIDENT = 500

    /** Un nome pronto per il confronto: conviene tenerlo da parte con liste da migliaia di canali. */
    class Key(val tokens: List<String>, val compact: String)

    // Tra parentesi quadre o tonde di solito c'è altro: qualità, paese, "Geo-blocked", "AAC 64KB". Le parentesi di
    // chiusura vanno con la barra: Android (ICU) rifiuta una } da sola e il programma si chiudeva, la JVM dei test no
    private val BRACKETS = Regex("""\[[^\]]*\]|\([^)]*\)|\{[^}]*\}""")
    private val LIST_PREFIX = Regex("""^\W*[A-Z]{2,3}\s*[:|]+\s*""")
    private val QUALITY = Regex(
        """(?<![\p{L}\p{N}])(?:hd|fhd|uhd|sd|hq|4k|8k|hevc|h26[45]|\d{3,4}[pi]|\d{2,3}\s?kbps)(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE
    )
    private val MARKS = Regex("\\p{M}+")
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
    private val LETTERS_AND_DIGITS = Regex("(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})")
    private val TRACK_NUMBER = Regex("""^\d{1,3}\s*[-._)]+\s*|^0\d\s+""")

    // Stessa grafia dei token: minuscole e senza accenti. Valgono tutte insieme, come le frasi dei comandi vocali
    private val NUMBERS: Map<String, String> = buildMap {
        listOf(
            listOf("uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove", "dieci"),
            listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"),
            listOf("uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez"),
            listOf("un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix"),
            listOf("eins", "zwei", "drei", "vier", "funf", "sechs", "sieben", "acht", "neun", "zehn"),
            listOf("um", "dois", "tres", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez"),
            listOf("один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять", "десять"),
            listOf("egy", "ketto", "harom", "negy", "ot", "hat", "het", "nyolc", "kilenc", "tiz"),
        ).forEach { words -> words.forEachIndexed { index, word -> put(word, (index + 1).toString()) } }
    }

    // "rtl 102 e 5", "repubblica punto it": non fanno parte del nome (né di quello detto né di quello in lista)
    private val CONNECTORS = setOf(
        "e", "and", "y", "et", "und", "punto", "dot", "point", "ponto", "punkt", "точка", "pont"
    )

    // Se ci sono nel nome bene, altrimenti non contano: "metti radio subasio fm" = "Radio Subasio"
    private val OPTIONAL = setOf(
        "il", "lo", "la", "i", "gli", "le", "l", "una", "the", "a", "an", "of", "el", "los", "las",
        "der", "die", "das", "den", "dem", "o", "os", "as", "de", "del", "della", "dello", "dei", "di", "do", "da",
        "du", "des", "von", "az", "radio", "radios", "stazione", "station", "emisora", "estacion", "sender",
        "estacao", "радио", "станция", "راديو", "رديو", "canale", "channel", "canal", "chaine", "kanal", "канал",
        "csatorna", "tv", "tele", "televisione", "television", "тв", "fm", "am", "live", "diretta"
    )

    fun key(text: String): Key {
        val cleaned = LIST_PREFIX.replace(BRACKETS.replace(text, " "), "")
            .let { QUALITY.replace(it, " ") }
        val simplified = MARKS.replace(Normalizer.normalize(cleaned, Normalizer.Form.NFD).lowercase(), "")
        val words = LETTERS_AND_DIGITS.replace(simplified, " ")
            .split(SEPARATORS)
            .filter { it.isNotEmpty() }
            .map { NUMBERS[it] ?: it.trimStart('0').ifEmpty { it } }
        val tokens = words.filter { it !in CONNECTORS }.ifEmpty { words }
        return Key(tokens, tokens.joinToString(""))
    }

    /** Nome di un file: il numero di traccia davanti non fa parte del titolo ("01 - Albachiara"). */
    fun fileKey(name: String): Key = key(TRACK_NUMBER.replace(name, ""))

    /** 0 = nessuna corrispondenza; più è alto, meglio è. Sopra [CONFIDENT] il nome contiene quello che si è chiesto. */
    fun score(query: Key, name: Key): Int {
        if (query.tokens.isEmpty() || name.tokens.isEmpty()) return 0
        if (query.compact == name.compact) return 1000
        val needed = query.tokens.filter { it !in OPTIONAL }.ifEmpty { query.tokens }
        // A parità di parole trovate vincono i nomi con meno parole in più
        val penalty = (name.tokens.size - needed.size).coerceIn(0, 25) * 8
        if (needed.all { it in name.tokens }) {
            val startsWith = name.tokens.take(needed.size) == needed
            return (if (startsWith) 900 else 800) - penalty
        }
        if (needed.all { word -> name.tokens.any { isNear(word, it) } }) return 700 - penalty
        // "dee jay" dentro "Radio Deejay"; i pezzi corti vengono dalla ricerca mentre si scrive
        if (name.compact.contains(query.compact)) {
            return when {
                query.compact.length >= 5 -> 550
                query.compact.length >= 3 -> 300
                else -> 100
            }
        }
        return 0
    }

    /** [items] dal più simile, a parità di punteggio nel loro ordine (le stazioni sono già dalla più ascoltata). */
    fun <T> rank(query: Key, items: List<T>, nameKey: (T) -> Key): List<Pair<T, Int>> =
        items.asSequence()
            .map { it to score(query, nameKey(it)) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .toList()

    // Inizio di parola ("sky" = "skytg"), parola più lunga ("deejays" = "deejay"), una lettera capita male
    private fun isNear(word: String, token: String): Boolean = when {
        word.length >= 3 && token.startsWith(word) -> true
        token.length >= 4 && word.startsWith(token) -> true
        minOf(word.length, token.length) >= 5 -> isOneEditAway(word, token)
        else -> false
    }

    private fun isOneEditAway(a: String, b: String): Boolean {
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (long.length - short.length > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < short.length && j < long.length) {
            if (short[i] == long[j]) {
                i++
                j++
            } else {
                if (++edits > 1) return false
                if (short.length == long.length) i++
                j++
            }
        }
        return edits + (long.length - j) <= 1
    }
}
