package com.fcaronte.aabrowser.utils

import android.webkit.WebResourceResponse
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Tramite degli stream della TV di CarWeb Media. I server IPTV di solito sono in http e non permettono alle pagine di
 * leggerli (CORS): la pagina (https://player.local/tv) chiede all'app e l'app apre lo stream. Gli indirizzi, che per
 * Xtream contengono utente e password, restano qui: la pagina riceve solo un id a caso, valido finché l'app è
 * aperta. Le liste HLS tornano con ogni indirizzo sostituito da un altro id, così anche i segmenti passano da qui.
 * Niente indirizzi nei log.
 */
object IptvProxy {
    // Da player: alcuni provider rifiutano i browser
    private const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
    private const val MAX_REDIRECTS = 5
    private const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024

    // Le liste HLS dal vivo cambiano segmenti di continuo: restano gli ultimi id dati
    private const val MAX_IDS = 4000

    const val STREAM_PATH = "/tv/stream/"

    /** Un indirizzo con gli header che la lista chiede per aprirlo (IptvHeaders): valgono anche per i suoi segmenti. */
    private data class Target(val url: String, val headers: Map<String, String>)

    // id -> indirizzo, e al contrario: le liste HLS dal vivo si ricaricano ogni pochi secondi con gli stessi segmenti
    private val idsByTarget = HashMap<Target, String>()
    private val ids = object : LinkedHashMap<String, Target>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Target>): Boolean {
            if (size <= MAX_IDS) return false
            idsByTarget.remove(eldest.value)
            return true
        }
    }

    private val NO_STORE = mapOf("Cache-Control" to "no-store")
    private val URI_ATTRIBUTE = Regex("""URI="([^"]+)"""")
    private val XTREAM_STREAM = Regex("""/[^/]+/[^/]+/\d+/?""")

    /** Indirizzo per la pagina (percorso di player.local) al posto di [url]. */
    fun proxyPath(url: String, headers: Map<String, String> = emptyMap()): String = synchronized(ids) {
        val target = Target(url, headers)
        val id = idsByTarget[target]?.takeIf { ids[it] != null } ?: UUID.randomUUID().toString().replace("-", "").also {
            ids[it] = target
            idsByTarget[target] = it
        }
        STREAM_PATH + id
    }

    class Upstream(private val connection: HttpURLConnection, val url: URL, val status: Int, val stream: InputStream) :
        Closeable {
        val contentType: String? = connection.contentType?.substringBefore(';')?.trim()?.lowercase()

        override fun close() {
            try {
                stream.close()
            } catch (_: Exception) {
            }
            connection.disconnect()
        }
    }

    /** Apre [address] con un user agent da player (o gli header dati) e segue i redirect, anche da http a https (HttpURLConnection no). */
    fun open(address: String, readTimeoutMs: Int = 30_000, headers: Map<String, String> = emptyMap()): Upstream {
        var url = URL(address)
        repeat(MAX_REDIRECTS + 1) {
            if (url.protocol != "http" && url.protocol != "https") throw IOException("Unsupported scheme")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = readTimeoutMs
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                url = URL(url, location ?: throw IOException("Redirect without location"))
                return@repeat
            }
            val stream = if (status >= 400) connection.errorStream ?: ByteArrayInputStream(ByteArray(0)) else connection.inputStream
            return Upstream(connection, url, status, stream)
        }
        throw IOException("Too many redirects")
    }

    /** Come riprodurre [url] nella pagina: "hls" (hls.js), "ts" o "flv" (mpegts.js) o "file" (direttamente nel video). */
    fun kindOf(url: String, headers: Map<String, String> = emptyMap()): String = kindOfPath(URL(url).path) ?: probe(url, headers)

    /** Dal percorso, quando basta; null se bisogna guardare l'inizio dello stream. */
    internal fun kindOfPath(rawPath: String): String? {
        val path = rawPath.lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> "hls"
            path.endsWith(".ts") || path.endsWith(".mpegts") -> "ts"
            path.endsWith(".flv") -> "flv"
            listOf(".mp4", ".m4v", ".mkv", ".webm", ".mp3", ".aac", ".m4a").any { path.endsWith(it) } -> "file"
            // "/utente/password/12345" delle liste Xtream è sempre MPEG-TS: una connessione di prova non serve, e con
            // un account da una connessione sola il server rifiuterebbe quella vera appena dopo
            XTREAM_STREAM.matches(path) -> "ts"
            else -> null
        }
    }

    // Senza estensione (tanti "/live/123"): si guarda l'inizio dello stream
    private fun probe(url: String, headers: Map<String, String>): String = try {
        open(url, readTimeoutMs = 10_000, headers = headers).use { upstream ->
            val head = ByteArray(8)
            val read = upstream.stream.read(head)
            val type = upstream.contentType.orEmpty()
            when {
                "mpegurl" in type || (read >= 7 && String(head, 0, 7, Charsets.US_ASCII) == "#EXTM3U") -> "hls"
                "flv" in type || (read >= 3 && String(head, 0, 3, Charsets.US_ASCII) == "FLV") -> "flv"
                "mp2t" in type || (read > 0 && head[0] == 0x47.toByte()) -> "ts"
                else -> "file"
            }
        }
    } catch (_: Exception) {
        "ts"
    }

    /** Risposta per [STREAM_PATH]<id>: lo stream, o la lista HLS con gli indirizzi sostituiti. */
    fun serve(id: String): WebResourceResponse {
        val target = synchronized(ids) { ids[id] } ?: return status(404, "Not Found")
        val upstream = try {
            open(target.url, readTimeoutMs = 20_000, headers = target.headers)
        } catch (e: Exception) {
            AppLog.w("IptvProxy", "Stream not reachable: ${e.javaClass.simpleName}")
            return status(502, "Bad Gateway")
        }
        if (upstream.status >= 400) {
            upstream.close()
            return status(upstream.status, "Upstream Error")
        }
        val stream = BufferedInputStream(upstream.stream, 64 * 1024)
        if (isPlaylist(upstream, stream)) {
            val text = try {
                readLimited(stream)
            } finally {
                upstream.close()
            }
            val rewritten = rewritePlaylist(text, upstream.url, target.headers)
            return WebResourceResponse(
                "application/vnd.apple.mpegurl", "utf-8", 200, "OK", NO_STORE,
                ByteArrayInputStream(rewritten.toByteArray(Charsets.UTF_8))
            )
        }
        val mime = upstream.contentType?.takeIf { it.startsWith("video/") || it.startsWith("audio/") } ?: "video/mp2t"
        // Chiuso dalla WebView (cambio canale, pagina chiusa): si chiude anche la connessione al server
        val closing = object : FilterInputStream(stream) {
            override fun close() = upstream.close()
        }
        return WebResourceResponse(mime, null, 200, "OK", NO_STORE, closing)
    }

    private fun isPlaylist(upstream: Upstream, stream: BufferedInputStream): Boolean {
        if (upstream.contentType?.contains("mpegurl") == true) return true
        val path = upstream.url.path.lowercase()
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return true
        // Tipo sbagliato o mancante: si guarda l'inizio
        stream.mark(16)
        val head = ByteArray(7)
        var read = 0
        while (read < head.size) {
            val count = stream.read(head, read, head.size - read)
            if (count < 0) break
            read += count
        }
        stream.reset()
        return read == head.size && String(head, Charsets.US_ASCII) == "#EXTM3U"
    }

    // readNBytes c'è solo da Android 13
    private fun readLimited(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < MAX_PLAYLIST_BYTES) {
            val count = stream.read(buffer, 0, minOf(buffer.size, MAX_PLAYLIST_BYTES - out.size()))
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        return out.toString("UTF-8")
    }

    // Ogni indirizzo della lista (segmenti, sottoliste, chiavi, mappe) passa dal tramite
    private fun rewritePlaylist(text: String, base: URL, headers: Map<String, String>): String = text.lineSequence().joinToString("\n") { line ->
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> line
            trimmed.startsWith("#") -> URI_ATTRIBUTE.replace(line) { "URI=\"${proxied(it.groupValues[1], base, headers)}\"" }
            else -> proxied(trimmed, base, headers)
        }
    }

    private fun proxied(reference: String, base: URL, headers: Map<String, String>): String = try {
        proxyPath(URL(base, reference).toString(), headers)
    } catch (_: Exception) {
        reference
    }

    private fun status(code: Int, reason: String): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", code, reason, NO_STORE, ByteArrayInputStream(ByteArray(0)))
}
