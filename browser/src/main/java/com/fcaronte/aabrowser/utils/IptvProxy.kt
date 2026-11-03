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
 * Tramite degli stream della TV di AAB Media. I server IPTV di solito sono in http e non permettono alle pagine di
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

    // id -> indirizzo, e al contrario: le liste HLS dal vivo si ricaricano ogni pochi secondi con gli stessi segmenti
    private val idsByUrl = HashMap<String, String>()
    private val ids = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
            if (size <= MAX_IDS) return false
            idsByUrl.remove(eldest.value)
            return true
        }
    }

    private val NO_STORE = mapOf("Cache-Control" to "no-store")
    private val URI_ATTRIBUTE = Regex("""URI="([^"]+)"""")

    /** Indirizzo per la pagina (percorso di player.local) al posto di [url]. */
    fun proxyPath(url: String): String = synchronized(ids) {
        val id = idsByUrl[url]?.takeIf { ids[it] != null } ?: UUID.randomUUID().toString().replace("-", "").also {
            ids[it] = url
            idsByUrl[url] = it
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

    /** Apre [address] con un user agent da player e segue i redirect, anche da http a https (HttpURLConnection no). */
    fun open(address: String, readTimeoutMs: Int = 30_000): Upstream {
        var url = URL(address)
        repeat(MAX_REDIRECTS + 1) {
            if (url.protocol != "http" && url.protocol != "https") throw IOException("Unsupported scheme")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = readTimeoutMs
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
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

    /** Come riprodurre [url] nella pagina: "hls" (hls.js), "ts" (mpegts.js) o "file" (direttamente nel video). */
    fun kindOf(url: String): String {
        val path = URL(url).path.lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> "hls"
            path.endsWith(".ts") || path.endsWith(".mpegts") -> "ts"
            listOf(".mp4", ".m4v", ".mkv", ".webm", ".mp3", ".aac", ".m4a").any { path.endsWith(it) } -> "file"
            else -> probe(url)
        }
    }

    // Senza estensione (tanti "/live/123"): si guarda l'inizio dello stream
    private fun probe(url: String): String = try {
        open(url, readTimeoutMs = 10_000).use { upstream ->
            val head = ByteArray(8)
            val read = upstream.stream.read(head)
            val type = upstream.contentType.orEmpty()
            when {
                "mpegurl" in type || (read >= 7 && String(head, 0, 7, Charsets.US_ASCII) == "#EXTM3U") -> "hls"
                "mp2t" in type || (read > 0 && head[0] == 0x47.toByte()) -> "ts"
                else -> "file"
            }
        }
    } catch (_: Exception) {
        "ts"
    }

    /** Risposta per [STREAM_PATH]<id>: lo stream, o la lista HLS con gli indirizzi sostituiti. */
    fun serve(id: String): WebResourceResponse {
        val address = synchronized(ids) { ids[id] } ?: return status(404, "Not Found")
        val upstream = try {
            open(address, readTimeoutMs = 20_000)
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
            val rewritten = rewritePlaylist(text, upstream.url)
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
    private fun rewritePlaylist(text: String, base: URL): String = text.lineSequence().joinToString("\n") { line ->
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> line
            trimmed.startsWith("#") -> URI_ATTRIBUTE.replace(line) { "URI=\"${proxied(it.groupValues[1], base)}\"" }
            else -> proxied(trimmed, base)
        }
    }

    private fun proxied(reference: String, base: URL): String = try {
        proxyPath(URL(base, reference).toString())
    } catch (_: Exception) {
        reference
    }

    private fun status(code: Int, reason: String): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", code, reason, NO_STORE, ByteArrayInputStream(ByteArray(0)))
}
