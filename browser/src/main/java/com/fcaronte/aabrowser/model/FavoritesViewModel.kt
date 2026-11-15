package com.fcaronte.aabrowser.model

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.UUID

class FavoritesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FavoritesRepository(application)

    val favorites: SnapshotStateList<FavoriteSite> = mutableStateListOf()

    init {
        favorites.addAll(repository.loadFavorites())
        // Cambiati dalla pagina delle impostazioni (ripristino, aggiunta dei predefiniti): la lista salvata è più nuova
        viewModelScope.launch {
            snapshotFlow { FavoritesRepository.externalChanges.value }.drop(1).collect {
                favorites.clear()
                favorites.addAll(repository.loadFavorites())
            }
        }
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
            id = UUID.randomUUID().toString(),
            name = name,
            url = url,
            color = color,
            faviconUrl = faviconUrl,
            isDesktopMode = isDesktopMode,
            mobileZoom = mobileZoom,
            desktopZoom = desktopZoom
        )
        favorites.add(newSite)
        repository.saveFavorites(favorites)
    }

    fun removeFavorite(site: FavoriteSite) {
        if (FavoritesRepository.isFixed(site)) return
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
            favorites[index] = site.copy(
                name = name,
                // Un preferito fisso resta sul suo indirizzo
                url = if (FavoritesRepository.isFixed(site)) site.url else url,
                color = color,
                faviconUrl = faviconUrl,
                isDesktopMode = isDesktopMode,
                mobileZoom = mobileZoom,
                desktopZoom = desktopZoom
            )
            repository.saveFavorites(favorites)
        }
    }

    fun moveLeft(index: Int) {
        if (index > 0 && index < favorites.size) {
            val item = favorites.removeAt(index)
            favorites.add(index - 1, item)
            repository.saveFavorites(favorites)
        }
    }

    fun moveRight(index: Int) {
        if (index >= 0 && index < favorites.size - 1) {
            val item = favorites.removeAt(index)
            favorites.add(index + 1, item)
            repository.saveFavorites(favorites)
        }
    }

    fun resetToDefaults() {
        val defaults = repository.resetToDefaults()
        favorites.clear()
        favorites.addAll(defaults)
    }

    fun addMissingDefaults(): Int {
        val addedCount = FavoritesRepository.addMissingDefaults(favorites, repository.getDefaultFavorites())
        if (addedCount > 0) {
            repository.saveFavorites(favorites)
        }
        return addedCount
    }
}