package com.fcaronte.aabrowser.utils

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import com.fcaronte.aabrowser.BuildConfig
import com.fcaronte.aabrowser.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Pagina https://player.local/podcast. La classifica del Paese e la ricerca vengono da Apple, che dà solo nomi e indirizzi
 * dei feed: copertine e audio arrivano dal feed e dal server di chi pubblica il podcast, e nell'app non c'è nessun
 * catalogo. I feed dell'utente (RSS, OPML) sono in IptvSources. La pagina vede solo gli id finché non si sceglie un episodio.
 * I comandi vocali cercano i nomi con [MediaMatcher], come per Radio e TV.
 */
internal object PodcastApi {
    private const val TAG = "PodcastApi"
    private val USER_AGENT = "CarWeb/${BuildConfig.VERSION_NAME} (https://github.com/fcaronte/AABrowser)"
    private const val MINE = "mine"
    private const val MAX_CHART = 100
    private const val MAX_RESULTS = 50
    private const val MAX_QUERY = 100
    private const val LOOKUP_BATCH = 50
    private const val MAX_REDIRECTS = 6
    private const val MAX_FEED_BYTES = 40L * 1024 * 1024
    private const val CHART_CACHE_MS = 6 * 60 * 60 * 1000L
    private const val FEED_CACHE_MS = 30 * 60 * 1000L
    private const val MAX_KEPT_FEEDS = 30
    private const val MAX_KEPT_SHOWS = 2000
    private const val MAX_FOLLOWED = 500

    private val SHOW_ID = Regex("""a\d{1,15}|u[0-9a-fA-F-]{36}""")
    private val COLLECTION_ID = Regex("""\d{1,15}""")
    // Detto nella frase ("metti muschio selvaggio podcast") di solito non fa parte del nome
    private val PODCAST_WORDS = Regex(
        """(?<![\p{L}\p{N}])(?:podcasts?|pódcast|podcastot|подкасты?|بودكاست|البودكاست|पॉडकास्ट)(?![\p{L}\p{N}])|播客""",
        RegexOption.IGNORE_CASE
    )

    private data class Show(val id: String, val title: String, val author: String, val feedUrl: String, val fromUser: Boolean)
    private data class CachedChart(val loadedAt: Long, val shows: List<Show>)
    private data class CachedFeed(val loadedAt: Long, val channel: PodcastFeed.Channel)

