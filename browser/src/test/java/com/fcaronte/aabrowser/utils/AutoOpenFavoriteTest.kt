package com.fcaronte.aabrowser.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** La scelta della pagina iniziale può cadere su un sito (il suo id) o su un'app del telefono (app://pacchetto). */
class AutoOpenFavoriteTest {

    @Test
    fun anAppIsRecognizedByItsAddress() {
        assertEquals("com.example.player", AutoOpenFavorite.appPackage("app://com.example.player"))
        assertEquals("org.videolan.vlc", AutoOpenFavorite.appPackage("app://org.videolan.vlc"))
    }

    @Test
    fun aSiteIsNeverTakenForAnApp() {
        // L'id di un sito predefinito è un numero, quello di un sito aggiunto dall'utente è un UUID
        assertNull(AutoOpenFavorite.appPackage("13"))
        assertNull(AutoOpenFavorite.appPackage("0b9f7d3e-4f55-4d54-a0e5-9a7c1f2b6a10"))
        assertNull(AutoOpenFavorite.appPackage("https://example.com/"))
        assertNull(AutoOpenFavorite.appPackage(""))
        assertNull(AutoOpenFavorite.appPackage(null))
    }

    @Test
    fun anAddressThatIsNotAPackageNameIsRefused() {
        // Il valore salvato non si fida: arriva dalle preferenze, che un ripristino può riempire con altro
        assertNull(AutoOpenFavorite.appPackage("app://"))
        assertNull(AutoOpenFavorite.appPackage("app://nodots"))
        assertNull(AutoOpenFavorite.appPackage("app://com.example.player; rm -rf"))
        assertNull(AutoOpenFavorite.appPackage("app://../etc/passwd"))
    }
}
