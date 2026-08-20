package com.fcaronte.aabrowser.model

import android.content.Context
import android.content.SharedPreferences

class FavoritesRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("BOOKMARKS", Context.MODE_PRIVATE)

    fun loadFavorites(): List<FavoriteSite> {
        val count = prefs.getInt("BookmarksCount", 0)
        val list = mutableListOf<FavoriteSite>()
        var migrationNeeded = false
        for (i in 0 until count) {
            val idFromPrefs = prefs.getString("BookmarkId$i", null)
            val id = idFromPrefs ?: java.util.UUID.randomUUID().toString()
            if (idFromPrefs == null) migrationNeeded = true
            
            val name = prefs.getString("BookmarkName$i", "") ?: ""
            val url = prefs.getString("BookmarkUrl$i", "") ?: ""
            val color = prefs.getLong("BookmarkColor$i", 0xFF2196F3)
            val faviconUrl = prefs.getString("BookmarkFavicon$i", null)
            list.add(FavoriteSite(id, name, url, color, faviconUrl))
        }

        if (migrationNeeded && list.isNotEmpty()) {
            saveFavorites(list)
        }

        // If empty, provide defaults
        if (list.isEmpty()) {
            val defaults = listOf(
                FavoriteSite("0", "Google", "https://www.google.com", 0xFF4285F4),
                FavoriteSite("1", "YouTube", "https://www.youtube.com", 0xFFFF0000),
                FavoriteSite("2", "Maps", "https://maps.google.com", 0xFF34A853),
                FavoriteSite("3", "News", "https://news.google.com", 0xFFFBBC05),
                FavoriteSite("4", "GitHub", "https://github.com", 0xFF24292E)
            )
            saveFavorites(defaults)
            return defaults
        }

        return list
    }

    fun saveFavorites(favorites: List<FavoriteSite>) {
        prefs.edit().apply {
            clear()
            putInt("BookmarksCount", favorites.size)
            favorites.forEachIndexed { i, site ->
                putString("BookmarkId$i", site.id)
                putString("BookmarkName$i", site.name)
                putString("BookmarkUrl$i", site.url)
                putLong("BookmarkColor$i", site.color)
                putString("BookmarkFavicon$i", site.faviconUrl)
            }
            apply()
        }
    }
}
