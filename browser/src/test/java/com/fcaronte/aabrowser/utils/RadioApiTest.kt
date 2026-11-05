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
}
