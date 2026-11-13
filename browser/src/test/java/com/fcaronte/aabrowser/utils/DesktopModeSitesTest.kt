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
    fun siteChoiceBeatsFavoriteAndGeneralSetting() {
        val overrides = setOf("0|example.com", "1|other.org")

        // Il sito scelto mobile resta mobile anche se il preferito e le impostazioni dicono desktop
        assertEquals(false, DesktopModeSites.decide("www.example.com", overrides, favoriteOverride = true, global = true))
        // Il sito scelto desktop lo è anche con tutto il resto su mobile
        assertEquals(true, DesktopModeSites.decide("other.org", overrides, favoriteOverride = false, global = false))
        // Senza scelta per il sito: prima il preferito, poi l'impostazione generale
        assertEquals(true, DesktopModeSites.decide("free.net", overrides, favoriteOverride = true, global = false))
        assertEquals(false, DesktopModeSites.decide("free.net", overrides, favoriteOverride = false, global = true))
        assertEquals(true, DesktopModeSites.decide("free.net", overrides, favoriteOverride = null, global = true))
    }

    @Test
    fun requiredSitesStayDesktopWhateverTheChoice() {
        val overrides = setOf("0|web.whatsapp.com")

        assertEquals(true, DesktopModeSites.decide("web.whatsapp.com", overrides, null, false, required = true))
        assertEquals(true, DesktopModeSites.decide("www.dazn.com", emptySet(), null, false, daznForced = true))
        assertEquals(false, DesktopModeSites.decide("www.dazn.com", emptySet(), null, false))
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
