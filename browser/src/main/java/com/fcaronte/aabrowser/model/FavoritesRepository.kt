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

            val isDesktopMode = if (prefs.contains("BookmarkDesktop$i")) prefs.getBoolean("BookmarkDesktop$i", false) else null
            val mobileZoom = if (prefs.contains("BookmarkMobileZoom$i")) prefs.getFloat("BookmarkMobileZoom$i", 1.0f) else null
            val desktopZoom = if (prefs.contains("BookmarkDesktopZoom$i")) prefs.getFloat("BookmarkDesktopZoom$i", 1.0f) else null

            list.add(FavoriteSite(id, name, url, color, faviconUrl, isDesktopMode, mobileZoom, desktopZoom))
        }

        if (migrationNeeded && list.isNotEmpty()) {
            saveFavorites(list)
        }

        // If empty, provide defaults
        if (list.isEmpty()) {
            val defaults = listOf(
                FavoriteSite("0", "Google", "https://www.google.com", 0xFF4285F4),
                FavoriteSite("1", "YouTube", "https://www.youtube.com", 0xFFFF0000),
                FavoriteSite("2", "YouTube Music", "https://music.youtube.com", 0xFFFF0000),
                FavoriteSite("3", "WhatsApp", "https://web.whatsapp.com", 0xFF34A853)
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
                site.isDesktopMode?.let { putBoolean("BookmarkDesktop$i", it) }
                site.mobileZoom?.let { putFloat("BookmarkMobileZoom$i", it) }
                site.desktopZoom?.let { putFloat("BookmarkDesktopZoom$i", it) }
            }
            apply()
        }
    }
}
