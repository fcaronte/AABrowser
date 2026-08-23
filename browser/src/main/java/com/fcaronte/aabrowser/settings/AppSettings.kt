package com.fcaronte.aabrowser.settings

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import com.fcaronte.aabrowser.R

enum class ThemeMode {
    LIGHT, DARK, AMOLED
}

enum class FABLocation {
    BOTTOM_RIGHT, BOTTOM_LEFT, TOP_RIGHT, TOP_LEFT
}

enum class FABBehavior {
    OPEN_MENU, JUMP_TO_URL
}

enum class TabBarMode {
    OFF, AUTO_HIDE, ALWAYS_ON
}

enum class SearchEngine(val baseUrlRes: Int, val homeUrlRes: Int) {
    GOOGLE(R.string.google_base_url, R.string.google_home_url),
    DUCKDUCKGO(R.string.duckduckgo_base_url, R.string.duckduckgo_home_url),
    BING(R.string.bing_base_url, R.string.bing_home_url),
    YAHOO(R.string.yahoo_base_url, R.string.yahoo_home_url)
}

object AppSettings {
    private const val PREFS_NAME = "aa_browser_settings"

    // Se false segue il sistema, se true forza la modalità manuale (ThemeMode)
    private val _forceTheme = mutableStateOf(false)
    val forceTheme: State<Boolean> = _forceTheme

    private val _themeMode = mutableStateOf(ThemeMode.AMOLED)
    val themeMode: State<ThemeMode> = _themeMode

    private val _dynamicColor = mutableStateOf(true)
    val dynamicColor: State<Boolean> = _dynamicColor

    private val _darkPages = mutableStateOf(false)
    val darkPages: State<Boolean> = _darkPages

    // Autoplay media all'avvio
    private val _autoplayMedia = mutableStateOf(false)
    val autoplayMedia: State<Boolean> = _autoplayMedia

    // Precaricamento schede preferiti all'avvio
    private val _preloadFavorites = mutableStateOf(false)
    val preloadFavorites: State<Boolean> = _preloadFavorites

    private val _preloadFavoritesCount = mutableIntStateOf(4)
    val preloadFavoritesCount: State<Int> = _preloadFavoritesCount

    private val _resumeLastPage = mutableStateOf(true)
    val resumeLastPage: State<Boolean> = _resumeLastPage

    private val _restoreLastTabs = mutableStateOf(false)
    val restoreLastTabs: State<Boolean> = _restoreLastTabs

    private val _persistentUrlBar = mutableStateOf(false)
    val persistentUrlBar: State<Boolean> = _persistentUrlBar

    private val _fabLocation = mutableStateOf(FABLocation.BOTTOM_RIGHT)
    val fabLocation: State<FABLocation> = _fabLocation

    private val _fabBehavior = mutableStateOf(FABBehavior.OPEN_MENU)
    val fabBehavior: State<FABBehavior> = _fabBehavior

    private val _displayScale = mutableFloatStateOf(1.0f)
    val displayScale: State<Float> = _displayScale

    private val _desktopMode = mutableStateOf(false)
    val desktopMode: State<Boolean> = _desktopMode

    private val _desktopScale = mutableFloatStateOf(1.0f)
    val desktopScale: State<Float> = _desktopScale

    private val _lastUrl = mutableStateOf("")
    val lastUrl: State<String> = _lastUrl

    private val _dashboardThreeColumns = mutableStateOf(false)
    val dashboardThreeColumns: State<Boolean> = _dashboardThreeColumns

    private val _forceEnglish = mutableStateOf(false)
    val forceEnglish: State<Boolean> = _forceEnglish

    // Se false usa Google di default, se true usa il provider personalizzato scelto in searchEngine
    private val _customSearchEngine = mutableStateOf(false)
    val customSearchEngine: State<Boolean> = _customSearchEngine

    private val _searchEngine = mutableStateOf(SearchEngine.GOOGLE)
    val searchEngine: State<SearchEngine> = _searchEngine

    val effectiveSearchEngine: SearchEngine
        get() = if (_customSearchEngine.value) _searchEngine.value else SearchEngine.GOOGLE

    private val _uiScale = mutableFloatStateOf(1.0f)
    val uiScale: State<Float> = _uiScale

    private val _autoOpenFavoriteId = mutableStateOf<String?>(null)
    val autoOpenFavoriteId: State<String?> = _autoOpenFavoriteId

    private val _persistentNavigation = mutableStateOf(false)
    val persistentNavigation: State<Boolean> = _persistentNavigation

