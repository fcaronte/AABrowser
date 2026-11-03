package com.fcaronte.aabrowser.utils

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Tastiera passeggero (Main e Wide in auto). Lettere QWERTY e, per chi usa l'app o il telefono in francese, tedesco,
 * russo o arabo, anche la disposizione della sua lingua (AZERTY, QWERTZ, ЙЦУКЕН, araba): 🌐 passa dall'una all'altra.
 * Poi numeri e simboli, e le lettere accentate delle lingue latine (in arabo: forme dell'hamza, cifre, punteggiatura
 * e segni vocalici). Solo tasti e stato: la Main la disegna in Compose, la Wide con le View sulla superficie dell'auto.
 * Hindi e cinese scrivono con la dettatura (servirebbe un vero metodo di input).
 * [local]: disposizione della lingua dell'app o del telefono (null = solo QWERTY); [startLocal]: si parte da lei.
 */
class PassengerKeyboard(private val local: Layout? = null, startLocal: Boolean = local != null) {
    enum class Layout(val language: String?) { QWERTY(null), AZERTY("fr"), QWERTZ("de"), RUSSIAN("ru"), ARABIC("ar") }

    enum class Page { LETTERS, SYMBOLS, ACCENTS }

    sealed interface Key {
        /** [display]: quello che si vede sul tasto, se diverso (i segni vocalici arabi si mostrano su ◌). */
        data class Text(val value: String, val display: String = value) : Key
        data object Shift : Key
        data object Backspace : Key
        data object Space : Key
        data object Enter : Key
        data object Globe : Key
        data class Switch(val page: Page) : Key
    }

    /** Cosa fa un tocco per chi usa la tastiera. */
    sealed interface Action {
        data class Type(val text: String) : Action
        data object Delete : Action
        data object Enter : Action
        data object None : Action
    }

    // L'ultima scelta col 🌐 resta per le prossime aperture
    var layout by mutableStateOf(
        lastChoice?.takeIf { it == Layout.QWERTY || it == local } ?: if (startLocal && local != null) local else Layout.QWERTY
    )
        private set

    var page by mutableStateOf(Page.LETTERS)
        private set

    // Maiuscola solo per il tasto successivo
    var shift by mutableStateOf(false)
        private set

    val rows: List<List<Key>>
        get() = when (page) {
            Page.LETTERS -> letterRows(layout) + listOf(bottomRow())
            Page.SYMBOLS -> SYMBOLS + listOf(bottomRow())
            Page.ACCENTS -> (if (layout == Layout.ARABIC) ARABIC_EXTRA else ACCENTS) + listOf(bottomRow())
        }

    /** Larghezza della pagina in tasti normali (la sua riga più larga): le righe corte si centrano. */
    val rowWeight: Float
        get() = rows.dropLast(1).maxOf { row -> row.sumOf { fixedWeight(it).toDouble() }.toFloat() }

    /** Larghezza di un tasto rispetto a una lettera; lo spazio riempie l'ultima riga. */
    fun weight(key: Key, row: List<Key>): Float {
        if (key != Key.Space) return fixedWeight(key)
        val others = row.filter { it != Key.Space }.sumOf { fixedWeight(it).toDouble() }.toFloat()
        return (rowWeight - others).coerceAtLeast(MIN_SPACE_WEIGHT)
    }

    fun label(key: Key): String = when (key) {
        is Key.Text -> if (shift) upper(key.display) else key.display
        Key.Shift -> "⇧"
        Key.Backspace -> "⌫"
        Key.Space -> ""
        Key.Enter -> "⏎"
        Key.Globe -> "🌐"
        is Key.Switch -> when (key.page) {
            Page.LETTERS -> "ABC"
            Page.SYMBOLS -> "?123"
            Page.ACCENTS -> if (layout == Layout.ARABIC) "أإآ" else "àé"
        }
    }

    /** Tasti evidenziati: invio e maiuscola attiva. */
    fun isHighlighted(key: Key): Boolean = key == Key.Enter || (key == Key.Shift && shift)

    fun press(key: Key): Action = when (key) {
        is Key.Text -> Action.Type(if (shift) upper(key.value) else key.value).also { shift = false }
        Key.Shift -> Action.None.also { shift = !shift }
        Key.Backspace -> Action.Delete
        Key.Space -> Action.Type(" ")
        Key.Enter -> Action.Enter
        Key.Globe -> Action.None.also {
            layout = if (layout == Layout.QWERTY && local != null) local else Layout.QWERTY
            lastChoice = layout
            shift = false
        }
        is Key.Switch -> Action.None.also {
            page = key.page
            shift = false
        }
    }

    fun reset() {
        page = Page.LETTERS
        shift = false
    }

    // Prima ?123 (o ABC); le lettere accentate dove la disposizione le ha, 🌐 solo tra le lettere e se c'è
    // un'altra disposizione
    private fun bottomRow(): List<Key> = buildList {
        val hasAccents = layout != Layout.RUSSIAN
        when (page) {
            Page.LETTERS -> {
                add(Key.Switch(Page.SYMBOLS))
                if (hasAccents) add(Key.Switch(Page.ACCENTS))
                if (local != null) add(Key.Globe)
            }
            Page.SYMBOLS -> {
                add(Key.Switch(Page.LETTERS))
                if (hasAccents) add(Key.Switch(Page.ACCENTS))
            }
            Page.ACCENTS -> {
                add(Key.Switch(Page.LETTERS))
                add(Key.Switch(Page.SYMBOLS))
            }
        }
        add(Key.Text(if (layout == Layout.ARABIC) "،" else ","))
        add(Key.Space)
        add(Key.Text("."))
        add(Key.Enter)
    }

