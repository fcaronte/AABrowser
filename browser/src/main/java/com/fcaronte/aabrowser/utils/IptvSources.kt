package com.fcaronte.aabrowser.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * Liste della TV di CarWeb Media: M3U via URL o file e account Xtream, aggiunti dalle Impostazioni sul telefono.
 * Salvati nell'app e fuori dal backup. La pagina ne vede solo id e nome: indirizzi, credenziali e URI dei file
 * restano qui (IptvLibrary, IptvProxy).
 */
object IptvSources {
    private const val TAG = "IptvSources"

    enum class Type { M3U, LOCAL_M3U, XTREAM }

    /** [url]: indirizzo M3U/server Xtream, oppure URI del documento per [Type.LOCAL_M3U]. */
    data class Source(
        val id: String,
        val type: Type,
        val name: String,
        val url: String,
        val username: String = "",
        val password: String = "",
    )

    private const val PREFS = "iptv_sources"
    private const val KEY = "sources"

    /** Cambia a ogni aggiunta o rimozione (elenco nelle Impostazioni). */
    val version = mutableIntStateOf(0)

    fun list(context: Context): List<Source> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.getJSONObject(i)
                val type = runCatching { Type.valueOf(item.getString("type")) }.getOrNull() ?: return@mapNotNull null
                Source(
                    id = item.getString("id"),
                    type = type,
                    name = item.optString("name"),
                    url = item.optString("url"),
                    username = item.optString("username"),
                    password = item.optString("password"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun find(context: Context, id: String?): Source? = id?.let { wanted -> list(context).firstOrNull { it.id == wanted } }

    /** Nuova lista; senza schema l'indirizzo diventa http:// (i server IPTV di solito non sono in https). */
    fun add(context: Context, type: Type, name: String, url: String, username: String = "", password: String = "") {
        val address = url.trim().let { if (it.contains("://")) it else "http://$it" }.trimEnd('/')
        val source = Source(
            id = UUID.randomUUID().toString(),
            type = type,
            name = name.trim().ifEmpty { Uri.parse(address).host ?: address },
            url = address,
            username = username.trim(),
            password = password,
        )
        save(context, list(context) + source)
    }

    /** Aggiunge un file M3U/M3U8 selezionato dall'utente, conservandone l'accesso dopo il riavvio. */
    fun addFile(context: Context, uri: Uri): Source {
        val sources = list(context)
        sources.firstOrNull { it.type == Type.LOCAL_M3U && it.url == uri.toString() }?.let { return it }

        val fileName = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
        if (!fileName.endsWith(".m3u", ignoreCase = true) && !fileName.endsWith(".m3u8", ignoreCase = true)) {
            throw IllegalArgumentException("Unsupported playlist file")
        }

        val resolver = context.contentResolver
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            resolver.openInputStream(uri)?.use { } ?: throw IOException("Unable to open playlist")
            val name = fileName.replace(Regex("""(?i)\.m3u8?$"""), "").ifBlank { "M3U" }
            val source = Source(
                id = UUID.randomUUID().toString(),
                type = Type.LOCAL_M3U,
                name = name,
                url = uri.toString(),
            )
            save(context, sources + source)
            return source
        } catch (e: Exception) {
            releaseFileAccess(context, uri)
            throw e
        }
    }

    fun remove(context: Context, id: String) {
        val sources = list(context)
        val removed = sources.firstOrNull { it.id == id }
        val remaining = sources.filterNot { it.id == id }
        save(context, remaining)
        IptvLibrary.forget(id)
        if (removed?.type == Type.LOCAL_M3U &&
            remaining.none { it.type == Type.LOCAL_M3U && it.url == removed.url }
        ) {
            releaseFileAccess(context, Uri.parse(removed.url))
        }
    }

    private fun releaseFileAccess(context: Context, uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            AppLog.w(TAG, "Unable to release playlist access", e)
        }
    }

    private fun save(context: Context, sources: List<Source>) {
        val array = JSONArray()
        sources.forEach { source ->
            array.put(
                JSONObject()
                    .put("id", source.id)
                    .put("type", source.type.name)
                    .put("name", source.name)
                    .put("url", source.url)
                    .put("username", source.username)
                    .put("password", source.password)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, array.toString()) }
        version.intValue++
    }
}
