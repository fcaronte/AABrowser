package com.fcaronte.aabrowser.utils

import com.fcaronte.aabrowser.utils.PassengerKeyboard.Action
import com.fcaronte.aabrowser.utils.PassengerKeyboard.Key
import com.fcaronte.aabrowser.utils.PassengerKeyboard.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassengerKeyboardTest {

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
    fun everyPageHasFiveRowsThatFitTheWidth() {
        val keyboard = PassengerKeyboard()
        for (page in Page.entries) {
            keyboard.press(Key.Switch(page))
            assertEquals(5, keyboard.rows.size)
            for (row in keyboard.rows) {
                val width = row.sumOf { PassengerKeyboard.weight(it).toDouble() }
                assertTrue("$page row $row is $width wide", width <= PassengerKeyboard.ROW_WEIGHT + 1e-6)
            }
            // Ogni pagina ha invio e cancellazione, e porta alle altre due
            val keys = keyboard.rows.flatten()
            assertTrue(Key.Enter in keys && Key.Backspace in keys && Key.Space in keys)
            assertEquals(Page.entries.toSet() - page, keys.filterIsInstance<Key.Switch>().map { it.page }.toSet())
        }
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
    fun deleteLastRemovesWholeCharacters() {
        assertEquals("ab", PassengerKeyboard.deleteLast("ab😀"))
        assertEquals("caf", PassengerKeyboard.deleteLast("café"))
        assertEquals("", PassengerKeyboard.deleteLast(""))
    }
}
