package com.fcaronte.aabrowser.utils

import com.fcaronte.aabrowser.utils.XtreamLink.Login
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamLinkTest {
    private val mario = Login("http://line.example.com:8080", "mario", "s3cret")

    @Test
    fun m3uListLink() {
        assertEquals(
            mario,
            XtreamLink.parse("http://line.example.com:8080/get.php?username=mario&password=s3cret&type=m3u_plus&output=ts"),
        )
    }

    @Test
    fun apiAndGuideLinks() {
        assertEquals(mario, XtreamLink.parse("http://line.example.com:8080/player_api.php?username=mario&password=s3cret"))
        // Spazi e a capo copiati insieme al link, parametri in un altro ordine e con le maiuscole
        assertEquals(mario, XtreamLink.parse("  http://line.example.com:8080/xmltv.php?Password=s3cret&USERNAME=mario\n"))
        assertEquals(
            Login("https://tv.example.com", "mario", "s3cret"),
            XtreamLink.parse("HTTPS://tv.example.com/?username=mario&password=s3cret#top"),
        )
    }

    @Test
    fun panelInAFolderKeepsTheFolder() {
        assertEquals(
            Login("http://example.com/iptv", "mario", "s3cret"),
            XtreamLink.parse("http://example.com/iptv/get.php?username=mario&password=s3cret"),
        )
    }

    @Test
    fun withoutSchemeItIsHttp() {
        assertEquals(mario, XtreamLink.parse("line.example.com:8080/get.php?username=mario&password=s3cret"))
    }

    @Test
    fun encodedCharacters() {
        // Nella query "+" è uno spazio e %2B un "+" (come le legge il pannello); nel percorso "+" resta "+"
        assertEquals(
            Login("http://line.example.com:8080", "mario rossi", "a+b&c=d"),
            XtreamLink.parse("http://line.example.com:8080/get.php?username=mario+rossi&password=a%2Bb%26c%3Dd"),
        )
        assertEquals(
            Login("http://line.example.com:8080", "mario", "a+b@c"),
            XtreamLink.parse("http://line.example.com:8080/live/mario/a+b%40c/123.ts"),
        )
        // Escape sbagliato: il testo resta com'è
        assertEquals(
            Login("http://line.example.com:8080", "mario", "100%"),
            XtreamLink.parse("http://line.example.com:8080/get.php?username=mario&password=100%"),
        )
    }

    @Test
    fun channelLinks() {
        assertEquals(mario, XtreamLink.parse("http://line.example.com:8080/live/mario/s3cret/12345.m3u8"))
        assertEquals(mario, XtreamLink.parse("http://line.example.com:8080/movie/mario/s3cret/678.mkv"))
        assertEquals(mario, XtreamLink.parse("http://line.example.com:8080/series/mario/s3cret/91.mp4"))
        assertEquals(mario, XtreamLink.parse("http://line.example.com:8080/timeshift/mario/s3cret/120/2026-11-03:20-00/12345.ts"))
        assertEquals(mario, XtreamLink.parse("http://line.example.com:8080/mario/s3cret/12345"))
        assertEquals(mario, XtreamLink.parse("http://line.example.com:8080/mario/s3cret/12345.ts"))
        // Utente che si chiama come una cartella dei contenuti
        assertEquals(
            Login("http://line.example.com:8080", "live", "s3cret"),
            XtreamLink.parse("http://line.example.com:8080/live/live/s3cret/12345.ts"),
        )
    }

    @Test
    fun credentialsInTheAddress() {
        assertEquals(mario, XtreamLink.parse("http://mario:s3cret@line.example.com:8080"))
        assertEquals(mario, XtreamLink.parse("http://mario:s3cret@line.example.com:8080/get.php"))
    }

    @Test
    fun notALinkWithUserAndPassword() {
        assertNull(XtreamLink.parse(""))
        assertNull(XtreamLink.parse("http://line.example.com:8080"))
        assertNull(XtreamLink.parse("http://line.example.com:8080/get.php?username=mario"))
        assertNull(XtreamLink.parse("http://line.example.com:8080/get.php?username=mario&password="))
        assertNull(XtreamLink.parse("http://line.example.com:8080/get.php?username=&password=s3cret"))
        assertNull(XtreamLink.parse("http://line.example.com:8080/live/mario/s3cret"))
        assertNull(XtreamLink.parse("http://line.example.com:8080/lista/canali.m3u"))
        assertNull(XtreamLink.parse("http://mario@line.example.com:8080"))
        assertNull(XtreamLink.parse("ftp://mario:s3cret@line.example.com"))
        assertNull(XtreamLink.parse("Link: http://line.example.com:8080/get.php?username=mario&password=s3cret"))
    }
}
