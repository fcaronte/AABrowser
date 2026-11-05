package com.fcaronte.aabrowser.utils

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import com.fcaronte.aabrowser.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Radio Browser data for https://player.local/radio. Stream addresses stay native until a station is selected. */
internal object RadioApi {
    private const val TAG = "RadioApi"
    private const val DISCOVERY_HOST = "all.api.radio-browser.info"
    private const val API_SUFFIX = ".api.radio-browser.info"
    private const val USER_AGENT = "CarWeb/2.5.1 (https://github.com/fcaronte/AABrowser)"
    private const val MAX_STATIONS = 200
    private const val MAX_ICY_INTERVAL = 1024 * 1024
    private const val CACHE_MS = 30 * 60 * 1000L

    private data class Station(
        val uuid: String,
        val name: String,
        val streamUrl: String,
        val favicon: String?,
        val country: String,
        val codec: String
    )

    private data class CachedStations(val loadedAt: Long, val stations: List<Station>)

    private val cache = ConcurrentHashMap<String, CachedStations>()
    @Volatile
    private var discoveredServers: List<String>? = null

    fun page(context: Context, token: String, theme: String): WebResourceResponse {
        val appContext = AppLocale.wrap(context)
        val locale = appContext.resources.configuration.locales[0]
        val countryCode = countryCodeFor(locale)
        val countryName = Locale.Builder().setRegion(countryCode).build().getDisplayCountry(locale)
        val strings = JSONObject()
            .put("title", appContext.getString(com.fcaronte.aabrowser.R.string.radio_title))
            .put("inCountry", appContext.getString(com.fcaronte.aabrowser.R.string.radio_in_country, countryName))
            .put("search", appContext.getString(com.fcaronte.aabrowser.R.string.radio_search))
            .put("allStations", appContext.getString(com.fcaronte.aabrowser.R.string.radio_all_stations))
            .put("favorites", appContext.getString(com.fcaronte.aabrowser.R.string.radio_favorites))
            .put("loading", appContext.getString(com.fcaronte.aabrowser.R.string.radio_loading))
            .put("noResults", appContext.getString(com.fcaronte.aabrowser.R.string.radio_no_results))
            .put("error", appContext.getString(com.fcaronte.aabrowser.R.string.radio_error))
            .put("unavailable", appContext.getString(com.fcaronte.aabrowser.R.string.radio_unavailable))
            .put("lyricsButton", appContext.getString(com.fcaronte.aabrowser.R.string.lyrics_button))
            .put("lyricsLoading", appContext.getString(com.fcaronte.aabrowser.R.string.lyrics_loading))
            .put("lyricsNotFound", appContext.getString(com.fcaronte.aabrowser.R.string.lyrics_not_found))
            .put("lyricsError", appContext.getString(com.fcaronte.aabrowser.R.string.lyrics_error))
            .put("lyricsInstrumental", appContext.getString(com.fcaronte.aabrowser.R.string.lyrics_instrumental))
            .put("lyricsChooseMatch", appContext.getString(com.fcaronte.aabrowser.R.string.lyrics_choose_match))
            .put("lyricsClose", appContext.getString(com.fcaronte.aabrowser.R.string.lyrics_close))
            .put("lyricsSearchHint", appContext.getString(com.fcaronte.aabrowser.R.string.lyrics_search_hint))
            .put("lyricsSearch", appContext.getString(com.fcaronte.aabrowser.R.string.lyrics_search))
        val client = "CarWeb/${BuildConfig.VERSION_NAME} (https://github.com/fcaronte/AABrowser)"
        val config = JSONObject().put("token", token).put("country", countryCode).put("strings", strings).put("client", client)
        val html = appContext.assets.open("player/radio.html").bufferedReader().use { it.readText() }
            .replace("/*AAB_CONFIG*/", "window.AAB_RADIO = $config;")
            .replace("/*AAB_THEME*/", theme)
        return response(html.toByteArray(Charsets.UTF_8), 200, "OK", "text/html")
    }

    fun handle(context: Context, path: String, url: Uri): WebResourceResponse? = when (path) {
        "/api/radio/stations" -> stations(context, url.getQueryParameter("refresh") == "1")
        "/api/radio/play" -> play(context, url.getQueryParameter("uuid"))
        "/api/radio/metadata" -> metadata(context, url.getQueryParameter("uuid"))
        else -> null
    }

    internal fun countryCodeFor(locale: Locale): String {
        val region = locale.country.uppercase(Locale.ROOT)
        if (region.matches(Regex("[A-Z]{2}"))) return region
        return when (locale.language.lowercase(Locale.ROOT)) {
            "ar" -> "SA"
            "de" -> "DE"
            "en" -> "US"
            "es" -> "ES"
            "fr" -> "FR"
            "hi" -> "IN"
            "hu" -> "HU"
            "it" -> "IT"
            "pt" -> "PT"
            "ru" -> "RU"
            "zh" -> "CN"
            else -> "US"
        }
    }

