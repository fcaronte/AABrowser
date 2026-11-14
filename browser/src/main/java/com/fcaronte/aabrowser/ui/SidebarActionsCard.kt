package com.fcaronte.aabrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.settings.SidebarAction
import com.fcaronte.aabrowser.settings.SidebarHost

/**
 * Impostazioni dei tasti della sidebar (il dock della Wide, e della Main se la si vuole): quali mostrare (interruttore) e in
 * che ordine (frecce). Home resta sempre: serve a non restare chiusi in una pagina. Chiusa di serie, come quella della barra
 * delle schede: tocca il titolo per aprirla. I tasti si propongono tutti, anche quelli che la Wide non può fare (le schede):
 * l'elenco è uno solo per le due app, ognuna mostra quelli che le servono.
 */
@Composable
fun SidebarActionsCard() {
    val context = LocalContext.current
    val shown by AppSettings.sidebarActions
    val hidden = SidebarAction.ALL.filter { it !in shown }
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.sidebar_actions_label),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                // Chiusa: quanti tasti ci sono; aperta: la spiegazione
                Text(
                    text = if (expanded) stringResource(R.string.sidebar_actions_desc)
                    else stringResource(R.string.sidebar_actions_count, shown.size),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }

        if (expanded) {
            // Prima quelli mostrati, nell'ordine in cui compaiono; sotto quelli nascosti
            shown.forEachIndexed { index, action ->
                SidebarActionRow(
                    action = action,
                    on = true,
                    canMoveUp = index > 0,
                    canMoveDown = index < shown.lastIndex,
                    onToggle = { AppSettings.setSidebarActions(context, SidebarAction.without(shown, action)) },
                    onUp = { AppSettings.setSidebarActions(context, SidebarAction.moved(shown, index, -1)) },
                    onDown = { AppSettings.setSidebarActions(context, SidebarAction.moved(shown, index, 1)) }
                )
            }
            hidden.forEach { action ->
                SidebarActionRow(
                    action = action,
                    on = false,
                    canMoveUp = false,
                    canMoveDown = false,
                    onToggle = { AppSettings.setSidebarActions(context, SidebarAction.with(shown, action)) },
                    onUp = {},
                    onDown = {}
                )
            }
        }
    }
}

@Composable
private fun SidebarActionRow(
    action: SidebarAction,
    on: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = action.icon(),
            contentDescription = null,
            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.size(24.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = stringResource(action.labelRes),
                color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                fontSize = 15.sp
            )
            // Dove il tasto c'è: una riga sola per le due app, ma le schede esistono solo nella Main
            if (!action.availableIn(SidebarHost.WIDE)) {
                Text(
                    text = stringResource(R.string.sidebar_action_main_only),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }
        }
        if (on) {
            IconButton(onClick = onUp, enabled = canMoveUp, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.tab_bar_action_up))
            }
            IconButton(onClick = onDown, enabled = canMoveDown, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.tab_bar_action_down))
            }
        }
        // Home non si spegne: è l'unico modo per tornare alla pagina da cui si cambia tutto
        Switch(checked = on, onCheckedChange = { onToggle() }, enabled = !action.essential)
    }
}

// Anche il dock della Main usa le stesse icone (MainDock)
internal fun SidebarAction.icon(): ImageVector = when (this) {
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
