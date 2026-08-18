package com.fcaronte.aabrowser.ui

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onSiteSelected: (String) -> Unit,
    onOpenTabManager: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit = {},
) {
    val favorites = viewModel.favorites
    var isEditMode by remember { mutableStateOf(value = false) }
    var siteToEdit by remember { mutableStateOf<FavoriteSite?>(null) }
    var showAddDialog by remember { mutableStateOf(value = false) }
    val lastUrl by AppSettings.lastUrl
    val useThreeColumns by AppSettings.dashboardThreeColumns

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
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
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )

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

            LazyVerticalGrid(
                columns = GridCells.Fixed(if (useThreeColumns) 3 else 2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(favorites) { index, site ->
                    FavoriteCard(
                        site = site,
                        isEditMode = isEditMode,
                        onEdit = { siteToEdit = site },
                        onDelete = { viewModel.removeFavorite(site) },
                        onMoveLeft = { if (index > 0) viewModel.moveFavorite(index, index - 1) },
                        onMoveRight = {
                            if (index < (favorites.size - 1)) {
                                viewModel.moveFavorite(index, index + 1)
                            }
                        },
                        onClick = {
                            if (!isEditMode) onSiteSelected(site.url)
                        },
                    )
                }

                if (isEditMode) {
                    item { AddFavoriteCard(onClick = { showAddDialog = true }) }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { if (lastUrl.isNotEmpty()) onSiteSelected(lastUrl) },
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    enabled = lastUrl.isNotEmpty(),
                    shape = RoundedCornerShape(23.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(Icons.Default.History, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.resume_button), fontSize = 11.sp, maxLines = 1)
                }

                Button(
                    onClick = onOpenTabManager,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(23.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(Icons.Default.Layers, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.tabs_button), fontSize = 11.sp, maxLines = 1)
                }

                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(23.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(Icons.Default.Settings, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.settings_button), fontSize = 11.sp, maxLines = 1)
                }
            }
        }

        if (showAddDialog) {
            EditFavoriteOverlay(
                onDismiss = { showAddDialog = false },
                onConfirm = { name, url, color, favicon ->
                    viewModel.addFavorite(name, url, color, favicon)
                    showAddDialog = false
                },
            )
        }

        siteToEdit?.let { site ->
            EditFavoriteOverlay(
                site = site,
                onDismiss = { siteToEdit = null },
                onConfirm = { name, url, color, favicon ->
                    viewModel.updateFavorite(site, name, url, color, favicon)
                    siteToEdit = null
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
                .combinedClickable(onClick = onClick, onLongClick = onEdit)
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
                        Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                    }
                }
                Row(modifier = Modifier.align(Alignment.BottomEnd)) {
                    IconButton(onClick = onMoveLeft, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onMoveRight, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                    }
                }
            }

            Column(modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(end = if (isEditMode) 60.dp else 0.dp)) {
                Text(text = site.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = domain, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
fun EditFavoriteOverlay(
    site: FavoriteSite? = null,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Long, String?) -> Unit,
) {
    var name by remember { mutableStateOf(site?.name ?: "") }
    var url by remember { mutableStateOf(site?.url ?: "") }
    var faviconUrl by remember { mutableStateOf(site?.faviconUrl ?: "") }
    val dynamicPrimaryColor = MaterialTheme.colorScheme.primary.toArgb().toLong() and 0xFFFFFFFFL
    var color by remember { mutableLongStateOf(site?.color ?: dynamicPrimaryColor) }
    val colorOptions = listOf(dynamicPrimaryColor, 0xFFFF0000, 0xFF34A853, 0xFFFBBC05, 0xFF24292E)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (site == null) stringResource(R.string.add_favorite_title) else stringResource(R.string.edit_favorite_title), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.field_name)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text(stringResource(R.string.field_url)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = faviconUrl, onValueChange = { faviconUrl = it }, label = { Text(stringResource(R.string.field_favicon)) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.field_color))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colorOptions.forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(c), RoundedCornerShape(4.dp))
                                .clickable { color = c }
                                .padding(4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (color == c) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
                    Button(onClick = { onConfirm(name, url, color, faviconUrl.ifBlank { null }) }) { Text(stringResource(R.string.confirm_button)) }
                }
            }
        }
    }
}
