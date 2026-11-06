package com.fcaronte.aabrowser.utils

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Canali delle liste della TV di CarWeb Media: legge la lista M3U da URL o file, oppure chiede all'account Xtream i
 * canali live, e li tiene in memoria per lista (6 ore, o finché la pagina chiede di aggiornarli). Alla pagina vanno gruppi e canali
 * senza indirizzi: quelli li apre solo IptvProxy. Liste anche da decine di migliaia di canali: l'M3U si legge riga
 * per riga e la risposta Xtream con un lettore JSON a flusso, senza tenere in memoria tutto il testo.
 */
object IptvLibrary {
    /** [key]: id stabile del canale nella lista (preferiti, ultimo canale); [group]: indice in [Lineup.groups]. */
    class Channel(val key: String, val name: String, val group: Int, val logo: String?, val url: String)

    /** [groups]: nomi dei gruppi, nell'ordine della lista ("" = canali senza gruppo). */
    class Lineup(val groups: List<String>, val channels: List<Channel>, private val loadedAt: Long) {
        /** Nomi pronti per la ricerca, nell'ordine di [channels]: si calcolano alla prima ricerca e poi restano. */
        val searchKeys: List<MediaMatcher.Key> by lazy { channels.map { MediaMatcher.key(it.name) } }

        private val byKey: Map<String, Channel> by lazy { channels.associateBy { it.key } }

        fun channel(key: String?): Channel? = key?.let { byKey[it] }

        fun isFresh(): Boolean = System.currentTimeMillis() - loadedAt < CACHE_MS
    }

    private const val TAG = "IptvLibrary"
    private const val CACHE_MS = 6 * 3600_000L
    private const val MAX_CHANNELS = 100_000

    private val cache = ConcurrentHashMap<String, Lineup>()
    private val locks = ConcurrentHashMap<String, Any>()

    /** Canali della lista (dalla memoria se recenti). Lancia un'eccezione se il server non risponde o rifiuta l'accesso. */
    fun lineup(context: Context, source: IptvSources.Source, refresh: Boolean = false): Lineup {
        synchronized(locks.getOrPut(source.id) { Any() }) {
            cache[source.id]?.takeIf { !refresh && it.isFresh() }?.let { return it }
            val loaded = when (source.type) {
                IptvSources.Type.DEFAULT_M3U, IptvSources.Type.M3U -> IptvProxy.open(source.url).use { upstream ->
                    if (upstream.status >= 400) throw IOException("HTTP ${upstream.status}")
                    parseM3u(upstream.stream)
                }
                IptvSources.Type.LOCAL_M3U -> {
                    val stream = context.contentResolver.openInputStream(Uri.parse(source.url))
                        ?: throw IOException("Unable to open playlist")
                    stream.use(::parseM3u)
                }
                IptvSources.Type.XTREAM -> loadXtream(source)
            }
            cache[source.id] = loaded
            AppLog.d(TAG, "Loaded ${loaded.channels.size} channels in ${loaded.groups.size} groups")
            return loaded
        }
    }

    fun forget(sourceId: String) {
        cache.remove(sourceId)
    }

    private class Builder {
        private val groups = LinkedHashMap<String, Int>()
        private val channels = ArrayList<Channel>()

        fun group(name: String): Int = groups.getOrPut(name.trim()) { groups.size }

        fun add(key: String, name: String, group: Int, logo: String?, url: String) {
            if (channels.size >= MAX_CHANNELS) return
            val title = name.trim().ifEmpty { Uri.parse(url).lastPathSegment.orEmpty() }
            channels.add(Channel(key, title, group, logo?.trim()?.takeIf { UrlUtils.isWebUrl(it) }, url))
        }

        fun build() = Lineup(groups.keys.toList(), channels, System.currentTimeMillis())
    }

    // ------------------------------------------------------------------
    // M3U
    // ------------------------------------------------------------------

