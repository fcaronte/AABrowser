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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import kotlin.time.Duration.Companion.seconds

sealed class Screen {
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

    var currentScreen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
    var reloadTrigger by remember { mutableIntStateOf(0) }
    var backTrigger by remember { mutableIntStateOf(0) }
    
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
        Density(density = currentDensity.density * uiScale, fontScale = currentDensity.fontScale * uiScale)
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
            Box(modifier = Modifier.fillMaxSize()) {
                // View invisibile (EditText) per gestire l'input nativo AA con supporto cursore
                AndroidView(
                    factory = { ctx ->
                        android.widget.EditText(ctx).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
                            background = null
                            setTextColor(android.graphics.Color.TRANSPARENT)
                            setCursorVisible(true)
                            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_FILTER
                            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                            
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
                                    } catch (_: Exception) {}
                                    editText.tag = null
                                } else if (editText.selectionStart != start || editText.selectionEnd != end) {
                                    try {
                                        editText.setSelection(
                                            start.coerceIn(0, text.length),
                                            end.coerceIn(0, text.length)
                                        )
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    },
                    modifier = Modifier.size(1.dp),
                )

                when (currentScreen) {
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
                                    val searchUrl = searchEngine.baseUrl + java.net.URLEncoder.encode(query, "UTF-8")
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
                                val searchUrl = searchEngine.baseUrl + java.net.URLEncoder.encode(query, "UTF-8")
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
                                    feedbackMessage = localizedContext.getString(R.string.favorite_added)
                                }
                            },
                            carInputManager = carInputManager,
                            webView = activeWebView ?: (inputHostView as? WebView), // Passiamo comunque una view valida se possibile
                        )
                    }
                    is Screen.Settings -> {
                        SettingsScreen(
                            onBack = {
                                currentScreen = if (TabManager.activeTab != null) Screen.Browser else Screen.Dashboard
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
                                currentScreen = if (TabManager.activeTab != null) Screen.Browser else Screen.Dashboard
                            },
                        )
                    }
                }

                // Feedback visivo (Snackbar personalizzata)
                AnimatedVisibility(
                    visible = feedbackMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp),
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
            }
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val isDesktopMode by AppSettings.desktopMode
    val desktopScale by AppSettings.desktopScale
    val isAdBlockEnabled by AdBlockSettings.isEnabled
    val isDarkPagesEnabled by AppSettings.darkPages
    val themeMode by AppSettings.themeMode
    val useThreeColumns by AppSettings.dashboardThreeColumns
    val searchEngine by AppSettings.searchEngine
    val uiScale by AppSettings.uiScale

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
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
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
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
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
                                    text = stringResource(R.string.dashboard_columns_label),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = stringResource(R.string.dashboard_columns_desc),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                )
                            }
                            Switch(
                                checked = useThreeColumns,
                                onCheckedChange = { enabled ->
                                    AppSettings.setDashboardThreeColumns(context, enabled)
                                },
                            )
                        }
                    }
                }

                item {
                    val forceEnglish by AppSettings.forceEnglish
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
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
                                    text = stringResource(R.string.force_english_label),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = stringResource(R.string.force_english_desc),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                )
                            }
                            Switch(
                                checked = forceEnglish,
                                onCheckedChange = { enabled ->
                                    AppSettings.setForceEnglish(context, enabled)
                                },
                            )
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
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
                                steps = 19,
                            )
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
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

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
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
                                    text = stringResource(R.string.dark_pages_label),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = stringResource(R.string.dark_pages_desc),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                )
                            }
                            Switch(
                                checked = isDarkPagesEnabled,
                                onCheckedChange = { enabled ->
                                    AppSettings.setDarkPages(context, enabled)
                                },
                            )
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
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
                                    onCheckedChange = { enabled ->
                                        AppSettings.setDesktopMode(context, enabled)
                                    },
                                )
                            }

                            if (isDesktopMode) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = stringResource(R.string.desktop_zoom_label, (desktopScale * 100).toInt()),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp,
                                )
                                androidx.compose.material3.Slider(
                                    value = desktopScale,
                                    onValueChange = { AppSettings.setDesktopScale(context, it) },
                                    valueRange = 0.25f..1.5f,
                                    steps = 10,
                                )
                            }
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
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
                                    text = stringResource(R.string.adblock_label),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = stringResource(R.string.adblock_desc),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                )
                            }
                            Switch(
                                checked = isAdBlockEnabled,
                                onCheckedChange = { enabled ->
                                    AdBlockSettings.setEnabled(context, enabled)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
