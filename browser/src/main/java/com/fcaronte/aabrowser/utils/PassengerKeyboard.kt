package com.fcaronte.aabrowser.utils

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Tastiera passeggero (Main e Wide in auto): QWERTY, numeri e simboli, lettere accentate delle lingue latine
 * dell'app. Solo tasti e stato: la Main la disegna in Compose, la Wide con le View sulla superficie dell'auto. Le
 * lingue con altri alfabeti (russo, arabo, hindi, cinese) scrivono con la dettatura.
 */
class PassengerKeyboard {
    enum class Page { LETTERS, SYMBOLS, ACCENTS }

    sealed interface Key {
        data class Text(val value: String) : Key
        data object Shift : Key
        data object Backspace : Key
        data object Space : Key
        data object Enter : Key
        data class Switch(val page: Page) : Key
    }

    /** Cosa fa un tocco per chi usa la tastiera. */
    sealed interface Action {
        data class Type(val text: String) : Action
        data object Delete : Action
        data object Enter : Action
        data object None : Action
    }

    var page by mutableStateOf(Page.LETTERS)
        private set

    // Maiuscola solo per il tasto successivo
    var shift by mutableStateOf(false)
        private set

    val rows: List<List<Key>>
        get() = when (page) {
            Page.LETTERS -> LETTERS
            Page.SYMBOLS -> SYMBOLS
            Page.ACCENTS -> ACCENTS
        }

    fun label(key: Key): String = when (key) {
        is Key.Text -> if (shift) upper(key.value) else key.value
        Key.Shift -> "⇧"
        Key.Backspace -> "⌫"
        Key.Space -> ""
        Key.Enter -> "⏎"
        is Key.Switch -> when (key.page) {
            Page.LETTERS -> "ABC"
            Page.SYMBOLS -> "?123"
            Page.ACCENTS -> "àé"
        }
    }

    /** Tasti evidenziati: invio e maiuscola attiva. */
    fun isHighlighted(key: Key): Boolean = key == Key.Enter || (key == Key.Shift && shift)

    fun press(key: Key): Action = when (key) {
        is Key.Text -> Action.Type(label(key)).also { shift = false }
        Key.Shift -> Action.None.also { shift = !shift }
        Key.Backspace -> Action.Delete
        Key.Space -> Action.Type(" ")
        Key.Enter -> Action.Enter
        is Key.Switch -> Action.None.also {
            page = key.page
            shift = false
        }
    }

    fun reset() {
        page = Page.LETTERS
        shift = false
    }

    companion object {
        /** Larghezza di ogni riga in tasti normali: le righe più corte si centrano. */
        const val ROW_WEIGHT = 10f

        /** Larghezza di un tasto rispetto a una lettera. */
        fun weight(key: Key): Float = when (key) {
            Key.Space -> 4f
            Key.Shift, Key.Backspace, Key.Enter -> 1.5f
            is Key.Switch -> 1.25f
            is Key.Text -> 1f
        }

        /** Testo senza l'ultimo carattere (anche le emoji, fatte di due char). */
        fun deleteLast(text: String): String =
            if (text.isEmpty()) text else text.substring(0, text.offsetByCodePoints(text.length, -1))

        // ß resta com'è: in maiuscolo diventerebbe "SS"
        private fun upper(value: String): String = value.uppercase().takeIf { it.length == value.length } ?: value

        private fun texts(chars: String): List<Key> = chars.split(' ').map { Key.Text(it) }

        private val LETTERS = listOf(
            texts("1 2 3 4 5 6 7 8 9 0"),
            texts("q w e r t y u i o p"),
            texts("a s d f g h j k l"),
            listOf(Key.Shift) + texts("z x c v b n m") + Key.Backspace,
            listOf(Key.Switch(Page.SYMBOLS), Key.Switch(Page.ACCENTS), Key.Text(","), Key.Space, Key.Text("."), Key.Enter),
        )

        private val SYMBOLS = listOf(
            texts("1 2 3 4 5 6 7 8 9 0"),
            texts("@ # € $ & _ - + ( )"),
            texts("/ \\ * \" ' : ; ! ? ="),
            texts("% < > [ ] { } ~") + Key.Backspace,
            listOf(Key.Switch(Page.LETTERS), Key.Switch(Page.ACCENTS), Key.Text(","), Key.Space, Key.Text("."), Key.Enter),
        )

        // Italiano, francese, tedesco, spagnolo, portoghese, ungherese
        private val ACCENTS = listOf(
            texts("à á â ä ã å æ ç"),
            texts("è é ê ë ì í î ï"),
            texts("ò ó ô ö õ ø œ ß"),
            listOf(Key.Shift) + texts("ù ú û ü ñ ő ű") + Key.Backspace,
            listOf(Key.Switch(Page.LETTERS), Key.Switch(Page.SYMBOLS), Key.Text(","), Key.Space, Key.Text("."), Key.Enter),
        )
    }
}
