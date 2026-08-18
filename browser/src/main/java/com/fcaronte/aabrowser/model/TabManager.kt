package com.fcaronte.aabrowser.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import java.util.UUID

data class TabState(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    var title: String = "New Tab",
    val faviconUrl: String? = null,
    val isLoading: Boolean = false
)

object TabManager {
    val tabs = mutableStateListOf<TabState>()
    var activeTabIndex by mutableIntStateOf(-1)
        private set

    val activeTab: TabState?
        get() = if (activeTabIndex in tabs.indices) tabs[activeTabIndex] else null

    fun addTab(url: String, setActive: Boolean = true) {
        val newTab = TabState(url = url)
        tabs.add(newTab)
        if (setActive) {
            activeTabIndex = tabs.size - 1
        }
    }

    fun closeTab(index: Int) {
        if (index in tabs.indices) {
            tabs.removeAt(index)
            if (activeTabIndex >= tabs.size) {
                activeTabIndex = tabs.size - 1
            }
            if (tabs.isEmpty()) {
                activeTabIndex = -1
            }
        }
    }

    fun switchTab(index: Int) {
        if (index in tabs.indices) {
            activeTabIndex = index
        }
    }

    fun updateTabUrl(id: String, newUrl: String) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index != -1) {
            tabs[index] = tabs[index].copy(url = newUrl)
        }
    }

    // Aggiunta questa funzione per aggiornare il titolo della pagina dinamicamente
    fun updateTabTitle(id: String, newTitle: String) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index != -1 && newTitle.isNotBlank()) {
            // Aggiorniamo direttamente la proprietà o riassegnamo l'elemento nella lista osservabile
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
