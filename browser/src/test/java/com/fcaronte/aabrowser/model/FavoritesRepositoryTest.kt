package com.fcaronte.aabrowser.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesRepositoryTest {
    @Test
    fun radioDefaultIsInsertedAfterMediaAndIptv() {
        val favorites = mutableListOf(
            FavoriteSite("a", "Google", "https://google.com"),
            FavoriteSite("b", "Media", "https://player.local/"),
            FavoriteSite("c", "IPTV", "https://player.local/tv"),
            FavoriteSite("d", "YouTube", "https://youtube.com"),
        )

        FavoritesRepository.insertDefaultFavorite(
            favorites,
            FavoriteSite("radio", "Radio", "https://player.local/radio"),
        )

        assertEquals(
            listOf(
                "https://google.com",
                "https://player.local/",
                "https://player.local/tv",
                "https://player.local/radio",
                "https://youtube.com",
            ),
            favorites.map { it.url },
        )
    }

    @Test
    fun podcastDefaultIsInsertedAfterRadio() {
        val favorites = mutableListOf(
            FavoriteSite("a", "Media", "https://player.local/"),
            FavoriteSite("b", "Radio", "https://player.local/radio"),
            FavoriteSite("c", "YouTube", "https://youtube.com"),
        )

        FavoritesRepository.insertDefaultFavorite(
            favorites,
            FavoriteSite("podcast", "Podcast", "https://player.local/podcast"),
        )

        assertEquals(
            listOf("https://player.local/", "https://player.local/radio", "https://player.local/podcast", "https://youtube.com"),
            favorites.map { it.url },
        )
    }

    @Test
    fun otherAppsDefaultIsInsertedAfterThePodcast() {
        val favorites = mutableListOf(
            FavoriteSite("a", "Media", "https://player.local/"),
            FavoriteSite("b", "Podcast", "https://player.local/podcast"),
            FavoriteSite("c", "YouTube", "https://youtube.com"),
        )

        FavoritesRepository.insertDefaultFavorite(
            favorites,
            FavoriteSite("apps", "Altre App", "https://player.local/apps"),
        )

        assertEquals(
            listOf("https://player.local/", "https://player.local/podcast", "https://player.local/apps", "https://youtube.com"),
            favorites.map { it.url },
        )
    }

    @Test
    fun missingDefaultsAreAddedOnceWithNewIds() {
        val favorites = mutableListOf(
            FavoriteSite("u1", "Mio", "https://example.com"),
            FavoriteSite("u2", "Media", "https://player.local/"),
        )
        val defaults = listOf(
            FavoriteSite("0", "Media", "https://player.local/"),
            FavoriteSite("2", "Radio", "https://player.local/radio"),
            // Stesso sito scritto in un altro modo (maiuscole, www, barra finale): c'è già
            FavoriteSite("3", "Mio", "HTTPS://www.Example.com/"),
        )

        val added = FavoritesRepository.addMissingDefaults(favorites, defaults)

        assertEquals(1, added)
        assertEquals(listOf("https://example.com", "https://player.local/", "https://player.local/radio"), favorites.map { it.url })
        // L'id del predefinito non si riusa: potrebbe scontrarsi con uno esistente
        assertNotEquals("2", favorites.last().id)
        // Una seconda volta non c'è più niente da aggiungere
        assertEquals(0, FavoritesRepository.addMissingDefaults(favorites, defaults))
    }

    @Test
    fun theOwnPagesOfCarWebAndPhoneAppsAreNotSites() {
        assertTrue(FavoritesRepository.isOwnPage("https://player.local/"))
        assertTrue(FavoritesRepository.isOwnPage("https://player.local/tv"))
        assertTrue(FavoritesRepository.isOwnPage("https://player.local/radio"))
        assertTrue(FavoritesRepository.isOwnPage("https://player.local/settings"))
        assertTrue(FavoritesRepository.isOwnPage("app://com.example.player"))
        assertFalse(FavoritesRepository.isOwnPage("https://example.com/"))
        assertFalse(FavoritesRepository.isOwnPage("https://www.youtube.com/tv"))
    }

    @Test
    fun onlyTheRealOwnPageIsFixedAndItsDuplicatesCanBeDeleted() {
        val real = FavoriteSite("1", "TV", "https://player.local/tv")
        val other = FavoriteSite("2", "Google", "https://google.com")
        // La stella aggiungeva la pagina anche da player.local: stesso indirizzo, scritto un po' diverso
        val duplicate = FavoriteSite("u1", "IPTV", "https://player.local/tv/")
        val third = FavoriteSite("u2", "TV", "HTTPS://player.local/tv")
        val list = listOf(real, other, duplicate, third)

        assertTrue("il vero resta fisso", FavoritesRepository.isFixed(real, list))
        assertFalse("un doppione si toglie", FavoritesRepository.isFixed(duplicate, list))
        assertFalse(FavoritesRepository.isFixed(third, list))
        assertFalse("un sito normale non è mai fisso", FavoritesRepository.isFixed(other, list))
        // Tolti i doppioni, il primo è ancora fisso
        assertTrue(FavoritesRepository.isFixed(real, listOf(real, other)))
        // Se il preferito vero non c'è più (un ripristino), il primo che rimane fa da vero: non si resta senza
        assertTrue(FavoritesRepository.isFixed(duplicate, listOf(other, duplicate, third)))
        assertFalse(FavoritesRepository.isFixed(third, listOf(other, duplicate, third)))
    }
}
