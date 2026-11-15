package com.fcaronte.aabrowser.utils

import android.content.Context
import android.net.Uri
import android.text.TextUtils
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.compose.runtime.snapshots.Snapshot
import androidx.core.net.toUri
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.car.HomePage
import com.fcaronte.aabrowser.model.FavoriteEdits
import com.fcaronte.aabrowser.model.FavoriteSite
import com.fcaronte.aabrowser.model.FavoritesRepository
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.smartphone.VirtualApps
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.UUID

/**
 * La home della Wide come pagina dell'app (assets/home), servita a https://home.local/ dalla WebView della Wide come le pagine
 * di CarWeb Media: ha i colori dell'app (tema automatico o forzato, colori del telefono), lo stesso aspetto della home della
 * Main (titolo, microfono, ricerca e matita in alto, preferiti, Impostazioni in basso) e permette di aggiungere, cambiare,
 * spostare ed eliminare i preferiti: le modifiche vanno nella stessa lista della Main (FavoritesRepository), che si rilegge.
 *
 * Il token arriva solo nell'HTML della home: nessun altro sito aperto nella Wide può leggere o cambiare i preferiti. Le
 * modifiche sono GET con i dati nell'indirizzo (la WebView non dà il corpo delle richieste a shouldInterceptRequest).
 */
object HomeApi {
    private const val HOST = "home.local"
    private const val TAG = "HomeApi"

    // Nuovo a ogni avvio dell'app: la pagina lo riceve nell'HTML e lo rimanda con ogni richiesta
    private val token = UUID.randomUUID().toString()

