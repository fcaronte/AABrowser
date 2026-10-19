package com.fcaronte.aabrowser.utils

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import android.util.LruCache
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.fcaronte.aabrowser.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.UUID

/**
 * Lettore multimediale locale dell'app (pagina in assets/player), uguale nella Main e nella Wide.
 *
 * Le WebView passano qui le richieste a https://player.local/: la pagina, l'elenco dei file audio/video della
 * cartella scelta sul telefono (LastFileSelection), i file con le richieste Range (per spostarsi nel brano),
 * tag e copertina incorporata. I dati vogliono il token che la pagina riceve nel suo HTML: un altro sito aperto
 * in una scheda non può né elencare né riprodurre i file dell'utente.
 */
object LocalPlayer {
    const val BASE_URL = "https://player.local/"
    private const val HOST = "player.local"
    private const val TAG = "LocalPlayer"

    // Stesso limite dell'elenco cartella di LastFileSelection
    private const val MAX_TRACKS = 5000

    // Anteprima dei video: lato massimo in pixel e istante massimo del fotogramma (30 s)
    private const val VIDEO_FRAME_SIZE = 512
    private const val VIDEO_FRAME_MAX_TIME_US = 30_000_000L

    // Nuovo a ogni avvio dell'app: la pagina lo riceve nell'HTML e lo rimanda con ogni richiesta di dati
    private val token = UUID.randomUUID().toString()