    private val _persistentTabBar = mutableStateOf(false)
    val persistentTabBar: State<Boolean> = _persistentTabBar

    private val _tabBarMode = mutableStateOf(TabBarMode.OFF)
    val tabBarMode: State<TabBarMode> = _tabBarMode

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _forceTheme.value = prefs.getBoolean("force_theme", false)
        _themeMode.value = ThemeMode.valueOf(
            prefs.getString("theme_mode", ThemeMode.AMOLED.name) ?: ThemeMode.AMOLED.name
        )
        _dynamicColor.value = prefs.getBoolean("dynamic_color", true)
        _darkPages.value = prefs.getBoolean("dark_pages", false)
        _autoplayMedia.value = prefs.getBoolean("autoplay_media", false)
        _preloadFavorites.value = prefs.getBoolean("preload_favorites", false)
        _preloadFavoritesCount.intValue = prefs.getInt("preload_favorites_count", 4)
        _resumeLastPage.value = prefs.getBoolean("resume_last_page", true)
        _restoreLastTabs.value = prefs.getBoolean("restore_last_tabs", false)
        _persistentUrlBar.value = prefs.getBoolean("persistent_url_bar", false)
        _fabLocation.value = FABLocation.valueOf(
            prefs.getString("fab_location", FABLocation.BOTTOM_RIGHT.name)
                ?: FABLocation.BOTTOM_RIGHT.name
        )
        _fabBehavior.value = FABBehavior.valueOf(
            prefs.getString("fab_behavior", FABBehavior.OPEN_MENU.name)
                ?: FABBehavior.OPEN_MENU.name
        )
        _displayScale.floatValue = prefs.getFloat("display_scale", 1.0f)
        _desktopMode.value = prefs.getBoolean("desktop_mode", false)
        _desktopScale.floatValue = prefs.getFloat("desktop_scale", 1.0f)
        _lastUrl.value = prefs.getString("last_url", "") ?: ""
        _dashboardThreeColumns.value = prefs.getBoolean("dashboard_three_columns", false)
        _forceEnglish.value = prefs.getBoolean("force_english", false)
        _customSearchEngine.value = prefs.getBoolean("custom_search_engine", false)
        _searchEngine.value = SearchEngine.valueOf(
            prefs.getString("search_engine", SearchEngine.GOOGLE.name) ?: SearchEngine.GOOGLE.name
        )
        _uiScale.floatValue = prefs.getFloat("ui_scale", 1.0f)
        _autoOpenFavoriteId.value = prefs.getString("auto_open_favorite_id", null)
        _persistentNavigation.value = prefs.getBoolean("persistent_navigation", false)
        _persistentTabBar.value = prefs.getBoolean("persistent_tab_bar", false)
        _tabBarMode.value = TabBarMode.valueOf(
            prefs.getString("tab_bar_mode", TabBarMode.OFF.name) ?: TabBarMode.OFF.name
        )