    companion object {
        private const val MIN_SPACE_WEIGHT = 2f

        /** Scelta fatta col 🌐 (solo in memoria: torna alla disposizione della lingua al riavvio). */
        internal var lastChoice: Layout? = null

        /** Tastiera con la disposizione della lingua dell'app (si parte da lei) o del telefono (si parte da QWERTY). */
        fun create(context: Context): PassengerKeyboard {
            layoutFor(context.resources.configuration.locales[0].language)?.let { return PassengerKeyboard(it, startLocal = true) }
            val phone = Resources.getSystem().configuration.locales
            for (i in 0 until phone.size()) {
                layoutFor(phone[i].language)?.let { return PassengerKeyboard(it, startLocal = false) }
            }
            return PassengerKeyboard()
        }

        /** Testo da destra a sinistra (arabo): la riga del testo deve mostrarne la fine, che sta a sinistra. */
        fun isRtl(text: String): Boolean {
            for (c in text) {
                when (Character.getDirectionality(c)) {
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                    Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
                }
            }
            return false
        }

        /** Testo senza l'ultimo carattere (anche le emoji, fatte di due char). */
        fun deleteLast(text: String): String =
            if (text.isEmpty()) text else text.substring(0, text.offsetByCodePoints(text.length, -1))

        private fun layoutFor(language: String): Layout? = Layout.entries.firstOrNull { it.language == language }

        private fun fixedWeight(key: Key): Float = when (key) {
            Key.Space -> 0f
            Key.Shift, Key.Backspace, Key.Enter -> 1.5f
            is Key.Switch -> 1.25f
            Key.Globe, is Key.Text -> 1f
        }

        // ß resta com'è: in maiuscolo diventerebbe "SS"
        private fun upper(value: String): String = value.uppercase().takeIf { it.length == value.length } ?: value

        private fun texts(chars: String): List<Key> = chars.split(' ').map { Key.Text(it) }

        private val DIGITS = texts("1 2 3 4 5 6 7 8 9 0")

        private fun letterRows(layout: Layout): List<List<Key>> = when (layout) {
            Layout.QWERTY -> listOf(
                DIGITS,
                texts("q w e r t y u i o p"),
                texts("a s d f g h j k l"),
                listOf(Key.Shift) + texts("z x c v b n m") + Key.Backspace,
            )
            Layout.AZERTY -> listOf(
                DIGITS,
                texts("a z e r t y u i o p"),
                texts("q s d f g h j k l m"),
                listOf(Key.Shift) + texts("w x c v b n '") + Key.Backspace,
            )
            Layout.QWERTZ -> listOf(
                DIGITS,
                texts("q w e r t z u i o p ü"),
                texts("a s d f g h j k l ö ä"),
                listOf(Key.Shift) + texts("y x c v b n m ß") + Key.Backspace,
            )
            // ъ ed ё in fondo alle righe: senza pressione lunga non c'è un altro posto
            Layout.RUSSIAN -> listOf(
                DIGITS,
                texts("й ц у к е н г ш щ з х ъ"),
                texts("ф ы в а п р о л д ж э ё"),
                listOf(Key.Shift) + texts("я ч с м и т ь б ю") + Key.Backspace,
            )
            // Niente maiuscole in arabo; forme dell'hamza e segni vocalici nella seconda pagina
            Layout.ARABIC -> listOf(
                DIGITS,
                texts("ض ص ث ق ف غ ع ه خ ح ج"),
                texts("ش س ي ب ل ا ت ن م ك ط"),
                texts("ذ ء ؤ ر ى ة و ز ظ د") + Key.Backspace,
            )
        }

        private val SYMBOLS = listOf(
            DIGITS,
            texts("@ # € $ & _ - + ( )"),
            texts("/ \\ * \" ' : ; ! ? ="),
            texts("% < > [ ] { } ~") + Key.Backspace,
        )

        // Italiano, francese, tedesco, spagnolo, portoghese, ungherese
        private val ACCENTS = listOf(
            texts("à á â ä ã å æ ç"),
            texts("è é ê ë ì í î ï"),
            texts("ò ó ô ö õ ø œ ß"),
            listOf(Key.Shift) + texts("ù ú û ü ñ ő ű") + Key.Backspace,
        )

        private val ARABIC_EXTRA = listOf(
            texts("أ إ آ ٱ ئ ؤ ء ى ة"),
            texts("١ ٢ ٣ ٤ ٥ ٦ ٧ ٨ ٩ ٠"),
            texts("، ؛ ؟ « » ـ ٪ ٫"),
            "ً ٌ ٍ َ ُ ِ ّ ْ".split(' ')
                .map { Key.Text(it, display = "◌$it") } + Key.Backspace,
        )
    }
}
