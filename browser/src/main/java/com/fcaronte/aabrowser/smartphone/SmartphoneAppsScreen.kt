package com.fcaronte.aabrowser.smartphone

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartphoneAppsScreen(
    viewModel: SmartphoneAppsViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val filteredApps by viewModel.filteredApps.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val favoritePackages by viewModel.favoritePackages.collectAsState()

    val isShizukuInstalled = remember { ShizukuManager.isShizukuInstalled(context) }
    var isShizukuRunning by remember { mutableStateOf(ShizukuManager.isShizukuRunning()) }
    var hasShizukuPerm by remember { mutableStateOf(ShizukuManager.hasPermission()) }

    LaunchedEffect(Unit) {
        val bound = ShizukuManager.tryBindShizuku(context)
        if (bound) {
            isShizukuRunning = true
            hasShizukuPerm = ShizukuManager.hasPermission()
        }
    }

    DisposableEffect(Unit) {
        ShizukuManager.registerListeners(
            onPermissionResult = { _, grantResult ->
                hasShizukuPerm = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
                isShizukuRunning = ShizukuManager.isShizukuRunning()
            },
            onBinderReceived = {
                isShizukuRunning = true
                hasShizukuPerm = ShizukuManager.hasPermission()
            }
        )
        onDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Smartphone (Shizuku)") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("← Indietro")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Shizuku Status Banner
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (hasShizukuPerm) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = when {
                            !isShizukuInstalled -> "Shizuku non è installato sul dispositivo."
                            !isShizukuRunning -> "Shizuku non è in esecuzione (avvia l'app Shizuku)."
                            !hasShizukuPerm -> "Permesso Shizuku non concesso (tocca per richiedere)."
                            else -> "Shizuku attivo e autorizzato correttamente."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (isShizukuInstalled && isShizukuRunning && !hasShizukuPerm) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { ShizukuManager.requestPermission() }
                        ) {
                            Text("Autorizza Shizuku")
                        }
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                placeholder = { Text("Cerca applicazione...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Cancella")
                        }
                    }
                },
                singleLine = true
            )

            // App List
            if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Nessuna applicazione trovata",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val isFav = favoritePackages.contains(app.packageName)
                        SmartphoneAppItem(
                            app = app,
                            isFavorite = isFav,
                            onToggleFavorite = { viewModel.toggleFavorite(app.packageName) },
                            onClick = { viewModel.launchAppDirectly(app.packageName) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SmartphoneAppItem(
    app: SmartphoneApp,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App Icon (Safe Conversion)
            val bitmap = remember(app.icon) {
                try {
                    val drawable = app.icon
                    if (drawable is BitmapDrawable && drawable.bitmap != null) {
                        drawable.bitmap.asImageBitmap()
                    } else if (drawable != null) {
                        val width = 96
                        val height = 96
                        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bmp)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        bmp.asImageBitmap()
                    } else {
                        null
                    }
                } catch (e: Throwable) {
                    null
                }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp)
                )
            } else {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(app.appName.take(1))
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // App Names
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Favorite Toggle Button
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = "Preferito",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
