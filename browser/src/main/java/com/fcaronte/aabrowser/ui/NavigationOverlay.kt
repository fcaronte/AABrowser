package com.fcaronte.aabrowser.ui

import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fcaronte.aabrowser.CarInputManager
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.settings.FABLocation
import com.fcaronte.aabrowser.settings.TabBarMode
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class MenuItem(
    val icon: ImageVector,
    val labelRes: Int,
    val onClick: () -> Unit,
)

@Composable
fun NavigationOverlay(
    currentUrl: String,
    onGoBack: () -> Unit,
    onGoForward: () -> Unit,
    onGoHome: () -> Unit,
    onReload: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTabManager: () -> Unit,
    onSearch: (String) -> Unit,
    onExit: () -> Unit,
    onAddToFavorites: () -> Unit = {},
    carInputManager: CarInputManager? = null,
    webView: WebView? = null,
    isVisible: Boolean = true,
    showMenu: Boolean = false,
    onShowMenuChange: (Boolean) -> Unit = {},
    onInteraction: () -> Unit = {},
) {
    var showQRCode by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }

    val fabLocation by AppSettings.fabLocation
    val tabBarMode by AppSettings.tabBarMode

    val baseAlignment = when (fabLocation) {
        FABLocation.BOTTOM_RIGHT -> Alignment.BottomEnd
        FABLocation.BOTTOM_LEFT -> Alignment.BottomStart
        FABLocation.TOP_RIGHT -> Alignment.TopEnd
        FABLocation.TOP_LEFT -> Alignment.TopStart
    }

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Transizione fluida basata esclusivamente sulla visibilità fornita dall'InactivityTracker
    val animatedFabAlpha by animateFloatAsState(
        targetValue = if (isVisible || showMenu) 1f else 0f,
        animationSpec = tween(durationMillis = 350),
        label = "fabAlpha"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidth = constraints.maxWidth.toFloat()
        val screenHeight = constraints.maxHeight.toFloat()

        val startX = when (baseAlignment) {
            Alignment.BottomEnd, Alignment.TopEnd -> screenWidth - 16.dp.value
            else -> 16.dp.value
        }
        val topOffset = when (tabBarMode) {
            TabBarMode.OFF -> 0f
            TabBarMode.ALWAYS_ON -> 48.dp.value
            TabBarMode.AUTO_HIDE -> if (isVisible) 48.dp.value else 0f
        }

        val startY = when (baseAlignment) {
            Alignment.BottomEnd, Alignment.BottomStart -> screenHeight - 16.dp.value
            else -> 16.dp.value + topOffset
        }

        val currentX = startX + offsetX
        val currentY = startY + offsetY

        val isRightSide = currentX > screenWidth / 2f
        val isBottomSide = currentY > screenHeight / 2f

        val startAngle = when {
            !isRightSide && !isBottomSide -> 0f
            isRightSide && !isBottomSide -> 90f
            isRightSide && isBottomSide -> 180f
            else -> 270f
        }

        // Sfondo per chiusura al tocco esterno quando il menu è aperto
        if (showMenu) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onInteraction()
                        onShowMenuChange(false)
                    }
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .align(baseAlignment)
                    .offset { IntOffset(x = offsetX.roundToInt(), y = offsetY.roundToInt()) }
                    .padding(16.dp)
            ) {
                Box {
                    val menuItems = remember(currentUrl) {
                        listOf(
                            MenuItem(Icons.AutoMirrored.Filled.ArrowBack, R.string.back_button) { onInteraction(); onGoBack(); onShowMenuChange(false) },
                            MenuItem(Icons.AutoMirrored.Filled.ArrowForward, R.string.forward_button) { onInteraction(); onGoForward(); onShowMenuChange(false) },
                            MenuItem(Icons.Default.Home, R.string.home_button) { onInteraction(); onGoHome(); onShowMenuChange(false) },
                            MenuItem(Icons.Default.Layers, R.string.tabs_button) { onInteraction(); onOpenTabManager(); onShowMenuChange(false) },
                            MenuItem(Icons.Default.Star, R.string.add_to_favorites) { onInteraction(); onAddToFavorites(); onShowMenuChange(false) },
                            MenuItem(Icons.Default.Refresh, R.string.reload_button) { onInteraction(); onReload(); onShowMenuChange(false) },
                            MenuItem(Icons.Default.Search, R.string.search_button) { onInteraction(); showSearchDialog = true; onShowMenuChange(false) },
                            MenuItem(Icons.Default.Share, R.string.share_button) { onInteraction(); showQRCode = true; onShowMenuChange(false) },
                            MenuItem(Icons.Default.Settings, R.string.settings_button) { onInteraction(); onOpenSettings(); onShowMenuChange(false) },
                            MenuItem(Icons.AutoMirrored.Filled.ExitToApp, R.string.exit_button) {
                                onInteraction()
                                onShowMenuChange(false)
                                onExit()
                            },
                        )
                    }

                    val sweepAngle = 90f
                    val innerRadius = 105.dp
                    val outerRadius = 180.dp

                    menuItems.forEachIndexed { index, item ->
                        val isOuter = index >= 4
                        val radius = if (isOuter) outerRadius else innerRadius
                        val arcIndex = if (isOuter) index - 4 else index
                        val arcTotal = if (isOuter) 6 else 4

                        val angle = startAngle + (sweepAngle / (arcTotal - 1).coerceAtLeast(1)) * arcIndex
                        val angleRad = Math.toRadians(angle.toDouble())

                        val targetX = if (showMenu) (cos(angleRad) * radius.value).dp else 0.dp
                        val targetY = if (showMenu) (sin(angleRad) * radius.value).dp else 0.dp

                        val animatedOffsetX by animateDpAsState(
                            targetValue = targetX,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                            label = "offsetX",
                        )
                        val animatedOffsetY by animateDpAsState(
                            targetValue = targetY,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                            label = "offsetY",
                        )
                        val alpha by animateFloatAsState(
                            targetValue = if (showMenu) 1f else 0f,
                            label = "alpha"
                        )

                        if (alpha > 0f) {
                            FloatingActionButton(
                                onClick = {
                                    onInteraction()
                                    item.onClick()
                                },
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                                shape = CircleShape,
                                modifier = Modifier
                                    .size(46.dp)
                                    .graphicsLayer(alpha = alpha)
                                    .offset {
                                        IntOffset(
                                            x = animatedOffsetX.roundToPx(),
                                            y = animatedOffsetY.roundToPx()
                                        )
                                    },
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = stringResource(id = item.labelRes),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    // FAB Principale
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shadowElevation = if (animatedFabAlpha > 0.1f) 6.dp else 0.dp,
                        modifier = Modifier
                            .size(58.dp)
                            .graphicsLayer(alpha = animatedFabAlpha)
                            .then(
                                if (!showMenu) {
                                    Modifier
                                        // Sostituiamo detectTapGestures con un .clickable diretto,
                                        // che è molto più reattivo sui sistemi automotive touch
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            onInteraction()
                                            onShowMenuChange(true)
                                        }
                                        .pointerInput(baseAlignment, screenWidth, screenHeight) {
                                            detectDragGestures { change, dragAmount ->
                                                change.consume()
                                                onInteraction()

                                                val newX = offsetX + dragAmount.x
                                                val newY = offsetY + dragAmount.y

                                                val fabSize = 58f
                                                val padding = 32f

                                                val (minX, maxX, minY, maxY) = when (baseAlignment) {
                                                    Alignment.BottomEnd -> arrayOf(-startX + fabSize + padding, 0f, -startY + fabSize + padding, 0f)
                                                    Alignment.BottomStart -> arrayOf(0f, screenWidth - startX - fabSize - padding, -startY + fabSize + padding, 0f)
                                                    Alignment.TopEnd -> arrayOf(-startX + fabSize + padding, 0f, 0f, screenHeight - startY - fabSize - padding)
                                                    else -> arrayOf(0f, screenWidth - startX - fabSize - padding, 0f, screenHeight - startY - fabSize - padding)
                                                }

                                                offsetX = newX.coerceIn(minX, maxX)
                                                offsetY = newY.coerceIn(minY, maxY)
                                            }
                                        }
                                } else {
                                    Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        onInteraction()
                                        onShowMenuChange(false)
                                    }
                                }
                            )
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                imageVector = if (showMenu) Icons.Default.Close else Icons.Default.Menu,
                                contentDescription = stringResource(R.string.menu_button),
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                }
            }
        }

        // Overlay QR Code
        if (showQRCode) {
            QRCodeOverlay(
                currentUrl = currentUrl,
                onDismiss = { showQRCode = false }
            )
        }

        // Overlay Ricerca
        if (showSearchDialog) {
            SearchOverlay(
                onSearch = { query ->
                    onSearch(query)
                    showSearchDialog = false
                },
                onDismiss = { showSearchDialog = false },
                carInputManager = carInputManager,
                inputView = webView,
            )
        }
    }
}