package com.fcaronte.aabrowser.utils

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class RadioApiTest {
    @Test
    fun explicitRegionTakesPrecedenceOverLanguageDefault() {
        assertEquals("GB", RadioApi.countryCodeFor(Locale.forLanguageTag("en-GB")))
        assertEquals("CA", RadioApi.countryCodeFor(Locale.forLanguageTag("fr-CA")))
    }

    @Test
    fun supportedLanguagesHaveCountryDefaults() {
        assertEquals("IT", RadioApi.countryCodeFor(Locale.forLanguageTag("it")))
        assertEquals("DE", RadioApi.countryCodeFor(Locale.forLanguageTag("de")))
        assertEquals("IN", RadioApi.countryCodeFor(Locale.forLanguageTag("hi")))
        assertEquals("RU", RadioApi.countryCodeFor(Locale.forLanguageTag("ru")))
        assertEquals("US", RadioApi.countryCodeFor(Locale.forLanguageTag("en")))
    }

    @Test
    fun parsesIcyStreamTitleAndUnescapesQuotes() {
        assertEquals(
            "Artista - L'estate",
            RadioApi.parseIcyTitle("StreamTitle='Artista - L\\'estate';".toByteArray())
        )
    }

    @Test
    fun apostrophesDoNotEndTheIcyTitle() {
        assertEquals(
            "Sombr - My Body Isn't Ready",
            RadioApi.parseIcyTitle("StreamTitle='Sombr - My Body Isn't Ready';StreamUrl='';\u0000\u0000".toByteArray())
        )
        assertEquals("Radio Onda d'Urto", RadioApi.parseIcyTitle("StreamTitle='Radio Onda d'Urto';".toByteArray()))
        assertEquals("L'estate", RadioApi.parseIcyTitle("StreamTitle='L'estate'".toByteArray()))
        assertEquals("Pubblicita'", RadioApi.parseIcyTitle("StreamTitle='Pubblicita'';StreamUrl='x';".toByteArray()))
    }

    @Test
    fun icyTitleInLatin1() {
        val latin1 = "StreamTitle='Loredana Bertè - Sei bellissima';".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Loredana Bertè - Sei bellissima", RadioApi.parseIcyTitle(latin1))
        val utf8 = "StreamTitle='Loredana Bertè - Sei bellissima';".toByteArray(Charsets.UTF_8)
        assertEquals("Loredana Bertè - Sei bellissima", RadioApi.parseIcyTitle(utf8))
    }

    @Test
    fun ignoresEmptyOrMissingIcyStreamTitle() {
        assertEquals(null, RadioApi.parseIcyTitle("StreamTitle='';".toByteArray()))
        assertEquals(null, RadioApi.parseIcyTitle("StreamUrl='https://example.com';".toByteArray()))
    }
}
