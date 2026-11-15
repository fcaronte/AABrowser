package com.fcaronte.aabrowser.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** Quello che la home della Wide mostra sotto il nome e come colore: lo stesso della scheda dei preferiti della Main. */
class HomeApiTest {
    @Test
    fun theDomainIsWhatTheMainShowsUnderTheName() {
        assertEquals("youtube.com", HomeApi.domainOf("https://www.youtube.com/watch?v=abc"))
        assertEquals("youtube.com", HomeApi.domainOf("https://music.youtube.com/"))
        assertEquals("whatsapp.com", HomeApi.domainOf("https://web.whatsapp.com"))
        assertEquals("spotify.com", HomeApi.domainOf("https://open.spotify.com"))
        // Il prefisso mobile resta: la Main lo lascia (solo web, music, open e www si tolgono)
        assertEquals("m.youtube.com", HomeApi.domainOf("https://m.youtube.com"))
        assertEquals("youtube.com", HomeApi.domainOf("https://www.youtubekids.com"))
        assertEquals("player.local", HomeApi.domainOf("https://player.local/tv"))
        assertEquals("192.168.1.5:8123", HomeApi.domainOf("http://192.168.1.5:8123/x"))
        assertEquals("google.com", HomeApi.domainOf("https://google.com"))
    }

    @Test
    fun theColorIsWrittenAsRgbWithoutTransparency() {
        assertEquals("#4285F4", HomeApi.colorHex(0xFF4285F4))
        assertEquals("#000000", HomeApi.colorHex(0xFF000000))
        assertEquals("#FF0000", HomeApi.colorHex(0xFFFF0000))
        assertEquals("#00BCD4", HomeApi.colorHex(0x8000BCD4))
    }
}
