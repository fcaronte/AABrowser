package com.fcaronte.aabrowser.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.utils.SitePermissions

/**
 * Siti con un permesso scelto, in poco spazio: chiuso mostra quanti sono, aperto un sito per riga con le icone dei
 * suoi permessi; toccando un sito si vedono i permessi uno per uno, ognuno da togliere (il sito chiederà di nuovo).
 */
@Composable
fun ColumnScope.SitePermissionsList() {
    val context = LocalContext.current
    val version by SitePermissions.version
    val sites = remember(version) { SitePermissions.sites(context) }
    var expanded by remember { mutableStateOf(false) }
    var openSite by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
    ) {
        Card(
            onClick = { expanded = !expanded },
            enabled = sites.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (sites.isEmpty()) stringResource(R.string.site_permissions_empty)
                    else stringResource(R.string.site_permissions_list_label, sites.size),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (sites.isNotEmpty()) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    AnimatedVisibility(visible = expanded && sites.isNotEmpty()) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sites.forEach { (origin, choices) ->
                val isOpen = openSite == origin
                Card(
                    onClick = { openSite = if (isOpen) null else origin },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = SitePermissions.displayName(origin),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            // Colpo d'occhio: icona del permesso, rossa se bloccato
                            choices.entries.sortedBy { it.key.ordinal }.forEach { (permission, allowed) ->
                                Icon(
                                    permission.icon,
                                    contentDescription = null,
                                    tint = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .padding(start = 6.dp)
                                        .size(18.dp)
                                )
                            }
                            Icon(
                                imageVector = if (isOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                        AnimatedVisibility(visible = isOpen) {
                            Column {
                                choices.entries.sortedBy { it.key.ordinal }.forEach { (permission, allowed) ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            permission.icon,
                                            contentDescription = null,
                                            tint = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = stringResource(
                                                R.string.site_permission_state,
                                                stringResource(permission.titleRes),
                                                stringResource(
                                                    if (allowed) R.string.site_permission_allowed
                                                    else R.string.site_permission_blocked
                                                )
                                            ),
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(start = 12.dp)
                                        )
                                        IconButton(onClick = { SitePermissions.remove(origin, permission) }) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = stringResource(R.string.site_permission_remove)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
