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
    fun youtubeUrlConversionWorksForMobileAndDesktop() {
        val desktopUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val mobileUrl = "https://m.youtube.com/watch?v=dQw4w9WgXcQ"

        assertEquals(mobileUrl, WebViewScriptRouter.convertYouTubeToMobileUrl(desktopUrl))
        assertEquals(desktopUrl, WebViewScriptRouter.convertYouTubeToDesktopUrl(mobileUrl))
        assertEquals(mobileUrl, WebViewScriptRouter.convertYouTubeToMobileUrl(mobileUrl))
    }
}
