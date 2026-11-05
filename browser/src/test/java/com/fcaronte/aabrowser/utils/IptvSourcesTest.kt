package com.fcaronte.aabrowser.utils

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class IptvSourcesTest {
    @Test
    fun supportedAppLanguagesSelectTheirIptvOrgPlaylist() {
        val languageCodes = mapOf(
            "ar" to "ara",
            "de" to "deu",
            "en" to "eng",
            "es" to "spa",
            "fr" to "fra",
            "hi" to "hin",
            "hu" to "hun",
            "it" to "ita",
            "pt" to "por",
            "ru" to "rus",
            "zh" to "zho",
        )

        languageCodes.forEach { (language, code) ->
            val locale = Locale.forLanguageTag(language)
            assertEquals(
                "https://iptv-org.github.io/iptv/languages/$code.m3u",
                IptvSources.playlistUrl(locale),
            )
        }
    }

    @Test
    fun unsupportedLanguageFallsBackToGeneralPlaylist() {
        assertEquals(
            "https://iptv-org.github.io/iptv/index.m3u",
            IptvSources.playlistUrl(Locale.forLanguageTag("ja")),
        )
    }
}
