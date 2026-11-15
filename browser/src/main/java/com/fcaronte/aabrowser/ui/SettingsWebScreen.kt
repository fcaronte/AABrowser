package com.fcaronte.aabrowser.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.fcaronte.aabrowser.utils.AdBlockLists
import com.fcaronte.aabrowser.utils.IptvSources
import com.fcaronte.aabrowser.utils.LocalPlayer
import com.fcaronte.aabrowser.utils.SitePermissions
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Quello che solo il telefono può fare per la pagina delle impostazioni: aprire un link, i selettori di file, le finestre per
 * scrivere indirizzi e password, chiedere un permesso, riavviare l'app. La pagina lo chiede al ponte (AndroidBridge.run) con
 * il nome dell'azione, a volte seguito da ":" e un argomento; sul telefono ci sono tutte ([phone], messo da
 * [SettingsPhoneActions]), in auto solo chiudere e riavviare.
 */
internal class SettingsNative {
    var phone: ((String) -> Unit)? = null
    var close: () -> Unit = {}
    var restart: () -> Unit = {}

    fun accepts(action: String): Boolean {
        if (action in COMMON) return true
        val name = action.substringBefore(':')
        return phone != null && name in PHONE
    }

    fun run(action: String) {
        when (action) {
            "close" -> close()
            "restart" -> restart()
            else -> phone?.invoke(action)
        }
    }

    private companion object {
        val COMMON = setOf("close", "restart")
        val PHONE = setOf(
            "open_link", "share_logs", "change_language", "weather_permission", "backup_export", "backup_import",
            "iptv_m3u", "iptv_file", "iptv_xtream", "iptv_country", "iptv_rss", "iptv_opml", "adblock_custom", "bt_input",
        )
    }
}

/** Il ponte della pagina delle impostazioni: poche azioni con un nome, mai una pagina qualunque (la WebView non carica altro). */
private class SettingsBridge(private val native: SettingsNative, private val pageHost: AtomicReference<String?>) {
    @JavascriptInterface
    @Suppress("unused")
    fun isPhone(): Boolean = native.phone != null

    /** true = l'azione è partita (sul thread principale); false = non è una di quelle che il ponte conosce o non è la nostra pagina. */
    @JavascriptInterface
    @Suppress("unused")
    fun run(action: String): Boolean {
        if (!LocalPlayer.isPlayerHost(pageHost.get()) || !native.accepts(action)) return false
        Handler(Looper.getMainLooper()).post { native.run(action) }
        return true
    }
}

private const val REFRESH_SCRIPT = "window.aabSettingsRefresh && window.aabSettingsRefresh();"
private const val BACK_SCRIPT = "window.aabSettingsBack ? window.aabSettingsBack() : false"

/**
 * Le Impostazioni nella Main: la pagina https://player.local/settings (la stessa che apre la Wide) in una WebView a tutto
 * schermo, con il ponte per quello che serve solo sul telefono. La WebView non carica niente che non sia la pagina
 * dell'app. [focus]: la sezione delle liste aperta e quella lista in vista (aperte dalle pagine di TV, Radio e Podcast).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SettingsWebScreen(
    focus: IptvSources.Section?,
    onBack: () -> Unit,
    onShowFeedback: (String) -> Unit,
    isCarMode: Boolean,
) {
    val context = LocalContext.current
    // Il contesto di MainScreen è localizzato e non è l'Activity
    val activity = LocalActivity.current
    val canPick = LocalActivityResultRegistryOwner.current != null
    val phone = !isCarMode && activity != null && canPick
    val background = MaterialTheme.colorScheme.background

    val native = remember { SettingsNative() }
    val pageHost = remember { AtomicReference<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }
    val url = remember(focus) { LocalPlayer.SETTINGS_URL + (focus?.let { "?focus=" + it.name.lowercase() } ?: "") }

    SideEffect {
        native.close = onBack
        native.restart = { restartApp(context, activity) }
    }

    // Indietro: prima chiude il riquadro aperto nella pagina, se c'è; poi lascia le Impostazioni
    PhoneBackHandler {
        val view = webView
        if (view == null) {
            onBack()
        } else {
            view.evaluateJavascript(BACK_SCRIPT) { handled -> if (handled != "true") onBack() }
        }
    }

    // Una lista aggiunta o tolta, una scelta dei siti, la lista dei siti pubblicitari scaricata: la pagina si rilegge
    val sourcesVersion = IptvSources.version.intValue
    val sitesVersion = SitePermissions.version.intValue
    val adBlockInfo = AdBlockLists.info.value
    LaunchedEffect(sourcesVersion, sitesVersion, adBlockInfo) {
        if (pageLoaded) webView?.evaluateJavascript(REFRESH_SCRIPT, null)
    }

    Box(modifier = Modifier.fillMaxSize().background(background)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(background.toArgb())
                    settings.javaScriptEnabled = true
                    // sessionStorage: le sezioni aperte restano aperte quando la pagina si ricarica
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setSupportZoom(false)
                    addJavascriptInterface(SettingsBridge(native, pageHost), "AndroidBridge")
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            if (request?.isForMainFrame == true) pageHost.set(request.url?.host)
                            // Solo le pagine dell'app: tutto il resto riceve un rifiuto
                            return LocalPlayer.intercept(context, request) ?: WebResourceResponse(
                                "text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0))
                            )
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

                        override fun onPageFinished(view: WebView?, url: String?) {
                            pageLoaded = true
                        }
                    }
                    loadUrl(url)
                    webView = this
                }
            },
            onRelease = { view ->
                webView = null
                view.destroy()
            },
        )
    }

    if (phone) {
        SettingsPhoneActions(
            native = native,
            onFeedback = onShowFeedback,
            refresh = { webView?.evaluateJavascript(REFRESH_SCRIPT, null) },
        )
    }
}

/** Riavvia l'app (dopo un ripristino del backup o un cambio che si applica all'avvio): la stessa uscita di sempre. */
internal fun restartApp(context: Context, activity: Activity?) {
    Handler(Looper.getMainLooper()).postDelayed({
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (intent != null) {
            val restartIntent = Intent.makeRestartActivityTask(intent.component)
            context.startActivity(restartIntent)
            Runtime.getRuntime().exit(0)
        } else {
            activity?.finishAffinity()
            Process.killProcess(Process.myPid())
        }
    }, 300)
}
