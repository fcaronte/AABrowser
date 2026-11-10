package com.fcaronte.aabrowser.utils

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import com.fcaronte.aabrowser.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * TV di CarWeb Media (https://player.local/tv): la pagina e le sue richieste di dati (gruppi, canali, avvio di un
 * canale). Come il resto di CarWeb Media i dati vogliono il token della pagina (lo controlla LocalPlayer); gli stream
 * passano da IptvProxy. Alla pagina vanno solo id e nomi: indirizzi e credenziali restano nell'app.
 */
internal object IptvApi {
    private const val TAG = "IptvApi"
    private const val MAX_SEARCH_RESULTS = 300
    private const val MAX_GROUP_CHANNELS = 20_000

    fun page(context: Context, token: String, theme: String): WebResourceResponse {
        val strings = JSONObject()
            .put("noSources", context.getString(R.string.tv_no_sources))
            .put("search", context.getString(R.string.tv_search))
            .put("favorites", context.getString(R.string.tv_favorites))
            .put("other", context.getString(R.string.tv_other_channels))
            .put("loading", context.getString(R.string.player_loading))
            .put("unavailable", context.getString(R.string.tv_unavailable))
            .put("sourceError", context.getString(R.string.tv_source_error))
            .put("noResults", context.getString(R.string.tv_no_results))
            .put("addList", context.getString(R.string.media_lists_add))
            .put("addOnPhone", context.getString(R.string.iptv_add_on_phone))
        val config = JSONObject()
            .put("token", token)
            .put("locale", context.resources.configuration.locales[0].toLanguageTag())
            .put("strings", strings)
            .put("sources", sourcesJson(context))
            .put("saved", MediaPages.saved(context, MediaPages.TV))
            .put("zoom", LocalPlayer.videoZoomStrings(context))
        val html = context.assets.open("player/tv.html").bufferedReader().use { it.readText() }
            .replace("/*AAB_CONFIG*/", "window.AAB_TV = $config;")
            .replace("/*AAB_THEME*/", theme)
        return response(
            html.toByteArray(Charsets.UTF_8),
            "text/html",
            200,
            mapOf("Cache-Control" to "no-store", "X-Frame-Options" to "DENY")
        )
    }

    /** Richieste /api/tv/…; null se il percorso non è della TV. */
    fun handle(context: Context, path: String, url: Uri): WebResourceResponse? {
        if (path == "/api/tv/sources") return json(JSONObject().put("sources", sourcesJson(context)))
        val source = IptvSources.find(context, url.getQueryParameter("source"))
        return when (path) {
            "/api/tv/groups" -> withLineup(context, source, url.getQueryParameter("refresh") == "1", ::groups)
            "/api/tv/channels" -> withLineup(context, source, false) { channels(it, url) }
            "/api/tv/play" -> withLineup(context, source, false) { play(it, url) }
            else -> null
        }
    }

    private fun sourcesJson(context: Context): JSONArray {
        val sources = JSONArray()
        IptvSources.list(context).forEach { sources.put(JSONObject().put("id", it.id).put("name", it.name)) }
        return sources
    }

    private fun withLineup(
        context: Context,
        source: IptvSources.Source?,
        refresh: Boolean,
        block: (IptvLibrary.Lineup) -> WebResourceResponse
    ): WebResourceResponse {
        if (source == null) return error(404, "source")
        val lineup = try {
            IptvLibrary.lineup(context, source, refresh)
        } catch (e: Exception) {
            AppLog.w(TAG, "List not loaded: ${e.javaClass.simpleName}")
            return error(502, "source")
        }
        return block(lineup)
    }

    private fun groups(lineup: IptvLibrary.Lineup): WebResourceResponse {
        val counts = IntArray(lineup.groups.size)
        lineup.channels.forEach { counts[it.group]++ }
        val groups = JSONArray()
        lineup.groups.forEachIndexed { index, name ->
            groups.put(JSONObject().put("i", index).put("name", name).put("count", counts[index]))
        }
        return json(JSONObject().put("groups", groups).put("total", lineup.channels.size))
    }

    // Un gruppo, o la ricerca per nome su tutta la lista (MediaMatcher), dal canale più simile; "m" e "confident" dicono
    // alla pagina se il primo è abbastanza sicuro da partire da solo (comandi vocali)
    private fun channels(lineup: IptvLibrary.Lineup, url: Uri): WebResourceResponse {
        val query = url.getQueryParameter("q").orEmpty().trim()
        val group = url.getQueryParameter("group")?.toIntOrNull()
        val channels = JSONArray()
        fun add(channel: IptvLibrary.Channel, score: Int?) {
            channels.put(
                JSONObject()
                    .put("k", channel.key)
                    .put("n", channel.name)
                    .put("l", channel.logo ?: JSONObject.NULL)
                    .put("g", lineup.groups[channel.group])
                    .apply { if (score != null) put("m", score) }
            )
        }
        when {
            query.isNotEmpty() -> {
                val key = MediaMatcher.key(query)
                lineup.channels.indices.asSequence()
                    .map { it to MediaMatcher.score(key, lineup.searchKeys[it]) }
                    .filter { it.second > 0 }
                    .sortedByDescending { it.second }
                    .take(MAX_SEARCH_RESULTS)
                    .forEach { (index, score) -> add(lineup.channels[index], score) }
            }
            group != null -> lineup.channels.asSequence()
                .filter { it.group == group }
                .take(MAX_GROUP_CHANNELS)
                .forEach { add(it, null) }
        }
        return json(JSONObject().put("channels", channels).put("confident", MediaMatcher.CONFIDENT))
    }

    private fun play(lineup: IptvLibrary.Lineup, url: Uri): WebResourceResponse {
        val channel = lineup.channel(url.getQueryParameter("ch")) ?: return error(404, "channel")
        val kind = IptvProxy.kindOf(channel.url, channel.headers)
        return json(JSONObject().put("url", IptvProxy.proxyPath(channel.url, channel.headers)).put("kind", kind))
    }

    private fun json(value: JSONObject): WebResourceResponse =
        response(value.toString().toByteArray(Charsets.UTF_8), "application/json", 200, mapOf("Cache-Control" to "no-store"))

    private fun error(code: Int, kind: String): WebResourceResponse = response(
        JSONObject().put("error", kind).toString().toByteArray(Charsets.UTF_8),
        "application/json",
        code,
        mapOf("Cache-Control" to "no-store")
    )

    private fun response(bytes: ByteArray, mime: String, code: Int, headers: Map<String, String>): WebResourceResponse =
        WebResourceResponse(mime, "utf-8", code, if (code == 200) "OK" else "Error", headers, ByteArrayInputStream(bytes))
}
