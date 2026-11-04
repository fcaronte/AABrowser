package com.fcaronte.aabrowser.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.utils.IptvSources
import com.fcaronte.aabrowser.utils.XtreamLink
import kotlin.math.abs

/**
 * Liste della TV di CarWeb Media nelle Impostazioni: elenco, aggiunta (M3U da URL o file, account Xtream) e rimozione.
 * In auto si possono solo vedere e togliere: le sorgenti si aggiungono sul telefono.
 */
@Composable
fun IptvSourcesCard(isCarMode: Boolean) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val version by IptvSources.version
    val sources = remember(version) { IptvSources.list(context) }
    var adding by remember { mutableStateOf<IptvSources.Type?>(null) }
    var fileError by remember { mutableStateOf(false) }

    SettingsCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.iptv_title),
                color = colors.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.iptv_desc),
                color = colors.onSurface.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
            sources.forEach { source ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = source.name, color = colors.onSurface, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = when (source.type) {
                                IptvSources.Type.M3U -> "M3U"
                                IptvSources.Type.LOCAL_M3U -> stringResource(R.string.iptv_source_local_m3u)
                                IptvSources.Type.XTREAM -> "Xtream"
                            },
                            color = colors.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    IconButton(onClick = { IptvSources.remove(context, source.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.iptv_remove))
                    }
                }
            }
            if (isCarMode) {
                Text(
                    text = stringResource(R.string.iptv_add_on_phone),
                    color = colors.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { adding = IptvSources.Type.M3U }) {
                            Text(stringResource(R.string.iptv_add_m3u))
                        }
                        IptvLocalM3uButton(onError = { fileError = it })
                    }
                    OutlinedButton(onClick = { adding = IptvSources.Type.XTREAM }) {
                        Text(stringResource(R.string.iptv_add_xtream))
                    }
                }
                if (fileError) {
                    Text(
                        text = stringResource(R.string.iptv_file_error),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }

    adding?.let { type ->
        IptvSourceDialog(type, onDismiss = { adding = null }) { name, url, username, password ->
            IptvSources.add(context, type, name, url, username, password)
            adding = null
        }
    }
}

@Composable
private fun IptvLocalM3uButton(onError: (Boolean) -> Unit) {
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                IptvSources.addFile(context, uri)
                onError(false)
            } catch (_: Exception) {
                onError(true)
            }
        }
    }

    OutlinedButton(
        onClick = {
            onError(false)
            filePicker.launch(
                arrayOf(
                    "application/x-mpegurl",
                    "application/vnd.apple.mpegurl",
                    "audio/mpegurl",
                    "audio/x-mpegurl",
                    "text/plain",
                    "application/octet-stream",
                )
            )
        }
    ) {
        Text(stringResource(R.string.iptv_add_m3u_file))
    }
}

@Composable
private fun IptvSourceDialog(
    type: IptvSources.Type,
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String, username: String, password: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val xtream = type == IptvSources.Type.XTREAM
    // Xtream: nel campo del server va bene anche il link completo con utente e password
    val link = if (xtream) XtreamLink.parse(url) else null
    val ready = url.isNotBlank() && (!xtream || link != null || (username.isNotBlank() && password.isNotEmpty()))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (xtream) R.string.iptv_add_xtream else R.string.iptv_add_m3u)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.iptv_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { value ->
                        // Link incollato: server, utente e password vanno nei loro campi. Non a ogni carattere
                        // scritto, si separerebbe a metà password: scritto a mano si separa all'aggiunta.
                        val pasted = if (xtream && abs(value.length - url.length) > 1) XtreamLink.parse(value) else null
                        if (pasted == null) {
                            url = value
                        } else {
                            url = pasted.server
                            username = pasted.username
                            password = pasted.password
                        }
                    },
                    label = { Text(stringResource(if (xtream) R.string.iptv_server else R.string.iptv_url)) },
                    supportingText = if (xtream) {
                        { Text(stringResource(R.string.iptv_server_link_hint)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (xtream) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.iptv_username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.iptv_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Link scritto a mano (quello incollato si è già separato): utente e password vengono dal link
                    val login = link ?: XtreamLink.Login(url, username, password)
                    onAdd(name, login.server, login.username, login.password)
                },
                enabled = ready,
            ) {
                Text(stringResource(R.string.iptv_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
        },
    )
}