    /** Risposta per gli indirizzi di home.local; null per tutti gli altri (la WebView li carica normalmente). */
    fun intercept(context: Context, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url ?: return null
        if (url.host != HOST) return null
        if (!request.method.equals("GET", ignoreCase = true)) return status(405, "Method Not Allowed")
        val path = url.path ?: "/"
        return try {
            when {
                // La pagina solo come pagina principale: dentro un riquadro di un altro sito non serve a nessuno
                path == "/" -> if (request.isForMainFrame) page(context) else status(403, "Forbidden")
                path == "/home.js" -> asset(context, "home/home.js", "application/javascript")
                path == "/home.css" -> asset(context, "home/home.css", "text/css")
                url.getQueryParameter("t") != token -> status(403, "Forbidden")
                path == "/api/home/get" -> json(snapshot(context))
                path == "/api/home/fav" -> json(editFavorites(context, url))
                else -> status(404, "Not Found")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Home request failed: ${e.javaClass.simpleName}")
            status(500, "Internal Error")
        }
    }

    // ------------------------------------------------------------------
    // Pagina
    // ------------------------------------------------------------------

    private fun page(context: Context): WebResourceResponse {
        val app = AppLocale.wrap(context)
        val config = JSONObject()
            .put("token", token)
            .put("rtl", isRtl(app))
            .put("strings", strings(app))
            .put("snapshot", snapshot(context))
        val html = app.assets.open("home/home.html").bufferedReader().use { it.readText() }
            // Il JSON esce dall'app con le barre rovesciate (</script> non può chiudere il tag)
            .replace("/*AAB_CONFIG*/", "window.AAB_HOME = $config;")
            .replace("/*AAB_THEME*/", LocalPlayer.themeCss(context))
        return response(html.toByteArray(Charsets.UTF_8), "text/html", mapOf("X-Frame-Options" to "DENY"))
    }

    private fun isRtl(app: Context): Boolean =
        TextUtils.getLayoutDirectionFromLocale(app.resources.configuration.locales[0]) == View.LAYOUT_DIRECTION_RTL

    /** I testi della pagina, nella lingua dell'app. */
    private fun strings(app: Context): JSONObject = JSONObject()
        .put("title", app.getString(R.string.wide_app_name))
        .put("subtitle", app.getString(R.string.dashboard_subtitle))
        .put("openMain", app.getString(R.string.app_name))
        .put("voice", app.getString(R.string.voice_command_button))
        .put("search", app.getString(R.string.search_button))
        .put("edit", app.getString(R.string.edit_mode_toggle))
        .put("resume", app.getString(R.string.resume_button))
        .put("settings", app.getString(R.string.settings_button))
        .put("noFavorites", app.getString(R.string.wide_no_favorites))
        .put("addFavorite", app.getString(R.string.add_favorite_title))
        .put("editFavorite", app.getString(R.string.edit_favorite_title))
        .put("name", app.getString(R.string.field_name))
        .put("url", app.getString(R.string.field_url))
        .put("favicon", app.getString(R.string.field_favicon))
        .put("color", app.getString(R.string.field_color))
        .put("desktop", app.getString(R.string.desktop_mode_label))
        .put("desktopDesc", app.getString(R.string.desktop_mode_desc_site))
        .put("mobileZoom", app.getString(R.string.mobile_zoom_label))
        .put("desktopZoom", app.getString(R.string.desktop_zoom_label))
        .put("confirm", app.getString(R.string.confirm_button))
        .put("cancel", app.getString(R.string.cancel_button))
        .put("close", app.getString(R.string.close_button))
        .put("delete", app.getString(R.string.home_delete))
        .put("moveBack", app.getString(R.string.home_move_back))
        .put("moveForward", app.getString(R.string.home_move_forward))
        .put("appTile", app.getString(R.string.vapp_tile_label))
        .put("update", app.getString(R.string.update_available))
        .put("added", app.getString(R.string.favorite_added))
        .put("updated", app.getString(R.string.favorite_updated))
        .put("notAllowed", app.getString(R.string.favorite_not_allowed))
        .put("error", app.getString(R.string.home_error))

    // ------------------------------------------------------------------
    // Dati: preferiti, app del telefono, avviso di aggiornamento
    // ------------------------------------------------------------------

    internal fun snapshot(context: Context): JSONObject {
        val app = AppLocale.wrap(context)
        val list = FavoritesRepository(context).loadFavorites()
        val favorites = JSONArray()
        list.forEach { favorites.put(favoriteJson(it, list)) }
        // Dopo i preferiti, le app del telefono con la stella (pagina "Altre App"): si aprono sullo schermo virtuale
        val apps = JSONArray()
        try {
            VirtualApps.favorites(context).forEach {
                apps.put(
                    JSONObject()
                        .put("name", it.name)
                        .put("url", VirtualApps.urlFor(it.packageName))
                        .put("icon", LocalPlayer.appIconUrl(it.packageName))
                )
            }
        } catch (_: Exception) {
        }
        val update = UpdateManager.lastInfo?.takeIf { it.isAvailable && !UpdateManager.isBannerDismissed }
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
        // "Riprendi": l'ultima pagina aperta nella Wide, se non è la home stessa
        val resume = AppSettings.wideLastUrl.value.takeIf { it.isNotBlank() && !it.startsWith(HomePage.BASE_URL) }
        return JSONObject()
            .put("version", version)
            .put("favorites", favorites)
            .put("apps", apps)
            .put("update", update?.let { app.getString(R.string.update_available, it.latestVersion) } ?: JSONObject.NULL)
            .put("resume", resume ?: JSONObject.NULL)
            .put(
                "zoom",
                JSONObject()
                    .put("mobile", AppSettings.displayScale.value.toDouble())
                    .put("desktop", AppSettings.desktopScale.value.toDouble())
            )
            .put("settingsUrl", LocalPlayer.SETTINGS_URL)
    }

    private fun favoriteJson(site: FavoriteSite, list: List<FavoriteSite>): JSONObject = JSONObject()
        .put("id", site.id)
        .put("name", site.name)
        .put("url", site.url)
        .put("domain", domainOf(site.url))
        .put("color", colorHex(site.color))
        .put("icons", JSONArray(iconCandidates(site.url, site.faviconUrl)))
        .put("favicon", site.faviconUrl ?: "")
        .put("fixed", FavoritesRepository.isFixed(site, list))
        .put("desktop", site.isDesktopMode ?: JSONObject.NULL)
        .put("mobileZoom", site.mobileZoom?.toDouble() ?: JSONObject.NULL)
        .put("desktopZoom", site.desktopZoom?.toDouble() ?: JSONObject.NULL)

    /** Il colore come #RRGGBB (senza la trasparenza). */
    internal fun colorHex(color: Long): String = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)

    /**
     * L'indirizzo come lo mostra il preferito della Main sotto il nome: l'host senza "web.", "music.", "open." e "www.", con la
     * porta; YouTube Kids come youtube.com.
     */
    internal fun domainOf(url: String): String = try {
        val uri = java.net.URI(url)
        var host = uri.host ?: url
        val parts = host.split(".")
        if (parts.size > 2 && parts[0] in setOf("web", "music", "open", "www")) host = parts.drop(1).joinToString(".")
        if (host.contains("youtubekids")) host = "youtube.com"
        if (uri.port != -1) "$host:${uri.port}" else host
    } catch (_: Exception) {
        url
    }

