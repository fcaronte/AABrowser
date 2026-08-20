package com.fcaronte.aabrowser.settings

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

enum class ThemeMode {
    LIGHT, DARK, AMOLED
}

enum class FABLocation {
    BOTTOM_RIGHT, BOTTOM_LEFT, TOP_RIGHT, TOP_LEFT
}

enum class FABBehavior {
    OPEN_MENU, JUMP_TO_URL
}

enum class SearchEngine(val baseUrl: String) {
    GOOGLE("https://www.google.com/search?q="),
    DUCKDUCKGO("https://duckduckgo.com/?q="),
    BING("https://www.bing.com/search?q="),
    YAHOO("https://search.yahoo.com/search?p=")
}

object AppSettings {
    private const val PREFS_NAME = "aa_browser_settings"

    private val _themeMode = mutableStateOf(ThemeMode.AMOLED)
    val themeMode: State<ThemeMode> = _themeMode

    // NUOVO: Toggle per il Material You (Dynamic Color)
    private val _dynamicColor = mutableStateOf(true)
    val dynamicColor: State<Boolean> = _dynamicColor

    private val _darkPages = mutableStateOf(false)
    val darkPages: State<Boolean> = _darkPages

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

    private val _displayScale = mutableStateOf(1.0f)
    val displayScale: State<Float> = _displayScale

    private val _desktopMode = mutableStateOf(false)
    val desktopMode: State<Boolean> = _desktopMode

    private val _desktopScale = mutableStateOf(1.0f)
    val desktopScale: State<Float> = _desktopScale

    private val _lastUrl = mutableStateOf("")
    val lastUrl: State<String> = _lastUrl

    private val _dashboardThreeColumns = mutableStateOf(false)
    val dashboardThreeColumns: State<Boolean> = _dashboardThreeColumns

    private val _forceEnglish = mutableStateOf(false)
    val forceEnglish: State<Boolean> = _forceEnglish

    private val _searchEngine = mutableStateOf(SearchEngine.GOOGLE)
    val searchEngine: State<SearchEngine> = _searchEngine

    private val _uiScale = mutableStateOf(1.0f)
    val uiScale: State<Float> = _uiScale

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _themeMode.value = ThemeMode.valueOf(
            prefs.getString("theme_mode", ThemeMode.AMOLED.name) ?: ThemeMode.AMOLED.name
        )
        _dynamicColor.value = prefs.getBoolean("dynamic_color", true) // Default attivo
        _darkPages.value = prefs.getBoolean("dark_pages", false)
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
        _displayScale.value = prefs.getFloat("display_scale", 1.0f)
        _desktopMode.value = prefs.getBoolean("desktop_mode", false)
        _desktopScale.value = prefs.getFloat("desktop_scale", 1.0f)
        _lastUrl.value = prefs.getString("last_url", "") ?: ""
        _dashboardThreeColumns.value = prefs.getBoolean("dashboard_three_columns", false)
        _forceEnglish.value = prefs.getBoolean("force_english", false)
        _searchEngine.value = SearchEngine.valueOf(
            prefs.getString("search_engine", SearchEngine.GOOGLE.name) ?: SearchEngine.GOOGLE.name
        )
        _uiScale.value = prefs.getFloat("ui_scale", 1.0f)

        updateLocale()
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        _themeMode.value = mode
        saveString(context, "theme_mode", mode.name)
    }

    // NUOVO: Setter per il Dynamic Color
    fun setDynamicColor(context: Context, enabled: Boolean) {
        _dynamicColor.value = enabled
        saveBoolean(context, "dynamic_color", enabled)
    }

    fun setDarkPages(context: Context, enabled: Boolean) {
        _darkPages.value = enabled
        saveBoolean(context, "dark_pages", enabled)
    }

    fun setDisplayScale(context: Context, scale: Float) {
        _displayScale.value = scale
        saveFloat(context, "display_scale", scale)
    }

    fun setDesktopMode(context: Context, enabled: Boolean) {
        _desktopMode.value = enabled
        saveBoolean(context, "desktop_mode", enabled)
    }

    fun setDesktopScale(context: Context, scale: Float) {
        _desktopScale.value = scale
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

    fun setSearchEngine(context: Context, engine: SearchEngine) {
        _searchEngine.value = engine
        saveString(context, "search_engine", engine.name)
    }

    fun setUiScale(context: Context, scale: Float) {
        _uiScale.value = scale
        saveFloat(context, "ui_scale", scale)
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
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(key, value)
            .apply()
    }

    private fun saveBoolean(context: Context, key: String, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(key, value)
            .apply()
    }

    private fun saveFloat(context: Context, key: String, value: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(key, value)
            .apply()
    }
}