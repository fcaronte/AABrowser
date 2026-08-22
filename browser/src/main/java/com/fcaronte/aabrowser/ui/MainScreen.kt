package com.fcaronte.aabrowser.ui

import android.app.Activity
import android.os.Process
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fcaronte.aabrowser.CarInputManager
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.mediaservice.MediaSessionManager
import com.fcaronte.aabrowser.model.FavoritesViewModel
import com.fcaronte.aabrowser.model.TabManager
import com.fcaronte.aabrowser.settings.AdBlockSettings
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.settings.TabBarMode
import com.fcaronte.aabrowser.settings.ThemeMode
import kotlinx.coroutines.delay
import java.net.URLEncoder
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

sealed class Screen {
    object Splash : Screen()
    object Dashboard : Screen()
    object Browser : Screen()
    object Settings : Screen()
    object TabManager : Screen()
}

fun calculateCacheSize(context: android.content.Context): String {
    return try {
        var size: Long = 0

        // 1. Cache standard Android (Internal & External)
        context.cacheDir?.let { size += getDirSize(it) }
        context.externalCacheDir?.let { size += getDirSize(it) }
        
        // 2. Code Cache
        context.codeCacheDir?.let { size += getDirSize(it) }

        // 3. Cache specifica di Chromium / WebView
        val appDataDir = context.applicationInfo.dataDir
        val webViewDir = java.io.File(appDataDir, "app_webview")
        
        if (webViewDir.exists()) {
            // Analisi approfondita delle sottocartelle di cache
            val cacheCandidates = listOf(
                "Cache", "Code Cache", "GPUCache", "ShaderCache", "GrShaderCache",
                "Default/Cache", "Default/Code Cache", "Default/GPUCache", "Default/Service Worker/CacheStorage",
                "blob_storage"
            )
            for (path in cacheCandidates) {
                val dir = java.io.File(webViewDir, path)
                if (dir.exists()) size += getDirSize(dir)
            }
        }

        // Formattazione
        val df = java.text.DecimalFormat("#.##")
        if (size <= 0) "0 B"
        else if (size < 1024) "$size B"
        else if (size < 1024 * 1024) "${df.format(size / 1024.0)} KB"
        else if (size < 1024 * 1024 * 1024) "${df.format(size / (1024.0 * 1024.0))} MB"
        else "${df.format(size / (1024.0 * 1024.0 * 1024.0))} GB"
    } catch (e: Exception) {
        "..."
    }
}

private fun getDirSize(dir: java.io.File): Long {
    var size: Long = 0
    dir.listFiles()?.forEach { file ->
        if (file.isFile) size += file.length()
        else if (file.isDirectory) size += getDirSize(file)
    }
    return size
}

