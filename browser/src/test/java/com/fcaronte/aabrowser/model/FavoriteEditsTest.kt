package com.fcaronte.aabrowser.model

import com.fcaronte.aabrowser.model.FavoriteEdits.Input
import com.fcaronte.aabrowser.model.FavoriteEdits.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Le modifiche ai preferiti, le stesse per la Main e per la home della Wide. */
class FavoriteEditsTest {
    private fun list() = mutableListOf(
        FavoriteSite("media", "Media", "https://player.local/"),
        FavoriteSite("yt", "YouTube", "https://m.youtube.com", 0xFFFF0000),
        FavoriteSite("wa", "WhatsApp", "https://web.whatsapp.com", 0xFF25D366, null, true, null, 0.9f),
    )

    @Test
    fun anAddressIsKeptOrCompletedWithHttps() {
        assertEquals("https://example.com", FavoriteEdits.normalizeAddress("example.com"))
        assertEquals("https://example.com/a?b=1", FavoriteEdits.normalizeAddress("  example.com/a?b=1 "))
        assertEquals("http://casa.local:8123", FavoriteEdits.normalizeAddress("http://casa.local:8123"))
        assertEquals("https://casa:8123", FavoriteEdits.normalizeAddress("casa:8123"))
        assertEquals("https://192.168.1.5:8123/x", FavoriteEdits.normalizeAddress("192.168.1.5:8123/x"))
    }

