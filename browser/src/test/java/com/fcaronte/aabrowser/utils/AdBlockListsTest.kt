package com.fcaronte.aabrowser.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Righe vere dell'AdGuard DNS filter e dei file hosts. */
class AdBlockListsTest {
    private val lists = AdBlockLists.parse(
        sequenceOf(
            "! Title: AdGuard DNS filter",
            "||wayanatomyunavailable.com^",
            "||histats.com^",
            "||adsrvmedia.adk2.co^\$important",
            "||10010.com^",
            "@@||ad.10010.com^",
            "@@|cdn.taboola.com^|",
            "||taboola.com^",
            "||pl.ua^",
            "||pl.ua^\$badfilter",
            "||ad-host-backup-*.aliyuncs.com^",
            "/^139\\.45\\.197\\.2(4[0-9]|5[0-4]):/",
            "||linksprf.com^\$third-party",
            "0.0.0.0 ads.example.net # comment",
            "127.0.0.1 localhost",
            "tracker.example.org",
            "example.com##.ad-banner",
            "",
        )
    )

    private fun blocked(host: String) = AdBlockLists.matches(host, lists)

    @Test
    fun domainsAndTheirSubdomains() {
        assertTrue(blocked("wayanatomyunavailable.com"))
        assertTrue(blocked("cdn.wayanatomyunavailable.com"))
        assertTrue(blocked("s10.histats.com"))
        assertTrue(blocked("adsrvmedia.adk2.co"))
        assertTrue(blocked("ads.example.net"))
        assertTrue(blocked("tracker.example.org"))
        assertFalse(blocked("example.net"))
        assertFalse(blocked("histats.com.example.it"))
        assertFalse(blocked("www.repubblica.it"))
    }

    @Test
    fun exceptionsWin() {
        assertTrue(blocked("ads.10010.com"))
        assertFalse(blocked("ad.10010.com"))
        assertFalse(blocked("x.ad.10010.com"))
        assertTrue(blocked("trc.taboola.com"))
        assertFalse(blocked("cdn.taboola.com"))
    }

    @Test
    fun skippedRules() {
        // Regola tolta da $badfilter, jolly, opzioni sconosciute, regole per nascondere elementi, localhost
        assertFalse(blocked("pl.ua"))
        assertFalse(blocked("ad-host-backup-1.aliyuncs.com"))
        assertFalse(blocked("linksprf.com"))
        assertFalse(blocked("example.com"))
        assertFalse(blocked("localhost"))
    }

    @Test
    fun emptyList() {
        assertFalse(AdBlockLists.matches("wayanatomyunavailable.com", AdBlockLists.parse(emptySequence())))
    }

    @Test
    fun suffixHashes() {
        // L'impronta di un dominio dentro un host è quella del dominio da solo
        assertEquals(AdBlockLists.hash("histats.com"), AdBlockLists.hash("s10.histats.com", 4))
    }

    @Test
    fun rotatingAdScripts() {
        val path = "/c7/93/5c/c7935c0275b404c4d7bc2d800efb8f68.js"
        assertTrue(AdBlockHost.isRotatingAdScript(path, "wayanatomyunavailable.com", "sportora.ru"))
        // Le cartelle devono essere l'inizio del nome
        assertFalse(AdBlockHost.isRotatingAdScript("/c7/93/5d/c7935c0275b404c4d7bc2d800efb8f68.js", "x.com", "sportora.ru"))
        // Dallo stesso sito della pagina va bene
        assertFalse(AdBlockHost.isRotatingAdScript(path, "static.sportora.ru", "sportora.ru"))
        assertFalse(AdBlockHost.isRotatingAdScript("/static/js/main.c7935c02.js", "x.com", "sportora.ru"))
    }
}
