package com.fcaronte.aabrowser.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** Come le liste M3U chiedono User-Agent e Referer (righe vere delle liste IPTV-org e dei formati di VLC, Kodi, TiviMate). */
class IptvHeadersTest {
    private val chrome = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    @Test
    fun vlcOptions() {
        assertEquals(mapOf("User-Agent" to chrome), IptvHeaders.fromDirective("#EXTVLCOPT:http-user-agent=$chrome"))
        // Il valore può contenere "=", "#" e "&"
        assertEquals(
            mapOf("Referer" to "https://www.grupposciscione.it/odeon-2/#/?playlistId=0&videoId=0"),
            IptvHeaders.fromDirective("#EXTVLCOPT:http-referrer=https://www.grupposciscione.it/odeon-2/#/?playlistId=0&videoId=0")
        )
        assertEquals(mapOf("Referer" to "https://a.it/"), IptvHeaders.fromDirective("#EXTVLCOPT:http-referer=https://a.it/"))
        assertEquals(emptyMap<String, String>(), IptvHeaders.fromDirective("#EXTVLCOPT:network-caching=1000"))
        assertEquals(emptyMap<String, String>(), IptvHeaders.fromDirective("#EXTGRP:Sport"))
    }

    @Test
    fun otherPlayers() {
        assertEquals(
            mapOf("User-Agent" to "ua", "Referer" to "https://a.it/", "Cookie" to "x=1"),
            IptvHeaders.fromDirective("""#EXTHTTP:{"User-Agent":"ua","Referer":"https://a.it/","cookie":"x=1"}""")
        )
        assertEquals(
            mapOf("User-Agent" to "my ua", "Referer" to "https://a.it/"),
            IptvHeaders.fromDirective("#KODIPROP:inputstream.adaptive.stream_headers=User-Agent=my%20ua&Referer=https%3A%2F%2Fa.it%2F")
        )
        assertEquals(emptyMap<String, String>(), IptvHeaders.fromDirective("#KODIPROP:inputstream.adaptive.manifest_type=hls"))
    }

    @Test
    fun attributes() {
        assertEquals(
            mapOf("User-Agent" to "ua", "Referer" to "https://a.it/"),
            IptvHeaders.fromAttributes(mapOf("tvg-id" to "x", "http-user-agent" to "ua", "http-referrer" to "https://a.it/"))
        )
    }

    @Test
    fun addressSuffix() {
        assertEquals("http://a.it/b.m3u8" to emptyMap<String, String>(), IptvHeaders.splitAddress("http://a.it/b.m3u8"))
        assertEquals(
            "http://a.it/b.m3u8" to mapOf("User-Agent" to "my ua", "Referer" to "https://c.it/"),
            IptvHeaders.splitAddress("http://a.it/b.m3u8|User-Agent=my%20ua&Referer=https://c.it/")
        )
        // Un "+" nel valore resta un "+"
        assertEquals(mapOf("User-Agent" to "VLC+3"), IptvHeaders.splitAddress("http://a.it/b|user-agent=VLC+3").second)
    }
}

class IptvStreamKindTest {
    @Test
    fun fromThePath() {
        assertEquals("hls", IptvProxy.kindOfPath("/live/channel.M3U8"))
        assertEquals("ts", IptvProxy.kindOfPath("/live/user/pass/123.ts"))
        assertEquals("flv", IptvProxy.kindOfPath("/stream/live.flv"))
        assertEquals("file", IptvProxy.kindOfPath("/video/film.mp4"))
        // Le liste Xtream senza estensione: nessuna connessione di prova
        assertEquals("ts", IptvProxy.kindOfPath("/user/pass/12345"))
        assertEquals(null, IptvProxy.kindOfPath("/live/12345"))
        assertEquals(null, IptvProxy.kindOfPath("/play.php"))
    }
}
