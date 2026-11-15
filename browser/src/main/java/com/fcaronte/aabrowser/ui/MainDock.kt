package com.fcaronte.aabrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveModerator
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fcaronte.aabrowser.settings.SidebarAction
import com.fcaronte.aabrowser.utils.LocalPlayer
import com.fcaronte.aabrowser.utils.UrlUtils

/** Larghezza del dock della Main: stretta, i tasti da 44 dp bastano e alla pagina resta più spazio. */
val MAIN_DOCK_WIDTH = 52.dp

/**
 * Il dock della Main: la stessa sidebar della Wide (SidebarAction, scelta e ordine nelle Impostazioni), sul bordo di inizio.
 * Con il dock acceso il tasto rotondo non apre più il menu a ventaglio: apre e chiude il dock (NavigationOverlay). I tasti
 * che aspettano una pagina web (scudo, preferiti, condividi) sono spenti sulla home e sulle pagine dell'app. Se i tasti non
 * ci stanno in altezza (telefono di taglio) si scorre; i tocchi sul dock non arrivano alla pagina sotto.
 */
@Composable
fun MainDock(
    actions: List<SidebarAction>,
    currentUrl: String,
    adBlockOff: Boolean?,
    desktopModeOn: Boolean?,
    onAction: (SidebarAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val webPage = UrlUtils.isWebUrl(currentUrl) && !LocalPlayer.isPlayerUrl(currentUrl)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(MAIN_DOCK_WIDTH)
            .background(Color(0xE61E1E1E))
            .pointerInput(Unit) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            actions.forEach { action ->
                val enabled = when (action) {
                    SidebarAction.ADBLOCK -> adBlockOff != null
                    SidebarAction.FAVORITE, SidebarAction.SHARE -> webPage
                    else -> true
                }
                IconButton(
                    onClick = { onAction(action) },
                    enabled = enabled,
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = Color.White,
                        disabledContentColor = Color.White.copy(alpha = 0.35f)
                    )
                ) {
                    Icon(
                        imageVector = action.dockIcon(adBlockOff, desktopModeOn),
                        contentDescription = stringResource(action.labelRes),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

// Scudo barrato se il sito è escluso dall'AdBlock, telefono se il sito è già in desktop (come nel tasto a ventaglio)
private fun SidebarAction.dockIcon(adBlockOff: Boolean?, desktopModeOn: Boolean?): ImageVector = when (this) {
    SidebarAction.ADBLOCK -> if (adBlockOff == true) Icons.Default.RemoveModerator else Icons.Default.VerifiedUser
    SidebarAction.DESKTOP -> if (desktopModeOn == true) Icons.Default.Smartphone else Icons.Default.DesktopWindows
    else -> icon()
}

// Le icone dei tasti della sidebar nella Main (nella Wide sono disegnate a mano: WideDockIcons)
private fun SidebarAction.icon(): ImageVector = when (this) {
    SidebarAction.HOME -> Icons.Default.Home
    SidebarAction.BACK -> Icons.AutoMirrored.Filled.ArrowBack
    SidebarAction.FORWARD -> Icons.AutoMirrored.Filled.ArrowForward
    SidebarAction.RELOAD -> Icons.Default.Refresh
    SidebarAction.TABS -> Icons.Default.Layers
    SidebarAction.SEARCH -> Icons.Default.Search
    SidebarAction.FAVORITE -> Icons.Default.Star
    SidebarAction.ADBLOCK -> Icons.Default.VerifiedUser
    SidebarAction.DESKTOP -> Icons.Default.DesktopWindows
    SidebarAction.VOICE -> Icons.Default.Mic
    SidebarAction.SHARE -> Icons.Default.Share
    SidebarAction.SETTINGS -> Icons.Default.Settings
    SidebarAction.EXIT -> Icons.AutoMirrored.Filled.ExitToApp
}
