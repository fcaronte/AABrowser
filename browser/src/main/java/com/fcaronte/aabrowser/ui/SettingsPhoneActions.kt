package com.fcaronte.aabrowser.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.fcaronte.aabrowser.BtInputActivity
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.settings.AdBlockSettings
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.utils.AdBlockLists
import com.fcaronte.aabrowser.utils.BackupRestoreUtils
import com.fcaronte.aabrowser.utils.IptvSources
import com.fcaronte.aabrowser.utils.PodcastApi
import com.fcaronte.aabrowser.utils.SettingsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface PhoneDialog {
    class Iptv(val type: IptvSources.Type, val section: IptvSources.Section) : PhoneDialog
    object Rss : PhoneDialog
    class Country(val section: IptvSources.Section) : PhoneDialog
    object AdBlock : PhoneDialog
}

/** Le liste M3U: il tipo di un file di playlist non è sempre quello giusto, dipende da chi l'ha salvato. */
private val M3U_TYPES = arrayOf(
    "application/x-mpegurl",
    "application/vnd.apple.mpegurl",
    "audio/mpegurl",
    "audio/x-mpegurl",
    "text/plain",
    "application/octet-stream",
)

/**
 * Quello che si fa solo sul telefono per la pagina delle impostazioni (SettingsNative.phone): i selettori di file di Android,
 * le finestre per scrivere indirizzi e password, i link, la lingua dell'app, i permessi, il log da condividere. Indirizzi e
 * password restano in queste finestre e vanno dritti all'app: la pagina non li vede. Va composta solo dove c'è
 * l'ActivityResultRegistryOwner (la Main sul telefono; in auto non c'è).
 */
