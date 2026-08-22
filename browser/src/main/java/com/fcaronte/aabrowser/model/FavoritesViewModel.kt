package com.fcaronte.aabrowser.model

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel

class FavoritesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FavoritesRepository(application)

    val favorites: List<FavoriteSite>
        field = mutableStateListOf<FavoriteSite>()

    init {
        favorites.addAll(repository.loadFavorites())
    }

    fun addFavorite(
        name: String,
        url: String,
        color: Long = 0xFF2196F3,
        faviconUrl: String? = null,
        isDesktopMode: Boolean? = null,
        mobileZoom: Float? = null,
        desktopZoom: Float? = null
    ) {
        val newSite = FavoriteSite(
            java.util.UUID.randomUUID().toString(),
            name,
            url,
            color,
            faviconUrl,
            isDesktopMode,
            mobileZoom,
            desktopZoom
        )
        favorites.add(newSite)
        repository.saveFavorites(favorites)
    }

    fun removeFavorite(site: FavoriteSite) {
        favorites.remove(site)
        repository.saveFavorites(favorites)
    }

    fun updateFavorite(
        site: FavoriteSite,
        name: String,
        url: String,
        color: Long,
        faviconUrl: String? = site.faviconUrl,
        isDesktopMode: Boolean? = site.isDesktopMode,
        mobileZoom: Float? = site.mobileZoom,
        desktopZoom: Float? = site.desktopZoom
    ) {
        val index = favorites.indexOfFirst { it.id == site.id }
        if (index != -1) {
            favorites[index] =
                site.copy(
                    name = name,
                    url = url,
                    color = color,
                    faviconUrl = faviconUrl,
                    isDesktopMode = isDesktopMode,
                    mobileZoom = mobileZoom,
                    desktopZoom = desktopZoom
                )
            repository.saveFavorites(favorites)
        }
    }

    fun moveFavorite(fromIndex: Int, toIndex: Int) {
        if (fromIndex in favorites.indices && toIndex in favorites.indices && fromIndex != toIndex) {
            val item = favorites.removeAt(fromIndex)
            favorites.add(toIndex, item)
            repository.saveFavorites(favorites)
        }
    }
}
