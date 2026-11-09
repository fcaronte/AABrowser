package com.fcaronte.aabrowser.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/** Feed RSS e file OPML, con le stranezze dei feed veri. */
class PodcastFeedTest {

    private fun feed(items: String, channel: String = "") = """<?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:media="http://search.yahoo.com/mrss/">
        <channel>
          <title>Il mio podcast</title>
          <itunes:author>Un Autore</itunes:author>
          <image><url>https://example.com/rss.jpg</url><title>Titolo immagine</title></image>
          <itunes:image href="https://example.com/show.jpg"/>
          $channel
          $items
        </channel></rss>
    """.trimIndent()

    private fun item(number: Int, extra: String = "") = """
        <item>
          <title>Episodio $number</title>
          <guid isPermaLink="false">guid-$number</guid>
          <pubDate>Mon, 03 Nov 2025 10:00:00 +0000</pubDate>
          <enclosure url="https://cdn.example.com/$number.mp3" type="audio/mpeg" length="1"/>
          <itunes:duration>1:02:03</itunes:duration>
          <description><![CDATA[<p>testo lungo</p>]]></description>
          $extra
        </item>
    """

    private fun parse(xml: String, maxEpisodes: Int = PodcastFeed.MAX_EPISODES, headOnly: Boolean = false) =
        PodcastFeed.parse(xml.byteInputStream(), maxEpisodes, headOnly)

    @Test
    fun readsTheShowAndItsEpisodes() {
        val channel = parse(feed(item(1) + item(2)))
        assertEquals("Il mio podcast", channel.title)
        assertEquals("Un Autore", channel.author)
        assertEquals("https://example.com/show.jpg", channel.image)
        assertEquals(listOf("Episodio 1", "Episodio 2"), channel.episodes.map { it.title })
        val first = channel.episodes[0]
        assertEquals("https://cdn.example.com/1.mp3", first.url)
        assertEquals(3723, first.duration)
        assertEquals(1762164000000L, first.publishedAt)
        assertNull(first.image)
    }

    @Test
    fun serialShowsAreRecognized() {
        assertEquals(false, parse(feed(item(1))).serial)
        assertEquals(true, parse(feed(item(1), channel = "<itunes:type>serial</itunes:type>")).serial)
        assertEquals(false, parse(feed(item(1), channel = "<itunes:type>episodic</itunes:type>")).serial)
        // Il tipo della puntata non è quello del podcast
        assertEquals(false, parse(feed(item(1, "<itunes:episodeType>full</itunes:episodeType>"))).serial)
    }

    @Test
    fun theChannelImageIsUsedWithoutItunesImage() {
        val xml = feed("").replace("""<itunes:image href="https://example.com/show.jpg"/>""", "")
        val channel = parse(xml)
        assertEquals("https://example.com/rss.jpg", channel.image)
        assertEquals("Il mio podcast", channel.title)
    }

    @Test
    fun otherNamespacesDoNotReplaceTheFields() {
        val episode = parse(feed(item(1, "<media:title>Sbagliato</media:title><itunes:title>Sbagliato</itunes:title>"))).episodes[0]
        assertEquals("Episodio 1", episode.title)
    }

    @Test
    fun theEpisodeImageIsKept() {
        val episode = parse(feed(item(1, """<itunes:image href="https://example.com/1.jpg"/>"""))).episodes[0]
        assertEquals("https://example.com/1.jpg", episode.image)
        assertEquals("https://example.com/show.jpg", parse(feed(item(1, """<itunes:image href="https://example.com/1.jpg"/>"""))).image)
    }

    @Test
    fun itemsWithoutAudioAreSkipped() {
        val pdf = """<item><title>Solo PDF</title><enclosure url="https://example.com/a.pdf" type="application/pdf"/></item>"""
        val none = """<item><title>Niente</title></item>"""
        assertEquals(listOf("Episodio 1"), parse(feed(pdf + none + item(1))).episodes.map { it.title })
    }

    @Test
    fun theSecondEnclosureDoesNotReplaceTheFirst() {
        val episode = parse(feed(item(1, """<enclosure url="https://example.com/altro.mp4" type="video/mp4"/>"""))).episodes[0]
        assertEquals("https://cdn.example.com/1.mp3", episode.url)
    }

