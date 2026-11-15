package com.fcaronte.aabrowser.utils

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mouse e tastiera Bluetooth: tasti rapidi, tasti che restano al telefono, movimento del puntatore, pulsanti e rotellina. */
class BtInputTest {

    private fun key(code: Int, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) =
        BtInput.shortcutFor(code, ctrl, alt, shift)

    @Test
    fun escapeAndAltArrowsGoBackAndForward() {
        assertEquals(BtInput.Shortcut.BACK, key(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(BtInput.Shortcut.BACK, key(KeyEvent.KEYCODE_DPAD_LEFT, alt = true))
        assertEquals(BtInput.Shortcut.FORWARD, key(KeyEvent.KEYCODE_DPAD_RIGHT, alt = true))
        // Le frecce da sole spostano il cursore nel testo o scorrono la pagina: vanno alla pagina
        assertNull(key(KeyEvent.KEYCODE_DPAD_LEFT))
        assertNull(key(KeyEvent.KEYCODE_DPAD_RIGHT))
        // Esc con un modificatore non è «indietro»
        assertNull(key(KeyEvent.KEYCODE_ESCAPE, ctrl = true))
    }

    @Test
    fun reloadAndSearchLikeABrowser() {
        assertEquals(BtInput.Shortcut.RELOAD, key(KeyEvent.KEYCODE_F5))
        assertEquals(BtInput.Shortcut.RELOAD, key(KeyEvent.KEYCODE_F5, ctrl = true))
        assertEquals(BtInput.Shortcut.RELOAD, key(KeyEvent.KEYCODE_R, ctrl = true))
        assertEquals(BtInput.Shortcut.SEARCH, key(KeyEvent.KEYCODE_L, ctrl = true))
        assertEquals(BtInput.Shortcut.SEARCH, key(KeyEvent.KEYCODE_K, ctrl = true))
        assertEquals(BtInput.Shortcut.SEARCH, key(KeyEvent.KEYCODE_F6))
        // Senza Ctrl la «r» e la «l» sono lettere da scrivere
        assertNull(key(KeyEvent.KEYCODE_R))
        assertNull(key(KeyEvent.KEYCODE_L))
    }

    @Test
    fun tabsAreNextPreviousCloseAndNew() {
        assertEquals(BtInput.Shortcut.NEXT_TAB, key(KeyEvent.KEYCODE_TAB, ctrl = true))
        assertEquals(BtInput.Shortcut.PREVIOUS_TAB, key(KeyEvent.KEYCODE_TAB, ctrl = true, shift = true))
        assertEquals(BtInput.Shortcut.NEXT_TAB, key(KeyEvent.KEYCODE_PAGE_DOWN, ctrl = true))
        assertEquals(BtInput.Shortcut.PREVIOUS_TAB, key(KeyEvent.KEYCODE_PAGE_UP, ctrl = true))
        assertEquals(BtInput.Shortcut.CLOSE_TAB, key(KeyEvent.KEYCODE_W, ctrl = true))
        assertEquals(BtInput.Shortcut.CLOSE_TAB, key(KeyEvent.KEYCODE_F4, ctrl = true))
        assertEquals(BtInput.Shortcut.NEW_TAB, key(KeyEvent.KEYCODE_T, ctrl = true))
        assertEquals(BtInput.Shortcut.HOME, key(KeyEvent.KEYCODE_MOVE_HOME, alt = true))
        // Il Tab da solo muove tra i campi della pagina
        assertNull(key(KeyEvent.KEYCODE_TAB))
        assertNull(key(KeyEvent.KEYCODE_TAB, shift = true))
    }

    @Test
    fun editingShortcutsStayWithThePage() {
        for (code in listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_Z)) {
            assertNull("Ctrl+$code serve a scrivere", key(code, ctrl = true))
        }
        assertNull(key(KeyEvent.KEYCODE_ENTER))
        assertNull(key(KeyEvent.KEYCODE_DEL))
        assertNull(key(KeyEvent.KEYCODE_SPACE))
    }

    @Test
    fun phoneAndMediaKeysStayWithThePhone() {
        for (code in listOf(
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_ENDCALL
        )) {
            assertFalse("$code resta al telefono", BtInput.forwardable(code))
        }
        for (code in listOf(
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT
        )) {
            assertTrue("$code va all'auto", BtInput.forwardable(code))
        }
    }

    @Test
    fun speedIsKeptInTheChosenSteps() {
        assertEquals(100, BtInput.DEFAULT_SPEED)
        assertEquals(50, BtInput.clampSpeed(0))
        assertEquals(50, BtInput.clampSpeed(-40))
        assertEquals(300, BtInput.clampSpeed(900))
        assertEquals(100, BtInput.clampSpeed(100))
        assertEquals(125, BtInput.clampSpeed(113))
        assertEquals(100, BtInput.clampSpeed(110))
    }

    @Test
    fun theCursorMovesWithTheSpeedAndNeverLeavesTheScreen() {
        val one = BtInput.moved(100f, 100f, 10f, -20f, 100, 800, 480)
        assertEquals(110f, one.x, 0.001f)
        assertEquals(80f, one.y, 0.001f)

        val fast = BtInput.moved(100f, 100f, 10f, 10f, 200, 800, 480)
        assertEquals(120f, fast.x, 0.001f)
        assertEquals(120f, fast.y, 0.001f)

        val slow = BtInput.moved(100f, 100f, 10f, 10f, 50, 800, 480)
        assertEquals(105f, slow.x, 0.001f)

        // Ai bordi si ferma: l'ultimo punto è larghezza-1 e altezza-1, e il bordo opposto è 0
        val corner = BtInput.moved(790f, 470f, 500f, 500f, 100, 800, 480)
        assertEquals(799f, corner.x, 0.001f)
        assertEquals(479f, corner.y, 0.001f)
        val origin = BtInput.moved(5f, 5f, -500f, -500f, 100, 800, 480)
        assertEquals(0f, origin.x, 0.001f)
        assertEquals(0f, origin.y, 0.001f)

        // Uscendo dal bordo il movimento successivo parte dal bordo, senza «spazio morto» fuori dallo schermo
        val back = BtInput.moved(corner.x, corner.y, -10f, -10f, 100, 800, 480)
        assertEquals(789f, back.x, 0.001f)
        assertEquals(469f, back.y, 0.001f)
    }

    @Test
    fun anEmptyScreenKeepsTheCursorAtTheOrigin() {
        val point = BtInput.moved(0f, 0f, 30f, 30f, 100, 0, 0)
        assertEquals(0f, point.x, 0.001f)
        assertEquals(0f, point.y, 0.001f)
        val centre = BtInput.centre(801, 481)
        assertEquals(400f, centre.x, 0.001f)
        assertEquals(240f, centre.y, 0.001f)
    }

    @Test
    fun theWheelScrollsAboutALinePerNotch() {
        assertEquals(56f, BtInput.wheelDistance(1f, 1f), 0.001f)
        assertEquals(-112f, BtInput.wheelDistance(-2f, 1f), 0.001f)
        // Sullo schermo dell'auto, a densità 0,74, lo scatto è più piccolo in pixel ma uguale in dp
        assertEquals(41.44f, BtInput.wheelDistance(1f, 0.74f), 0.01f)
        assertEquals(0f, BtInput.wheelDistance(0f, 2f), 0.001f)
    }

    @Test
    fun aClickIsASmallMovementOnly() {
        assertTrue(BtInput.isClick(100f, 100f, 100f, 100f, 12f))
        assertTrue(BtInput.isClick(100f, 100f, 106f, 108f, 12f))
        assertFalse(BtInput.isClick(100f, 100f, 130f, 100f, 12f))
    }

    @Test
    fun mouseButtonsChangeOneAtATime() {
        val none = 0
        val left = MotionEvent.BUTTON_PRIMARY
        val both = MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_SECONDARY

        val press = BtInput.changes(none, left)
        assertEquals(1, press.size)
        assertEquals(BtInput.Button.PRIMARY, press[0].button)
        assertTrue(press[0].pressed)

        val release = BtInput.changes(left, none)
        assertEquals(BtInput.Button.PRIMARY, release[0].button)
        assertFalse(release[0].pressed)

        // Un secondo pulsante mentre il primo è premuto: cambia solo quello
        val second = BtInput.changes(left, both)
        assertEquals(1, second.size)
        assertEquals(BtInput.Button.SECONDARY, second[0].button)
        assertTrue(second[0].pressed)

        assertTrue(BtInput.changes(left, left).isEmpty())
        // I pulsanti della penna non sono del mouse
        assertTrue(BtInput.changes(none, MotionEvent.BUTTON_STYLUS_PRIMARY).isEmpty())
    }

    @Test
    fun sideAndRightButtonsNavigate() {
        assertEquals(BtInput.Shortcut.BACK, BtInput.shortcutFor(BtInput.Button.SECONDARY))
        assertEquals(BtInput.Shortcut.BACK, BtInput.shortcutFor(BtInput.Button.BACK))
        assertEquals(BtInput.Shortcut.FORWARD, BtInput.shortcutFor(BtInput.Button.FORWARD))
        assertNull(BtInput.shortcutFor(BtInput.Button.PRIMARY))
        assertNull(BtInput.shortcutFor(BtInput.Button.MIDDLE))
    }
}