@Composable
internal fun SettingsPhoneActions(native: SettingsNative, onFeedback: (String) -> Unit, refresh: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<PhoneDialog?>(null) }
    var fileSection by remember { mutableStateOf(IptvSources.Section.TV) }

    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { BackupRestoreUtils.export(context, it) } == true
                }.getOrDefault(false)
            }
            Toast.makeText(context, if (saved) R.string.backup_success else R.string.backup_error, Toast.LENGTH_LONG).show()
        }
    }

    // Un file .json non ha un tipo su cui contare: dipende da chi l'ha salvato
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val restored = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { BackupRestoreUtils.import(context, it) } == true
                }.getOrDefault(false)
            }
            Toast.makeText(context, if (restored) R.string.import_success else R.string.import_error, Toast.LENGTH_LONG).show()
            // Il ripristino sostituisce tutto: l'app riparte per rileggerlo
            if (restored) restartApp(context, activity)
        }
    }

    val playlistFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            IptvSources.addFile(context, fileSection, uri)
            refresh()
        } catch (_: Exception) {
            Toast.makeText(context, R.string.iptv_file_error, Toast.LENGTH_LONG).show()
        }
    }

    // I file OPML non hanno un tipo su cui contare: dipende da chi li ha salvati
    val opmlFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val feeds = withContext(Dispatchers.IO) {
                runCatching { PodcastApi.readOpml(context, uri) }.getOrDefault(emptyList())
            }
            if (feeds.isNotEmpty()) IptvSources.addFeeds(context, feeds)
            else Toast.makeText(context, R.string.podcast_opml_error, Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    SideEffect {
        native.phone = { action ->
            val name = action.substringBefore(':')
            val arg = action.substringAfter(':', "")
            // La lista a cui si riferisce l'azione, se è una di quelle ammesse: le liste M3U vanno alla TV e alla Radio, Xtream
            // solo alla TV, i Paesi alla Radio e ai Podcast, RSS e OPML ai Podcast
            fun sectionAmong(vararg allowed: IptvSources.Section): IptvSources.Section? =
                IptvSources.Section.entries.firstOrNull { it.name.equals(arg, ignoreCase = true) }?.takeIf { it in allowed }
            when (name) {
                "open_link" -> openLink(context, arg, onFeedback)
                "share_logs" -> exportAndShareLogs(context, scope)
                "change_language" -> changeLanguage(context)
                "weather_permission" -> activity?.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1001)
                "backup_export" -> {
                    val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                    exportFile.launch("carweb_backup_$day.json")
                }
                "backup_import" -> importFile.launch(arrayOf("*/*"))
                "iptv_m3u" -> sectionAmong(IptvSources.Section.TV, IptvSources.Section.RADIO)?.let {
                    dialog = PhoneDialog.Iptv(IptvSources.Type.M3U, it)
                }
                "iptv_xtream" -> sectionAmong(IptvSources.Section.TV)?.let {
                    dialog = PhoneDialog.Iptv(IptvSources.Type.XTREAM, it)
                }
                "iptv_file" -> sectionAmong(IptvSources.Section.TV, IptvSources.Section.RADIO)?.let {
                    fileSection = it
                    playlistFile.launch(M3U_TYPES)
                }
                "iptv_country" -> sectionAmong(IptvSources.Section.RADIO, IptvSources.Section.PODCAST)?.let {
                    dialog = PhoneDialog.Country(it)
                }
                "iptv_rss" -> sectionAmong(IptvSources.Section.PODCAST)?.let { dialog = PhoneDialog.Rss }
                "iptv_opml" -> sectionAmong(IptvSources.Section.PODCAST)?.let { opmlFile.launch(arrayOf("*/*")) }
                "adblock_custom" -> dialog = PhoneDialog.AdBlock
                // Mouse e tastiera Bluetooth del telefono per lo schermo dell'auto: una schermata da lasciare aperta
                // (dall'Activity: il contesto di MainScreen non lo è, e senza una nuova attività non può avviarne altre)
                "bt_input" -> activity?.startActivity(Intent(activity, BtInputActivity::class.java))
            }
        }
    }
    DisposableEffect(native) {
        onDispose { native.phone = null }
    }

    when (val shown = dialog) {
        is PhoneDialog.Iptv -> IptvSourceDialog(shown.type, onDismiss = { dialog = null }) { name, url, username, password ->
            IptvSources.add(context, shown.section, shown.type, name, url, username, password)
            dialog = null
            refresh()
        }

        is PhoneDialog.Country -> RadioCountryDialog(onDismiss = { dialog = null }) { code ->
            IptvSources.addCountry(context, shown.section, code)
            dialog = null
            refresh()
        }

        PhoneDialog.Rss -> PodcastFeedDialog(onDismiss = { dialog = null }, onAdded = {
            dialog = null
            refresh()
        })

        PhoneDialog.AdBlock -> AdBlockListDialog(
            initial = AdBlockSettings.customListUrl.value,
            onDismiss = { dialog = null },
            onConfirm = { address ->
                dialog = null
                AdBlockSettings.setCustomListUrl(context, address)
                AdBlockLists.choose(context, AdBlockLists.CUSTOM_KEY)
                refresh()
            }
        )

        null -> Unit
    }
}

/** Un link dell'app (README, GitHub, PayPal, caffè) nel browser del telefono; se non c'è, l'indirizzo come messaggio. */
private fun openLink(context: Context, id: String, onFeedback: (String) -> Unit) {
    val url = SettingsApi.linkUrl(context, id) ?: return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        onFeedback(url)
    }
}

/** La scelta della lingua dell'app di Android (13+); prima "Forza inglese" si spegne, altrimenti vincerebbe lui. */
private fun changeLanguage(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (AppSettings.forceEnglish.value) AppSettings.setForceEnglish(context, false)
        val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS).apply {
            data = "package:${context.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } else {
        Toast.makeText(context, R.string.language_settings_android_13, Toast.LENGTH_LONG).show()
    }
}

/**
 * Log dell'app (logcat del suo processo) in un file da condividere. La lettura di logcat può durare qualche secondo:
 * fuori dal thread principale, poi la scelta dell'app su quello principale.
 */
private fun exportAndShareLogs(context: Context, scope: CoroutineScope) {
    scope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val logFile = File(context.cacheDir, "CarWeb_logs.txt")
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "--pid=${android.os.Process.myPid()}"))
                val logText = process.inputStream.bufferedReader().use { it.readText() }
                logFile.writeText(logText.ifBlank { "No logs captured." })
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", logFile)
            }
        }
        result.onSuccess { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, context.getString(R.string.share_logs_title)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }.onFailure { e ->
            Toast.makeText(context, context.getString(R.string.share_logs_failed, e.message.orEmpty()), Toast.LENGTH_SHORT).show()
        }
    }
}
