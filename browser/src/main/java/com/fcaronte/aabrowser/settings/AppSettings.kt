package com.fcaronte.aabrowser.settings

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import com.fcaronte.aabrowser.CarService
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.utils.AppLog
import com.fcaronte.aabrowser.utils.CarShareTarget
import com.fcaronte.aabrowser.utils.DrivingState
import com.fcaronte.aabrowser.utils.SponsorBlock

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

    private val _forceMaxYouTubeQuality = mutableStateOf(false)
    val forceMaxYouTubeQuality: State<Boolean> = _forceMaxYouTubeQuality

    // SponsorBlock su YouTube (acceso) e quanto saltare (SponsorBlock.LEVEL_…)
    private val _sponsorBlock = mutableStateOf(true)
    val sponsorBlock: State<Boolean> = _sponsorBlock
    private val _sponsorBlockLevel = mutableStateOf(SponsorBlock.LEVEL_DEFAULT)
    val sponsorBlockLevel: State<String> = _sponsorBlockLevel

    // Qualità massima di YouTube con forceMaxYouTubeQuality attiva: livello del player (hd1080, hd1440, ...)
    const val DEFAULT_YOUTUBE_QUALITY = "hd1440"
    private val _youtubeQuality = mutableStateOf(DEFAULT_YOUTUBE_QUALITY)
    val youtubeQuality: State<String> = _youtubeQuality

    // Livelli offerti nelle impostazioni, con l'etichetta mostrata (uguale in tutte le lingue)
    val youtubeQualityOptions = listOf(
        "large" to "480p",
        "hd720" to "720p",
        "hd1080" to "1080p",
        "hd1440" to "1440p",
        "hd2160" to "2160p (4K)"
    )

    // Precaricamento schede preferiti all'avvio
    private val _preloadFavorites = mutableStateOf(false)
    val preloadFavorites: State<Boolean> = _preloadFavorites

    private val _preloadFavoritesCount = mutableIntStateOf(4)
    val preloadFavoritesCount: State<Int> = _preloadFavoritesCount

    private val _wideReopenLastPage = mutableStateOf(false)
    val wideReopenLastPage: State<Boolean> = _wideReopenLastPage

    private val _wideSidebarFixed = mutableStateOf(true)
    val wideSidebarFixed: State<Boolean> = _wideSidebarFixed

    // Wide, video a schermo intero con il menu fisso: true = il menu sta sotto il video e compare con i comandi
    // ("Menu al tocco" nella barra del video), false = il video si ferma al bordo del menu
    private val _wideVideoMenuOnTap = mutableStateOf(false)
    val wideVideoMenuOnTap: State<Boolean> = _wideVideoMenuOnTap

    // Dimensione del menu fisso della Wide, in percentuale: con schermi piccoli occupa troppo spazio
    val wideSidebarSizes = listOf(60, 70, 80, 90, 100, 115, 130)
    private val _wideSidebarSize = mutableStateOf(100)
    val wideSidebarSize: State<Int> = _wideSidebarSize

    private val _fabLocation = mutableStateOf(FABLocation.BOTTOM_RIGHT)
    val fabLocation: State<FABLocation> = _fabLocation

    private val _displayScale = mutableFloatStateOf(1.0f)
    val displayScale: State<Float> = _displayScale

    private val _desktopMode = mutableStateOf(false)
    val desktopMode: State<Boolean> = _desktopMode

    private val _desktopScale = mutableFloatStateOf(1.0f)
    val desktopScale: State<Float> = _desktopScale

    private val _lastUrl = mutableStateOf("")
    val lastUrl: State<String> = _lastUrl

    private val _wideLastUrl = mutableStateOf("")
    val wideLastUrl: State<String> = _wideLastUrl

    private val _dashboardThreeColumns = mutableStateOf(false)
    val dashboardThreeColumns: State<Boolean> = _dashboardThreeColumns

    private val _openDashboardOnNewTab = mutableStateOf(false)
    val openDashboardOnNewTab: State<Boolean> = _openDashboardOnNewTab

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

    private val _tabBarMode = mutableStateOf(TabBarMode.OFF)
    val tabBarMode: State<TabBarMode> = _tabBarMode

    private val _weatherWidgetEnabled = mutableStateOf(false)
    val weatherWidgetEnabled: State<Boolean> = _weatherWidgetEnabled

    private val _mediaPlayerEnabled = mutableStateOf(true)
    val mediaPlayerEnabled: State<Boolean> = _mediaPlayerEnabled

    // Tocco sulla scheda del player di Android Auto: apre la Main invece della Wide (WideSession)
    private val _playerCardOpensMain = mutableStateOf(false)
    val playerCardOpensMain: State<Boolean> = _playerCardOpensMain

    // Video singolo di YouTube sul player di Android Auto: false = -10/+10 s, true = video precedente/successivo
    private val _youtubeVideoSkip = mutableStateOf(false)
    val youtubeVideoSkip: State<Boolean> = _youtubeVideoSkip

    // "Condividi su CarWeb": la voce nel menu Condividi del telefono (CarShareTarget), di serie solo con l'auto collegata
    private val _shareToCarWeb = mutableStateOf(true)
    val shareToCarWeb: State<Boolean> = _shareToCarWeb
    private val _shareAlways = mutableStateOf(false)
    val shareAlways: State<Boolean> = _shareAlways

    private val _multiWindow = mutableStateOf(false)
    val multiWindow: State<Boolean> = _multiWindow

    private val _onboardingCompleted = mutableStateOf(false)
    val onboardingCompleted: State<Boolean> = _onboardingCompleted

    // Microfono, posizione e file del telefono a ogni sito senza chiedere (come prima della 2.5); spento: ogni sito
    // chiede e la scelta resta per quel sito (SitePermissions)
    private val _autoSitePermissions = mutableStateOf(false)
    val autoSitePermissions: State<Boolean> = _autoSitePermissions

    // Sullo schermo dell'auto in movimento l'app va solo con questa accesa (se l'auto si muove lo dice Android Auto,
    // DrivingState): pensata per chi viaggia accanto a chi guida
    private val _passengerMode = mutableStateOf(false)
    val passengerMode: State<Boolean> = _passengerMode

    // Tastiera QWERTY dell'app al posto di quella di Android Auto, che in movimento si blocca
    private val _passengerKeyboard = mutableStateOf(false)
    val passengerKeyboard: State<Boolean> = _passengerKeyboard

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _forceTheme.value = prefs.getBoolean("force_theme", false)
        _themeMode.value = ThemeMode.valueOf(
            prefs.getString("theme_mode", ThemeMode.AMOLED.name) ?: ThemeMode.AMOLED.name
        )
        _dynamicColor.value = prefs.getBoolean("dynamic_color", true)
        _darkPages.value = prefs.getBoolean("dark_pages", false)
        _autoplayMedia.value = prefs.getBoolean("autoplay_media", false)
        _forceMaxYouTubeQuality.value = prefs.getBoolean("force_max_youtube_quality", false)
        _sponsorBlock.value = prefs.getBoolean("sponsorblock", true)
        _sponsorBlockLevel.value = prefs.getString("sponsorblock_level", null)
            ?.takeIf { it in SponsorBlock.LEVELS } ?: SponsorBlock.LEVEL_DEFAULT
        _youtubeQuality.value = prefs.getString("youtube_quality", DEFAULT_YOUTUBE_QUALITY)
            ?.takeIf { level -> youtubeQualityOptions.any { it.first == level } }
            ?: DEFAULT_YOUTUBE_QUALITY
        _preloadFavorites.value = prefs.getBoolean("preload_favorites", false)
        _preloadFavoritesCount.intValue = prefs.getInt("preload_favorites_count", 4)
        _wideReopenLastPage.value = prefs.getBoolean("reopen_last_page", false)
        _wideSidebarFixed.value = prefs.getBoolean("wide_sidebar_fixed", true)
        _wideVideoMenuOnTap.value = prefs.getBoolean("wide_video_menu_on_tap", false)
        _wideSidebarSize.value = prefs.getInt("wide_sidebar_size", 100).takeIf { it in wideSidebarSizes } ?: 100
        _fabLocation.value = FABLocation.valueOf(
            prefs.getString("fab_location", FABLocation.BOTTOM_RIGHT.name)
                ?: FABLocation.BOTTOM_RIGHT.name
        )
        _displayScale.floatValue = prefs.getFloat("display_scale", 1.0f)
        _desktopMode.value = prefs.getBoolean("desktop_mode", false)
        _desktopScale.floatValue = prefs.getFloat("desktop_scale", 1.0f)
        _lastUrl.value = prefs.getString("last_url", "") ?: ""
        _wideLastUrl.value = prefs.getString("wide_last_url", "") ?: ""
        _dashboardThreeColumns.value = prefs.getBoolean("dashboard_three_columns", false)
        _openDashboardOnNewTab.value = prefs.getBoolean("open_dashboard_on_new_tab", false)
        _forceEnglish.value = prefs.getBoolean("force_english", false)
        _customSearchEngine.value = prefs.getBoolean("custom_search_engine", false)
        _searchEngine.value = SearchEngine.valueOf(
            prefs.getString("search_engine", SearchEngine.GOOGLE.name) ?: SearchEngine.GOOGLE.name
        )
        _uiScale.floatValue = prefs.getFloat("ui_scale", 1.0f)
        _autoOpenFavoriteId.value = prefs.getString("auto_open_favorite_id", null)
        _persistentNavigation.value = prefs.getBoolean("persistent_navigation", false)
        _tabBarMode.value = TabBarMode.valueOf(
            prefs.getString("tab_bar_mode", TabBarMode.OFF.name) ?: TabBarMode.OFF.name
        )
        _weatherWidgetEnabled.value = prefs.getBoolean("weather_widget_enabled", false)
        _mediaPlayerEnabled.value = prefs.getBoolean("media_player_enabled", true)
        _playerCardOpensMain.value = prefs.getBoolean("player_card_opens_main", false)
        _youtubeVideoSkip.value = prefs.getBoolean("youtube_video_skip", false)
        _shareToCarWeb.value = prefs.getBoolean("share_to_carweb", true)
        _shareAlways.value = prefs.getBoolean("share_always", false)
        restoreMainAfterMediaAppTest(context)
        _multiWindow.value = prefs.getBoolean("multi_window", false)
        _onboardingCompleted.value = prefs.getBoolean("onboarding_completed", false)
        _autoSitePermissions.value = prefs.getBoolean("site_permissions_auto", false)
        _passengerMode.value = prefs.getBoolean("passenger_mode", false)
        _passengerKeyboard.value = prefs.getBoolean("passenger_keyboard", false)

        enableWeatherByDefaultIfLocationGranted(context)
        if (_forceEnglish.value) updateLocale()
    }

    fun enableWeatherByDefaultIfLocationGranted(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (
            !prefs.contains("weather_widget_enabled") &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            _weatherWidgetEnabled.value = true
            saveBoolean(context, "weather_widget_enabled", true)
        }
    }

    fun setOnboardingCompleted(context: Context, completed: Boolean) {
        _onboardingCompleted.value = completed
        saveBoolean(context, "onboarding_completed", completed)
    }

    fun setAutoSitePermissions(context: Context, enabled: Boolean) {
        _autoSitePermissions.value = enabled
        saveBoolean(context, "site_permissions_auto", enabled)
    }

    fun setPassengerMode(context: Context, enabled: Boolean) {
        _passengerMode.value = enabled
        saveBoolean(context, "passenger_mode", enabled)
        DrivingState.notifyChanged()
    }

    fun setPassengerKeyboard(context: Context, enabled: Boolean) {
        _passengerKeyboard.value = enabled
        saveBoolean(context, "passenger_keyboard", enabled)
    }

    fun setWeatherWidgetEnabled(context: Context, enabled: Boolean) {
        if (!_mediaPlayerEnabled.value) {
            return
        }
        _weatherWidgetEnabled.value = enabled
        saveBoolean(context, "weather_widget_enabled", enabled)
    }

    fun setPlayerCardOpensMain(context: Context, enabled: Boolean) {
        _playerCardOpensMain.value = enabled
        saveBoolean(context, "player_card_opens_main", enabled)
    }

    fun setYouTubeVideoSkip(context: Context, enabled: Boolean) {
        _youtubeVideoSkip.value = enabled
        saveBoolean(context, "youtube_video_skip", enabled)
    }

    fun setShareToCarWeb(context: Context, enabled: Boolean) {
        _shareToCarWeb.value = enabled
        saveBoolean(context, "share_to_carweb", enabled)
        CarShareTarget.update(context)
    }

    fun setShareAlways(context: Context, always: Boolean) {
        _shareAlways.value = always
        saveBoolean(context, "share_always", always)
        CarShareTarget.update(context)
    }

    // L'opzione "CarWeb tra le app media di Android Auto" (solo build di prova del 07/11/2026, tolta perché Gemini non
    // manda comunque le richieste) spegneva CarService: lo stato dei componenti resta dopo l'aggiornamento e la Main
    // sparirebbe da Android Auto. Riacceso una volta
    private fun restoreMainAfterMediaAppTest(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains("media_app_in_menu")) return
        try {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, CarService::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP
            )
            prefs.edit { remove("media_app_in_menu") }
        } catch (e: Exception) {
            AppLog.w("AppSettings", "Main component not restored: ${e.javaClass.simpleName}")
        }
    }

    fun setMediaPlayerEnabled(context: Context, enabled: Boolean) {
        _mediaPlayerEnabled.value = enabled
        saveBoolean(context, "media_player_enabled", enabled)
        if (!enabled) {
            _weatherWidgetEnabled.value = false
            saveBoolean(context, "weather_widget_enabled", false)
        }
    }

    fun setMultiWindow(context: Context, enabled: Boolean) {
        _multiWindow.value = enabled
        saveBoolean(context, "multi_window", enabled)
    }

    fun setForceTheme(context: Context, enabled: Boolean) {
        _forceTheme.value = enabled
        saveBoolean(context, "force_theme", enabled)
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        _themeMode.value = mode
        saveString(context, "theme_mode", mode.name)
    }

    fun setAutoplayMedia(context: Context, enabled: Boolean) {
        _autoplayMedia.value = enabled
        saveBoolean(context, "autoplay_media", enabled)
    }

    fun setForceMaxYouTubeQuality(context: Context, enabled: Boolean) {
        _forceMaxYouTubeQuality.value = enabled
        saveBoolean(context, "force_max_youtube_quality", enabled)
    }

    fun setYouTubeQuality(context: Context, level: String) {
        _youtubeQuality.value = level
        saveString(context, "youtube_quality", level)
    }

    fun setSponsorBlock(context: Context, enabled: Boolean) {
        _sponsorBlock.value = enabled
        saveBoolean(context, "sponsorblock", enabled)
    }

    fun setSponsorBlockLevel(context: Context, level: String) {
        _sponsorBlockLevel.value = level
        saveString(context, "sponsorblock_level", level)
    }

    fun setWideReopenLastPage(context: Context, enabled: Boolean) {
        _wideReopenLastPage.value = enabled
        saveBoolean(context, "reopen_last_page", enabled)
    }

    fun setWideSidebarFixed(context: Context, enabled: Boolean) {
        _wideSidebarFixed.value = enabled
        saveBoolean(context, "wide_sidebar_fixed", enabled)
    }

    fun setWideVideoMenuOnTap(context: Context, onTap: Boolean) {
        _wideVideoMenuOnTap.value = onTap
        saveBoolean(context, "wide_video_menu_on_tap", onTap)
    }

    /** Un passo più grande (+1) o più piccolo (-1) tra [wideSidebarSizes]. */
    fun stepWideSidebarSize(context: Context, step: Int) {
        val index = (wideSidebarSizes.indexOf(_wideSidebarSize.value) + step).coerceIn(wideSidebarSizes.indices)
        _wideSidebarSize.value = wideSidebarSizes[index]
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt("wide_sidebar_size", _wideSidebarSize.value).apply()
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

    fun setWideLastUrl(context: Context, url: String) {
        _wideLastUrl.value = url
        saveString(context, "wide_last_url", url)
    }

    fun setDashboardThreeColumns(context: Context, enabled: Boolean) {
        _dashboardThreeColumns.value = enabled
        saveBoolean(context, "dashboard_three_columns", enabled)
    }

    fun setOpenDashboardOnNewTab(context: Context, enabled: Boolean) {
        _openDashboardOnNewTab.value = enabled
        saveBoolean(context, "open_dashboard_on_new_tab", enabled)
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

    fun setTabBarMode(context: Context, mode: TabBarMode) {
        _tabBarMode.value = mode
        saveString(context, "tab_bar_mode", mode.name)
    }

    // Velocità di riproduzione YouTube (globale, persistente tra sessioni)
    fun getYouTubeSpeed(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat("youtube_playback_speed", 1.0f)
    }

    fun setYouTubeSpeed(context: Context, speed: Float) {
        saveFloat(context, "youtube_playback_speed", speed)
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
            AppLog.e("AppSettings", "Error clearing cache", e)
        }
    }
    fun clearCookies() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            AppLog.e("AppSettings", "Error clearing cookies", e)
        }
    }

    private fun updateLocale() {
        val languageCode = if (_forceEnglish.value) "en" else null
        val localeList = if (languageCode != null) {
            LocaleListCompat.forLanguageTags(languageCode)
        } else {
            LocaleListCompat.getEmptyLocaleList()
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