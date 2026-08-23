package com.fcaronte.aabrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.model.TabManager
import com.fcaronte.aabrowser.model.TabState
import java.net.URI

@Composable
fun PersistentTabBar(
    modifier: Modifier = Modifier,
    onTabSelected: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onAddTab: () -> Unit,
    onGoBack: () -> Unit,
    onGoForward: () -> Unit
) {
    val tabs = TabManager.tabs
    val activeIndex = TabManager.activeTabIndex
    val listState = rememberLazyListState()

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            listState.animateScrollToItem(activeIndex)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(tabs, key = { _, tab -> tab.id }) { index, tab ->
                    TabItem(
                        tab = tab,
                        isActive = index == activeIndex,
                        onClick = { onTabSelected(index) },
                        onClose = { onCloseTab(index) }
                    )
                }

                item {
                    IconButton(
                        onClick = onAddTab,
                        modifier = Modifier
                            .size(38.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(12.dp)
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.new_tab),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Tasti Navigazione a destra
            VerticalDivider(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(vertical = 12.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                IconButton(
                    onClick = onGoBack,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back_button),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(
                    onClick = onGoForward,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.forward_button),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun TabItem(
    tab: TabState,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    val domain = remember(tab.url) {
        try {
            URI(tab.url).host?.removePrefix("www.") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    val faviconUrl = remember(tab.url, tab.faviconUrl) {
        if (!tab.faviconUrl.isNullOrEmpty()) tab.faviconUrl
        else "https://www.google.com/s2/favicons?domain=$domain&sz=128"
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .widthIn(max = 180.dp)
            .fillMaxHeight(),
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer 
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = if (isActive) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AsyncImage(
                model = faviconUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Fit
            )

            Text(
                text = tab.title.ifBlank { stringResource(R.string.new_tab) },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer 
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )

            IconButton(
                onClick = onClose,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close_button),
                    modifier = Modifier.size(14.dp),
                    tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) 
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}