    /**
     * Le icone da provare in ordine: quella salvata, poi Google, DuckDuckGo e il /favicon.ico del sito; alla fine la pagina
     * mostra la lettera iniziale. Le pagine di CarWeb hanno la loro icona, servita dall'app.
     */
    private fun iconCandidates(url: String, stored: String?): List<String> {
        if (LocalPlayer.isAppsUrl(url)) return listOf(LocalPlayer.APPS_ICON_URL)
        if (LocalPlayer.isTvUrl(url)) return listOf(LocalPlayer.TV_ICON_URL)
        if (LocalPlayer.isRadioUrl(url)) return listOf(LocalPlayer.RADIO_ICON_URL)
        if (LocalPlayer.isPodcastUrl(url)) return listOf(LocalPlayer.PODCAST_ICON_URL)
        if (LocalPlayer.isPlayerUrl(url)) return listOf(LocalPlayer.ICON_URL)
        val list = mutableListOf<String>()
        stored?.takeIf { it.isNotBlank() }?.let { list.add(it) }
        val uri = try { url.toUri() } catch (_: Exception) { null }
        val host = uri?.host
        if (host != null) {
            when {
                // WhatsApp: il favicon di Google dà 403, si usa DuckDuckGo
                host.contains("whatsapp.com") || host.contains("wa.me") -> list.add("https://icons.duckduckgo.com/ip3/whatsapp.com.ico")
                host.contains("youtubekids.com") -> list.add("https://www.google.com/s2/favicons?domain=youtube.com&sz=128")
                else -> list.add("https://www.google.com/s2/favicons?domain=$host&sz=128")
            }
            list.add("https://icons.duckduckgo.com/ip3/$host.ico")
            list.add("${uri.scheme ?: "https"}://$host/favicon.ico")
        }
        return list.distinct()
    }

    // ------------------------------------------------------------------
    // Modifiche ai preferiti
    // ------------------------------------------------------------------

    /**
     * Aggiunge, cambia, sposta o toglie un preferito (FavoriteEdits: le stesse regole della Main) e risponde con i dati di ora e
     * l'esito (result: ok, not_allowed, not_found, invalid). La Main tiene la sua lista in memoria: si rilegge.
     */
    private fun editFavorites(context: Context, url: Uri): JSONObject {
        val repository = FavoritesRepository(context)
        val list = repository.loadFavorites().toMutableList()
        val id = url.getQueryParameter("id").orEmpty()
        val result = when (url.getQueryParameter("op")) {
            "add" -> FavoriteEdits.add(list, inputOf(url))
            "update" -> FavoriteEdits.update(list, id, inputOf(url))
            "delete" -> FavoriteEdits.delete(list, id)
            "move" -> FavoriteEdits.move(list, id, url.getQueryParameter("dir")?.toIntOrNull() ?: 0)
            else -> FavoriteEdits.Result.INVALID
        }
        if (result == FavoriteEdits.Result.OK) {
            repository.saveFavorites(list)
            // Fuori da ogni composizione (qui non ce n'è una) la scrittura va notificata a mano, o la Main non la vede
            FavoritesRepository.notifyExternalChange()
            Snapshot.sendApplyNotifications()
        }
        return snapshot(context).put("result", result.name.lowercase(Locale.ROOT))
    }

    private fun inputOf(url: Uri): FavoriteEdits.Input {
        fun text(name: String) = url.getQueryParameter(name).orEmpty()
        val color = text("color").removePrefix("#").toLongOrNull(16)?.let { 0xFF000000 or (it and 0xFFFFFF) }
            ?: FavoriteEdits.DEFAULT_COLOR
        return FavoriteEdits.Input(
            name = text("name"),
            url = text("url"),
            color = color,
            faviconUrl = text("icon").ifBlank { null },
            desktop = when (text("desktop")) {
                "1" -> true
                "0" -> false
                else -> null
            },
            mobileZoom = text("mz").toFloatOrNull(),
            desktopZoom = text("dz").toFloatOrNull(),
        )
    }

    // ------------------------------------------------------------------
    // Risposte
    // ------------------------------------------------------------------

    private fun asset(context: Context, name: String, mime: String): WebResourceResponse =
        response(context.assets.open(name).use { it.readBytes() }, mime, emptyMap())

    private fun json(value: JSONObject): WebResourceResponse =
        response(value.toString().toByteArray(Charsets.UTF_8), "application/json", emptyMap())

    private fun response(bytes: ByteArray, mime: String, headers: Map<String, String>): WebResourceResponse =
        WebResourceResponse(mime, "UTF-8", 200, "OK", headers + ("Cache-Control" to "no-store"), ByteArrayInputStream(bytes))

    private fun status(code: Int, reason: String): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", code, reason, mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
}
