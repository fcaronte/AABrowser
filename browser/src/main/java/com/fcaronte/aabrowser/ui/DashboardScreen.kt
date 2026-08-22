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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
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

    val isDarkTheme = MaterialTheme.colorScheme.background.red < 0.5f

    LaunchedEffect(Unit) {
        updateInfo = UpdateManager.checkForUpdates(context)
        updateInfo?.let {
            if (it.isAvailable) {
                UpdateManager.showUpdateNotification(context, it)
            }
        }
    }

    if (showAddDialog) {
        val feedbackMsg = stringResource(R.string.favorite_added)
        EditFavoriteScreen(
            currentWebView = currentWebView,
            carInputManager = carInputManager,
            inputHostView = inputHostView,
            onBack = { showAddDialog = false },
            onConfirm = { name, url, color, favicon, isDesktop, mobileZoom, desktopZoom ->
                viewModel.addFavorite(name, url, color, favicon, isDesktop, mobileZoom, desktopZoom)
                showAddDialog = false
                onShowFeedback(feedbackMsg)
            }
        )
        return
    }

    siteToEdit?.let { site ->
        val feedbackMsg = stringResource(R.string.favorite_updated)
        EditFavoriteScreen(
            site = site,
            currentWebView = currentWebView,
            carInputManager = carInputManager,
            inputHostView = inputHostView,
            onBack = { siteToEdit = null },
            onConfirm = { name, url, color, favicon, isDesktop, mobileZoom, desktopZoom ->
                viewModel.updateFavorite(site, name, url, color, favicon, isDesktop, mobileZoom, desktopZoom)
                siteToEdit = null
                onShowFeedback(feedbackMsg)
            }
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Mostra l'immagine di sfondo sfumata solo se il tema è scuro
        if (isDarkTheme) {
            AsyncImage(
                model = "https://images.unsplash.com/photo-1614850523296-d8c1af93d400?q=80&w=2070&auto=format&fit=crop",
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                alpha = 0.2f,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }

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
                            modifier = Modifier.padding(bottom = 2.dp) // Piccolo offset per allineare meglio le baseline
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
    }
}