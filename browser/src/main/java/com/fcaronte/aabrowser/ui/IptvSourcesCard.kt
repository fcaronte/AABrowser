package com.fcaronte.aabrowser.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.LaunchedEffect
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
import com.fcaronte.aabrowser.utils.AppLocale
import com.fcaronte.aabrowser.utils.IptvSources
import com.fcaronte.aabrowser.utils.XtreamLink
import kotlinx.coroutines.delay
import java.text.Collator
import java.util.Locale
import kotlin.math.abs

// La scheda chiesta dalle pagine di TV e Radio si porta in vista dopo che la sezione si è disposta
private const val BRING_INTO_VIEW_DELAY_MS = 150L

/**
 * Liste di CarWeb Media nelle Impostazioni, una scheda per TV e una per Radio: elenco, aggiunta (M3U da URL o file, poi
 * account Xtream per la TV e Paesi di Radio Browser per la Radio) e rimozione.
 * In auto si possono solo vedere e togliere: le liste si aggiungono sul telefono.
 */
@Composable
fun IptvSourcesCard(section: IptvSources.Section, isCarMode: Boolean, inFocus: Boolean) {
    val context = LocalContext.current
    val appContext = AppLocale.wrap(context)
    val localeTag = appContext.resources.configuration.locales[0].toLanguageTag()
    val colors = MaterialTheme.colorScheme
    val version by IptvSources.version
    val sources = remember(version, localeTag, section) { IptvSources.list(appContext, section) }
    val isRadio = section == IptvSources.Section.RADIO
    var adding by remember { mutableStateOf<IptvSources.Type?>(null) }
    var fileError by remember { mutableStateOf(false) }

    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(inFocus) {
        if (inFocus) {
            delay(BRING_INTO_VIEW_DELAY_MS)
            bringIntoView.bringIntoView()
        }
    }

    Box(modifier = Modifier.bringIntoViewRequester(bringIntoView)) {
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(if (isRadio) R.string.radio_lists_title else R.string.iptv_title),
                    color = colors.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(if (isRadio) R.string.radio_lists_desc else R.string.iptv_desc),
                    color = colors.onSurface.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                )
                sources.forEach { source ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = source.name, color = colors.onSurface, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = when (source.type) {
                                    IptvSources.Type.DEFAULT_M3U -> stringResource(R.string.iptv_source_default)
                                    IptvSources.Type.DEFAULT_COUNTRY -> stringResource(R.string.radio_source_default)
                                    IptvSources.Type.COUNTRY -> "Radio Browser"
                                    IptvSources.Type.M3U -> "M3U"
                                    IptvSources.Type.LOCAL_M3U -> stringResource(R.string.iptv_source_local_m3u)
                                    IptvSources.Type.XTREAM -> "Xtream"
                                },
                                color = colors.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                        if (source.type != IptvSources.Type.DEFAULT_M3U && source.type != IptvSources.Type.DEFAULT_COUNTRY) {
                            IconButton(onClick = { IptvSources.remove(context, section, source.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.iptv_remove))
                            }
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
                            IptvLocalM3uButton(section, onError = { fileError = it })
                        }
                        val extraType = if (isRadio) IptvSources.Type.COUNTRY else IptvSources.Type.XTREAM
                        OutlinedButton(onClick = { adding = extraType }) {
                            Text(stringResource(if (isRadio) R.string.radio_add_country else R.string.iptv_add_xtream))
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
    }

    adding?.let { type ->
        if (type == IptvSources.Type.COUNTRY) {
            RadioCountryDialog(onDismiss = { adding = null }) { code ->
                IptvSources.addCountry(context, code)
                adding = null
            }
        } else {
            IptvSourceDialog(type, onDismiss = { adding = null }) { name, url, username, password ->
                IptvSources.add(context, section, type, name, url, username, password)
                adding = null
            }
        }
    }
}

@Composable
private fun IptvLocalM3uButton(section: IptvSources.Section, onError: (Boolean) -> Unit) {
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                IptvSources.addFile(context, section, uri)
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
private fun RadioCountryDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val locale = AppLocale.wrap(context).resources.configuration.locales[0]
    val countries = remember(locale) {
        val collator = Collator.getInstance(locale)
        Locale.getISOCountries()
            .map { code -> code to Locale.Builder().setRegion(code).build().getDisplayCountry(locale) }
            .filter { it.second.isNotBlank() }
            .sortedWith { a, b -> collator.compare(a.second, b.second) }
    }
    var query by remember { mutableStateOf("") }
    val shown = remember(query, countries) { countries.filter { it.second.contains(query.trim(), ignoreCase = true) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.radio_add_country)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.radio_country_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(shown, key = { it.first }) { (code, name) ->
                        Text(
                            text = name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(code) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
        },
    )
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
