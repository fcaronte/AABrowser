package com.fcaronte.aabrowser.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finestre aperte dagli script: le pubblicità a ogni tocco no, i link toccati, lo stesso sito e gli accessi sì. */
class PopupGuardTest {
    private val ads = setOf("wayanatomyunavailable.com", "popads.net")
    private fun isAd(host: String) = ads.any { host == it || host.endsWith(".$it") }

    private fun allows(url: String, page: String?, tapped: String? = null) = PopupGuard.decide(url, page, tapped, ::isAd)

    @Test
    fun adsThatOpenAtEveryTap() {
        // Il sito di streaming dell'esempio: un tocco qualsiasi apre un altro sito
        assertFalse(allows("https://landing.random-offer.top/?z=123", "sportora.ru"))
        assertFalse(allows("https://wayanatomyunavailable.com/c7/93/x", "sportora.ru", "https://sportora.ru/match/1"))
        // Anche toccando un link: la finestra va altrove
        assertFalse(allows("https://landing.random-offer.top/", "sportora.ru", "https://sportora.ru/match/1"))
        // Una pubblicità delle liste, anche se è il link toccato
        assertFalse(allows("https://c.popads.net/go", "example.com", "https://c.popads.net/go"))
    }

    @Test
    fun wantedWindows() {
        assertTrue(allows("https://m.example.com/page", "www.example.com"))
        assertTrue(allows("https://player.other.net/embed/1", "sportora.ru", "https://player.other.net/embed/1"))
        // Il link toccato può passare da un altro sottodominio dello stesso sito
        assertTrue(allows("https://go.other.net/r?id=1", "sportora.ru", "https://www.other.net/x"))
        // Accessi e pagamenti
        assertTrue(allows("https://login.microsoftonline.com/common/oauth2/authorize", "example.com"))
        assertTrue(allows("https://appleid.apple.com/auth/authorize?client_id=x", "example.com"))
        assertTrue(allows("https://www.paypal.com/checkoutnow?token=x", "shop.example.it"))
        // Non porta su un altro sito
        assertTrue(allows("about:blank", "example.com"))
        assertTrue(allows("blob:https://example.com/123", "example.com"))
    }

    @Test
    fun signInWordsOnlyInHostAndPath() {
        // "login" solo nei parametri: è una pubblicità che si finge accesso
        assertFalse(allows("https://offer.example.top/?next=login", "sportora.ru"))
        // Parole intere, non pezzi di nomi
        assertFalse(allows("https://www.rossoporpora.com/promo", "sportora.ru"))
        assertTrue(allows("https://sso.example-bank.it/sign-in", "shop.example.it"))
    }

    @Test
    fun sites() {
        assertEquals("youtube.com", PopupGuard.siteOf("m.youtube.com"))
        assertEquals("bbc.co.uk", PopupGuard.siteOf("news.bbc.co.uk"))
        assertEquals("globo.com.br", PopupGuard.siteOf("g1.globo.com.br"))
        assertEquals("repubblica.it", PopupGuard.siteOf("www.repubblica.it"))
        assertEquals("example.com", PopupGuard.siteOf("example.com"))
        assertEquals("192.168.1.10", PopupGuard.siteOf("192.168.1.10"))
    }
}
