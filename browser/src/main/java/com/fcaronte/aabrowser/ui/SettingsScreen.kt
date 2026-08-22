package com.fcaronte.aabrowser.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.model.FavoritesViewModel
import com.fcaronte.aabrowser.settings.AdBlockSettings
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.settings.SearchEngine
import com.fcaronte.aabrowser.settings.ThemeMode

@Composable
fun SettingsScreen(
    favoritesViewModel: FavoritesViewModel,
    onBack: () -> Unit,
    onShowFeedback: (String) -> Unit
) {
    val context = LocalContext.current
    val isDesktopMode by AppSettings.desktopMode
    val desktopScale by AppSettings.desktopScale
    val displayScale by AppSettings.displayScale
    val isAdBlockEnabled by AdBlockSettings.isEnabled
    val isYouTubeAdBlockEnabled by AdBlockSettings.isYouTubeEnabled
    val isDarkPagesEnabled by AppSettings.darkPages
    val themeMode by AppSettings.themeMode
    val useThreeColumns by AppSettings.dashboardThreeColumns
    val searchEngine by AppSettings.searchEngine
    val uiScale by AppSettings.uiScale
    val forceEnglish by AppSettings.forceEnglish
    val persistentNav by AppSettings.persistentNavigation
    val preloadFavorites by AppSettings.preloadFavorites
    val preloadFavoritesCount by AppSettings.preloadFavoritesCount
    val autoplayMedia by AppSettings.autoplayMedia

    var expandedAppSection by remember { mutableStateOf(false) }
    var expandedWebSection by remember { mutableStateOf(true) }
    var cacheSize by remember { mutableStateOf("...") }
    val dataClearedMsg = stringResource(R.string.data_cleared)

    LaunchedEffect(Unit) {
        val size = calculateCacheSize(context)
        cacheSize = size
    }

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
                                                onClick = {
                                                    AppSettings.setThemeMode(
                                                        context,
                                                        mode
                                                    )
                                                },
                                                modifier = Modifier.weight(1f),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.surfaceVariant,
                                                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                                ),
                                            ) {
                                                Text(
                                                    text = mode.name,
                                                    fontSize = 11.sp,
                                                    maxLines = 1
                                                )
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
                                    onCheckedChange = {
                                        AppSettings.setDashboardThreeColumns(
                                            context,
                                            it
                                        )
                                    }
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
                                                if (autoOpenFavoriteId == null) {
                                                    AppSettings.setAutoOpenFavoriteId(
                                                        context,
                                                        favorites.firstOrNull()?.id
                                                    )
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
                                                    onClick = {
                                                        AppSettings.setAutoOpenFavoriteId(
                                                            context,
                                                            favorite.id
                                                        )
                                                    },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = if (isSelected)
                                                            MaterialTheme.colorScheme.primaryContainer
                                                        else MaterialTheme.colorScheme.surfaceVariant.copy(
                                                            alpha = 0.5f
                                                        )
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

                            // Autoplay
                            SettingsCard {
                                SettingsSwitchItem(
                                    label = "Riproduzione automatica media",
                                    description = "Consente l'avvio immediato di audio e video all'apertura delle schede",
                                    checked = autoplayMedia,
                                    onCheckedChange = { AppSettings.setAutoplayMedia(context, it) }
                                )
                            }

                            // Precarica schede preferiti all'avvio
                            SettingsCard {
                                Column(modifier = Modifier.padding(bottom = if (preloadFavorites) 12.dp else 0.dp)) {
                                    SettingsSwitchItem(
                                        label = "Precarica schede preferiti all'avvio",
                                        description = "Apre i primi siti preferiti in schede di background all'apertura dell'app",
                                        checked = preloadFavorites,
                                        onCheckedChange = { AppSettings.setPreloadFavorites(context, it) }
                                    )

                                    AnimatedVisibility(visible = preloadFavorites) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = "Numero di schede da precaricare: $preloadFavoritesCount",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Slider(
                                                value = preloadFavoritesCount.toFloat(),
                                                onValueChange = {
                                                    AppSettings.setPreloadFavoritesCount(context, it.toInt())
                                                },
                                                valueRange = 1f..8f,
                                                steps = 6 // crea 6 punti intermedi discreti per 1, 2, 3, 4, 5, 6, 7, 8
                                            )
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
                                    onCheckedChange = {
                                        AppSettings.setPersistentNavigation(
                                            context,
                                            it
                                        )
                                    }
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
                                        onValueChange = { scale ->
                                            AppSettings.setUiScale(
                                                context,
                                                scale
                                            )
                                        },
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
                                        SearchEngine.entries.forEach { engine ->
                                            val isSelected = searchEngine == engine
                                            Button(
                                                onClick = {
                                                    AppSettings.setSearchEngine(
                                                        context,
                                                        engine
                                                    )
                                                },
                                                modifier = Modifier.weight(1f),
                                                contentPadding = PaddingValues(horizontal = 4.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.surfaceVariant,
                                                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                                ),
                                            ) {
                                                Text(
                                                    text = engine.name,
                                                    fontSize = 9.sp,
                                                    maxLines = 1
                                                )
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
                                                color = MaterialTheme.colorScheme.onSurface.copy(
                                                    alpha = 0.6f
                                                ),
                                                fontSize = 12.sp,
                                            )
                                        }
                                        Switch(
                                            checked = isDesktopMode,
                                            onCheckedChange = {
                                                AppSettings.setDesktopMode(
                                                    context,
                                                    it
                                                )
                                            },
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    if (isDesktopMode) {
                                        Text(
                                            text = stringResource(
                                                R.string.desktop_zoom_label,
                                                (desktopScale * 100).toInt()
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 14.sp,
                                        )
                                        Slider(
                                            value = desktopScale,
                                            onValueChange = {
                                                AppSettings.setDesktopScale(
                                                    context,
                                                    it
                                                )
                                            },
                                            valueRange = 0.25f..1.5f,
                                            steps = 24, // 0.05 steps: 0.25, 0.30, ..., 1.0, ..., 1.5
                                        )
                                    } else {
                                        Text(
                                            text = stringResource(
                                                R.string.mobile_zoom_label,
                                                (displayScale * 100).toInt()
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 14.sp,
                                        )
                                        Slider(
                                            value = displayScale,
                                            onValueChange = {
                                                AppSettings.setDisplayScale(
                                                    context,
                                                    it
                                                )
                                            },
                                            valueRange = 0.25f..1.5f,
                                            steps = 24, // 0.05 steps
                                        )
                                    }
                                }
                            }

                            // AdBlock
                            SettingsCard {
                                Column {
                                    SettingsSwitchItem(
                                        label = stringResource(R.string.adblock_label),
                                        description = stringResource(R.string.adblock_desc),
                                        checked = isAdBlockEnabled,
                                        onCheckedChange = {
                                            AdBlockSettings.setEnabled(
                                                context,
                                                it
                                            )
                                        }
                                    )
                                    SettingsSwitchItem(
                                        label = stringResource(R.string.adblock_youtube_label),
                                        description = stringResource(R.string.adblock_youtube_desc),
                                        checked = isYouTubeAdBlockEnabled,
                                        onCheckedChange = {
                                            AdBlockSettings.setYouTubeEnabled(
                                                context,
                                                it
                                            )
                                        }
                                    )
                                }
                            }

                            // Clear Data
                            SettingsCard {
                                var clearCache by remember { mutableStateOf(false) }
                                var clearCookies by remember { mutableStateOf(false) }

                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = stringResource(R.string.clear_data_label),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Text(
                                                text = stringResource(R.string.clear_data_desc),
                                                color = MaterialTheme.colorScheme.onSurface.copy(
                                                    alpha = 0.6f
                                                ),
                                                fontSize = 12.sp,
                                            )
                                        }

                                        // Tasto refresh manuale dimensione cache
                                        IconButton(onClick = {
                                            cacheSize = calculateCacheSize(context)
                                        }) {
                                            Icon(
                                                Icons.Default.Refresh,
                                                contentDescription = "Refresh cache size",
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = clearCache,
                                            onCheckedChange = { clearCache = it }
                                        )
                                        Text(
                                            text = "Cache ($cacheSize)",
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = clearCookies,
                                            onCheckedChange = { clearCookies = it }
                                        )
                                        Text(
                                            text = "Cookies",
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Button(
                                        onClick = {
                                            if (clearCache) AppSettings.clearCache(context)
                                            if (clearCookies) AppSettings.clearCookies()
                                            onShowFeedback(dataClearedMsg)
                                        },
                                        enabled = clearCache || clearCookies,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            disabledContainerColor = MaterialTheme.colorScheme.error.copy(
                                                alpha = 0.3f
                                            )
                                        )
                                    ) {
                                        Text(stringResource(R.string.confirm_button))
                                    }
                                }
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
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
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
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f)
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
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
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