package com.fcaronte.aabrowser.ui

import android.webkit.WebView
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveModerator
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fcaronte.aabrowser.CarInputManager
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.settings.FABLocation
import com.fcaronte.aabrowser.settings.SidebarAction
import com.fcaronte.aabrowser.settings.SidebarHost
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
    adBlockOff: Boolean? = null, // null: niente sito (pagina locale), true: sito nella whitelist dell'AdBlock
    onToggleAdBlock: () -> Unit = {},
    desktopModeOn: Boolean? = null,
    onToggleDesktopMode: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenTabManager: () -> Unit,
    onSearch: (String) -> Unit,
    onVoiceCommand: () -> Unit = {},
    onExit: () -> Unit,
    onAddToFavorites: () -> Unit = {},
    carInputManager: CarInputManager? = null,
    webView: WebView? = null,
    isVisible: Boolean = true,
    showMenu: Boolean = false,
    onShowMenuChange: (Boolean) -> Unit = {},
    onInteraction: () -> Unit = {},
    // Con il dock nella Main (Impostazioni) il tasto rotondo non apre il menu a ventaglio: apre e chiude il dock, la sidebar
    // con gli stessi tasti della Wide
    dockMode: Boolean = false,
    dockOpen: Boolean = true,
    onDockToggle: () -> Unit = {},
    // Una tastiera Bluetooth (Ctrl+L): ogni numero nuovo apre la ricerca
    searchRequest: Int = 0,
) {
    var showQRCode by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    LaunchedEffect(searchRequest) {
        if (searchRequest > 0) showSearchDialog = true
    }

    // Cosa fa ogni tasto del dock: gli stessi gesti del menu a ventaglio
    val sidebarActions by AppSettings.sidebarActions
    val performDockAction: (SidebarAction) -> Unit = { action ->
        onInteraction()
        when (action) {
            SidebarAction.HOME -> onGoHome()
            SidebarAction.BACK -> onGoBack()
            SidebarAction.FORWARD -> onGoForward()
            SidebarAction.RELOAD -> onReload()
            SidebarAction.TABS -> onOpenTabManager()
            SidebarAction.SEARCH -> showSearchDialog = true
            SidebarAction.FAVORITE -> onAddToFavorites()
            SidebarAction.ADBLOCK -> onToggleAdBlock()
            SidebarAction.DESKTOP -> onToggleDesktopMode()
            SidebarAction.VOICE -> onVoiceCommand()
            SidebarAction.SHARE -> showQRCode = true
            SidebarAction.SETTINGS -> onOpenSettings()
            SidebarAction.EXIT -> onExit()
        }
    }

    PhoneBackHandler(enabled = showMenu) {
        onInteraction()
        onShowMenuChange(false)
    }

    val fabLocation by AppSettings.fabLocation
    val tabBarMode by AppSettings.tabBarMode

    val baseAlignment = when (fabLocation) {
        FABLocation.BOTTOM_RIGHT -> Alignment.BottomEnd
        FABLocation.BOTTOM_LEFT -> Alignment.BottomStart
        FABLocation.TOP_RIGHT -> Alignment.TopEnd
        FABLocation.TOP_LEFT -> Alignment.TopStart
    }

    // In basso parte un centimetro sopra l'angolo, per non coprire i tasti in basso dei player (utente, 10/11/2026);
    // trascinandolo si porta anche più giù. Cambiando l'angolo nelle Impostazioni si riparte da lì
    val bottomLiftPx = with(LocalDensity.current) { FAB_BOTTOM_LIFT.toPx() }
    val startsAtBottom = baseAlignment == Alignment.BottomEnd || baseAlignment == Alignment.BottomStart
    var offsetX by remember(baseAlignment) { mutableFloatStateOf(0f) }
    var offsetY by remember(baseAlignment) { mutableFloatStateOf(if (startsAtBottom) -bottomLiftPx else 0f) }

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

        // Il dock della Main: la sidebar della Wide, sul bordo di inizio e sotto la barra delle schede
        if (dockMode && dockOpen) {
            val dockTop = when (tabBarMode) {
                TabBarMode.OFF -> 0.dp
                TabBarMode.ALWAYS_ON -> 48.dp
                TabBarMode.AUTO_HIDE -> if (isVisible) 48.dp else 0.dp
            }
            MainDock(
                actions = SidebarAction.forHost(sidebarActions, SidebarHost.MAIN),
                currentUrl = currentUrl,
                adBlockOff = adBlockOff,
                desktopModeOn = desktopModeOn,
                onAction = performDockAction,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(top = dockTop)
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
                    val menuItems = remember(currentUrl, adBlockOff, desktopModeOn) {
                        listOf(
                            // Inner Ring (2 items: AdBlock, Desktop Mode)
                            MenuItem(
                                if (adBlockOff == true) Icons.Default.RemoveModerator else Icons.Default.VerifiedUser,
                                R.string.adblock_this_site
                            ) { onInteraction(); onToggleAdBlock(); onShowMenuChange(false) },
                            MenuItem(
                                if (desktopModeOn == true) Icons.Default.Smartphone else Icons.Default.DesktopWindows,
                                when (desktopModeOn) {
                                    true -> R.string.desktop_mode_switch_to_mobile
                                    false -> R.string.desktop_mode_switch_to_desktop
                                    null -> R.string.desktop_mode_this_site
                                }
                            ) { onInteraction(); onToggleDesktopMode(); onShowMenuChange(false) },

                            // Middle Ring (5 items: Back, Forward, Home, Tabs, Refresh)
                            MenuItem(Icons.AutoMirrored.Filled.ArrowBack, R.string.back_button) { onInteraction(); onGoBack(); onShowMenuChange(false) },
                            MenuItem(Icons.AutoMirrored.Filled.ArrowForward, R.string.forward_button) { onInteraction(); onGoForward(); onShowMenuChange(false) },
                            MenuItem(Icons.Default.Home, R.string.home_button) { onInteraction(); onGoHome(); onShowMenuChange(false) },
                            MenuItem(Icons.Default.Layers, R.string.tabs_button) { onInteraction(); onOpenTabManager(); onShowMenuChange(false) },
                            MenuItem(Icons.Default.Refresh, R.string.reload_button) { onInteraction(); onReload(); onShowMenuChange(false) },

                            // Outer Ring (6 items)
                            MenuItem(Icons.Default.Search, R.string.search_button) { onInteraction(); showSearchDialog = true; onShowMenuChange(false) },
                            MenuItem(Icons.Default.Star, R.string.add_to_favorites) { onInteraction(); onAddToFavorites(); onShowMenuChange(false) },
                            MenuItem(Icons.Default.Mic, R.string.voice_command_button) { onInteraction(); onVoiceCommand(); onShowMenuChange(false) },
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
                    val innerRadius = 85.dp
                    val middleRadius = 145.dp
                    val outerRadius = 205.dp
                    val innerCount = 2
                    val middleCount = 5

                    // Con il dock acceso non c'è il menu a ventaglio: i suoi tasti stanno nel dock
                    if (!dockMode) menuItems.forEachIndexed { index, item ->
                        val ring = when {
                            index < innerCount -> 0
                            index < innerCount + middleCount -> 1
                            else -> 2
                        }
                        val radius = when (ring) {
                            0 -> innerRadius
                            1 -> middleRadius
                            else -> outerRadius
                        }
                        val arcIndex = when (ring) {
                            0 -> index
                            1 -> index - innerCount
                            else -> index - innerCount - middleCount
                        }
                        val arcTotal = when (ring) {
                            0 -> innerCount
                            1 -> middleCount
                            else -> menuItems.size - innerCount - middleCount
                        }

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
                                            if (dockMode) onDockToggle() else onShowMenuChange(true)
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
                                imageVector = when {
                                    dockMode -> if (dockOpen) Icons.AutoMirrored.Filled.MenuOpen else Icons.Default.Menu
                                    showMenu -> Icons.Default.Close
                                    else -> Icons.Default.Menu
                                },
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

// Quanto sale dall'angolo in basso la posizione di partenza del tasto di navigazione: circa un centimetro
private val FAB_BOTTOM_LIFT = 64.dp
