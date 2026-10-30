package com.fcaronte.aabrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.utils.PendingSitePermission
import com.fcaronte.aabrowser.utils.SitePermission
import com.fcaronte.aabrowser.utils.SitePermissionChoice

/**
 * Richiesta di un permesso da parte di un sito (microfono, posizione, file del telefono in auto), come in Chrome.
 * È un popup nella pagina e non una finestra di dialogo, così funziona anche in auto. Toccare fuori = non ora.
 */
@Composable
fun SitePermissionPopup(
    prompt: PendingSitePermission,
    onChoice: (SitePermissionChoice) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onChoice(SitePermissionChoice.DISMISS) },
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .padding(24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { },
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        prompt.permission.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = stringResource(prompt.permission.titleRes),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = stringResource(prompt.permission.messageRes, prompt.site),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onChoice(SitePermissionChoice.ALWAYS) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.site_permission_allow_always))
                    }
                    OutlinedButton(
                        onClick = { onChoice(SitePermissionChoice.ONCE) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.site_permission_allow_once))
                    }
                    TextButton(
                        onClick = { onChoice(SitePermissionChoice.DENY) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.site_permission_deny))
                    }
                }
            }
        }
    }
}

val SitePermission.icon: ImageVector
    get() = when (this) {
        SitePermission.MICROPHONE -> Icons.Default.Mic
        SitePermission.LOCATION -> Icons.Default.LocationOn
        SitePermission.FILES -> Icons.Default.Folder
    }
