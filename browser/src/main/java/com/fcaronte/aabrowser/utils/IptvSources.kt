package com.fcaronte.aabrowser.utils

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Liste della TV di AAB Media: liste M3U (un indirizzo) e account Xtream (server, utente, password), aggiunte dalle
 * Impostazioni sul telefono. Salvate nell'app e fuori dal backup (credenziali). La pagina ne vede solo id e nome:
 * indirizzi e password restano qui (IptvLibrary, IptvProxy).
 */
object IptvSources {
    enum class Type { M3U, XTREAM }

    /** [url]: indirizzo della lista M3U, o server Xtream (http://host:porta). */
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

    fun remove(context: Context, id: String) {
        save(context, list(context).filterNot { it.id == id })
        IptvLibrary.forget(id)
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