    private fun stations(context: Context, refresh: Boolean): WebResourceResponse {
        val appContext = AppLocale.wrap(context)
        val locale = appContext.resources.configuration.locales[0]
        val countryCode = countryCodeFor(locale)
        val current = cache[countryCode]
        val entries = if (!refresh && current != null && System.currentTimeMillis() - current.loadedAt < CACHE_MS) {
            current.stations
        } else {
            try {
                loadStations(countryCode).also {
                    cache[countryCode] = CachedStations(System.currentTimeMillis(), it)
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "Station list unavailable: ${e.javaClass.simpleName}")
                return error(502, "stations")
            }
        }

        val jsonStations = JSONArray()
        entries.forEach { station ->
            jsonStations.put(
                JSONObject()
                    .put("uuid", station.uuid)
                    .put("name", station.name)
                    .put("favicon", station.favicon ?: JSONObject.NULL)
                    .put("country", station.country)
                    .put("codec", station.codec)
            )
        }
        return json(JSONObject().put("country", countryCode).put("stations", jsonStations))
    }

    private fun play(context: Context, uuid: String?): WebResourceResponse {
        if (uuid == null || !uuid.matches(Regex("[0-9a-fA-F-]{36}"))) return error(400, "station")
        val appContext = AppLocale.wrap(context)
        val countryCode = countryCodeFor(appContext.resources.configuration.locales[0])
        val station = cache[countryCode]?.stations?.firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }
            ?: return error(404, "station")

        val resolvedUrl = try {
            val click = getJson("/json/url/${station.uuid}")
            if (click.optBoolean("ok")) click.optString("url").takeIf(String::isNotBlank) ?: station.streamUrl
            else station.streamUrl
        } catch (e: Exception) {
            AppLog.w(TAG, "Station click tracking unavailable: ${e.javaClass.simpleName}")
            station.streamUrl
        }

