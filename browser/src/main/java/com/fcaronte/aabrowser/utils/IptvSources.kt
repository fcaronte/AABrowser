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
 * Liste di CarWeb Media. TV: playlist IPTV-org automatica, M3U via URL o file e account Xtream. Radio: stazioni del
 * Paese della lingua dell'app, altri Paesi di Radio Browser e M3U via URL o file. Quelle aggiunte dall'utente sono
 * salvate nell'app e fuori dal backup. La pagina ne vede solo id e nome: indirizzi, credenziali e URI dei file restano
 * qui (IptvLibrary, IptvProxy, RadioApi).
 */
object IptvSources {
    private const val TAG = "IptvSources"

    enum class Type { DEFAULT_M3U, M3U, LOCAL_M3U, XTREAM, DEFAULT_COUNTRY, COUNTRY }

    /** Le pagine che hanno delle liste, salvate ognuna per conto suo. */
    enum class Section(internal val key: String) { TV("sources"), RADIO("radio_sources") }

    /** [url]: indirizzo M3U/server Xtream, URI del documento per [Type.LOCAL_M3U], codice del Paese per le radio. */
    data class Source(
        val id: String,
        val type: Type,
        val name: String,
        val url: String,
        val username: String = "",
        val password: String = "",
    ) {
        val isPlaylist: Boolean get() = type != Type.DEFAULT_COUNTRY && type != Type.COUNTRY
    }

    private const val PREFS = "iptv_sources"
    private const val DEFAULT_ID_PREFIX = "iptv-org-language-"
    private const val DEFAULT_RADIO_ID_PREFIX = "radio-browser-"
    private const val IPTV_ORG_BASE_URL = "https://iptv-org.github.io/iptv/"

    /** Cambia a ogni aggiunta o rimozione (elenco nelle Impostazioni, menu delle pagine aperte). */
    val version = mutableIntStateOf(0)

    /** La lista automatica, sempre per prima, e quelle aggiunte. */
    fun list(context: Context, section: Section = Section.TV): List<Source> = when (section) {
        Section.TV -> listOf(defaultLanguageSource(context)) + savedSources(context, section)
        Section.RADIO -> listOf(defaultCountrySource(context)) + savedSources(context, section).map { source ->
            if (source.type == Type.COUNTRY) source.copy(name = countryListName(context, source.url)) else source
        }
    }

    private fun savedSources(context: Context, section: Section): List<Source> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(section.key, null) ?: "[]"
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

    fun find(context: Context, id: String?, section: Section = Section.TV): Source? =
        id?.let { wanted -> list(context, section).firstOrNull { it.id == wanted } }

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

    private fun defaultCountrySource(context: Context): Source {
        val code = RadioApi.countryCodeFor(AppLocale.wrap(context).resources.configuration.locales[0])
        return Source(
            id = "$DEFAULT_RADIO_ID_PREFIX$code",
            type = Type.DEFAULT_COUNTRY,
            name = countryListName(context, code),
            url = code,
        )
    }

    // Il nome si calcola ogni volta: se cambia la lingua dell'app cambia anche lui
    private fun countryListName(context: Context, code: String): String {
        val localizedContext = AppLocale.wrap(context)
        val locale = localizedContext.resources.configuration.locales[0]
        val country = Locale.Builder().setRegion(code).build().getDisplayCountry(locale)
        return localizedContext.getString(com.fcaronte.aabrowser.R.string.radio_in_country, country)
    }

    /** Nuova lista; senza schema l'indirizzo diventa http:// (i server IPTV di solito non sono in https). */
    fun add(
        context: Context,
        section: Section,
        type: Type,
        name: String,
        url: String,
        username: String = "",
        password: String = "",
    ) {
        val address = url.trim().let { if (it.contains("://")) it else "http://$it" }.trimEnd('/')
        val source = Source(
            id = UUID.randomUUID().toString(),
            type = type,
            name = name.trim().ifEmpty { Uri.parse(address).host ?: address },
            url = address,
            username = username.trim(),
            password = password,
        )
        save(context, section, savedSources(context, section) + source)
    }

    /** Stazioni di un altro Paese di Radio Browser; un Paese che c'è già non si aggiunge una seconda volta. */
    fun addCountry(context: Context, code: String) {
        if (list(context, Section.RADIO).any { !it.isPlaylist && it.url == code }) return
        val source = Source(id = UUID.randomUUID().toString(), type = Type.COUNTRY, name = "", url = code)
        save(context, Section.RADIO, savedSources(context, Section.RADIO) + source)
    }

    /** Aggiunge un file M3U/M3U8 selezionato dall'utente, conservandone l'accesso dopo il riavvio. */
    fun addFile(context: Context, section: Section, uri: Uri): Source {
        val sources = savedSources(context, section)
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
            save(context, section, sources + source)
            return source
        } catch (e: Exception) {
            releaseFileAccess(context, uri)
            throw e
        }
    }

    fun remove(context: Context, section: Section, id: String) {
        val sources = savedSources(context, section)
        val removed = sources.firstOrNull { it.id == id }
        save(context, section, sources.filterNot { it.id == id })
        IptvLibrary.forget(id)
        // Lo stesso file può essere anche nell'altra pagina
        if (removed?.type == Type.LOCAL_M3U &&
            Section.entries.flatMap { savedSources(context, it) }.none { it.type == Type.LOCAL_M3U && it.url == removed.url }
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

    private fun save(context: Context, section: Section, sources: List<Source>) {
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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(section.key, array.toString()) }
        version.intValue++
    }
}
