package com.fcaronte.aabrowser.smartphone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackFrameTest {
    private val size = BlackFrame.WIDTH * BlackFrame.HEIGHT

    private fun frame(color: Int) = IntArray(size) { color }

    @Test
    fun aFrameOfPureBlackLooksBlack() {
        // Quello che arriva da una finestra protetta: nero opaco, o nessun fotogramma (trasparente)
        assertTrue(BlackFrame.looksBlack(frame(0xFF000000.toInt())))
        assertTrue(BlackFrame.looksBlack(frame(0)))
    }

    @Test
    fun anAppWithAnInterfaceIsNotBlack() {
        // Fondo nero con un po' di testo e di icone: l'app si vede, anche se per la maggior parte è scura
        val pixels = frame(0xFF000000.toInt())
        for (i in 0 until size step 20) pixels[i] = 0xFFFFFFFF.toInt()

        assertFalse(BlackFrame.looksBlack(pixels))
    }

    @Test
    fun aDarkThemeBackgroundIsNotBlack() {
        // #121212, il grigio scuro del tema scuro di Material, non è il nero delle finestre protette
        assertFalse(BlackFrame.looksBlack(frame(0xFF121212.toInt())))
        // Un nero quasi puro (#0A0A0A) sì: sotto la soglia
        assertTrue(BlackFrame.looksBlack(frame(0xFF0A0A0A.toInt())))
    }

    @Test
    fun aFewStrayPixelsDoNotCountButABitMoreDoes() {
        val pixels = frame(0xFF000000.toInt())
        for (i in 0 until 3) pixels[i * 100] = 0xFFFFFFFF.toInt()
        assertTrue(BlackFrame.looksBlack(pixels))

        pixels[400] = 0xFFFFFFFF.toInt()
        assertFalse(BlackFrame.looksBlack(pixels))
    }

    @Test
    fun anyColourChannelCounts() {
        // Un fondo tutto rosso, verde o blu non è nero (basta un canale sopra la soglia)
        assertFalse(BlackFrame.looksBlack(frame(0xFFFF0000.toInt())))
        assertFalse(BlackFrame.looksBlack(frame(0xFF00FF00.toInt())))
        assertFalse(BlackFrame.looksBlack(frame(0xFF0000FF.toInt())))
    }

    @Test
    fun nothingToLookAtIsNotBlack() {
        // Senza campione non si dice niente: l'avviso non parte
        assertFalse(BlackFrame.looksBlack(IntArray(0)))
    }
}
