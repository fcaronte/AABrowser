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
import java.util.Locale
import java.util.UUID

/**
 * Liste della TV di CarWeb Media: playlist IPTV-org automatica, M3U via URL o file e account Xtream.
 * Le sorgenti aggiunte dall'utente sono salvate nell'app e fuori dal backup. La pagina ne vede solo id e nome:
 * indirizzi, credenziali e URI dei file restano qui (IptvLibrary, IptvProxy).
 */
object IptvSources {
    private const val TAG = "IptvSources"

    enum class Type { DEFAULT_M3U, M3U, LOCAL_M3U, XTREAM }

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
    private const val DEFAULT_ID_PREFIX = "iptv-org-language-"
    private const val IPTV_ORG_BASE_URL = "https://iptv-org.github.io/iptv/"

    /** Cambia a ogni aggiunta o rimozione (elenco nelle Impostazioni). */
    val version = mutableIntStateOf(0)

    fun list(context: Context): List<Source> = listOf(defaultLanguageSource(context)) + savedSources(context)

    private fun savedSources(context: Context): List<Source> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: "[]"
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

    internal fun languagePlaylistCode(locale: Locale): String? = when (locale.language.lowercase(Locale.ROOT)) {
        "ar" -> "ara"
        "de" -> "deu"
        "en" -> "eng"
        "es" -> "spa"
        "fr" -> "fra"
        "hi" -> "hin"
        "hu" -> "hun"
        "it" -> "ita"
        "pt" -> "por"
        "ru" -> "rus"
        "zh" -> "zho"
        else -> null
    }

    internal fun playlistUrl(locale: Locale): String {
        val code = languagePlaylistCode(locale) ?: return "${IPTV_ORG_BASE_URL}index.m3u"
        return "${IPTV_ORG_BASE_URL}languages/$code.m3u"
    }

    private fun defaultLanguageSource(context: Context): Source {
        val localizedContext = AppLocale.wrap(context)
        val locale = localizedContext.resources.configuration.locales[0]
        val code = languagePlaylistCode(locale) ?: "all"
        val languageName = locale.getDisplayLanguage(locale).ifBlank { locale.language }
        return Source(
            id = "$DEFAULT_ID_PREFIX$code",
            type = Type.DEFAULT_M3U,
            name = localizedContext.getString(com.fcaronte.aabrowser.R.string.iptv_default_language_list, languageName),
            url = playlistUrl(locale),
        )
    }

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
        save(context, savedSources(context) + source)
    }

    /** Aggiunge un file M3U/M3U8 selezionato dall'utente, conservandone l'accesso dopo il riavvio. */
    fun addFile(context: Context, uri: Uri): Source {
        val sources = savedSources(context)
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
        val sources = savedSources(context)
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
