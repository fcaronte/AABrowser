package com.fcaronte.aabrowser.utils

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.compose.runtime.snapshots.Snapshot
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.settings.SidebarAction
import com.fcaronte.aabrowser.settings.SidebarHost
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Le impostazioni come pagina (https://player.local/settings, assets/player/settings.html): lo stesso formato per la Wide, che
 * non ha schermate sue, e per la Main. Come le altre pagine di CarWeb Media i dati vogliono il token che la pagina riceve nel
 * suo HTML (LocalPlayer). Le scelte che hanno senso solo sul telefono (file, permessi di sistema, liste IPTV) non ci sono:
 * la pagina lo dice e rimanda al telefono, come fanno già gli altri avvisi "dal telefono".
 *
 * Ogni voce è una chiave fissa con il suo tipo e un valore da controllare: la pagina non può cambiare altro (`apply`).
 */
object SettingsApi {
    fun page(context: Context, token: String, theme: String): WebResourceResponse {
        val app = AppLocale.wrap(context)
        val strings = JSONObject()
            .put("title", app.getString(R.string.settings_title))
            .put("back", app.getString(R.string.back_button))
            .put("up", app.getString(R.string.tab_bar_action_up))
            .put("down", app.getString(R.string.tab_bar_action_down))
            .put("mainOnly", app.getString(R.string.sidebar_action_main_only))
            .put("phoneNote", app.getString(R.string.settings_phone_note))
            .put("error", app.getString(R.string.settings_page_error))
        val config = JSONObject().put("token", token).put("strings", strings)
        val html = app.assets.open("player/settings.html").bufferedReader().use { it.readText() }
            .replace("/*AAB_CONFIG*/", "window.AAB_SETTINGS = $config;")
            .replace("/*AAB_THEME*/", theme)
        return response(html.toByteArray(Charsets.UTF_8), "text/html", 200, "OK")
    }

    fun handle(context: Context, path: String, url: Uri): WebResourceResponse? = when (path) {
        "/api/settings/get" -> json(snapshot(context))
        "/api/settings/set" -> {
            val key = url.getQueryParameter("key")
            val value = url.getQueryParameter("value")
            // Dopo la modifica la pagina riceve lo stato nuovo, com'è davvero (un valore rifiutato non cambia niente)
            if (key != null && value != null && apply(context, key, value)) json(snapshot(context))
            else response(ByteArray(0), "text/plain", 400, "Bad Request")
        }
        else -> null
    }

    /** "1" / "0" come arrivano dalla pagina; altro = null (rifiutato). */
    internal fun parseFlag(value: String): Boolean? = when (value) {
        "1" -> true
        "0" -> false
        else -> null
    }

    /** Applica una scelta della pagina; false se la chiave o il valore non sono validi. */
    internal fun apply(context: Context, key: String, value: String): Boolean {
        val done = when (key) {
            "wide_reopen_last_page" -> parseFlag(value)?.let { AppSettings.setWideReopenLastPage(context, it) } != null
            "wide_video_menu_on_tap" -> parseFlag(value)?.let { AppSettings.setWideVideoMenuOnTap(context, it) } != null
            "main_dock" -> parseFlag(value)?.let { AppSettings.setMainDock(context, it) } != null
            "wide_sidebar_size" -> value.toIntOrNull()?.takeIf { it in AppSettings.wideSidebarSizes }
                ?.let { AppSettings.setWideSidebarSize(context, it) } != null
            // I tasti mostrati, nell'ordine scelto: gli altri sono quelli nascosti. Home c'è sempre (SidebarAction.parse)
            "sidebar_actions" -> {
                AppSettings.setSidebarActions(context, SidebarAction.parse(value))
                true
            }
            else -> false
        }
        // Scritto fuori dalla UI di Compose (qui, o nella Wide senza nessuna schermata aperta): chi osserva lo stato lo sa solo
        // dopo la notifica, e la Wide si riadatta da lì (il dock si ricostruisce, la dimensione cambia)
        if (done) Snapshot.sendApplyNotifications()
        return done
    }

    /** Tutte le voci con il valore di ora, nella lingua dell'app. */
    internal fun snapshot(context: Context): JSONObject {
        val app = AppLocale.wrap(context)
        val shown = AppSettings.sidebarActions.value
        val actions = JSONArray()
        (shown + SidebarAction.ALL.filter { it !in shown }).forEach { action ->
            actions.put(
                JSONObject()
                    .put("id", action.id)
                    .put("label", app.getString(action.labelRes))
                    .put("on", action in shown)
                    .put("essential", action.essential)
                    .put("mainOnly", !action.availableIn(SidebarHost.WIDE))
            )
        }
        val wide = JSONArray()
            .put(switchItem("wide_reopen_last_page", app.getString(R.string.wide_reopen_last_page), null, AppSettings.wideReopenLastPage.value))
            .put(
                choiceItem(
                    "wide_sidebar_size", app.getString(R.string.wide_sidebar_size),
                    AppSettings.wideSidebarSizes.map { it.toString() to "$it%" }, AppSettings.wideSidebarSize.value.toString()
                )
            )
            .put(
                choiceItem(
                    "wide_video_menu_on_tap", app.getString(R.string.wide_video_menu_label),
                    listOf("0" to app.getString(R.string.video_menu_always), "1" to app.getString(R.string.video_menu_touch)),
                    if (AppSettings.wideVideoMenuOnTap.value) "1" else "0"
                )
            )
            .put(
                JSONObject().put("key", "sidebar_actions").put("type", "actions")
                    .put("label", app.getString(R.string.sidebar_actions_label))
                    .put("desc", app.getString(R.string.sidebar_actions_desc))
                    .put("value", actions)
            )
        val main = JSONArray()
            .put(switchItem("main_dock", app.getString(R.string.main_dock_label), app.getString(R.string.main_dock_desc), AppSettings.mainDock.value))
        return JSONObject().put(
            "sections",
            JSONArray()
                .put(JSONObject().put("title", app.getString(R.string.wide_app_name)).put("items", wide))
                .put(JSONObject().put("title", app.getString(R.string.app_name)).put("items", main))
        )
    }

    private fun switchItem(key: String, label: String, desc: String?, value: Boolean): JSONObject =
        JSONObject().put("key", key).put("type", "switch").put("label", label).put("desc", desc ?: JSONObject.NULL).put("value", value)

    private fun choiceItem(key: String, label: String, options: List<Pair<String, String>>, value: String): JSONObject {
        val list = JSONArray()
        options.forEach { (id, text) -> list.put(JSONArray().put(id).put(text)) }
        return JSONObject().put("key", key).put("type", "choice").put("label", label).put("options", list).put("value", value)
    }

    private fun json(body: JSONObject): WebResourceResponse =
        response(body.toString().toByteArray(Charsets.UTF_8), "application/json", 200, "OK")

    private fun response(bytes: ByteArray, mime: String, status: Int, reason: String): WebResourceResponse =
        WebResourceResponse(
            mime, "utf-8", status, reason,
            mapOf("Cache-Control" to "no-store", "X-Frame-Options" to "DENY"),
            ByteArrayInputStream(bytes)
        )
}
