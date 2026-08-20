package com.fcaronte.aabrowser.ui

import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fcaronte.aabrowser.CarInputManager
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.mediaservice.MediaSessionManager
import com.fcaronte.aabrowser.model.FavoritesViewModel
import com.fcaronte.aabrowser.model.TabManager
import com.fcaronte.aabrowser.settings.AdBlockSettings
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.settings.ThemeMode
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

sealed class Screen {
    object Splash : Screen()
    object Dashboard : Screen()
    object Browser : Screen()
    object Settings : Screen()
    object TabManager : Screen()
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

    var currentScreen by remember { 
        mutableStateOf<Screen>(
            if (TabManager.activeTab != null) Screen.Browser 
            else if (AppSettings.autoOpenFavoriteId.value != null) Screen.Splash
            else Screen.Dashboard
        ) 
    }
    var reloadTrigger by remember { mutableIntStateOf(0) }
    var backTrigger by remember { mutableIntStateOf(0) }

    var isNavVisible by remember { mutableStateOf(true) }
    var isNavMenuOpen by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val persistentNav by AppSettings.persistentNavigation

    LaunchedEffect(lastInteractionTime, persistentNav) {
        if (!persistentNav) {
            delay(5.seconds)
            isNavVisible = false
            isNavMenuOpen = false
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

    // View "fantasma" per gestire l'input sulla Dashboard
    var inputHostView by remember { mutableStateOf<android.view.View?>(null) }

    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var showDashboardSearch by remember { mutableStateOf(value = false) }

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

        if (currentScreen == Screen.Splash) {
            // Aspettiamo che i preferiti siano pronti (caricati dal repo)
            // Se la lista rimane vuota per troppo tempo, andiamo alla Dashboard
            var attempts = 0
            while (favoritesViewModel.favorites.isEmpty() && attempts < 20) {
                delay(100.milliseconds)
                attempts++
            }

            val autoOpenId = AppSettings.autoOpenFavoriteId.value
            val favorite = favoritesViewModel.favorites.find { it.id == autoOpenId }
            
            if (favorite != null && TabManager.tabs.isEmpty()) {
                android.util.Log.d("MainScreen", "Auto-opening favorite: ${favorite.name}")
                TabManager.addTab(favorite.url)
                currentScreen = Screen.Browser
            } else {
                android.util.Log.d("MainScreen", "No auto-open match found (ID: $autoOpenId), going to Dashboard")
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
    val forceEnglish by AppSettings.forceEnglish
    val uiScale by AppSettings.uiScale

    // Calcoliamo la densità personalizzata per la scala UI
    val currentDensity = LocalDensity.current
    val customDensity = remember(currentDensity, uiScale) {
        Density(
            density = currentDensity.density * uiScale,
            fontScale = currentDensity.fontScale * uiScale
        )
    }

    // Creiamo un contesto localizzato per forzare la lingua nel display dell'auto
    val localizedContext = remember(context, forceEnglish, configuration) {
        if (forceEnglish) {
            val config = android.content.res.Configuration(configuration)
            config.setLocale(java.util.Locale.ENGLISH)
            context.createConfigurationContext(config)
        } else {
            context
        }
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

                // View invisibile (EditText) per gestire l'input nativo AA con supporto cursore
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
                                override fun beforeTextChanged(
                                    s: CharSequence?,
                                    start: Int,
                                    count: Int,
                                    after: Int
                                ) {
                                }

                                override fun onTextChanged(
                                    s: CharSequence?,
                                    start: Int,
                                    before: Int,
                                    count: Int
                                ) {
                                }

                                override fun afterTextChanged(s: android.text.Editable?) {
                                    if (tag != "internal_update") {
                                        carInputManager?.let { manager ->
                                            if (manager.isImeUpdating) return
                                            val newText = s?.toString() ?: ""
                                            manager.updateState(
                                                newText,
                                                selectionStart,
                                                selectionEnd
                                            )
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

                            // Aggiorniamo l'EditText solo se differente dallo stato Compose
                            // Usiamo un tag per evitare loop infiniti tra TextWatcher e Compose
                            if (editText.tag != "internal_update" && !manager.isImeUpdating) {
                                if (editText.text.toString() != text) {
                                    editText.tag = "internal_update"
                                    editText.setText(text)
                                    try {
                                        editText.setSelection(
                                            start.coerceIn(0, text.length),
                                            end.coerceIn(0, text.length)
                                        )
                                    } catch (_: Exception) {
                                    }
                                    editText.tag = null
                                } else if (editText.selectionStart != start || editText.selectionEnd != end) {
                                    try {
                                        editText.setSelection(
                                            start.coerceIn(0, text.length),
                                            end.coerceIn(0, text.length)
                                        )
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.size(1.dp),
                )

                when (currentScreen) {
                    is Screen.Splash -> {
                        // Già gestito sopra con il Box vuoto
                    }

                    is Screen.Dashboard -> {
                        DashboardScreen(
                            currentWebView = activeWebView,
                            carInputManager = carInputManager,
                            inputHostView = inputHostView,
                            onSiteSelected = { url ->
                                val activeTab = TabManager.activeTab
                                if (activeTab != null) {
                                    TabManager.updateTabUrl(activeTab.id, url)
                                } else {
                                    TabManager.addTab(url)
                                }
                                currentScreen = Screen.Browser
                            },
                            onOpenTabManager = { currentScreen = Screen.TabManager },
                            onOpenSettings = { currentScreen = Screen.Settings },
                            onOpenSearch = { showDashboardSearch = true },
                        )

                        if (showDashboardSearch) {
                            val searchEngine by AppSettings.searchEngine
                            SearchOverlay(
                                onSearch = { query ->
                                    val searchUrl =
                                        searchEngine.baseUrl + java.net.URLEncoder.encode(
                                            query,
                                            "UTF-8"
                                        )
                                    // La ricerca globale apre sempre una nuova scheda
                                    TabManager.addTab(searchUrl)
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
                        val activeTab = TabManager.activeTab
                        val isDesktopMode by AppSettings.desktopMode
                        val searchEngine by AppSettings.searchEngine

                        if (activeTab != null) {
                            BrowserScreen(
                                url = activeTab.url,
                                reloadTrigger = reloadTrigger,
                                backTrigger = backTrigger,
                                isDesktopMode = isDesktopMode,
                                mediaSessionManager = mediaSessionManager,
                                carInputManager = carInputManager,
                                onPageFinished = { newUrl ->
                                    TabManager.updateTabUrl(activeTab.id, newUrl)
                                    AppSettings.setLastUrl(context, newUrl)
                                },
                                onWebViewCreated = { activeWebView = it },
                            )
                        } else {
                            currentScreen = Screen.Dashboard
                        }

                        NavigationOverlay(
                            currentUrl = activeTab?.url ?: "",
                            onGoBack = { backTrigger++ },
                            onGoHome = { currentScreen = Screen.Dashboard },
                            onReload = { reloadTrigger++ },
                            onOpenSettings = { currentScreen = Screen.Settings },
                            onOpenTabManager = { currentScreen = Screen.TabManager },
                            onSearch = { query ->
                                val searchUrl = searchEngine.baseUrl + java.net.URLEncoder.encode(
                                    query,
                                    "UTF-8"
                                )
                                TabManager.addTab(searchUrl)
                                currentScreen = Screen.Browser
                            },
                            onAddToFavorites = {
                                TabManager.activeTab?.let { activeTab ->
                                    favoritesViewModel.addFavorite(
                                        name = activeTab.title,
                                        url = activeTab.url,
                                        faviconUrl = activeTab.faviconUrl
                                    )
                                    feedbackMessage =
                                        localizedContext.getString(R.string.favorite_added)
                                }
                            },
                            carInputManager = carInputManager,
                            webView = activeWebView
                                ?: (inputHostView as? WebView), // Passiamo comunque una view valida se possibile
                            isVisible = isNavVisible,
                            showMenu = isNavMenuOpen,
                            onShowMenuChange = { isNavMenuOpen = it },
                            onInteraction = { onInteraction() },
                            onExit = {
                                try {
                                    val activity = context as? android.app.Activity
                                    activity?.finishAndRemoveTask()
                                    // Forza la chiusura del processo per assicurare il ritorno alla home di AA
                                    android.os.Process.killProcess(android.os.Process.myPid())
                                } catch (e: Exception) {
                                    (context as? android.app.Activity)?.finish()
                                }
                            }
                        )
                    }

                    is Screen.Settings -> {
                        SettingsScreen(
                            favoritesViewModel = favoritesViewModel,
                            onBack = {
                                currentScreen =
                                    if (TabManager.activeTab != null) Screen.Browser else Screen.Dashboard
                            },
                        )
                    }

                    is Screen.TabManager -> {
                        TabManagerScreen(
                            onTabSelected = { index ->
                                TabManager.switchTab(index)
                                currentScreen = Screen.Browser
                            },
                            onCloseTab = { index ->
                                TabManager.closeTab(index)
                            },
                            onAddTab = { currentScreen = Screen.Dashboard },
                            onBack = {
                                currentScreen =
                                    if (TabManager.activeTab != null) Screen.Browser else Screen.Dashboard
                            },
                        )
                    }
                }

                // Feedback visivo (Snackbar personalizzata)
                AnimatedVisibility(
                    visible = feedbackMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 80.dp),
                ) {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.inverseSurface),
                    ) {
                        Text(
                            text = feedbackMessage ?: "",
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                // Tasto Kill Globale di Emergenza - Sempre in alto a destra
                if (carInputManager?.isInputActiveState?.value == true) {
                    FloatingActionButton(
                        onClick = { 
                            android.util.Log.d("##MainScreen", "Emergency Kill clicked")
                            carInputManager.stopInput()
                            // Forza blur sulla WebView attiva se presente
                            activeWebView?.evaluateJavascript("(function(){ if(document.activeElement) document.activeElement.blur(); })();", null)
                        },
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = CircleShape,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .size(64.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardHide,
                            contentDescription = "Force close keyboard",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(favoritesViewModel: FavoritesViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val isDesktopMode by AppSettings.desktopMode
    val desktopScale by AppSettings.desktopScale
    val displayScale by AppSettings.displayScale
    val isAdBlockEnabled by AdBlockSettings.isEnabled
    val isDarkPagesEnabled by AppSettings.darkPages
    val themeMode by AppSettings.themeMode
    val useThreeColumns by AppSettings.dashboardThreeColumns
    val searchEngine by AppSettings.searchEngine
    val uiScale by AppSettings.uiScale
    val forceEnglish by AppSettings.forceEnglish
    val persistentNav by AppSettings.persistentNavigation

    var expandedAppSection by remember { mutableStateOf(false) }
    var expandedWebSection by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back_button),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    text = stringResource(R.string.settings_title),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // SEZIONE: ESTETICA APP
                item {
                    SettingsSectionHeader(
                        title = stringResource(R.string.settings_category_app),
                        isExpanded = expandedAppSection,
                        onClick = { expandedAppSection = !expandedAppSection }
                    )
                }

                item {
                    AnimatedVisibility(visible = expandedAppSection) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Tema
                            SettingsCard {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.theme_mode_label),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = stringResource(R.string.theme_mode_desc),
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        fontSize = 12.sp,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        ThemeMode.entries.forEach { mode ->
                                            val isSelected = themeMode == mode
                                            Button(
                                                onClick = { AppSettings.setThemeMode(context, mode) },
                                                modifier = Modifier.weight(1f),
                                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.surfaceVariant,
                                                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                                ),
                                            ) {
                                                Text(text = mode.name, fontSize = 11.sp, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }

                            // Dashboard 3 colonne
                            SettingsCard {
                                SettingsSwitchItem(
                                    label = stringResource(R.string.dashboard_columns_label),
                                    description = stringResource(R.string.dashboard_columns_desc),
                                    checked = useThreeColumns,
                                    onCheckedChange = { AppSettings.setDashboardThreeColumns(context, it) }
                                )
                            }

                            // Auto-Open Favorite
                            val autoOpenFavoriteId by AppSettings.autoOpenFavoriteId
                            val favorites = favoritesViewModel.favorites
                            SettingsCard {
                                Column(modifier = Modifier.padding(bottom = if (autoOpenFavoriteId != null) 16.dp else 0.dp)) {
                                    SettingsSwitchItem(
                                        label = stringResource(R.string.auto_open_favorite_label),
                                        description = stringResource(R.string.auto_open_favorite_desc),
                                        checked = autoOpenFavoriteId != null,
                                        onCheckedChange = { enabled ->
                                            if (enabled) {
                                                // Se attiviamo e non c'è nulla, mettiamo il primo della lista se esiste
                                                if (autoOpenFavoriteId == null) {
                                                    AppSettings.setAutoOpenFavoriteId(context, favorites.firstOrNull()?.id)
                                                }
                                            } else {
                                                AppSettings.setAutoOpenFavoriteId(context, null)
                                            }
                                        }
                                    )

                                    if (autoOpenFavoriteId != null && favorites.isNotEmpty()) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            favorites.forEach { favorite ->
                                                val isSelected = autoOpenFavoriteId == favorite.id
                                                Card(
                                                    onClick = { AppSettings.setAutoOpenFavoriteId(context, favorite.id) },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = if (isSelected)
                                                            MaterialTheme.colorScheme.primaryContainer
                                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                                    ),
                                                    shape = RoundedCornerShape(12.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(12.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        RadioButton(
                                                            selected = isSelected,
                                                            onClick = null
                                                        )
                                                        Text(
                                                            favorite.name,
                                                            modifier = Modifier.padding(start = 12.dp),
                                                            style = MaterialTheme.typography.bodyLarge,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Tasto Navigazione Persistente
                            SettingsCard {
                                SettingsSwitchItem(
                                    label = stringResource(R.string.persistent_nav_label),
                                    description = stringResource(R.string.persistent_nav_desc),
                                    checked = persistentNav,
                                    onCheckedChange = { AppSettings.setPersistentNavigation(context, it) }
                                )
                            }

                            // Forza Inglese
                            SettingsCard {
                                SettingsSwitchItem(
                                    label = stringResource(R.string.force_english_label),
                                    description = stringResource(R.string.force_english_desc),
                                    checked = forceEnglish,
                                    onCheckedChange = { AppSettings.setForceEnglish(context, it) }
                                )
                            }

                            // Scala DPI (UI Scale)
                            SettingsCard {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = stringResource(R.string.ui_scale_label) + ": ${(uiScale * 100).toInt()}%",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = stringResource(R.string.ui_scale_desc),
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        fontSize = 12.sp,
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Slider(
                                        value = uiScale,
                                        onValueChange = { scale -> AppSettings.setUiScale(context, scale) },
                                        valueRange = 0.5f..1.5f,
                                        steps = 19, // 0.05 steps: 0.5, 0.55, ..., 1.0, ..., 1.5
                                    )
                                }
                            }

                            // Motore di Ricerca
                            SettingsCard {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.search_engine_label),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = stringResource(R.string.search_engine_desc),
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        fontSize = 12.sp,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        com.fcaronte.aabrowser.settings.SearchEngine.entries.forEach { engine ->
                                            val isSelected = searchEngine == engine
                                            Button(
                                                onClick = { AppSettings.setSearchEngine(context, engine) },
                                                modifier = Modifier.weight(1f),
                                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.surfaceVariant,
                                                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                                ),
                                            ) {
                                                Text(text = engine.name, fontSize = 9.sp, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // SEZIONE: PAGINE WEB
                item {
                    SettingsSectionHeader(
                        title = stringResource(R.string.settings_category_web),
                        isExpanded = expandedWebSection,
                        onClick = { expandedWebSection = !expandedWebSection }
                    )
                }

                item {
                    AnimatedVisibility(visible = expandedWebSection) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Dark Pages
                            SettingsCard {
                                SettingsSwitchItem(
                                    label = stringResource(R.string.dark_pages_label),
                                    description = stringResource(R.string.dark_pages_desc),
                                    checked = isDarkPagesEnabled,
                                    onCheckedChange = { AppSettings.setDarkPages(context, it) }
                                )
                            }

                            // Desktop Mode & Zooms
                            SettingsCard {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = stringResource(R.string.desktop_mode_label),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Text(
                                                text = stringResource(R.string.desktop_mode_desc),
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                fontSize = 12.sp,
                                            )
                                        }
                                        Switch(
                                            checked = isDesktopMode,
                                            onCheckedChange = { AppSettings.setDesktopMode(context, it) },
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    if (isDesktopMode) {
                                        Text(
                                            text = stringResource(R.string.desktop_zoom_label, (desktopScale * 100).toInt()),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 14.sp,
                                        )
                                        Slider(
                                            value = desktopScale,
                                            onValueChange = { AppSettings.setDesktopScale(context, it) },
                                            valueRange = 0.25f..1.5f,
                                            steps = 24, // 0.05 steps: 0.25, 0.30, ..., 1.0, ..., 1.5
                                        )
                                    } else {
                                        Text(
                                            text = stringResource(R.string.mobile_zoom_label, (displayScale * 100).toInt()),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 14.sp,
                                        )
                                        Slider(
                                            value = displayScale,
                                            onValueChange = { AppSettings.setDisplayScale(context, it) },
                                            valueRange = 0.25f..1.5f,
                                            steps = 24, // 0.05 steps
                                        )
                                    }
                                }
                            }

                            // AdBlock
                            SettingsCard {
                                SettingsSwitchItem(
                                    label = stringResource(R.string.adblock_label),
                                    description = stringResource(R.string.adblock_desc),
                                    checked = isAdBlockEnabled,
                                    onCheckedChange = { AdBlockSettings.setEnabled(context, it) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsSectionHeader(title: String, isExpanded: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.ExtraBold
            )
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        content = content
    )
}

@Composable
fun SettingsSwitchItem(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
