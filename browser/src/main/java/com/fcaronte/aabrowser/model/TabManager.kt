package com.fcaronte.aabrowser.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import java.net.URI
import java.util.UUID

data class TabState(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String = "New Tab",
    val faviconUrl: String? = null,
    val isLoading: Boolean = false,
    val desktopModeOverride: Boolean? = null,
    val mobileZoomOverride: Float? = null,
    val desktopZoomOverride: Float? = null,
    val isWideTab: Boolean = false
)

object TabManager {
    val tabs = mutableStateListOf<TabState>()
    var activeTabIndex by mutableIntStateOf(-1)
        private set

    val activeTab: TabState?
        get() = if (activeTabIndex in tabs.indices) tabs[activeTabIndex] else null

    fun addTab(
        url: String,
        title: String = "New Tab",
        faviconUrl: String? = null,
        setActive: Boolean = true,
        desktopModeOverride: Boolean? = null,
        mobileZoomOverride: Float? = null,
        desktopZoomOverride: Float? = null
    ): TabState {
        val newTab = TabState(
            url = url,
            title = title,
            faviconUrl = faviconUrl,
            desktopModeOverride = desktopModeOverride,
            mobileZoomOverride = mobileZoomOverride,
            desktopZoomOverride = desktopZoomOverride
        )
        tabs.add(newTab)
        if (setActive) {
            activeTabIndex = tabs.size - 1
        }
        return newTab
    }

    /**
     * Cerca se esiste già una scheda con lo stesso dominio/host o URL.
     * Se esiste fa lo switch a quell'indice, altrimenti crea una nuova scheda.
     */
    fun openOrSwitchTo(
        url: String,
        title: String = "New Tab",
        faviconUrl: String? = null,
        desktopModeOverride: Boolean? = null,
        mobileZoomOverride: Float? = null,
        desktopZoomOverride: Float? = null
    ) {
        val existingIndex = findTabIndexByUrlOrHost(url)
        if (existingIndex != -1) {
            switchTab(existingIndex)
        } else {
            val current = activeTab
            if (current != null && current.url.isEmpty()) {
                updateTabUrl(current.id, url)
                updateTabTitle(current.id, title)
                if (faviconUrl != null) updateTabFavicon(current.id, faviconUrl)
            } else {
                addTab(
                    url = url,
                    title = title,
                    faviconUrl = faviconUrl,
                    setActive = true,
                    desktopModeOverride = desktopModeOverride,
                    mobileZoomOverride = mobileZoomOverride,
                    desktopZoomOverride = desktopZoomOverride
                )
            }
        }
    }

    /**
     * Precarica una lista di preferiti impostando subito i metadati noti (titolo, icona, zoom).
     */
    fun preloadFavorites(favorites: List<FavoriteSite>, limit: Int) {
        val sitesToPreload = favorites.take(limit)
        sitesToPreload.forEach { fav ->
            // Evita di creare duplicati se una scheda esiste già
            if (findTabIndexByUrlOrHost(fav.url) == -1) {
                addTab(
                    url = fav.url,
                    title = fav.name,
                    faviconUrl = fav.faviconUrl,
                    setActive = false,
                    desktopModeOverride = fav.isDesktopMode,
                    mobileZoomOverride = fav.mobileZoom,
                    desktopZoomOverride = fav.desktopZoom
                )
            }
        }
    }

    fun findTabIndexByUrlOrHost(targetUrl: String): Int {
        if (targetUrl.isBlank()) return -1
        val targetHost = extractHost(targetUrl)
        if (targetHost.isEmpty()) return -1
        
        return tabs.indexOfFirst { tab ->
            // La scheda "Wide:" rispecchia la Wide: aprire un sito dalla Main non deve finire su di essa
            !tab.isWideTab && (tab.url == targetUrl || (extractHost(tab.url) == targetHost))
        }
    }

    private fun extractHost(urlStr: String): String {
        return try {
            val uri = URI(urlStr)
            var host = uri.host ?: ""
            if (host.isEmpty() && urlStr.contains("://")) {
                // Fallback per URL malformati ma con schema
                host = urlStr.substringAfter("://").substringBefore("/")
            } else if (host.isEmpty()) {
                host = urlStr.substringBefore("/")
            }
            
            // Normalizzazione: rimuove www. e m. (mobile) per il matching intelligente
            host.lowercase()
                .removePrefix("www.")
                .removePrefix("m.")
                .removePrefix("mobile.")
        } catch (_: Exception) {
            urlStr.lowercase().removePrefix("www.").removePrefix("m.")
        }
    }

    fun updateOrAddWideTab(url: String, title: String = "Wide", faviconUrl: String? = null) {
        if (url.isBlank() || url.startsWith("https://home.local/") || url.startsWith("about:home")) {
            removeWideTab()
            return
        }

        val existingIndex = tabs.indexOfFirst { it.isWideTab }
        if (existingIndex != -1) {
            tabs[existingIndex] = tabs[existingIndex].copy(
                url = url,
                title = title.ifBlank { "Car Display" },
                faviconUrl = faviconUrl ?: tabs[existingIndex].faviconUrl
            )
        } else {
            val newTab = TabState(
                url = url,
                title = title.ifBlank { "Car Display" },
                faviconUrl = faviconUrl,
                isWideTab = true
            )
            tabs.add(newTab)
        }
    }

    fun removeWideTab() {
        val index = tabs.indexOfFirst { it.isWideTab }
        if (index != -1) {
            removeTabAt(index)
        }
    }

    fun closeTab(index: Int) {
        if (index in tabs.indices) {
            val tab = tabs[index]
            if (tab.isWideTab) {
                com.fcaronte.aabrowser.car.WideScreen.activeInstance?.navigateToHome()
            }
            removeTabAt(index)
        }
    }

    // La scheda attiva resta la stessa anche se cambia posizione; se si chiude proprio lei si passa
    // a quella alla sua sinistra (alla nuova prima scheda se era la prima)
    private fun removeTabAt(index: Int) {
        val wasActive = index == activeTabIndex
        tabs.removeAt(index)
        when {
            tabs.isEmpty() -> activeTabIndex = -1
            wasActive -> switchTab((index - 1).coerceAtLeast(0))
            index < activeTabIndex -> activeTabIndex--
        }
    }

    fun switchTab(index: Int) {
        if (index in tabs.indices) {
            val tab = tabs[index]
            if (tab.isWideTab) {
                // La scheda "Wide:" diventa una scheda normale della Main tenendo la sua WebView
                // (stesso id): niente chiusura né ricarica e la Wide resta sulla sua pagina.
                // Il player passerà a chi avvia la prossima riproduzione.
                tabs[index] = tab.copy(isWideTab = false)
            }
            activeTabIndex = index
        }
    }

    fun updateTabUrl(id: String, newUrl: String) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index != -1) {
            tabs[index] = tabs[index].copy(url = newUrl)
        }
    }

    fun updateTabTitle(id: String, newTitle: String) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index != -1 && newTitle.isNotBlank()) {
            tabs[index] = tabs[index].copy(title = newTitle)
        }
    }

    fun updateTabFavicon(id: String, faviconUrl: String) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index != -1 && faviconUrl.isNotBlank()) {
            tabs[index] = tabs[index].copy(faviconUrl = faviconUrl)
        }
    }

    fun closeAllTabs() {
        tabs.clear()
        activeTabIndex = -1
    }
}