    @Test
    fun idsStayTheSameAcrossReads() {
        assertEquals(parse(feed(item(1))).episodes[0].id, parse(feed(item(1))).episodes[0].id)
        assertNotEquals(parse(feed(item(1))).episodes[0].id, parse(feed(item(2))).episodes[0].id)
        val withoutGuid = """<item><title>A</title><enclosure url="https://example.com/a.mp3" type="audio/mpeg"/></item>"""
        assertEquals(36, parse(feed(withoutGuid)).episodes[0].id.length)
    }

    @Test
    fun stopsAfterTheLimit() {
        val items = (1..10).joinToString("") { item(it) }
        assertEquals(3, parse(feed(items), maxEpisodes = 3).episodes.size)
    }

    @Test
    fun theHeadDoesNotReadTheEpisodes() {
        val channel = parse(feed(item(1) + "<item>non valido"), headOnly = true)
        assertEquals("Il mio podcast", channel.title)
        assertTrue(channel.episodes.isEmpty())
    }

    @Test
    fun datesInOtherFormats() {
        fun date(value: String) = parse(feed(item(1).replace("Mon, 03 Nov 2025 10:00:00 +0000", value))).episodes[0].publishedAt
        assertEquals(1762164000000L, date("3 Nov 2025 10:00:00 +0000"))
        assertEquals(1762164000000L, date("Mon, 3 Nov 2025 10:00:00 GMT"))
        assertEquals(1762164000000L, date("Mon, 03 Nov 2025 10:00 +0000"))
        assertEquals(1762164000000L, date("2025-11-03T10:00:00Z"))
        assertEquals(0L, date("ieri"))
    }

    @Test
    fun durations() {
        assertEquals(3723, PodcastFeed.parseDuration("1:02:03"))
        assertEquals(125, PodcastFeed.parseDuration("02:05"))
        assertEquals(3600, PodcastFeed.parseDuration("3600"))
        assertEquals(90, PodcastFeed.parseDuration(" 90.5 "))
        assertEquals(0, PodcastFeed.parseDuration(""))
        assertEquals(0, PodcastFeed.parseDuration("un'ora"))
    }

    @Test
    fun theDeclaredEncodingIsRead() {
        val xml = """<?xml version="1.0" encoding="windows-1251"?><rss version="2.0"><channel><title>Подкаст</title></channel></rss>"""
        val channel = PodcastFeed.parse(xml.toByteArray(Charset.forName("windows-1251")).inputStream())
        assertEquals("Подкаст", channel.title)
    }

    @Test
    fun aByteOrderMarkAndBlanksBeforeTheDeclarationAreAccepted() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals("Il mio podcast", PodcastFeed.parse((bom + feed("").toByteArray()).inputStream()).title)
        assertEquals("Il mio podcast", parse("\n  " + feed("")).title)
    }

    @Test
    fun theItunesNamespaceCanHaveCapitalLetters() {
        val xml = feed("").replace("http://www.itunes.com/dtds/podcast-1.0.dtd", "http://www.itunes.com/DTDs/Podcast-1.0.dtd")
        assertEquals("Un Autore", parse(xml).author)
    }

    @Test
    fun whatIsNotAFeedIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { parse("<html><body>pagina</body></html>") }
        assertThrows(Exception::class.java) { parse("non è xml") }
    }

    @Test
    fun externalEntitiesAreNotRead() {
        val xml = """<?xml version="1.0"?><!DOCTYPE rss [<!ENTITY x SYSTEM "file:///etc/hostname">]>
            <rss version="2.0"><channel><title>Nome&x;</title></channel></rss>"""
        assertEquals("Nome", parse(xml).title)
    }

    @Test
    fun opmlFolders() {
        val xml = """<?xml version="1.0"?><opml version="2.0"><body>
            <outline text="Cartella">
              <outline type="rss" text="Uno" xmlUrl="https://example.com/1.xml"/>
              <outline type="rss" title="Due" xmlurl="https://example.com/2.xml"/>
            </outline>
            <outline type="rss" text="Uno di nuovo" xmlUrl="https://example.com/1.xml"/>
            <outline type="rss" text="Non web" xmlUrl="file:///x.xml"/>
            </body></opml>"""
        assertEquals(
            listOf("Uno" to "https://example.com/1.xml", "Due" to "https://example.com/2.xml"),
            PodcastFeed.parseOpml(xml.byteInputStream())
        )
    }
}