        if (!isPublicHttpUrl(resolvedUrl)) return error(403, "station")
        return json(JSONObject().put("url", resolvedUrl))
    }

    private fun metadata(context: Context, uuid: String?): WebResourceResponse {
        if (uuid == null || !uuid.matches(Regex("[0-9a-fA-F-]{36}"))) return error(400, "station")
        val appContext = AppLocale.wrap(context)
        val countryCode = countryCodeFor(appContext.resources.configuration.locales[0])
        val station = cache[countryCode]?.stations?.firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }
            ?: return error(404, "station")

        return try {
            val result = readIcyMetadata(station.streamUrl)
            json(JSONObject()
                .put("supported", result.supported)
                .put("title", result.title ?: JSONObject.NULL))
        } catch (_: Exception) {
            json(JSONObject().put("supported", true).put("title", JSONObject.NULL))
        }
    }

    private data class IcyMetadata(val supported: Boolean, val title: String?)

    private fun readIcyMetadata(initialUrl: String): IcyMetadata {
        var target = URL(initialUrl)
        repeat(6) { redirect ->
            if (!isPublicHttpUrl(target.toString())) throw IllegalArgumentException("Invalid stream host")
            val connection = (target.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 8_000
                instanceFollowRedirects = false
                setRequestProperty("Icy-MetaData", "1")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    if (redirect == 5) throw IllegalStateException("Too many stream redirects")
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("Stream redirect without location")
                    target = URL(target, location)
                    return@repeat
                }
                if (code !in 200..299) throw IllegalStateException("Stream returned HTTP $code")

                val interval = connection.getHeaderField("icy-metaint")?.toIntOrNull()
                    ?.takeIf { it in 1..MAX_ICY_INTERVAL }
                    ?: return IcyMetadata(supported = false, title = null)
                connection.inputStream.use { input ->
                    skipFully(input, interval)
                    val blockUnits = input.read()
                    if (blockUnits <= 0) return IcyMetadata(supported = true, title = null)
                    val block = ByteArray(blockUnits * 16)
                    var offset = 0
                    while (offset < block.size) {
                        val read = input.read(block, offset, block.size - offset)
                        if (read < 0) break
                        offset += read
                    }
                    return IcyMetadata(supported = true, title = parseIcyTitle(block))
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("Unable to read stream metadata")
    }

    private fun skipFully(input: java.io.InputStream, count: Int) {
        var remaining = count
        val buffer = ByteArray(8 * 1024)
        while (remaining > 0) {
            val skipped = input.skip(remaining.toLong()).toInt()
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) throw IllegalStateException("Stream ended before ICY metadata")
                remaining -= read
            }
        }
    }

    internal fun parseIcyTitle(metadata: ByteArray): String? {
        val text = metadata.toString(Charsets.UTF_8).trimEnd('\u0000', ' ')
        val match = Regex("""(?:^|;)\s*StreamTitle\s*=\s*'((?:\\.|[^'])*)'""", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        return match.groupValues[1]
            .replace("\\'", "'")
            .replace("\\\\", "\\")
            .trim()
            .takeIf(String::isNotEmpty)
    }

    private fun loadStations(countryCode: String): List<Station> {
        val array = getArray("/json/stations/bycountrycodeexact/$countryCode?hidebroken=true&limit=$MAX_STATIONS&order=clickcount&reverse=true")
        val result = ArrayList<Station>(minOf(array.length(), MAX_STATIONS))
        val seen = HashSet<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val uuid = item.optString("stationuuid").takeIf { it.matches(Regex("[0-9a-fA-F-]{36}")) } ?: continue
            val name = item.optString("name").trim().takeIf(String::isNotEmpty) ?: continue
            val streamUrl = item.optString("url_resolved").takeIf(String::isNotBlank)
                ?: item.optString("url").takeIf(String::isNotBlank)
                ?: continue
            val favicon = item.optString("favicon").takeIf(::isHttpUrl)
            if (item.optInt("lastcheckok", 0) != 1 || !isHttpUrl(streamUrl) || !seen.add(uuid)) continue
            result += Station(
                uuid = uuid,
                name = name,
                streamUrl = streamUrl,
                favicon = favicon,
                country = item.optString("country"),
                codec = item.optString("codec")
            )
            if (result.size >= MAX_STATIONS) break
        }
        return result
    }

    private fun getArray(path: String): JSONArray = request(path) as? JSONArray
        ?: throw IllegalStateException("Unexpected Radio Browser response")

    private fun getJson(path: String): JSONObject = request(path) as? JSONObject
        ?: throw IllegalStateException("Unexpected Radio Browser response")

    private fun request(path: String): Any {
        var lastError: Exception? = null
        for (host in servers().take(3)) {
            val connection = try {
                (URL("https://$host$path").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                }
            } catch (e: Exception) {
                lastError = e
                continue
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("Radio Browser returned HTTP ${connection.responseCode}")
                }
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                return if (body.startsWith("[")) JSONArray(body) else JSONObject(body)
            } catch (e: Exception) {
                lastError = e
            } finally {
                connection.disconnect()
            }
        }
        throw lastError ?: IllegalStateException("No Radio Browser server available")
    }

    private fun servers(): List<String> {
        discoveredServers?.let { return it.shuffled() }
        val resolved = try {
            InetAddress.getAllByName(DISCOVERY_HOST).mapNotNull { address ->
                address.canonicalHostName.lowercase(Locale.ROOT)
                    .takeIf { it.matches(Regex("""[a-z0-9-]+\.api\.radio-browser\.info""")) && it != DISCOVERY_HOST }
            }.distinct()
        } catch (e: Exception) {
            AppLog.w(TAG, "Radio Browser server discovery failed: ${e.javaClass.simpleName}")
            emptyList()
        }
        return (resolved.ifEmpty { listOf("de1$API_SUFFIX") }).also { discoveredServers = it }.shuffled()
    }

    private fun isHttpUrl(value: String): Boolean {
        val uri = Uri.parse(value)
        return uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.port in -1..65535
    }

    private fun isPublicHttpUrl(value: String): Boolean {
        if (!isHttpUrl(value)) return false
        val host = Uri.parse(value).host?.lowercase(Locale.ROOT) ?: return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host.endsWith(".lan") || host.endsWith(".internal") || host.endsWith(".home") ||
            host.endsWith(".home.arpa")
        ) return false

        return try {
            val addresses = InetAddress.getAllByName(host)
            addresses.isNotEmpty() && addresses.all(::isPublicAddress)
        } catch (_: Exception) {
            false
        }
    }

    private fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val bytes = address.address
        if (address is Inet4Address) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            val third = bytes[2].toInt() and 0xff
            return when {
                first == 0 || first >= 224 -> false
                first == 100 && second in 64..127 -> false
                first == 192 && (second == 0 || second == 168) -> false
                first == 198 && second in 18..19 -> false
                first == 198 && second == 51 && third == 100 -> false
                first == 203 && second == 0 && third == 113 -> false
                else -> true
            }
        }
        if (address is Inet6Address) {
            if (bytes.take(10).all { it == 0.toByte() } &&
                bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
            ) {
                return isPublicAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
            }
            val first = bytes[0].toInt() and 0xff
            return (first and 0xfe) != 0xfc && !(first == 0xfe && (bytes[1].toInt() and 0xc0) == 0x80)
        }
        return false
    }

    private fun json(value: JSONObject): WebResourceResponse = response(
        value.toString().toByteArray(Charsets.UTF_8), 200, "OK"
    )

    private fun error(code: Int, reason: String): WebResourceResponse = response(
        JSONObject().put("error", reason).toString().toByteArray(Charsets.UTF_8), code, "Error"
    )

    private fun response(
        bytes: ByteArray,
        code: Int,
        reason: String,
        mimeType: String = "application/json",
    ): WebResourceResponse =
        WebResourceResponse(
            mimeType,
            "utf-8",
            code,
            reason,
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(bytes)
        )
}