    private val ATTRIBUTE = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    private fun parseM3u(stream: InputStream): Lineup {
        val builder = Builder()
        var name: String? = null
        var group: String? = null
        var logo: String? = null
        InputStreamReader(stream, Charsets.UTF_8).buffered().useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                when {
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        val comma = nameSeparator(line)
                        val attributes = ATTRIBUTE.findAll(line.substring(0, comma))
                            .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                        name = line.substring((comma + 1).coerceAtMost(line.length)).trim()
                            .ifEmpty { attributes["tvg-name"].orEmpty() }
                        group = attributes["group-title"]
                        logo = attributes["tvg-logo"]
                    }
                    line.startsWith("#EXTGRP:", ignoreCase = true) -> group = line.substringAfter(':')
                    line.isEmpty() || line.startsWith("#") -> Unit
                    else -> {
                        // Solo stream http(s): rtmp, udp e simili la WebView non li apre
                        if (UrlUtils.isWebUrl(line)) {
                            builder.add(line.hashCode().toUInt().toString(36), name.orEmpty(), builder.group(group.orEmpty()), logo, line)
                        }
                        name = null
                        group = null
                        logo = null
                    }
                }
            }
        }
        return builder.build()
    }

    // Virgola che separa gli attributi dal nome: la prima fuori dalle virgolette (group-title può contenerne)
    private fun nameSeparator(line: String): Int {
        var quoted = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return i
            }
        }
        return line.length
    }

    // ------------------------------------------------------------------
    // Xtream
    // ------------------------------------------------------------------

    private fun loadXtream(source: IptvSources.Source): Lineup {
        val api = "${source.url}/player_api.php?username=${query(source.username)}&password=${query(source.password)}"
        val account = JSONObject(readText(api))
        val user = account.optJSONObject("user_info") ?: throw IOException("Not an Xtream server")
        if (user.optString("auth") != "1") throw IOException("Access refused")
        // HLS se l'account lo permette (hls.js), altrimenti MPEG-TS (mpegts.js)
        val formats = user.optJSONArray("allowed_output_formats") ?: JSONArray()
        val extension = if ((0 until formats.length()).any { formats.optString(it) == "m3u8" }) "m3u8" else "ts"

        val builder = Builder()
        val categories = HashMap<String, Int>()
        val categoryList = JSONArray(readText("$api&action=get_live_categories"))
        for (i in 0 until categoryList.length()) {
            val category = categoryList.optJSONObject(i) ?: continue
            categories[category.optString("category_id")] = builder.group(category.optString("category_name"))
        }

        val base = "${source.url}/live/${Uri.encode(source.username)}/${Uri.encode(source.password)}/"
        IptvProxy.open("$api&action=get_live_streams").use { upstream ->
            if (upstream.status >= 400) throw IOException("HTTP ${upstream.status}")
            JsonReader(InputStreamReader(upstream.stream, Charsets.UTF_8)).use { reader ->
                reader.beginArray()
                while (reader.hasNext()) {
                    var id: String? = null
                    var name = ""
                    var logo: String? = null
                    var category = ""
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val field = reader.nextName()
                        if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull()
                            continue
                        }
                        when (field) {
                            "stream_id" -> id = reader.nextString()
                            "name" -> name = reader.nextString()
                            "stream_icon" -> logo = reader.nextString()
                            "category_id" -> category = reader.nextString()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    val streamId = id ?: continue
                    val group = categories[category] ?: builder.group("")
                    builder.add("x$streamId", name, group, logo, "$base$streamId.$extension")
                }
                reader.endArray()
            }
        }
        return builder.build()
    }

    private fun readText(url: String): String = IptvProxy.open(url).use { upstream ->
        if (upstream.status >= 400) throw IOException("HTTP ${upstream.status}")
        upstream.stream.bufferedReader().readText()
    }

    private fun query(value: String): String = URLEncoder.encode(value, "UTF-8")
}
