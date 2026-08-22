package com.fcaronte.aabrowser.ui

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.model.FavoriteSite
import com.fcaronte.aabrowser.model.FavoritesViewModel
import com.fcaronte.aabrowser.model.TabManager
import com.fcaronte.aabrowser.utils.UpdateManager
import com.fcaronte.aabrowser.settings.AppSettings
import java.net.URI

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(
    viewModel: FavoritesViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(
            LocalContext.current.applicationContext as Application,
        ),
    ),
    currentWebView: android.webkit.WebView? = null,
    carInputManager: com.fcaronte.aabrowser.CarInputManager? = null,
    inputHostView: android.view.View? = null,
    onSiteSelected: (String, Boolean?, Float?, Float?) -> Unit,
    onOpenTabManager: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onShowFeedback: (String) -> Unit = {},
) {
    val favorites = viewModel.favorites
    var isEditMode by remember { mutableStateOf(value = false) }
    var siteToEdit by remember { mutableStateOf<FavoriteSite?>(null) }
    var showAddDialog by remember { mutableStateOf(value = false) }
    val lastUrl by AppSettings.lastUrl
    val useThreeColumns by AppSettings.dashboardThreeColumns
    val context = LocalContext.current

    val currentVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
    }

    var updateInfo by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    var bannerDismissed by remember { mutableStateOf(UpdateManager.isBannerDismissed) }

    LaunchedEffect(Unit) {
        updateInfo = UpdateManager.checkForUpdates(context)
        updateInfo?.let {
            if (it.isAvailable) {
                UpdateManager.showUpdateNotification(context, it)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Background Image
        AsyncImage(
            model = "https://images.unsplash.com/photo-1614850523296-d8c1af93d400?q=80&w=2070&auto=format&fit=crop",
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            alpha = 0.2f,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = stringResource(R.string.app_name),
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "v$currentVersion",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            // Piccolo offset per allineare meglio le baseline
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }

                    Row {
                        IconButton(onClick = onOpenSearch) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            )
                        }
                        IconButton(onClick = { isEditMode = !isEditMode }) {
                            Icon(
                                imageVector = if (isEditMode) Icons.Default.Check else Icons.Default.Edit,
                                contentDescription = stringResource(R.string.edit_mode_toggle),
                                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.dashboard_subtitle),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                )
            }

            // Griglia dei preferiti
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (useThreeColumns) 3 else 2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(favorites, key = { _, site -> site.id }) { index, site ->
                    FavoriteCard(
                        site = site,
                        isEditMode = isEditMode,
                        onEdit = { siteToEdit = site },
                        onDelete = { viewModel.removeFavorite(site) },
                        onMoveLeft = { viewModel.moveLeft(index) },
                        onMoveRight = { viewModel.moveRight(index) },
                        onClick = {
                            if (!isEditMode) {
                                // Usa l'apertura intelligente: se esiste già fa lo switch, altrimenti apre/carica
                                TabManager.openOrSwitchTo(
                                    url = site.url,
                                    title = site.name,
                                    faviconUrl = site.faviconUrl,
                                    desktopModeOverride = site.isDesktopMode,
                                    mobileZoomOverride = site.mobileZoom,
                                    desktopZoomOverride = site.desktopZoom
                                )
                                onSiteSelected(
                                    site.url,
                                    site.isDesktopMode,
                                    site.mobileZoom,
                                    site.desktopZoom
                                )
                            }
                        },
                    )
                }

                if (isEditMode) {
                    item { AddFavoriteCard(onClick = { showAddDialog = true }) }
                }
            }

            // Update Banner (Sopra i tasti in basso)
            updateInfo?.let { info ->
                if (info.isAvailable && !bannerDismissed) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        UpdateManager.openDownloadPage(
                                            context,
                                            info.downloadUrl
                                        )
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.SystemUpdate,
                                    null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(
                                        R.string.update_available,
                                        info.latestVersion
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            // Tasto X piccolo e discreto
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable {
                                        bannerDismissed = true
                                        UpdateManager.isBannerDismissed = true
                                    },
                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }

            // Bottom Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        if (lastUrl.isNotEmpty()) {
                            TabManager.openOrSwitchTo(lastUrl)
                            onSiteSelected(lastUrl, null, null, null)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    enabled = lastUrl.isNotEmpty(),
                    shape = RoundedCornerShape(23.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.resume_button), fontSize = 11.sp, maxLines = 1)
                }

                Button(
                    onClick = onOpenTabManager,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(23.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(Icons.Default.Layers, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.tabs_button), fontSize = 10.sp, maxLines = 1)
                }

                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(23.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(Icons.Default.Settings, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.settings_button), fontSize = 10.sp, maxLines = 1)
                }
            }
        }

        if (showAddDialog) {
            val feedbackMsg = stringResource(R.string.favorite_added)
            EditFavoriteOverlay(
                currentWebView = currentWebView,
                carInputManager = carInputManager,
                inputHostView = inputHostView,
                onDismiss = { showAddDialog = false },
                onConfirm = { name, url, color, favicon, isDesktop, mobileZoom, desktopZoom ->
                    viewModel.addFavorite(name, url, color, favicon, isDesktop, mobileZoom, desktopZoom)
                    showAddDialog = false
                    onShowFeedback(feedbackMsg)
                },
            )
        }

        siteToEdit?.let { site ->
            val feedbackMsg = stringResource(R.string.favorite_updated)
            EditFavoriteOverlay(
                site = site,
                currentWebView = currentWebView,
                carInputManager = carInputManager,
                inputHostView = inputHostView,
                onDismiss = { siteToEdit = null },
                onConfirm = { name, url, color, favicon, isDesktop, mobileZoom, desktopZoom ->
                    viewModel.updateFavorite(site, name, url, color, favicon, isDesktop, mobileZoom, desktopZoom)
                    siteToEdit = null
                    onShowFeedback(feedbackMsg)
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoriteCard(
    site: FavoriteSite,
    isEditMode: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onClick: () -> Unit,
) {
    val domain = remember(site.url) {
        try {
            val uri = URI(site.url)
            val host = uri.host ?: site.url
            if (uri.port != -1) "$host:${uri.port}" else host
        } catch (_: Exception) {
            site.url
        }
    }

    val faviconUrl = remember(site.url, site.faviconUrl) {
        if (!site.faviconUrl.isNullOrEmpty()) site.faviconUrl
        else "https://www.google.com/s2/favicons?domain=$domain&sz=128"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick)
                .padding(10.dp),
        ) {
            Surface(
                modifier = Modifier
                    .size(28.dp)
                    .align(Alignment.TopStart),
                shape = CircleShape,
                color = Color(site.color).copy(alpha = 0.25f),
            ) {
                AsyncImage(
                    model = faviconUrl,
                    contentDescription = null,
                    modifier = Modifier.padding(6.dp),
                    error = rememberVectorPainter(Icons.Default.Language),
                    placeholder = rememberVectorPainter(Icons.Default.Language),
                )
            }

            if (isEditMode) {
                Row(modifier = Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Edit,
                            null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Row(modifier = Modifier.align(Alignment.BottomEnd)) {
                    IconButton(onClick = onMoveLeft, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = onMoveRight, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(end = if (isEditMode) 60.dp else 0.dp)
            ) {
                Text(
                    text = site.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = domain,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun AddFavoriteCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(95.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                alpha = 0.5f
            )
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Add,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

@Composable
fun EditFavoriteOverlay(
    site: FavoriteSite? = null,
    currentWebView: android.webkit.WebView? = null,
    carInputManager: com.fcaronte.aabrowser.CarInputManager? = null,
    inputHostView: android.view.View? = null,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Long, String?, Boolean?, Float?, Float?) -> Unit,
) {
    var nameValue by remember {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                site?.name ?: ""
            )
        )
    }
    var urlValue by remember {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                site?.url ?: ""
            )
        )
    }
    var faviconValue by remember {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                site?.faviconUrl ?: ""
            )
        )
    }

    var isDesktopOverride by remember { mutableStateOf(site?.isDesktopMode) }
    var mobileZoomOverride by remember { mutableStateOf(site?.mobileZoom) }
    var desktopZoomOverride by remember { mutableStateOf(site?.desktopZoom) }
    var siteDataExpanded by remember { mutableStateOf(site == null) }
    var displaySettingsExpanded by remember { mutableStateOf(site != null) }

    val dynamicPrimaryColor = MaterialTheme.colorScheme.primary.toArgb().toLong() and 0xFFFFFFFFL
    var color by remember { mutableLongStateOf(site?.color ?: dynamicPrimaryColor) }
    val colorOptions = listOf(dynamicPrimaryColor, 0xFFFF0000, 0xFF34A853, 0xFFFBBC05, 0xFF24292E)

    DisposableEffect(Unit) {
        onDispose {
            carInputManager?.clearListeners()
        }
    }

    // Sincronizzazione con CarInputManager
    var focusedField by remember { mutableStateOf(0) } // 0: none, 1: name, 2: url, 3: favicon

    LaunchedEffect(focusedField, nameValue, urlValue, faviconValue) {
        if (carInputManager != null && focusedField > 0) {
            val currentValue = when (focusedField) {
                1 -> nameValue
                2 -> urlValue
                3 -> faviconValue
                else -> androidx.compose.ui.text.input.TextFieldValue("")
            }
            carInputManager.updateState(
                text = currentValue.text,
                selectionStart = currentValue.selection.start,
                selectionEnd = currentValue.selection.end
            )
        }
    }

    var lastCommitTime by remember { mutableLongStateOf(0L) }
    var lastText by remember { mutableStateOf("") }

    LaunchedEffect(focusedField) {
        if (focusedField > 0 && carInputManager != null) {
            carInputManager.setOnInputEventListener(
                onText = { text ->
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastCommitTime < 100 && text == lastText) {
                        return@setOnInputEventListener
                    }
                    lastCommitTime = currentTime
                    lastText = text

                    when (focusedField) {
                        1 -> {
                            val selection = nameValue.selection
                            val newText = StringBuilder(nameValue.text).replace(
                                selection.min,
                                selection.max,
                                text
                            ).toString()
                            nameValue = nameValue.copy(
                                text = newText,
                                selection = androidx.compose.ui.text.TextRange(selection.min + text.length)
                            )
                        }

                        2 -> {
                            val selection = urlValue.selection
                            val newText = StringBuilder(urlValue.text).replace(
                                selection.min,
                                selection.max,
                                text
                            ).toString()
                            urlValue = urlValue.copy(
                                text = newText,
                                selection = androidx.compose.ui.text.TextRange(selection.min + text.length)
                            )
                        }

                        3 -> {
                            val selection = faviconValue.selection
                            val newText = StringBuilder(faviconValue.text).replace(
                                selection.min,
                                selection.max,
                                text
                            ).toString()
                            faviconValue = faviconValue.copy(
                                text = newText,
                                selection = androidx.compose.ui.text.TextRange(selection.min + text.length)
                            )
                        }
                    }
                },
                onDelete = { length ->
                    when (focusedField) {
                        1 -> {
                            val selection = nameValue.selection
                            val start = (selection.start - length).coerceAtLeast(0)
                            val newText =
                                StringBuilder(nameValue.text).delete(start, selection.start)
                                    .toString()
                            nameValue = nameValue.copy(
                                text = newText,
                                selection = androidx.compose.ui.text.TextRange(start)
                            )
                        }

                        2 -> {
                            val selection = urlValue.selection
                            val start = (selection.start - length).coerceAtLeast(0)
                            val newText =
                                StringBuilder(urlValue.text).delete(start, selection.start)
                                    .toString()
                            urlValue = urlValue.copy(
                                text = newText,
                                selection = androidx.compose.ui.text.TextRange(start)
                            )
                        }

                        3 -> {
                            val selection = faviconValue.selection
                            val start = (selection.start - length).coerceAtLeast(0)
                            val newText =
                                StringBuilder(faviconValue.text).delete(start, selection.start)
                                    .toString()
                            faviconValue = faviconValue.copy(
                                text = newText,
                                selection = androidx.compose.ui.text.TextRange(start)
                            )
                        }
                    }
                },
                onSelection = { start, end ->
                    val currentValue = when (focusedField) {
                        1 -> nameValue
                        2 -> urlValue
                        3 -> faviconValue
                        else -> null
                    }
                    currentValue?.let {
                        if (it.selection.start != start || it.selection.end != end) {
                            val newVal =
                                it.copy(selection = androidx.compose.ui.text.TextRange(start, end))
                            when (focusedField) {
                                1 -> nameValue = newVal
                                2 -> urlValue = newVal
                                3 -> faviconValue = newVal
                            }
                        }
                    }
                }
            )
        } else {
            carInputManager?.clearListeners()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(
                remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                null
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 16.dp)
                .clickable(
                    remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    null
                ) { },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    if (site == null) stringResource(R.string.add_favorite_title) else stringResource(
                        R.string.edit_favorite_title
                    ), style = MaterialTheme.typography.titleLarge
                )

                SettingsSectionHeader(
                    title = stringResource(R.string.site_info_title),
                    isExpanded = siteDataExpanded,
                    onClick = { siteDataExpanded = !siteDataExpanded }
                )

                androidx.compose.animation.AnimatedVisibility(visible = siteDataExpanded) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = nameValue,
                            onValueChange = { nameValue = it },
                            label = { Text(stringResource(R.string.field_name)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        focusedField = 1
                                        inputHostView?.let { view ->
                                            view.requestFocus()
                                            carInputManager?.startInput(view)
                                        }
                                    }
                                }
                        )

                        OutlinedTextField(
                            value = urlValue,
                            onValueChange = { urlValue = it },
                            label = { Text(stringResource(R.string.field_url)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        focusedField = 2
                                        inputHostView?.let { view ->
                                            view.requestFocus()
                                            carInputManager?.startInput(view)
                                        }
                                    }
                                }
                        )

                        OutlinedTextField(
                            value = faviconValue,
                            onValueChange = { faviconValue = it },
                            label = { Text(stringResource(R.string.field_favicon)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        focusedField = 3
                                        inputHostView?.let { view ->
                                            view.requestFocus()
                                            carInputManager?.startInput(view)
                                        }
                                    }
                                }
                        )

                        Text(
                            text = stringResource(R.string.field_color),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            colorOptions.forEach { c ->
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(Color(c), RoundedCornerShape(4.dp))
                                        .clickable { color = c }
                                        .padding(4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (color == c) Icon(
                                        Icons.Default.Check,
                                        null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                SettingsSectionHeader(
                    title = stringResource(R.string.display_settings_title),
                    isExpanded = displaySettingsExpanded,
                    onClick = { displaySettingsExpanded = !displaySettingsExpanded },
                )

                androidx.compose.animation.AnimatedVisibility(visible = displaySettingsExpanded) {
                    val isDesktop = isDesktopOverride ?: AppSettings.desktopMode.value
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SettingsSwitchItem(
                            label = stringResource(R.string.desktop_mode_label),
                            description = stringResource(R.string.desktop_mode_desc_site),
                            checked = isDesktop,
                            onCheckedChange = { isDesktopOverride = it }
                        )

                        Column {
                            val currentZoom =
                                if (isDesktop) (desktopZoomOverride ?: AppSettings.desktopScale.value)
                                else (mobileZoomOverride ?: AppSettings.displayScale.value)

                            Text(
                                text = (if (isDesktop) stringResource(R.string.desktop_zoom_label, (currentZoom * 100).toInt())
                                else stringResource(R.string.mobile_zoom_label, (currentZoom * 100).toInt())),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            androidx.compose.material3.Slider(
                                value = currentZoom,
                                onValueChange = {
                                    if (isDesktop) desktopZoomOverride = it
                                    else mobileZoomOverride = it
                                },
                                valueRange = 0.25f..1.5f,
                                steps = 24
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
                    Button(onClick = {
                        val finalUrl =
                            if (!urlValue.text.startsWith("http://") && !urlValue.text.startsWith("https://")) "https://${urlValue.text}" else urlValue.text

                        if (faviconValue.text.isBlank()) {
                            if (currentWebView != null && currentWebView.url?.contains(
                                    URI(
                                        finalUrl
                                    ).host ?: ""
                                ) == true
                            ) {
                                currentWebView.evaluateJavascript(
                                    "(function() { const icon = document.querySelector('link[rel=\"apple-touch-icon\"]') || document.querySelector('link[rel=\"icon\"]'); return icon ? icon.href : ''; })();"
                                ) { result ->
                                    val extracted =
                                        result?.removeSurrounding("\"")?.takeIf { it.isNotBlank() }
                                    val fallback = "https://www.google.com/s2/favicons?domain=${
                                        URI(finalUrl).host ?: finalUrl
                                    }&sz=128"
                                    onConfirm(
                                        nameValue.text,
                                        finalUrl,
                                        color,
                                        extracted ?: fallback,
                                        isDesktopOverride,
                                        mobileZoomOverride,
                                        desktopZoomOverride
                                    )
                                }
                            } else {
                                val fallback = "https://www.google.com/s2/favicons?domain=${
                                    URI(finalUrl).host ?: finalUrl
                                }&sz=128"
                                onConfirm(
                                    nameValue.text,
                                    finalUrl,
                                    color,
                                    fallback,
                                    isDesktopOverride,
                                    mobileZoomOverride,
                                    desktopZoomOverride
                                )
                            }
                        } else {
                            onConfirm(
                                nameValue.text,
                                finalUrl,
                                color,
                                faviconValue.text,
                                isDesktopOverride,
                                mobileZoomOverride,
                                desktopZoomOverride
                            )
                        }
                    }) {
                        Text(stringResource(R.string.confirm_button))
                    }
                }
            }
        }
    }
}