@Composable
fun MainScreen(carInputManager: CarInputManager? = null) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val favoritesViewModel: FavoritesViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(
            context.applicationContext as android.app.Application,
        ),
    )

    val favoriteAddedMsg = stringResource(R.string.favorite_added)
    val favoriteUpdatedMsg = stringResource(R.string.favorite_updated)

    var previousScreen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
    var currentScreen by remember {
        mutableStateOf<Screen>(
            if (AppSettings.autoOpenFavoriteId.value != null) Screen.Splash else Screen.Dashboard
        )
    }

    var reloadTrigger by remember { mutableIntStateOf(0) }
    var backTrigger by remember { mutableIntStateOf(0) }
    var forwardTrigger by remember { mutableIntStateOf(0) }

    var isNavVisible by remember { mutableStateOf(true) }
    var isNavMenuOpen by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isKeyboardActiveManual by remember { mutableStateOf(false) }

    val persistentNav by AppSettings.persistentNavigation
    val tabBarMode by AppSettings.tabBarMode

    LaunchedEffect(persistentNav) {
        if (persistentNav) {
            isNavVisible = true
        }
    }

    LaunchedEffect(lastInteractionTime, persistentNav, isNavMenuOpen) {
        if (!persistentNav && !isNavMenuOpen) {
            delay(5.seconds)
            isNavVisible = false
        }
    }

    // Polling per rilevare lo stato della tastiera car se lo State non si aggiorna
    LaunchedEffect(Unit) {
        while (true) {
            val active = carInputManager?.isInputActive == true
            if (isKeyboardActiveManual != active) {
                isKeyboardActiveManual = active
            }
            delay(500.milliseconds)
        }
    }

    fun onInteraction(fromPage: Boolean = false) {
        lastInteractionTime = System.currentTimeMillis()
        isNavVisible = true
        if (fromPage) {
            isNavMenuOpen = false
        }
    }

    // Riferimento alla WebView attiva per l'input AA
    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    var isFullScreen by remember { mutableStateOf(false) }

    // View "fantasma" per gestire l'input sulla Dashboard
    var inputHostView by remember { mutableStateOf<android.view.View?>(null) }

    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var showDashboardSearch by remember { mutableStateOf(false) }
    var showBrowserSearch by remember { mutableStateOf(false) }
    val isGlobalSearchActive = showDashboardSearch || showBrowserSearch

    val mediaSessionManager = remember { MediaSessionManager(context) }

    LaunchedEffect(feedbackMessage) {
        if (feedbackMessage != null) {
            delay(3.seconds)
            feedbackMessage = null
        }
    }

    LaunchedEffect(Unit) {
        AdBlockSettings.init(context)
        mediaSessionManager.connect()

        // Aspetta che i preferiti siano caricati dal repository
        var attempts = 0
        while (favoritesViewModel.favorites.isEmpty() && attempts < 20) {
            delay(100.milliseconds)
            attempts++
        }

        val autoOpenId = AppSettings.autoOpenFavoriteId.value
        val isPreloadEnabled = AppSettings.preloadFavorites.value
        val preloadLimit = AppSettings.preloadFavoritesCount.value

        if (TabManager.tabs.isEmpty()) {
            if (isPreloadEnabled && favoritesViewModel.favorites.isNotEmpty()) {
                // Precarica i preferiti passando subito nome e icona
                TabManager.preloadFavorites(favoritesViewModel.favorites, preloadLimit)

                if (autoOpenId != null) {
                    val targetIndex = TabManager.tabs.indexOfFirst { it.id == autoOpenId }
                    if (targetIndex != -1) {
                        TabManager.switchTab(targetIndex)
                        currentScreen = Screen.Browser
                    } else {
                        // Se l'auto-open non è tra i primi N, lo apriamo esplicitamente
                        val favorite = favoritesViewModel.favorites.find { it.id == autoOpenId }
                        if (favorite != null) {
                            TabManager.addTab(
                                url = favorite.url,
                                title = favorite.name,
                                faviconUrl = favorite.faviconUrl,
                                desktopModeOverride = favorite.isDesktopMode,
                                mobileZoomOverride = favorite.mobileZoom,
                                desktopZoomOverride = favorite.desktopZoom
                            )
                            TabManager.switchTab(TabManager.tabs.lastIndex)
                            currentScreen = Screen.Browser
                        } else {
                            if (TabManager.tabs.isNotEmpty()) TabManager.switchTab(0)
                            currentScreen = Screen.Dashboard
                        }
                    }
                } else {
                    // Nessun auto-open: precarica silenziosamente e resta sulla Dashboard
                    if (TabManager.tabs.isNotEmpty()) TabManager.switchTab(0)
                    currentScreen = Screen.Dashboard
                }
            } else if (autoOpenId != null) {
                val favorite = favoritesViewModel.favorites.find { it.id == autoOpenId }
                if (favorite != null) {
                    TabManager.addTab(
                        url = favorite.url,
                        title = favorite.name,
                        faviconUrl = favorite.faviconUrl,
                        desktopModeOverride = favorite.isDesktopMode,
                        mobileZoomOverride = favorite.mobileZoom,
                        desktopZoomOverride = favorite.desktopZoom
                    )
                    TabManager.switchTab(0)
                    currentScreen = Screen.Browser
                } else {
                    currentScreen = Screen.Dashboard
                }
            } else {
                currentScreen = Screen.Dashboard
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mediaSessionManager.disconnect()
        }
    }

    val themeMode by AppSettings.themeMode
    val forceTheme by AppSettings.forceTheme
    val forceEnglish by AppSettings.forceEnglish
    val uiScale by AppSettings.uiScale

    // Calcoliamo se l'app è in modalità scura (seguendo l'auto/sistema o il forzato)
    val systemIsDark = isSystemInDarkTheme()
    val isAppDark = when {
        forceTheme -> themeMode == ThemeMode.DARK || themeMode == ThemeMode.AMOLED
        else -> systemIsDark
    }

    // Calcoliamo la densità personalizzata per la scala UI
    val currentDensity = LocalDensity.current
    val customDensity = remember(currentDensity, uiScale) {
        Density(
            density = currentDensity.density * uiScale,
            fontScale = currentDensity.fontScale * uiScale
        )
    }

    // Creiamo un contesto configurato (lingua e tema) per la WebView e i componenti nativi
    val localizedContext = remember(context, forceEnglish, configuration, isAppDark) {
        val config = android.content.res.Configuration(configuration)
        
        val uiMode = if (isAppDark) {
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        } else {
            android.content.res.Configuration.UI_MODE_NIGHT_NO
        }
        config.uiMode = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or uiMode
        
        val localized = context.createConfigurationContext(config)
        // Forza l'applicazione del tema corretto al nuovo contesto
        localized.setTheme(R.style.AppTheme)
        localized
    }

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalDensity provides customDensity
    ) {
        AABrowserTheme(themeMode = themeMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                onInteraction(fromPage = true)
                            }
                        }
                    }
            ) {
                if (currentScreen == Screen.Splash) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }

                // View invisibile (EditText) per gestire l'input nativo AA
                AndroidView(
                    factory = { ctx ->
                        android.widget.EditText(ctx).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
                            background = null
                            setTextColor(android.graphics.Color.TRANSPARENT)
                            setCursorVisible(true)
                            inputType =
                                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_FILTER
                            imeOptions =
                                android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI

                            addTextChangedListener(object : android.text.TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                                override fun afterTextChanged(s: android.text.Editable?) {
                                    if (tag != "internal_update") {
                                        carInputManager?.let { manager ->
                                            if (manager.isImeUpdating) return
                                            val newText = s?.toString() ?: ""
                                            manager.updateState(newText, selectionStart, selectionEnd)
                                        }
                                    }
                                }
                            })
                        }
                    },
                    update = { editText ->
                        inputHostView = editText
                        carInputManager?.let { manager ->
                            val text = manager.getCurrentText()
                            val start = manager.getSelectionStart()
                            val end = manager.getSelectionEnd()
                            if (editText.tag != "internal_update" && !manager.isImeUpdating) {
                                if (editText.text.toString() != text) {
                                    editText.tag = "internal_update"
                                    editText.setText(text)
                                    try { editText.setSelection(start.coerceIn(0, text.length), end.coerceIn(0, text.length)) } catch (_: Exception) {}
                                    editText.tag = null
                                } else if (editText.selectionStart != start || editText.selectionEnd != end) {
                                    try { editText.setSelection(start.coerceIn(0, text.length), end.coerceIn(0, text.length)) } catch (_: Exception) {}
                                }
                            }
                        }
                    },
                    modifier = Modifier.size(1.dp),
                )

                // Gestione globale delle schede (sempre in composizione per il background play)
                val activeTab = TabManager.activeTab
                val isDesktopMode by AppSettings.desktopMode
                val searchEngine by AppSettings.searchEngine
                val isBrowserVisible = currentScreen == Screen.Browser

                val isTabBarVisible = when (tabBarMode) {
                    TabBarMode.OFF -> false
                    TabBarMode.AUTO_HIDE -> isNavVisible && !isFullScreen
                    TabBarMode.ALWAYS_ON -> !isFullScreen
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = if (isTabBarVisible && isBrowserVisible) 48.dp else 0.dp)
                        .graphicsLayer(alpha = if (isBrowserVisible) 1f else 0.01f)
                        .zIndex(if (isBrowserVisible) 1f else -1f)
                ) {
                    val sortedTabs = TabManager.tabs.sortedBy { it.id == activeTab?.id }
                    sortedTabs.forEach { tab ->
                        val isTabActive = activeTab?.id == tab.id
                        androidx.compose.runtime.key(tab.id) { // Se qui forzo il ricaricamneto immediato della pagina web al cambio giorno notte si blocca la musica in riproduzione
                            Box(modifier = Modifier.fillMaxSize()) {
                                BrowserScreen(
                                    tabId = tab.id,
                                    url = tab.url,
                                    reloadTrigger = if (isTabActive) reloadTrigger else 0,
                                    backTrigger = if (isTabActive) backTrigger else 0,
                                    forwardTrigger = if (isTabActive) forwardTrigger else 0,
                                    isDesktopMode = isDesktopMode,
                                    mediaSessionManager = mediaSessionManager,
                                    carInputManager = carInputManager,
                                    desktopModeOverride = tab.desktopModeOverride,
                                    mobileZoomOverride = tab.mobileZoomOverride,
                                    desktopZoomOverride = tab.desktopZoomOverride,
                                    isTabActive = isTabActive,
                                    isGlobalSearchActive = isGlobalSearchActive,
                                    isAppDarkOverride = isAppDark, // <--- Passato correttamente alla WebView
                                    onPageFinished = { newUrl ->
                                        TabManager.updateTabUrl(tab.id, newUrl)
                                        if (isTabActive) AppSettings.setLastUrl(context, newUrl)
                                    },
                                    onWebViewCreated = { if (isTabActive) activeWebView = it },
                                    onFullScreenChange = { if (isTabActive) isFullScreen = it }
                                )
                                if (!isTabActive || !isBrowserVisible) {
                                    Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Transparent).pointerInput(Unit) {})
                                }
                            }
                        }
                    }
                }

                when (currentScreen) {
                    is Screen.Splash -> {}

                    is Screen.Dashboard -> {
                        DashboardScreen(
                            currentWebView = activeWebView,
                            carInputManager = carInputManager,
                            inputHostView = inputHostView,
                            onSiteSelected = { url, desktop, mobileZoom, desktopZoom ->
                                currentScreen = Screen.Browser
                            },
                            onOpenTabManager = {
                                previousScreen = Screen.Dashboard
                                currentScreen = Screen.TabManager
                            },
                            onOpenSettings = {
                                previousScreen = Screen.Dashboard
                                currentScreen = Screen.Settings
                            },
                            onOpenSearch = { showDashboardSearch = true },
                            onShowFeedback = { feedbackMessage = it }
                        )

                        if (showDashboardSearch) {
                            SearchOverlay(
                                onSearch = { query ->
                                    val searchUrl = searchEngine.baseUrl + URLEncoder.encode(query, "UTF-8")
                                    TabManager.openOrSwitchTo(searchUrl)
                                    currentScreen = Screen.Browser
                                    showDashboardSearch = false
                                },
                                onDismiss = { showDashboardSearch = false },
                                carInputManager = carInputManager,
                                inputView = inputHostView,
                            )
                        }
                    }

                    is Screen.Browser -> {
                        Box(modifier = Modifier.fillMaxSize().zIndex(10f)) {
                            if (isTabBarVisible) {
                                PersistentTabBar(
                                    modifier = Modifier.align(Alignment.TopCenter),
                                    onTabSelected = { index ->
                                        TabManager.switchTab(index)
                                        onInteraction()
                                    },
                                    onCloseTab = { index ->
                                        TabManager.closeTab(index)
                                        if (TabManager.tabs.isEmpty()) {
                                            currentScreen = Screen.Dashboard
                                        }
                                        onInteraction()
                                    },
                                    onAddTab = {
                                        TabManager.addTab(AppSettings.effectiveSearchEngine.homeUrl)
                                        onInteraction()
                                    },
                                    onGoBack = {
                                        backTrigger++
                                        onInteraction()
                                    },
                                    onGoForward = {
                                        forwardTrigger++
                                        onInteraction()
                                    }
                                )
                            }

                            NavigationOverlay(
                                currentUrl = activeTab?.url ?: "",
                                onGoBack = {
                                    backTrigger++
                                    onInteraction()
                                },
                                onGoForward = {
                                    forwardTrigger++
                                    onInteraction()
                                },
                                onGoHome = {
                                    currentScreen = Screen.Dashboard
                                    onInteraction()
                                },
                                onReload = {
                                    reloadTrigger++
                                    onInteraction()
                                },
                                onOpenSettings = {
                                    previousScreen = Screen.Browser
                                    currentScreen = Screen.Settings
                                    onInteraction()
                                },
                                onOpenTabManager = {
                                    previousScreen = Screen.Browser
                                    currentScreen = Screen.TabManager
                                    onInteraction()
                                },
                                onSearch = { query ->
                                    val searchUrl = searchEngine.baseUrl + URLEncoder.encode(query, "UTF-8")
                                    TabManager.openOrSwitchTo(searchUrl)
                                    currentScreen = Screen.Browser
                                    onInteraction()
                                },
                                onAddToFavorites = {
                                    TabManager.activeTab?.let { activeTab ->
                                        favoritesViewModel.addFavorite(
                                            name = activeTab.title,
                                            url = activeTab.url,
                                            faviconUrl = activeTab.faviconUrl,
                                            isDesktopMode = activeTab.desktopModeOverride,
                                            mobileZoom = activeTab.mobileZoomOverride,
                                            desktopZoom = activeTab.desktopZoomOverride
                                        )
                                        feedbackMessage = favoriteAddedMsg
                                    }
                                    onInteraction()
                                },
                                carInputManager = carInputManager,
                                webView = activeWebView ?: (inputHostView as? WebView),
                                isVisible = isNavVisible,
                                showMenu = isNavMenuOpen,
                                onShowMenuChange = {
                                    isNavMenuOpen = it
                                    com.fcaronte.aabrowser.utils.InactivityTracker.isMenuOpen = it
                                    onInteraction()
                                },
                                onInteraction = { onInteraction(fromPage = false) },
                                onExit = {
                                    try {
                                        val activity = context as? Activity
                                        activity?.finishAndRemoveTask()
                                        Process.killProcess(Process.myPid())
                                    } catch (_: Exception) { (context as? Activity)?.finish() }
                                }
                            )
                        }
                    }

                    is Screen.Settings -> {
                        SettingsScreen(
                            favoritesViewModel = favoritesViewModel,
                            onBack = { currentScreen = previousScreen },
                            onShowFeedback = { feedbackMessage = it }
                        )
                    }

                    is Screen.TabManager -> {
                        TabManagerScreen(
                            onTabSelected = { index ->
                                TabManager.switchTab(index)
                                currentScreen = Screen.Browser
                            },
                            onCloseTab = { index -> TabManager.closeTab(index) },
                            onAddTab = {
                                TabManager.addTab(AppSettings.effectiveSearchEngine.homeUrl)
                                currentScreen = Screen.Browser
                            },
                            onBack = {
                                currentScreen = if (TabManager.tabs.isEmpty()) Screen.Dashboard else previousScreen
                            },
                        )
                    }
                }

                // Feedback visivo
                AnimatedVisibility(
                    visible = feedbackMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp).zIndex(99f),
                ) {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.inverseSurface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Text(
                            text = feedbackMessage ?: "",
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                // Tasto Emergenza Globale (permette di chiudere la tastiera AA forzatamente)
                if (isKeyboardActiveManual) {
                    FloatingActionButton(
                        onClick = {
                            carInputManager?.stopInput()
                            activeWebView?.evaluateJavascript("(function(){ if(document.activeElement) document.activeElement.blur(); })();", null)
                        },
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = CircleShape,
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(64.dp).zIndex(100f),
                    ) {
                        Icon(Icons.Default.KeyboardHide, null, modifier = Modifier.size(32.dp))
                    }
                }
            }
        }
    }
}