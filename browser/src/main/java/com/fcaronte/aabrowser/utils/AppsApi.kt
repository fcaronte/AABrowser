package com.fcaronte.aabrowser.utils

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.webkit.WebResourceResponse
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.smartphone.FavoriteAppsManager
import com.fcaronte.aabrowser.smartphone.ShizukuManager
import com.fcaronte.aabrowser.smartphone.VirtualApps
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * "Altre App" (https://player.local/apps): elenco delle app del telefono, stato di Shizuku, stelle dei preferiti e icone
 * per la pagina (assets/player/apps.html). Come le altre pagine di CarWeb Media, i dati vogliono il token nell'HTML
 * (LocalPlayer): un sito aperto in una scheda non può sapere quali app ha l'utente.
 */
object AppsApi {
    private const val TAG = "AppsApi"
    private const val ICON_SIZE = 128

    private val iconBytes = object : LruCache<String, ByteArray>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    fun page(context: Context, token: String, theme: String): WebResourceResponse {
        val app = AppLocale.wrap(context)
        val strings = JSONObject()
            .put("title", app.getString(R.string.apps_title))
            .put("search", app.getString(R.string.apps_search))
            .put("loading", app.getString(R.string.apps_loading))
            .put("empty", app.getString(R.string.apps_empty))
            .put("ready", app.getString(R.string.apps_shizuku_ready))
            .put("missing", app.getString(R.string.apps_shizuku_missing))
            .put("stopped", app.getString(R.string.apps_shizuku_stopped))
            .put("permission", app.getString(R.string.apps_shizuku_permission))
            .put("authorize", app.getString(R.string.apps_authorize))
            .put("favoriteAdd", app.getString(R.string.apps_favorite_add))
            .put("favoriteRemove", app.getString(R.string.apps_favorite_remove))
            .put("hint", app.getString(R.string.apps_hint_phone))
        val config = JSONObject().put("token", token).put("strings", strings)
        val html = app.assets.open("player/apps.html").bufferedReader().use { it.readText() }
            .replace("/*AAB_CONFIG*/", "window.AAB_APPS = $config;")
            .replace("/*AAB_THEME*/", theme)
        return response(html.toByteArray(Charsets.UTF_8), "text/html", mapOf("Cache-Control" to "no-store", "X-Frame-Options" to "DENY"))
    }

    fun handle(context: Context, path: String, url: Uri): WebResourceResponse? = when (path) {
        "/api/apps/list" -> list(context)
        "/api/apps/status" -> json(JSONObject().put("status", statusName(ShizukuManager.status(context))))
        "/api/apps/favorite" -> favorite(context, url)
        "/api/apps/authorize" -> {
            // Il permesso si chiede da qui: la finestra di Shizuku compare sul telefono
            ShizukuManager.requestPermission()
            json(JSONObject().put("ok", true))
        }
        "/api/app/icon" -> icon(context, url.getQueryParameter("pkg"))
        else -> null
    }

    internal fun statusName(status: ShizukuManager.Status): String = when (status) {
        ShizukuManager.Status.READY -> "ready"
        ShizukuManager.Status.NOT_INSTALLED -> "missing"
        ShizukuManager.Status.NOT_RUNNING -> "stopped"
        ShizukuManager.Status.NO_PERMISSION -> "permission"
    }

    private fun list(context: Context): WebResourceResponse {
        val favorites = FavoriteAppsManager(context).loadFavoritePackageNames().toSet()
        val apps = JSONArray()
        VirtualApps.installed(context).forEach { app ->
            apps.put(JSONObject().put("p", app.packageName).put("n", app.name).put("f", app.packageName in favorites))
        }
        return json(JSONObject().put("apps", apps))
    }

    private fun favorite(context: Context, url: Uri): WebResourceResponse {
        val packageName = url.getQueryParameter("pkg")
        if (packageName == null || !VirtualApps.isAvailable(context, packageName)) return status(400, "Bad Request")
        FavoriteAppsManager(context).setFavorite(packageName, url.getQueryParameter("on") == "1")
        return json(JSONObject().put("ok", true))
    }

    private fun icon(context: Context, packageName: String?): WebResourceResponse? {
        if (packageName.isNullOrBlank() || !VirtualApps.isAvailable(context, packageName)) return null
        val bytes = iconBytes.get(packageName) ?: run {
            val bitmap = VirtualApps.icon(context, packageName, ICON_SIZE) ?: return null
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }.also { iconBytes.put(packageName, it) }
        }
        return response(bytes, "image/png", mapOf("Cache-Control" to "private, max-age=3600"))
    }

    private fun json(body: JSONObject): WebResourceResponse =
        response(body.toString().toByteArray(Charsets.UTF_8), "application/json", mapOf("Cache-Control" to "no-store"))

    private fun status(code: Int, reason: String): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", code, reason, mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))

    private fun response(bytes: ByteArray, mime: String, headers: Map<String, String>): WebResourceResponse =
        WebResourceResponse(mime, if (mime.startsWith("image/")) null else "UTF-8", 200, "OK", headers, ByteArrayInputStream(bytes))
}
