package com.fcaronte.aabrowser.model

import org.junit.Assert.assertEquals
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
}
