package com.fcaronte.aabrowser.model

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import java.util.Collections

class FavoritesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FavoritesRepository(application)
    
    private val _favorites = mutableStateListOf<FavoriteSite>()
    val favorites: List<FavoriteSite> get() = _favorites

    init {
        _favorites.addAll(repository.loadFavorites())
    }

    fun addFavorite(name: String, url: String, color: Long = 0xFF2196F3, faviconUrl: String? = null) {
        val newSite = FavoriteSite(_favorites.size.toString(), name, url, color, faviconUrl)
        _favorites.add(newSite)
        repository.saveFavorites(_favorites)
    }

    fun removeFavorite(site: FavoriteSite) {
        _favorites.remove(site)
        repository.saveFavorites(_favorites)
    }

    fun updateFavorite(site: FavoriteSite, name: String, url: String, color: Long, faviconUrl: String? = site.faviconUrl) {
        val index = _favorites.indexOfFirst { it.id == site.id }
        if (index != -1) {
            _favorites[index] = site.copy(name = name, url = url, color = color, faviconUrl = faviconUrl)
            repository.saveFavorites(_favorites)
        }
    }

    fun moveFavorite(fromIndex: Int, toIndex: Int) {
        if (fromIndex in _favorites.indices && toIndex in _favorites.indices) {
            Collections.swap(_favorites, fromIndex, toIndex)
            repository.saveFavorites(_favorites)
        }
    }
}