    private val tagsCache = LruCache<String, JSONObject>(300)
    private val artCache = object : LruCache<String, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    private val audioExtensions = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "weba")
    private val videoExtensions = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp")
    private val playlistExtensions = setOf("m3u", "m3u8", "pls", "cue")

    /** Risposta per gli indirizzi di player.local; null per tutti gli altri (la WebView li carica normalmente). */
    fun intercept(context: Context, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url ?: return null
        if (url.host != HOST) return null
        val path = url.path ?: "/"
        return try {
            when {
                path == "/" || path == "/index.html" -> page(context)
                path == "/player.js" -> asset(context, "player/player.js", "application/javascript")
                path == "/player.css" -> asset(context, "player/player.css", "text/css")
                url.getQueryParameter("t") != token -> status(403, "Forbidden")
                path == "/api/library" -> library(context)
                path.startsWith("/media/") -> media(context, path.removePrefix("/media/"), request)
                path.startsWith("/api/tags/") -> tags(context, path.removePrefix("/api/tags/"))
                path.startsWith("/api/art/") -> artwork(context, path.removePrefix("/api/art/"))
                else -> status(404, "Not Found")
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Request failed", e)
            status(500, "Internal Error")
        }
    }

    /** Copertina di un indirizzo di player.local per il player di Android Auto (senza passare dalla rete). */
    fun artworkBitmap(context: Context, url: String): Bitmap? {
        val uri = Uri.parse(url)
        if (uri.host != HOST) return null
        val id = uri.path?.removePrefix("/api/art/")?.takeIf { it.isNotBlank() } ?: return null
        val bytes = artBytes(context, id) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    fun isPlayerUrl(url: String?): Boolean = url?.startsWith(BASE_URL) == true

    // ------------------------------------------------------------------
    // Pagina
    // ------------------------------------------------------------------

    private fun page(context: Context): WebResourceResponse {
        val strings = JSONObject()
            .put("chooseFolder", context.getString(R.string.player_choose_folder))
            .put("noFolder", context.getString(R.string.player_no_folder))
            .put("emptyFolder", context.getString(R.string.player_empty_folder))
            .put("loading", context.getString(R.string.player_loading))
            .put("unplayable", context.getString(R.string.player_unplayable))
            .put("audio", context.getString(R.string.player_tab_audio))
            .put("video", context.getString(R.string.player_tab_video))
        val config = JSONObject().put("token", token).put("strings", strings)
        val html = context.assets.open("player/index.html").bufferedReader().use { it.readText() }
            .replace("/*AAB_CONFIG*/", "window.AAB_PLAYER = $config;")
        return response(
            html.toByteArray(Charsets.UTF_8),
            "text/html",
            mapOf("Cache-Control" to "no-store", "X-Frame-Options" to "DENY")
        )
    }

    private fun asset(context: Context, name: String, mime: String): WebResourceResponse =
        response(context.assets.open(name).use { it.readBytes() }, mime, mapOf("Cache-Control" to "no-store"))

    // ------------------------------------------------------------------
    // Elenco dei brani
    // ------------------------------------------------------------------

    private fun library(context: Context): WebResourceResponse {
        val folder = LastFileSelection.rememberedFolderUri(context)
        val tracks = JSONArray()
        folder?.let { listMedia(context.contentResolver, it).forEach(tracks::put) }
        val result = JSONObject()
            .put("folder", folder?.let { LastFileSelection.rememberedFolderName(context) } ?: JSONObject.NULL)
            .put("tracks", tracks)
        return json(result)
    }

    // Una query per cartella, sottocartelle comprese; l'ordinamento naturale lo fa la pagina
    private fun listMedia(resolver: ContentResolver, treeUri: Uri): List<JSONObject> {
        val tracks = mutableListOf<JSONObject>()
        val folders = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(treeUri) to ""))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        while (folders.isNotEmpty() && tracks.size < MAX_TRACKS) {
            val (folderId, folderPath) = folders.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext() && tracks.size < MAX_TRACKS) {
                    val documentId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    val mime = cursor.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        folders.add(documentId to if (folderPath.isEmpty()) name else "$folderPath/$name")
                        continue
                    }
                    val kind = mediaKind(name, mime) ?: continue
                    tracks.add(
                        JSONObject()
                            .put("id", encodeId(documentId))
                            .put("name", name.substringBeforeLast('.'))
                            .put("folder", folderPath)
                            .put("kind", kind)
                    )
                }
            }
        }
        if (tracks.size >= MAX_TRACKS) AppLog.w(TAG, "Library truncated to $MAX_TRACKS tracks")
        return tracks
    }

    private fun mediaKind(name: String, mime: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when {
            extension in playlistExtensions -> null
            extension in audioExtensions -> "audio"
            extension in videoExtensions -> "video"
            mime.startsWith("audio/") -> "audio"
            mime.startsWith("video/") -> "video"
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // File, tag e copertine
    // ------------------------------------------------------------------

    private fun media(context: Context, id: String, request: WebResourceRequest): WebResourceResponse {
        val documentUri = documentUri(context, id) ?: return status(404, "Not Found")
        val resolver = context.contentResolver
        val mime = resolver.getType(documentUri)?.takeIf { it != "application/octet-stream" } ?: "application/octet-stream"
        val descriptor = resolver.openAssetFileDescriptor(documentUri, "r") ?: return status(404, "Not Found")
        val length = if (descriptor.length >= 0) descriptor.length else descriptor.parcelFileDescriptor.statSize
        val input = descriptor.createInputStream()

        // La WebView chiede i file a pezzi (Range): così il video si avvia subito e ci si sposta nel brano
        val rangeHeader = request.requestHeaders.entries.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value
        val range = parseRange(rangeHeader, length)
        if (range == null) {
            val headers = mutableMapOf("Accept-Ranges" to "bytes", "Cache-Control" to "no-store")
            if (length >= 0) headers["Content-Length"] = length.toString()
            return WebResourceResponse(mime, null, 200, "OK", headers, input)
        }

        try {
            skipFully(input, range.first)
        } catch (e: Exception) {
            input.close()
            throw e
        }
        val count = range.last - range.first + 1
        val headers = mapOf(
            "Accept-Ranges" to "bytes",
            "Content-Range" to "bytes ${range.first}-${range.last}/$length",
            "Content-Length" to count.toString(),
            "Cache-Control" to "no-store"
        )
        return WebResourceResponse(mime, null, 206, "Partial Content", headers, BoundedInputStream(input, count))
    }

    private fun tags(context: Context, id: String): WebResourceResponse {
        tagsCache.get(id)?.let { return json(it) }
        val documentUri = documentUri(context, id) ?: return status(404, "Not Found")
        val result = JSONObject()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, documentUri)
            fun put(key: String, metadataKey: Int) {
                retriever.extractMetadata(metadataKey)?.trim()?.takeIf { it.isNotEmpty() }?.let { result.put(key, it) }
            }
            put("title", MediaMetadataRetriever.METADATA_KEY_TITLE)
            put("artist", MediaMetadataRetriever.METADATA_KEY_ARTIST)
            if (!result.has("artist")) put("artist", MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            put("album", MediaMetadataRetriever.METADATA_KEY_ALBUM)
            // Copertina incorporata; per i video senza copertina un fotogramma (anteprima e player dell'auto)
            val art = retriever.embeddedPicture ?: videoFrame(retriever)
            art?.let {
                artCache.put(id, it)
                result.put("hasArt", true)
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Tags not readable: ${e.javaClass.simpleName}")
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
        tagsCache.put(id, result)
        return json(result)
    }

    private fun artwork(context: Context, id: String): WebResourceResponse {
        val bytes = artBytes(context, id) ?: return status(404, "Not Found")
        val isPng = bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
        return response(bytes, if (isPng) "image/png" else "image/jpeg", mapOf("Cache-Control" to "no-store"))
    }

    private fun videoFrame(retriever: MediaMetadataRetriever): ByteArray? {
        if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return null
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        // Un po' dentro il video: il primo fotogramma spesso è nero
        val timeUs = (duration * 1000 / 10).coerceAtMost(VIDEO_FRAME_MAX_TIME_US)
        val frame = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, VIDEO_FRAME_SIZE, VIDEO_FRAME_SIZE)
            ?: return null
        return try {
            ByteArrayOutputStream().use { out ->
                frame.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
        } finally {
            frame.recycle()
        }
    }

    private fun artBytes(context: Context, id: String): ByteArray? {
        artCache.get(id)?.let { return it }
        // Tag non ancora letti (es. copertina chiesta dal player dell'auto): li legge adesso
        tags(context, id)
        return artCache.get(id)
    }

    private fun documentUri(context: Context, id: String): Uri? {
        val folder = LastFileSelection.rememberedFolderUri(context) ?: return null
        val documentId = decodeId(id) ?: return null
        // Costruito dentro l'albero concesso: il provider non dà accesso a documenti fuori dalla cartella
        return DocumentsContract.buildDocumentUriUsingTree(folder, documentId)
    }

    private fun encodeId(documentId: String): String =
        Base64.encodeToString(documentId.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decodeId(id: String): String? = try {
        String(Base64.decode(id, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    // "bytes=START-END", "bytes=START-" oppure "bytes=-ULTIMI"; null se assente o non valido (si risponde col file intero)
    private fun parseRange(header: String?, length: Long): LongRange? {
        if (header == null || length <= 0 || !header.startsWith("bytes=")) return null
        val spec = header.removePrefix("bytes=").substringBefore(',').trim()
        val startText = spec.substringBefore('-').trim()
        val endText = spec.substringAfter('-', "").trim()
        val range = if (startText.isEmpty()) {
            val suffix = endText.toLongOrNull() ?: return null
            (length - suffix).coerceAtLeast(0) until length
        } else {
            val start = startText.toLongOrNull() ?: return null
            val end = endText.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
            start..end
        }
        return range.takeIf { it.first in 0 until length && it.last >= it.first }
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    private class BoundedInputStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = super.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val read = super.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (read > 0) remaining -= read
            return read
        }

        override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()
    }

    // ------------------------------------------------------------------
    // Risposte
    // ------------------------------------------------------------------

    private fun json(value: JSONObject): WebResourceResponse =
        response(value.toString().toByteArray(Charsets.UTF_8), "application/json", mapOf("Cache-Control" to "no-store"))

    private fun response(bytes: ByteArray, mime: String, headers: Map<String, String>): WebResourceResponse {
        val encoding = if (mime.startsWith("image/")) null else "UTF-8"
        return WebResourceResponse(mime, encoding, 200, "OK", headers, ByteArrayInputStream(bytes))
    }

    private fun status(code: Int, reason: String): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", code, reason, mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
}