    private val charts = ConcurrentHashMap<String, CachedChart>()
    private val knownShows = ConcurrentHashMap<String, Show>()
    private val covers = ConcurrentHashMap<String, String>()
    private val feeds = Collections.synchronizedMap(object : LinkedHashMap<String, CachedFeed>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedFeed>?): Boolean = size > MAX_KEPT_FEEDS
    })

    fun page(context: Context, token: String, theme: String): WebResourceResponse {
        val app = AppLocale.wrap(context)
        val strings = JSONObject()
            .put("title", app.getString(R.string.podcast_title))
            .put("search", app.getString(R.string.podcast_search))
            .put("all", app.getString(R.string.podcast_all))
            .put("following", app.getString(R.string.podcast_following))
            .put("follow", app.getString(R.string.podcast_follow))
            .put("loading", app.getString(R.string.radio_loading))
            .put("noResults", app.getString(R.string.podcast_no_results))
            .put("error", app.getString(R.string.podcast_error))
            .put("unavailable", app.getString(R.string.podcast_unavailable))
            .put("listened", app.getString(R.string.podcast_listened))
            .put("left", app.getString(R.string.podcast_left))
            .put("addList", app.getString(R.string.media_lists_add))
            .put("addOnPhone", app.getString(R.string.iptv_add_on_phone))
        val config = JSONObject()
            .put("token", token)
            .put("locale", app.resources.configuration.locales[0].toLanguageTag())
            .put("sources", sourcesJson(app))
            .put("strings", strings)
            .put("saved", MediaPages.saved(context, MediaPages.PODCAST))
        val html = app.assets.open("player/podcast.html").bufferedReader().use { it.readText() }
            .replace("/*AAB_CONFIG*/", "window.AAB_PODCAST = $config;")
            .replace("/*AAB_THEME*/", theme)
        return response(html.toByteArray(Charsets.UTF_8), 200, "OK", "text/html")
    }

    fun handle(context: Context, path: String, url: Uri): WebResourceResponse? = when (path) {
        "/api/podcast/sources" -> json(JSONObject().put("sources", sourcesJson(AppLocale.wrap(context))))
        "/api/podcast/shows" -> shows(context, url.getQueryParameter("source"), url.getQueryParameter("refresh") == "1")
        "/api/podcast/search" -> search(context, url.getQueryParameter("q").orEmpty(), url.getQueryParameter("source"))
        "/api/podcast/find" -> find(
            context,
            url.getQueryParameter("q").orEmpty(),
            url.getQueryParameter("source"),
            url.getQueryParameter("f")
        )
        "/api/podcast/episodes" -> episodes(context, url.getQueryParameter("show"), url.getQueryParameter("refresh") == "1")
        "/api/podcast/cover" -> cover(context, url.getQueryParameter("show"))
        "/api/podcast/play" -> play(context, url.getQueryParameter("show"), url.getQueryParameter("episode"))
        else -> null
    }

    /** Controlla un feed scritto nelle Impostazioni: nome del podcast e indirizzo, null se non è un feed raggiungibile. */
    fun checkFeed(address: String): Pair<String, String>? {
        val url = address.trim().let { if (it.contains("://")) it else "https://$it" }
        return try {
            val channel = readFeed(url, fromUser = true) { PodcastFeed.parse(it, headOnly = true) }
            channel.title.takeIf(String::isNotBlank)?.let { it to url }
        } catch (e: Exception) {
            AppLog.w(TAG, "Feed not added: ${e.javaClass.simpleName}")
            null
        }
    }

    /** I feed di un file OPML scelto nelle Impostazioni: nome e indirizzo. */
    fun readOpml(context: Context, uri: Uri): List<Pair<String, String>> =
        context.contentResolver.openInputStream(uri)?.use { PodcastFeed.parseOpml(it) }.orEmpty()

    // Le classifiche, una voce a parte per ogni Paese, e un'unica voce per i feed aggiunti (possono essere centinaia)
    private fun sourcesJson(app: Context): JSONArray {
        val sources = IptvSources.list(app, IptvSources.Section.PODCAST)
        val result = JSONArray()
        sources.filterNot { it.isPlaylist }.forEach { result.put(JSONObject().put("id", it.id).put("name", it.name)) }
        if (sources.any { it.isPlaylist }) {
            result.put(JSONObject().put("id", MINE).put("name", app.getString(R.string.podcast_mine)))
        }
        return result
    }

    private fun chartSource(context: Context, sourceId: String?): IptvSources.Source =
        IptvSources.find(context, sourceId, IptvSources.Section.PODCAST)?.takeUnless { it.isPlaylist }
            ?: IptvSources.list(context, IptvSources.Section.PODCAST).first()

    private fun userShow(source: IptvSources.Source) = Show("u${source.id}", source.name, "", source.url, fromUser = true)

    private fun shows(context: Context, sourceId: String?, refresh: Boolean): WebResourceResponse {
        val list = try {
            if (sourceId == MINE) {
                IptvSources.list(context, IptvSources.Section.PODCAST).filter { it.isPlaylist }.map(::userShow)
            } else {
                chart(chartSource(context, sourceId).url, refresh)
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Show list unavailable: ${e.javaClass.simpleName}")
            return error(502, "shows")
        }
        return json(JSONObject().put("shows", showsJson(list)))
    }

    private fun search(context: Context, text: String, sourceId: String?): WebResourceResponse {
        val found = try {
            searchApple(context, text, sourceId)
        } catch (e: Exception) {
            AppLog.w(TAG, "Search unavailable: ${e.javaClass.simpleName}")
            return error(502, "search")
        }
        return json(JSONObject().put("shows", showsJson(found)))
    }

    private fun searchApple(context: Context, text: String, sourceId: String?): List<Show> {
        val term = text.trim().take(MAX_QUERY)
        if (term.isEmpty()) return emptyList()
        val country = chartSource(context, sourceId).url.lowercase(Locale.ROOT)
        val results = getJson(
            "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=$MAX_RESULTS&country=$country&term=" +
                URLEncoder.encode(term, "UTF-8")
        ).optJSONArray("results") ?: JSONArray()
        val found = (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val feed = item.optString("feedUrl").takeIf(RadioApi::isHttpUrl) ?: return@mapNotNull null
            Show("a${item.optLong("collectionId")}", item.optString("collectionName"), item.optString("artistName"), feed, fromUser = false)
        }
        keep(found)
        return found
    }

    /**
     * Ricerca per nome dei comandi vocali: i podcast seguiti (li conosce la pagina, [followed]), i feed aggiunti, la
     * classifica e infine Apple. Dal più simile, con "m" e "confident" come per la radio; i risultati di Apple che non
     * somigliano al nome restano in fondo, nel loro ordine ("cerca il podcast …" li mostra).
     */
    private fun find(context: Context, text: String, sourceId: String?, followed: String?): WebResourceResponse {
        val variants = listOf(text, text.replace(PODCAST_WORDS, " ")).map(String::trim).filter(String::isNotEmpty).distinct()
        val keys = variants.map(MediaMatcher::key)
        val scores = HashMap<String, Pair<Show, Int>>()
        fun add(shows: List<Show>) = keys.forEach { key ->
            MediaMatcher.rank(key, shows) { MediaMatcher.key(it.title) }.forEach { (show, score) ->
                if ((scores[show.id]?.second ?: -1) < score) scores[show.id] = show to score
            }
        }
        fun confident() = scores.values.any { it.second >= MediaMatcher.CONFIDENT }

        add(followedShows(followed) + IptvSources.list(context, IptvSources.Section.PODCAST).filter { it.isPlaylist }.map(::userShow))
        if (!confident()) {
            add(
                try {
                    chart(chartSource(context, sourceId).url, refresh = false)
                } catch (e: Exception) {
                    AppLog.w(TAG, "Show list unavailable: ${e.javaClass.simpleName}")
                    emptyList()
                }
            )
        }
        val others = ArrayList<Show>()
        for (variant in variants) {
            if (confident()) break
            val found = try {
                searchApple(context, variant, sourceId)
            } catch (e: Exception) {
                AppLog.w(TAG, "Search unavailable: ${e.javaClass.simpleName}")
                emptyList()
            }
            add(found)
            others += found
        }
        val ranked = scores.values.sortedByDescending { it.second } +
            others.filter { it.id !in scores }.distinctBy { it.id }.map { it to 0 }
        val shows = JSONArray()
        ranked.take(MAX_RESULTS).forEach { (show, score) -> shows.put(showJson(show).put("m", score)) }
        return json(JSONObject().put("shows", shows).put("confident", MediaMatcher.CONFIDENT))
    }

    // I podcast seguiti li tiene la pagina: qui servono id e nome, l'indirizzo del feed si ritrova dall'id quando serve
    private fun followedShows(json: String?): List<Show> = try {
        val array = JSONArray(json ?: "[]")
        (0 until minOf(array.length(), MAX_FOLLOWED)).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.matches(SHOW_ID) } ?: return@mapNotNull null
            Show(id, item.optString("title"), "", "", fromUser = id.startsWith("u"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun showJson(show: Show): JSONObject =
        JSONObject().put("id", show.id).put("title", show.title).put("author", show.author)
            .apply { covers[show.id]?.takeIf(String::isNotEmpty)?.let { put("image", it) } }

    private fun showsJson(list: List<Show>): JSONArray {
        val result = JSONArray()
        list.forEach { result.put(showJson(it)) }
        return result
    }

    private fun keep(shows: List<Show>) {
        if (knownShows.size > MAX_KEPT_SHOWS) knownShows.clear()
        shows.forEach { knownShows[it.id] = it }
    }

    private fun chart(countryCode: String, refresh: Boolean): List<Show> {
        val country = countryCode.lowercase(Locale.ROOT)
        val cached = charts[country]
        if (!refresh && cached != null && System.currentTimeMillis() - cached.loadedAt < CHART_CACHE_MS) return cached.shows
        val entries = getJson("https://itunes.apple.com/$country/rss/toppodcasts/limit=$MAX_CHART/explicit=true/json")
            .getJSONObject("feed").optJSONArray("entry") ?: JSONArray()
        val ranked = (0 until entries.length()).mapNotNull { index ->
            val entry = entries.optJSONObject(index) ?: return@mapNotNull null
            val id = entry.optJSONObject("id")?.optJSONObject("attributes")?.optString("im:id")
                ?.takeIf { it.matches(COLLECTION_ID) } ?: return@mapNotNull null
            val name = entry.optJSONObject("im:name")?.optString("label").orEmpty()
            Triple(id, name, entry.optJSONObject("im:artist")?.optString("label").orEmpty())
        }
        val addresses = lookup(ranked.map { it.first }, country)
        val shows = ranked.mapNotNull { (id, name, artist) ->
            addresses[id]?.let { Show("a$id", name, artist, it, fromUser = false) }
        }
        keep(shows)
        charts[country] = CachedChart(System.currentTimeMillis(), shows)
        return shows
    }

    private fun lookup(ids: List<String>, country: String): Map<String, String> {
        val addresses = HashMap<String, String>()
        ids.chunked(LOOKUP_BATCH).forEach { batch ->
            val results = getJson("https://itunes.apple.com/lookup?id=${batch.joinToString(",")}&entity=podcast&country=$country")
                .optJSONArray("results") ?: return@forEach
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val feed = item.optString("feedUrl").takeIf(RadioApi::isHttpUrl) ?: continue
                addresses[item.optLong("collectionId").toString()] = feed
            }
        }
        return addresses
    }

    // Anche un podcast seguito o l'ultimo ascoltato, dopo un riavvio dell'app o da un'altra lista
    private fun showById(context: Context, id: String?): Show? {
        if (id == null || !id.matches(SHOW_ID)) return null
        if (id.startsWith("u")) {
            return IptvSources.find(context, id.drop(1), IptvSources.Section.PODCAST)?.takeIf { it.isPlaylist }?.let(::userShow)
        }
        knownShows[id]?.let { return it }
        val collection = id.drop(1)
        val address = try {
            lookup(listOf(collection), chartSource(context, null).url.lowercase(Locale.ROOT))[collection]
        } catch (e: Exception) {
            AppLog.w(TAG, "Show not found: ${e.javaClass.simpleName}")
            null
        } ?: return null
        return Show(id, "", "", address, fromUser = false).also { keep(listOf(it)) }
    }

    private fun channelOf(show: Show, refresh: Boolean): PodcastFeed.Channel {
        val cached = feeds[show.id]
        if (!refresh && cached != null && System.currentTimeMillis() - cached.loadedAt < FEED_CACHE_MS) return cached.channel
        val channel = readFeed(show.feedUrl, show.fromUser) { PodcastFeed.parse(it) }
        feeds[show.id] = CachedFeed(System.currentTimeMillis(), channel)
        covers[show.id] = channel.image.orEmpty()
        return channel
    }

    private fun episodes(context: Context, showId: String?, refresh: Boolean): WebResourceResponse {
        val show = showById(context, showId) ?: return error(404, "show")
        val channel = try {
            channelOf(show, refresh)
        } catch (e: Exception) {
            AppLog.w(TAG, "Feed unavailable: ${e.javaClass.simpleName}")
            return error(502, "feed")
        }
        val list = JSONArray()
        channel.episodes.sortedByDescending { it.publishedAt }.forEach { episode ->
            list.put(
                JSONObject()
                    .put("id", episode.id)
                    .put("title", episode.title)
                    .put("date", episode.publishedAt)
                    .put("duration", episode.duration)
                    .apply { episode.image?.let { put("image", it) } }
            )
        }
        val details = JSONObject()
            .put("id", show.id)
            .put("title", channel.title.ifBlank { show.title })
            .put("author", channel.author.ifBlank { show.author })
            .put("serial", channel.serial)
            .apply { channel.image?.let { put("image", it) } }
        return json(JSONObject().put("show", details).put("episodes", list))
    }

    // La copertina è in cima al feed: non serve scaricare gli episodi
    private fun cover(context: Context, showId: String?): WebResourceResponse {
        val show = showById(context, showId) ?: return error(404, "show")
        val image = covers[show.id] ?: try {
            readFeed(show.feedUrl, show.fromUser) { PodcastFeed.parse(it, headOnly = true) }.image.orEmpty()
                .also { covers[show.id] = it }
        } catch (e: Exception) {
            AppLog.w(TAG, "Cover unavailable: ${e.javaClass.simpleName}")
            ""
        }
        return json(JSONObject().put("image", image))
    }

    private fun play(context: Context, showId: String?, episodeId: String?): WebResourceResponse {
        val show = showById(context, showId) ?: return error(404, "show")
        val channel = try {
            channelOf(show, refresh = false)
        } catch (e: Exception) {
            AppLog.w(TAG, "Feed unavailable: ${e.javaClass.simpleName}")
            return error(502, "feed")
        }
        val episode = channel.episodes.firstOrNull { it.id == episodeId } ?: return error(404, "episode")
        if (!show.fromUser && !RadioApi.isPublicHttpUrl(episode.url)) return error(403, "episode")
        return json(JSONObject().put("url", episode.url))
    }

    // Dalle liste di Apple solo indirizzi pubblici; un feed aggiunto dall'utente può essere anche in casa, come le sue liste TV
    private fun <T> readFeed(address: String, fromUser: Boolean, read: (InputStream) -> T): T {
        var target = URL(address)
        repeat(MAX_REDIRECTS) {
            if (!RadioApi.isHttpUrl(target.toString()) || (!fromUser && !RadioApi.isPublicHttpUrl(target.toString()))) {
                throw IllegalArgumentException("Invalid feed address")
            }
            val connection = (target.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    target = URL(target, connection.getHeaderField("Location") ?: throw IllegalStateException("Redirect without location"))
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("Feed returned HTTP $code")
                return AdBlockLists.LimitedStream(connection.inputStream, MAX_FEED_BYTES).use(read)
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    private fun getJson(address: String): JSONObject {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Catalog returned HTTP ${connection.responseCode}")
            }
            return JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun json(value: JSONObject): WebResourceResponse =
        response(value.toString().toByteArray(Charsets.UTF_8), 200, "OK")

    private fun error(code: Int, reason: String): WebResourceResponse =
        response(JSONObject().put("error", reason).toString().toByteArray(Charsets.UTF_8), code, "Error")

    private fun response(bytes: ByteArray, code: Int, reason: String, mimeType: String = "application/json"): WebResourceResponse =
        WebResourceResponse(mimeType, "utf-8", code, reason, mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(bytes))
}