        updateLocale()
    }

    fun setForceTheme(context: Context, enabled: Boolean) {
        _forceTheme.value = enabled
        saveBoolean(context, "force_theme", enabled)
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        _themeMode.value = mode
        saveString(context, "theme_mode", mode.name)
    }

    fun setDynamicColor(context: Context, enabled: Boolean) {
        _dynamicColor.value = enabled
        saveBoolean(context, "dynamic_color", enabled)
    }

    fun setAutoplayMedia(context: Context, enabled: Boolean) {
        _autoplayMedia.value = enabled
        saveBoolean(context, "autoplay_media", enabled)
    }

    fun setDarkPages(context: Context, enabled: Boolean) {
        _darkPages.value = enabled
        saveBoolean(context, "dark_pages", enabled)
    }

    fun setPreloadFavorites(context: Context, enabled: Boolean) {
        _preloadFavorites.value = enabled
        saveBoolean(context, "preload_favorites", enabled)
    }

    fun setPreloadFavoritesCount(context: Context, count: Int) {
        _preloadFavoritesCount.intValue = count
        saveInt(context, "preload_favorites_count", count)
    }

    private fun saveInt(context: Context, key: String, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(key, value)
        }
    }

    fun setDisplayScale(context: Context, scale: Float) {
        _displayScale.floatValue = scale
        saveFloat(context, "display_scale", scale)
    }

    fun setDesktopMode(context: Context, enabled: Boolean) {
        _desktopMode.value = enabled
        saveBoolean(context, "desktop_mode", enabled)
    }

    fun setDesktopScale(context: Context, scale: Float) {
        _desktopScale.floatValue = scale
        saveFloat(context, "desktop_scale", scale)
    }

    fun setLastUrl(context: Context, url: String) {
        _lastUrl.value = url
        saveString(context, "last_url", url)
    }

    fun setDashboardThreeColumns(context: Context, enabled: Boolean) {
        _dashboardThreeColumns.value = enabled
        saveBoolean(context, "dashboard_three_columns", enabled)
    }

    fun setForceEnglish(context: Context, enabled: Boolean) {
        _forceEnglish.value = enabled
        saveBoolean(context, "force_english", enabled)
        updateLocale()
    }

    fun setCustomSearchEngine(context: Context, enabled: Boolean) {
        _customSearchEngine.value = enabled
        saveBoolean(context, "custom_search_engine", enabled)
    }

    fun setSearchEngine(context: Context, engine: SearchEngine) {
        _searchEngine.value = engine
        saveString(context, "search_engine", engine.name)
    }

    fun setUiScale(context: Context, scale: Float) {
        _uiScale.floatValue = scale
        saveFloat(context, "ui_scale", scale)
    }

    fun setAutoOpenFavoriteId(context: Context, id: String?) {
        _autoOpenFavoriteId.value = id
        saveStringOrNull(context, "auto_open_favorite_id", id)
    }

    fun setPersistentNavigation(context: Context, enabled: Boolean) {
        _persistentNavigation.value = enabled
        saveBoolean(context, "persistent_navigation", enabled)
    }

    fun setPersistentTabBar(context: Context, enabled: Boolean) {
        _persistentTabBar.value = enabled
        saveBoolean(context, "persistent_tab_bar", enabled)
    }

    fun setTabBarMode(context: Context, mode: TabBarMode) {
        _tabBarMode.value = mode
        saveString(context, "tab_bar_mode", mode.name)
    }

    fun clearCache(context: Context) {
        try {
            // 1. Pulizia standard API WebView: rimuove file HTML/immagini temporanei
            // NON tocca cookie, localStorage o IndexedDB se non diversamente specificato
            val webView = WebView(context)
            webView.clearCache(true)
            webView.destroy()

            // 2. Pulizia manuale sicura delle sole cartelle di cache di Chromium
            val appDir = java.io.File(context.applicationInfo.dataDir)
            val webViewDir = java.io.File(appDir, "app_webview")

            if (webViewDir.exists()) {
                // Elenchiamo solo le cartelle di cache pura, escludendo database e cookie
                val pureCacheDirs = listOf(
                    "Cache", "Code Cache", "GPUCache", "ShaderCache", "GrShaderCache",
                    "Default/Cache", "Default/Code Cache", "Default/GPUCache", "Default/Service Worker/CacheStorage",
                    "blob_storage"
                )

                for (subDir in pureCacheDirs) {
                    val target = java.io.File(webViewDir, subDir)
                    if (target.exists()) {
                        target.deleteRecursively()
                    }
                }
            }

            // 3. Pulisce la cache standard di Android dell'app
            context.cacheDir?.let { cache ->
                if (cache.exists()) {
                    cache.listFiles()?.forEach { it.deleteRecursively() }
                }
            }
            context.externalCacheDir?.let { cache ->
                if (cache.exists()) {
                    cache.listFiles()?.forEach { it.deleteRecursively() }
                }
            }
            context.codeCacheDir?.let { cache ->
                if (cache.exists()) {
                    cache.listFiles()?.forEach { it.deleteRecursively() }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AppSettings", "Error clearing cache", e)
        }
    }

    fun clearBrowserData(context: Context) {
        clearCache(context)
        clearCookies()
    }
    fun clearCookies() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            android.util.Log.e("AppSettings", "Error clearing cookies", e)
        }
    }

    private fun updateLocale() {
        val languageCode = if (_forceEnglish.value) "en" else null
        val localeList = if (languageCode != null) {
            androidx.core.os.LocaleListCompat.forLanguageTags(languageCode)
        } else {
            androidx.core.os.LocaleListCompat.getEmptyLocaleList()
        }
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(localeList)
    }

    private fun saveString(context: Context, key: String, value: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(key, value)
        }
    }

    private fun saveStringOrNull(context: Context, key: String, value: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(key, value)
        }
    }

    private fun saveBoolean(context: Context, key: String, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(key, value)
        }
    }

    private fun saveFloat(context: Context, key: String, value: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putFloat(key, value)
        }
    }
}