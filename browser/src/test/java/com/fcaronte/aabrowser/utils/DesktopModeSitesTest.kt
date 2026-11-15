package com.fcaronte.aabrowser.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopModeSitesTest {
    @Test
    fun siteOverrideAppliesToSubdomainsButNotSimilarHosts() {
        val overrides = setOf("1|example.com")

        assertEquals(true, DesktopModeSites.resolveOverride("www.example.com", overrides))
        assertEquals(true, DesktopModeSites.resolveOverride("video.example.com", overrides))
        assertEquals(null, DesktopModeSites.resolveOverride("example.net", overrides))
        assertEquals(null, DesktopModeSites.resolveOverride("notexample.com", overrides))
    }

    @Test
    fun mostSpecificOverrideWinsAndFalseIsRemembered() {
        val overrides = setOf("1|example.com", "0|shop.example.com")

        assertEquals(false, DesktopModeSites.resolveOverride("www.shop.example.com", overrides))
        assertEquals(true, DesktopModeSites.resolveOverride("news.example.com", overrides))
    }

    @Test
    fun siteChoiceBeatsTheFavorite() {
        val overrides = setOf("0|example.com", "1|other.org")

        // Il sito scelto mobile resta mobile anche se il preferito dice desktop
        assertEquals(false, DesktopModeSites.decide("www.example.com", overrides, favoriteOverride = true))
        // Il sito scelto desktop lo è anche se il preferito dice mobile
        assertEquals(true, DesktopModeSites.decide("other.org", overrides, favoriteOverride = false))
        // Senza scelta per il sito: il preferito, altrimenti mobile (non c'è più l'impostazione generale)
        assertEquals(true, DesktopModeSites.decide("free.net", overrides, favoriteOverride = true))
        assertEquals(false, DesktopModeSites.decide("free.net", overrides, favoriteOverride = false))
        assertEquals(false, DesktopModeSites.decide("free.net", overrides, favoriteOverride = null))
    }

    @Test
    fun requiredSitesStayDesktopWhateverTheChoice() {
        val overrides = setOf("0|web.whatsapp.com")

        assertEquals(true, DesktopModeSites.decide("web.whatsapp.com", overrides, null, required = true))
        assertEquals(true, DesktopModeSites.decide("www.dazn.com", emptySet(), null, daznForced = true))
        assertEquals(false, DesktopModeSites.decide("www.dazn.com", emptySet(), null))
    }

    @Test
    fun mobileAndDesktopHostsOfASiteShareTheChoice() {
        // YouTube passa da m.youtube.com a www.youtube.com: la scelta fatta da una pagina deve valere per l'altra
        assertEquals("youtube.com", DesktopModeSites.siteKey("m.youtube.com"))
        assertEquals("youtube.com", DesktopModeSites.siteKey("www.youtube.com"))
        assertEquals("youtube.com", DesktopModeSites.siteKey("YouTube.com"))
        assertEquals("twitter.com", DesktopModeSites.siteKey("mobile.twitter.com"))
        assertEquals("example.org", DesktopModeSites.siteKey("touch.example.org"))
        // I sottodomini veri restano come sono, e un nome che comincia per "m." senza altro dominio dopo non è mobile
        assertEquals("music.youtube.com", DesktopModeSites.siteKey("music.youtube.com"))
        assertEquals("m.com", DesktopModeSites.siteKey("m.com"))
        assertEquals(null, DesktopModeSites.siteKey(""))

        val overrides = setOf("1|youtube.com")
        assertEquals(true, DesktopModeSites.resolveOverride("m.youtube.com", overrides))
        assertEquals(true, DesktopModeSites.resolveOverride("www.youtube.com", overrides))
        assertEquals(true, DesktopModeSites.resolveOverride("music.youtube.com", overrides))
        // Una scelta con l'host mobile vale per tutto il sito, non solo per m.
        assertEquals(true, DesktopModeSites.resolveOverride("www.youtube.com", setOf("1|m.youtube.com")))
    }

    @Test
    fun oldChoicesSavedWithAMobileHostAreDropped() {
        val saved = setOf("1|m.youtube.com", "0|mobile.twitter.com", "1|example.org", "0|shop.example.com", "1|www.example.net")

        assertEquals(
            setOf("1|example.org", "0|shop.example.com", "1|www.example.net"),
            DesktopModeSites.withoutMobileHosts(saved)
        )
        assertEquals(emptySet<String>(), DesktopModeSites.withoutMobileHosts(emptySet()))
    }

    @Test
    fun youtubeUrlConversionWorksForMobileAndDesktop() {
        val desktopUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val mobileUrl = "https://m.youtube.com/watch?v=dQw4w9WgXcQ"

        assertEquals(mobileUrl, WebViewScriptRouter.convertYouTubeToMobileUrl(desktopUrl))
        assertEquals(desktopUrl, WebViewScriptRouter.convertYouTubeToDesktopUrl(mobileUrl))
        assertEquals(mobileUrl, WebViewScriptRouter.convertYouTubeToMobileUrl(mobileUrl))
    }
}
