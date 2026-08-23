package com.fcaronte.aabrowser.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.model.TabManager
import com.fcaronte.aabrowser.model.TabState
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabManagerScreen(
    onTabSelected: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onAddTab: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tabs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_button)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        TabManager.closeAllTabs()
                        onBack()
                    }) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.close_all_tabs)
                        )
                    }
                    IconButton(onClick = onAddTab) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.new_tab)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(TabManager.tabs) { index, tab ->
                TabCard(
                    tab = tab,
                    isActive = index == TabManager.activeTabIndex,
                    onClick = { onTabSelected(index) },
                    onClose = {
                        onCloseTab(index)
                        if (TabManager.tabs.isEmpty()) {
                            onBack()
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun TabCard(
    tab: TabState,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    val containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val contentColor = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    val domain = remember(tab.url) {
        try {
            val uri = URI(tab.url)
            uri.host ?: tab.url
        } catch (_: Exception) {
            tab.url
        }
    }

    val faviconUrl = remember(tab.url, tab.faviconUrl) {
        if (!tab.faviconUrl.isNullOrEmpty()) tab.faviconUrl
        else "https://www.google.com/s2/favicons?domain=$domain&sz=128"
    }

    // Una scheda è considerata "Pronta/Caricata" se ha titolo e url assegnati
    val isLoaded = tab.title.isNotBlank() && tab.title != tab.url && tab.url.isNotBlank()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        border = if (isActive) BorderStroke(
            2.dp,
            MaterialTheme.colorScheme.primary
        ) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Favicon della pagina
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ) {
                AsyncImage(
                    model = faviconUrl,
                    contentDescription = null,
                    modifier = Modifier.padding(7.dp),
                    error = rememberVectorPainter(Icons.Default.Language),
                    placeholder = rememberVectorPainter(Icons.Default.Language)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (tab.title.isNotBlank()) tab.title else stringResource(R.string.new_tab),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Spacer(Modifier.width(6.dp))

                    // Indicatore di stato caricamento
                    if (isLoaded) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = stringResource(R.string.status_loaded),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    } else if (tab.url.isNotBlank()) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(Modifier.height(2.dp))

                Text(
                    text = if (tab.url.isNotBlank()) tab.url else "about:blank",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close_tab),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}