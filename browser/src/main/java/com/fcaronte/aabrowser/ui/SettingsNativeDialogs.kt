package com.fcaronte.aabrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.utils.AdBlockLists
import com.fcaronte.aabrowser.utils.AppLocale
import com.fcaronte.aabrowser.utils.IptvSources
import com.fcaronte.aabrowser.utils.PodcastApi
import com.fcaronte.aabrowser.utils.XtreamLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale
import kotlin.math.abs

/*
 * Le finestre del telefono per quello che la pagina delle impostazioni non fa passare: indirizzi di liste, server Xtream con
 * utente e password, feed RSS, Paese, indirizzo di una lista dei siti pubblicitari. Gli indirizzi e le password si scrivono
 * qui e vanno dritti all'app: la pagina (anche quella delle impostazioni) non li vede mai. Sono finestre di sistema: si
 * aprono solo sul telefono, in auto la pagina rimanda al telefono.
 */

/** Una lista M3U da indirizzo o un account Xtream (server, utente e password, o il link che li contiene tutti). */
@Composable
internal fun IptvSourceDialog(
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

/** Un feed RSS: si legge subito, un indirizzo sbagliato si scopre qui e il nome del podcast arriva dal feed. */
@Composable
internal fun PodcastFeedDialog(onDismiss: () -> Unit, onAdded: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.podcast_add_rss)) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    failed = false
                },
                label = { Text(stringResource(R.string.podcast_rss_url)) },
                isError = failed,
                supportingText = if (failed) {
                    { Text(stringResource(R.string.podcast_rss_error)) }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank() && !checking,
                onClick = {
                    checking = true
                    scope.launch {
                        val feed = withContext(Dispatchers.IO) { PodcastApi.checkFeed(url) }
                        checking = false
                        if (feed == null) {
                            failed = true
                        } else {
                            IptvSources.addFeeds(context, listOf(feed))
                            onAdded()
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.iptv_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
        },
    )
}

/** Un Paese di Radio Browser (Radio) o di Apple Podcasts (Podcast): elenco con ricerca, nella lingua dell'app. */
@Composable
internal fun RadioCountryDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
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

/** L'indirizzo di una lista dei siti pubblicitari scelta dall'utente: un dominio per riga, formato hosts o regole ||dominio^. */
@Composable
internal fun AdBlockListDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var address by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.adblock_lists_custom)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.adblock_lists_custom_desc), fontSize = 13.sp)
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.adblock_lists_custom_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(address.trim()) },
                enabled = AdBlockLists.isValidAddress(address.trim()),
            ) {
                Text(stringResource(R.string.ok_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
        },
    )
}
