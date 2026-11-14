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
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.settings.TabBarAction

/**
 * Impostazioni dei pulsanti a destra della barra delle schede: quali mostrare (interruttore), in che ordine (frecce) e
 * quanti se ne vedono insieme. Gli altri si raggiungono strisciando i pulsanti a destra e a sinistra (PersistentTabBar).
 * Chiusa di serie (utente, 14/11/2026): tocca il titolo per aprirla.
 */
@Composable
fun TabBarActionsCard() {
    val context = LocalContext.current
    val shown by AppSettings.tabBarActions
    val wantedVisible by AppSettings.tabBarVisibleCount
    val hidden = TabBarAction.DEFAULT.filter { it !in shown }
    val visible = TabBarAction.visibleCount(wantedVisible, shown.size)
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
                    text = stringResource(R.string.tab_bar_actions_label),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                // Chiusa: in una riga quanti se ne vedono; aperta: la spiegazione
                Text(
                    text = if (expanded) stringResource(R.string.tab_bar_actions_desc)
                    else stringResource(R.string.tab_bar_actions_visible, visible),
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
                ActionRow(
                    action = action,
                    on = true,
                    canMoveUp = index > 0,
                    canMoveDown = index < shown.lastIndex,
                    onToggle = { AppSettings.setTabBarActions(context, shown - action) },
                    onUp = { AppSettings.setTabBarActions(context, TabBarAction.moved(shown, index, -1)) },
                    onDown = { AppSettings.setTabBarActions(context, TabBarAction.moved(shown, index, 1)) }
                )
            }
            hidden.forEach { action ->
                ActionRow(
                    action = action,
                    on = false,
                    canMoveUp = false,
                    canMoveDown = false,
                    onToggle = { AppSettings.setTabBarActions(context, shown + action) },
                    onUp = {},
                    onDown = {}
                )
            }

            if (shown.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.tab_bar_actions_visible, visible),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { AppSettings.setTabBarVisibleCount(context, visible - 1) },
                        enabled = visible > 1
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = null)
                    }
                    IconButton(
                        onClick = { AppSettings.setTabBarVisibleCount(context, visible + 1) },
                        enabled = visible < shown.size
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    action: TabBarAction,
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
        Text(
            text = stringResource(action.label()),
            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            fontSize = 15.sp,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        )
        if (on) {
            IconButton(onClick = onUp, enabled = canMoveUp, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.tab_bar_action_up))
            }
            IconButton(onClick = onDown, enabled = canMoveDown, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.tab_bar_action_down))
            }
        }
        Switch(checked = on, onCheckedChange = { onToggle() })
    }
}

private fun TabBarAction.icon(): ImageVector = when (this) {
    TabBarAction.VOICE -> Icons.Default.Mic
    TabBarAction.ADBLOCK -> Icons.Default.VerifiedUser
    TabBarAction.DESKTOP -> Icons.Default.DesktopWindows
    TabBarAction.BACK -> Icons.AutoMirrored.Filled.ArrowBack
    TabBarAction.FORWARD -> Icons.AutoMirrored.Filled.ArrowForward
}

private fun TabBarAction.label(): Int = when (this) {
    TabBarAction.VOICE -> R.string.voice_command_button
    TabBarAction.ADBLOCK -> R.string.adblock_this_site
    TabBarAction.DESKTOP -> R.string.desktop_mode_this_site
    TabBarAction.BACK -> R.string.back_button
    TabBarAction.FORWARD -> R.string.forward_button
}
