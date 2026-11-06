package com.fcaronte.aabrowser.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nomi come li scrive il riconoscimento vocale, contro nomi come arrivano dalle liste IPTV e da Radio Browser. */
class MediaMatcherTest {

    private fun score(query: String, name: String) = MediaMatcher.score(MediaMatcher.key(query), MediaMatcher.key(name))

    private fun assertConfident(query: String, name: String) =
        assertTrue("$query = $name (${score(query, name)})", score(query, name) >= MediaMatcher.CONFIDENT)

    private fun assertNotConfident(query: String, name: String) =
        assertTrue("$query != $name (${score(query, name)})", score(query, name) < MediaMatcher.CONFIDENT)

    @Test
    fun spokenNumbersAndListNoise() {
        assertEquals(1000, score("rai uno", "Rai 1 HD"))
        assertEquals(1000, score("rai 1", "IT: RAI 1 (1080p) [Geo-blocked]"))
        assertEquals(1000, score("la sette", "La7"))
        assertEquals(1000, score("italia uno", "Italia 1"))
        assertEquals(1000, score("canale cinque", "Canale 5"))
        assertEquals(1000, score("rtl 102 e 5", "RTL 102.5"))
        assertEquals(1000, score("rtl 102 punto 5", "RTL 102.5"))
        assertEquals(1000, score("Radio Italia", "Radio Italia"))
        assertEquals(1000, score("nove", "NOVE"))
        assertEquals(1000, score("dee jay", "Deejay"))
    }

    @Test
    fun genericWordsDoNotCount() {
        assertConfident("radio subasio fm", "Radio Subasio")
        assertConfident("il canale rai 1", "Rai 1")
        assertConfident("subasio", "Radio Subasio")
        assertConfident("deejay", "Radio Deejay")
        assertConfident("virgin radio", "Virgin Radio Italia")
    }

    @Test
    fun allWordsAreNeeded() {
        assertEquals(0, score("radio ga ga", "Radio Italia"))
        assertEquals(0, score("video killed the radio star", "Virgin Radio"))
        assertNotConfident("ai", "Rai 1")
        assertEquals(0, score("rai 7", "Rai 1"))
    }

    @Test
    fun oneWrongLetterOrWordEnd() {
        assertConfident("subasi", "Radio Subasio")
        assertConfident("deejays", "Radio Deejay")
        assertConfident("sky", "SkyTG24")
    }

    @Test
    fun typingStillFindsPiecesOfNames() {
        assertTrue(score("ra", "Rai 1") > 0)
        assertTrue(score("ai 1", "Rai 1") > 0)
    }

    @Test
    fun exactBeatsLongerNames() {
        assertTrue(score("rai 1", "Rai 1") > score("rai 1", "Rai 1 Sicilia"))
        assertTrue(score("rai 1", "Rai 1 Sicilia") > score("rai 1", "Canale Rai 1 Sicilia Occidentale"))
    }

    @Test
    fun rankKeepsOrderOnTies() {
        val names = listOf("Radio Uno", "Rai Radio 1", "Rai 1", "Rai 1 Sicilia", "Italia 1")
        val ranked = MediaMatcher.rank(MediaMatcher.key("rai uno"), names) { MediaMatcher.key(it) }.map { it.first }
        assertEquals(listOf("Rai 1", "Rai 1 Sicilia", "Rai Radio 1"), ranked)
    }

    @Test
    fun files() {
        assertEquals(1000, MediaMatcher.score(MediaMatcher.key("albachiara"), MediaMatcher.fileKey("01 - Albachiara")))
        assertEquals(1000, MediaMatcher.score(MediaMatcher.key("albachiara"), MediaMatcher.fileKey("07. Albachiara")))
        // Un numero che fa parte del titolo resta
        assertEquals(1000, MediaMatcher.score(MediaMatcher.key("99 problems"), MediaMatcher.fileKey("99 Problems")))
        assertEquals(1000, MediaMatcher.score(MediaMatcher.key("7 years"), MediaMatcher.fileKey("7 Years")))
        assertEquals(1000, MediaMatcher.score(MediaMatcher.key("hello"), MediaMatcher.fileKey("03 Hello")))
    }
}
