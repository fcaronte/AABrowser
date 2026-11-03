package com.fcaronte.aabrowser.utils

import com.fcaronte.aabrowser.utils.PassengerKeyboard.Action
import com.fcaronte.aabrowser.utils.PassengerKeyboard.Key
import com.fcaronte.aabrowser.utils.PassengerKeyboard.Layout
import com.fcaronte.aabrowser.utils.PassengerKeyboard.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PassengerKeyboardTest {

    @Before
    fun forgetGlobeChoice() {
        PassengerKeyboard.lastChoice = null
    }

    @Test
    fun shiftCapitalizesOnlyTheNextLetter() {
        val keyboard = PassengerKeyboard()
        keyboard.press(Key.Shift)
        assertEquals(Action.Type("A"), keyboard.press(Key.Text("a")))
        assertEquals(Action.Type("b"), keyboard.press(Key.Text("b")))
    }

    @Test
    fun switchingPageResetsShift() {
        val keyboard = PassengerKeyboard()
        keyboard.press(Key.Shift)
        keyboard.press(Key.Switch(Page.SYMBOLS))
        assertEquals(Page.SYMBOLS, keyboard.page)
        assertFalse(keyboard.shift)
        assertTrue(Key.Text("@") in keyboard.rows.flatten())
    }

    @Test
    fun everyLayoutAndPageFitsTheWidth() {
        // null = solo QWERTY; poi ogni lingua con la sua disposizione, da sola e passando a QWERTY
        for (local in listOf(null) + Layout.entries.filter { it != Layout.QWERTY }) {
            for (layout in setOfNotNull(Layout.QWERTY, local)) {
                val keyboard = PassengerKeyboard(local, startLocal = layout == local)
                assertEquals(layout, keyboard.layout)
                for (page in Page.entries) {
                    keyboard.press(Key.Switch(page))
                    val rows = keyboard.rows
                    assertEquals("$layout $page", 5, rows.size)
                    for (row in rows) {
                        val width = row.sumOf { keyboard.weight(it, row).toDouble() }
                        assertTrue("$layout $page row $row is $width wide", width <= keyboard.rowWeight + 1e-6)
                    }
                    val keys = rows.flatten()
                    assertTrue(Key.Enter in keys && Key.Backspace in keys && Key.Space in keys)
                    assertTrue(keyboard.weight(Key.Space, rows.last()) >= 2f)
                    // 🌐 solo tra le lettere e solo se c'è un'altra disposizione
                    assertEquals("$layout $page", page == Page.LETTERS && local != null, Key.Globe in keys)
                    // Ogni pagina porta alle altre (il russo non ha lettere accentate)
                    val noAccents = layout == Layout.RUSSIAN && page != Page.ACCENTS
                    val expected = Page.entries.toSet() - page - (if (noAccents) setOf(Page.ACCENTS) else emptySet())
                    assertEquals("$layout $page", expected, keys.filterIsInstance<Key.Switch>().map { it.page }.toSet())
                }
            }
        }
    }

    @Test
    fun globeSwitchesBetweenQwertyAndTheLocalLayout() {
        val keyboard = PassengerKeyboard(Layout.RUSSIAN, startLocal = true)
        assertEquals(Layout.RUSSIAN, keyboard.layout)
        keyboard.press(Key.Globe)
        assertEquals(Layout.QWERTY, keyboard.layout)
        // La scelta resta per la prossima apertura
        assertEquals(Layout.QWERTY, PassengerKeyboard(Layout.RUSSIAN, startLocal = true).layout)
        keyboard.press(Key.Globe)
        assertEquals(Layout.RUSSIAN, keyboard.layout)
        // Lingua solo del telefono: si parte da QWERTY
        PassengerKeyboard.lastChoice = null
        assertEquals(Layout.QWERTY, PassengerKeyboard(Layout.ARABIC, startLocal = false).layout)
    }

    @Test
    fun layoutsHaveTheirLetters() {
        fun letters(layout: Layout) =
            PassengerKeyboard(layout).rows.flatten().filterIsInstance<Key.Text>().map { it.value }
        assertEquals(listOf("a", "z", "e"), letters(Layout.AZERTY).drop(10).take(3))
        assertTrue(letters(Layout.QWERTZ).containsAll(listOf("z", "ü", "ö", "ä", "ß")))
        assertTrue(letters(Layout.RUSSIAN).containsAll(listOf("й", "ъ", "ё", "ю")))
        assertTrue(letters(Layout.ARABIC).containsAll(listOf("ض", "ا", "ة", "،")))
    }

    @Test
    fun russianUppercaseAndArabicWithoutShift() {
        val russian = PassengerKeyboard(Layout.RUSSIAN)
        russian.press(Key.Shift)
        assertEquals(Action.Type("Ё"), russian.press(Key.Text("ё")))
        val arabic = PassengerKeyboard(Layout.ARABIC)
        assertFalse(Key.Shift in arabic.rows.flatten())
        assertEquals("أإآ", arabic.label(Key.Switch(Page.ACCENTS)))
    }

    @Test
    fun arabicMarksTypeOnlyTheMark() {
        val arabic = PassengerKeyboard(Layout.ARABIC)
        arabic.press(Key.Switch(Page.ACCENTS))
        val fatha = arabic.rows.flatten().filterIsInstance<Key.Text>().first { it.value == "َ" }
        assertEquals("◌َ", arabic.label(fatha))
        assertEquals(Action.Type("َ"), arabic.press(fatha))
    }

    @Test
    fun accentsGoUppercaseExceptSharpS() {
        val keyboard = PassengerKeyboard()
        keyboard.press(Key.Switch(Page.ACCENTS))
        keyboard.press(Key.Shift)
        assertEquals(Action.Type("É"), keyboard.press(Key.Text("é")))
        keyboard.press(Key.Shift)
        assertEquals(Action.Type("ß"), keyboard.press(Key.Text("ß")))
    }

    @Test
    fun serviceKeys() {
        val keyboard = PassengerKeyboard()
        assertEquals(Action.Type(" "), keyboard.press(Key.Space))
        assertEquals(Action.Enter, keyboard.press(Key.Enter))
        assertEquals(Action.Delete, keyboard.press(Key.Backspace))
        assertEquals(Action.None, keyboard.press(Key.Shift))
        assertTrue(keyboard.isHighlighted(Key.Shift))
        keyboard.reset()
        assertEquals(Page.LETTERS, keyboard.page)
        assertFalse(keyboard.shift)
    }

    @Test
    fun rightToLeftText() {
        assertTrue(PassengerKeyboard.isRtl("مرحبا"))
        assertTrue(PassengerKeyboard.isRtl("123 مرحبا"))
        assertFalse(PassengerKeyboard.isRtl("ciao مرحبا"))
        assertFalse(PassengerKeyboard.isRtl(""))
    }

    @Test
    fun deleteLastRemovesWholeCharacters() {
        assertEquals("ab", PassengerKeyboard.deleteLast("ab😀"))
        assertEquals("caf", PassengerKeyboard.deleteLast("café"))
        assertEquals("", PassengerKeyboard.deleteLast(""))
    }
}
