package com.fcaronte.aabrowser.model

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

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

    /** Le pagine di CarWeb e le app del telefono hanno già il loro posto (home, Altre App): non diventano un preferito. */
    fun canAdd(url: String): Boolean = FavoriteEdits.canAdd(url)

    /** Fisso (non si elimina né cambia indirizzo): solo la pagina di CarWeb vera, mai un suo doppione. */
    fun isFixed(site: FavoriteSite): Boolean = FavoritesRepository.isFixed(site, favorites)

    /** false se l'indirizzo non si può aggiungere ([canAdd]); un sito che c'è già non si ripete (true, niente da fare). */
    fun addFavorite(
        name: String,
        url: String,
        color: Long = 0xFF2196F3,
        faviconUrl: String? = null,
        isDesktopMode: Boolean? = null,
        mobileZoom: Float? = null,
        desktopZoom: Float? = null
    ): Boolean {
        // Le stesse regole della home della Wide (FavoriteEdits): né pagine di CarWeb né app, niente doppioni
        val result = FavoriteEdits.add(
            favorites,
            FavoriteEdits.Input(name, url, color, faviconUrl, isDesktopMode, mobileZoom, desktopZoom)
        )
        if (result == FavoriteEdits.Result.OK) repository.saveFavorites(favorites)
        return result == FavoriteEdits.Result.OK
    }

    fun removeFavorite(site: FavoriteSite) {
        if (FavoriteEdits.delete(favorites, site.id) == FavoriteEdits.Result.OK) repository.saveFavorites(favorites)
    }

    /** false se i dati non vanno (indirizzo non consentito o senza sito): il preferito resta com'era. */
    fun updateFavorite(
        site: FavoriteSite,
        name: String,
        url: String,
        color: Long,
        faviconUrl: String? = site.faviconUrl,
        isDesktopMode: Boolean? = site.isDesktopMode,
        mobileZoom: Float? = site.mobileZoom,
        desktopZoom: Float? = site.desktopZoom
    ): Boolean {
        // Un preferito fisso resta sul suo indirizzo (FavoriteEdits.update)
        val result = FavoriteEdits.update(
            favorites,
            site.id,
            FavoriteEdits.Input(name, url, color, faviconUrl, isDesktopMode, mobileZoom, desktopZoom)
        )
        if (result == FavoriteEdits.Result.OK) repository.saveFavorites(favorites)
        return result == FavoriteEdits.Result.OK
    }

    fun moveLeft(index: Int) = move(index, -1)

    fun moveRight(index: Int) = move(index, 1)

    private fun move(index: Int, direction: Int) {
        val id = favorites.getOrNull(index)?.id ?: return
        if (FavoriteEdits.move(favorites, id, direction) == FavoriteEdits.Result.OK) repository.saveFavorites(favorites)
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