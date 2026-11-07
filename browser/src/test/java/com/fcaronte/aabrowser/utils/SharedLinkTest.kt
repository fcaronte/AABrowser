package com.fcaronte.aabrowser.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Testi come li condividono le app del telefono (YouTube, Chrome, app di notizie). */
class SharedLinkTest {

    @Test
    fun youTubeApp() {
        assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ", SharedLink.find("https://youtu.be/dQw4w9WgXcQ?si=Ab12Cd34"))
        assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=42", SharedLink.find("https://youtu.be/dQw4w9WgXcQ?si=x&t=42"))
        assertEquals(
            "https://m.youtube.com/watch?v=abc&t=10s",
            SharedLink.find("https://www.youtube.com/watch?v=abc&si=x&t=10s")
        )
        assertEquals("https://m.youtube.com/shorts/xyz", SharedLink.find("https://youtube.com/shorts/xyz?si=q"))
        assertEquals(
            "https://music.youtube.com/watch?v=abc&list=RD1",
            SharedLink.find("https://music.youtube.com/watch?v=abc&list=RD1&si=z")
        )
    }

    @Test
    fun linkInsideText() {
        assertEquals(
            "https://www.repubblica.it/cronaca/2026/11/07/news-1.html",
            SharedLink.find("Guarda questa notizia:\nhttps://www.repubblica.it/cronaca/2026/11/07/news-1.html")
        )
        assertEquals("https://example.com/x", SharedLink.find("(vedi https://example.com/x)."))
        assertEquals("https://example.com/page", SharedLink.find("Leggi «https://example.com/page»"))
        // La parentesi fa parte dell'indirizzo
        assertEquals("https://it.wikipedia.org/wiki/Roma_(citt%C3%A0)", SharedLink.find("https://it.wikipedia.org/wiki/Roma_(citt%C3%A0)."))
        assertEquals("http://192.168.1.10:8123/lovelace", SharedLink.find("http://192.168.1.10:8123/lovelace"))
    }

    @Test
    fun noLink() {
        assertNull(SharedLink.find(null))
        assertNull(SharedLink.find(""))
        assertNull(SharedLink.find("solo del testo"))
        assertNull(SharedLink.find("javascript:alert(1)"))
        assertNull(SharedLink.find("ftp://example.com/file"))
        assertNull(SharedLink.find("https://"))
    }
}