    @Test
    fun whatIsNotAWebAddressIsRefused() {
        assertNull(FavoriteEdits.normalizeAddress(""))
        assertNull(FavoriteEdits.normalizeAddress("   "))
        assertNull(FavoriteEdits.normalizeAddress("javascript:alert(1)"))
        assertNull(FavoriteEdits.normalizeAddress("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(FavoriteEdits.normalizeAddress("file:///sdcard/a.html"))
        assertNull(FavoriteEdits.normalizeAddress("data:text/html,<b>x</b>"))
        assertNull(FavoriteEdits.normalizeAddress("https://"))
        assertNull(FavoriteEdits.normalizeAddress("https://exa mple.com"))
    }

    @Test
    fun addingAppendsOnceAndKeepsTheChoices() {
        val favorites = list()

        assertEquals(Result.OK, FavoriteEdits.add(favorites, Input("Sito", "example.com", 0xFF00AA00, "https://i.example.com/i.png", true, 0.8f, 1.2f)) { "new" })

        val added = favorites.last()
        assertEquals(4, favorites.size)
        assertEquals("new", added.id)
        assertEquals("https://example.com", added.url)
        assertEquals(0xFF00AA00, added.color)
        assertEquals("https://i.example.com/i.png", added.faviconUrl)
        assertEquals(true, added.isDesktopMode)
        assertEquals(0.8f, added.mobileZoom)
        assertEquals(1.2f, added.desktopZoom)
    }

    @Test
    fun anAddressThatIsAlreadyThereIsNotRepeated() {
        val favorites = list()
        // Stesso sito con altro schema, www e barra finale: è lo stesso indirizzo
        assertEquals(Result.OK, FavoriteEdits.add(favorites, Input("Di nuovo", "http://www.web.whatsapp.com/")))
        assertEquals(3, favorites.size)
    }

    @Test
    fun theOwnPagesOfCarWebAndPhoneAppsAreNotFavorites() {
        val favorites = list()
        assertEquals(Result.NOT_ALLOWED, FavoriteEdits.add(favorites, Input("Radio", "https://player.local/radio")))
        assertEquals(Result.NOT_ALLOWED, FavoriteEdits.add(favorites, Input("App", "app://com.example.app")))
        assertEquals(Result.NOT_ALLOWED, FavoriteEdits.add(favorites, Input("Strano", "javascript:alert(1)")))
        assertEquals(Result.NOT_ALLOWED, FavoriteEdits.add(favorites, Input("Dentro", "player.local/radio")))
        assertEquals(Result.INVALID, FavoriteEdits.add(favorites, Input("Vuoto", "")))
        assertEquals(Result.INVALID, FavoriteEdits.add(favorites, Input("Senza sito", "https://")))
        assertEquals(3, favorites.size)
    }

    @Test
    fun aBlankNameFallsBackToTheAddress() {
        val favorites = list()
        FavoriteEdits.add(favorites, Input("  ", "example.com")) { "n" }
        assertEquals("https://example.com", favorites.last().name)
    }

    @Test
    fun zoomStaysInsideTheLimits() {
        val favorites = list()
        FavoriteEdits.add(favorites, Input("Z", "example.com", mobileZoom = 5f, desktopZoom = 0.01f)) { "z" }
        assertEquals(FavoriteEdits.MAX_ZOOM, favorites.last().mobileZoom)
        assertEquals(FavoriteEdits.MIN_ZOOM, favorites.last().desktopZoom)
    }

    @Test
    fun updatingChangesEverythingButAFixedFavoriteKeepsItsAddress() {
        val favorites = list()

        assertEquals(Result.OK, FavoriteEdits.update(favorites, "yt", Input("YT", "youtube.com/feed", 0xFF111111, null, false, 0.7f, null)))
        val yt = favorites[1]
        assertEquals("YT", yt.name)
        assertEquals("https://youtube.com/feed", yt.url)
        assertEquals(0xFF111111, yt.color)
        assertEquals(false, yt.isDesktopMode)
        assertEquals(0.7f, yt.mobileZoom)
        assertNull(yt.desktopZoom)

        // La pagina di CarWeb di serie cambia nome e aspetto, mai indirizzo
        assertEquals(Result.OK, FavoriteEdits.update(favorites, "media", Input("Musica", "https://example.com", 0xFF222222)))
        assertEquals("Musica", favorites[0].name)
        assertEquals("https://player.local/", favorites[0].url)
    }

    @Test
    fun updatingRefusesWhatAddingRefuses() {
        val favorites = list()
        assertEquals(Result.NOT_ALLOWED, FavoriteEdits.update(favorites, "yt", Input("YT", "https://player.local/tv")))
        assertEquals(Result.INVALID, FavoriteEdits.update(favorites, "yt", Input("YT", "")))
        assertEquals(Result.NOT_FOUND, FavoriteEdits.update(favorites, "nessuno", Input("X", "example.com")))
        assertEquals("https://m.youtube.com", favorites[1].url)
    }

    @Test
    fun aBlankNameKeepsTheOldOneOnUpdate() {
        val favorites = list()
        FavoriteEdits.update(favorites, "yt", Input(" ", "https://m.youtube.com"))
        assertEquals("YouTube", favorites[1].name)
    }

    @Test
    fun deletingKeepsTheFixedPageButAllowsItsCopies() {
        val favorites = list()
        assertEquals(Result.NOT_ALLOWED, FavoriteEdits.delete(favorites, "media"))
        assertEquals(Result.OK, FavoriteEdits.delete(favorites, "yt"))
        assertEquals(listOf("media", "wa"), favorites.map { it.id })
        assertEquals(Result.NOT_FOUND, FavoriteEdits.delete(favorites, "yt"))

        // Un doppione della pagina di CarWeb (la stella una volta li faceva) si toglie, l'originale no
        favorites.add(FavoriteSite("copy", "Media bis", "https://player.local"))
        assertEquals(Result.OK, FavoriteEdits.delete(favorites, "copy"))
        assertEquals(Result.NOT_ALLOWED, FavoriteEdits.delete(favorites, "media"))
    }

    @Test
    fun movingSwapsWithTheNeighbourAndStopsAtTheEdges() {
        val favorites = list()
        assertEquals(Result.OK, FavoriteEdits.move(favorites, "yt", 1))
        assertEquals(listOf("media", "wa", "yt"), favorites.map { it.id })
        assertEquals(Result.OK, FavoriteEdits.move(favorites, "yt", 1))
        assertEquals(listOf("media", "wa", "yt"), favorites.map { it.id })
        assertEquals(Result.OK, FavoriteEdits.move(favorites, "yt", -1))
        assertEquals(listOf("media", "yt", "wa"), favorites.map { it.id })
        assertEquals(Result.OK, FavoriteEdits.move(favorites, "media", -1))
        assertEquals(listOf("media", "yt", "wa"), favorites.map { it.id })
        assertEquals(Result.NOT_FOUND, FavoriteEdits.move(favorites, "nessuno", 1))
    }
}
