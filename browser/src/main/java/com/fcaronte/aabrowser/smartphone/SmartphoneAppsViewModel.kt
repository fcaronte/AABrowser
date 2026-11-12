package com.fcaronte.aabrowser.smartphone

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SmartphoneAppsViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "SmartphoneAppsViewModel"
    private val favoriteAppsManager = FavoriteAppsManager(application)

    private val _allApps = MutableStateFlow<List<SmartphoneApp>>(emptyList())
    private val _filteredApps = MutableStateFlow<List<SmartphoneApp>>(emptyList())
    val filteredApps: StateFlow<List<SmartphoneApp>> = _filteredApps.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _favoritePackages = MutableStateFlow<Set<String>>(emptySet())
    val favoritePackages: StateFlow<Set<String>> = _favoritePackages.asStateFlow()

    init {
        loadInstalledApps()
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                favoriteAppsManager.getInstalledApps()
            }
            val favs = withContext(Dispatchers.IO) {
                favoriteAppsManager.loadFavoritePackageNames().toSet()
            }
            _allApps.value = apps
            _favoritePackages.value = favs
            filterApps(_searchQuery.value)
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        filterApps(query)
    }

    private fun filterApps(query: String) {
        val trimmed = query.trim()
        val all = _allApps.value
        if (trimmed.isEmpty()) {
            _filteredApps.value = all
        } else {
            _filteredApps.value = all.filter {
                it.appName.contains(trimmed, ignoreCase = true) ||
                it.packageName.contains(trimmed, ignoreCase = true)
            }
        }
    }

    fun toggleFavorite(packageName: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                favoriteAppsManager.toggleFavorite(packageName)
            }
            val favs = withContext(Dispatchers.IO) {
                favoriteAppsManager.loadFavoritePackageNames().toSet()
            }
            _favoritePackages.value = favs
        }
    }

    fun launchAppDirectly(packageName: String) {
        try {
            val context = getApplication<Application>()
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else {
                Log.w(TAG, "No launch intent found for $packageName")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error launching app $packageName", e)
        }
    }
}
