package com.fcaronte.aabrowser.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.edit

/**
 * Desktop o mobile per sito: l'icona nella barra delle schede, nel menu radiale e nella Wide scrive qui la scelta
 * (salvata come "1|sito" o "0|sito", vale anche per i sottodomini).
 *
 * L'ordine è sempre lo stesso: scelta per il sito, poi quella del preferito, poi quella generale delle Impostazioni.
 * [wantsDesktop] è l'unico punto che lo applica: Main, Wide e icone lo chiamano tutti, così la pagina riceve lo user
 * agent che l'icona mostra. Prima la scelta per sito cambiava solo l'icona: la WebView sceglieva lo user agent senza
 * leggerla e al tocco la pagina restava com'era.
 */
object DesktopModeSites {
    private const val KEY_OVERRIDES = "desktop_mode_site_overrides"

    private val overrides = LinkedHashSet<String>()
    private var prefs: SharedPreferences? = null
    private val _version = mutableIntStateOf(0)

    /** Cambia a ogni scelta salvata o tolta: chi la legge in Compose si ricompone, i servizi la osservano con snapshotFlow. */
    val version: State<Int> = _version

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("aa_browser_settings", Context.MODE_PRIVATE).also {
            synchronized(overrides) {
                overrides.clear()
                overrides.addAll(it.getStringSet(KEY_OVERRIDES, emptySet()).orEmpty())
            }
        }
    }

    fun overrideForUrl(url: String?): Boolean? {
        val host = url?.let { Uri.parse(it).host } ?: return null
        return synchronized(overrides) { resolveOverride(host, overrides) }
    }

    fun isEnabledForUrl(url: String?, favoriteOverride: Boolean?, global: Boolean): Boolean =
        overrideForUrl(url) ?: favoriteOverride ?: global

    /**
     * Cosa chiede davvero la pagina: la scelta di [isEnabledForUrl], più i siti che funzionano solo in desktop
     * (WhatsApp Web, gli accessi) e DAZN dopo il clic su un evento, che l'utente non può cambiare.
     */
    fun wantsDesktop(url: String?, favoriteOverride: Boolean?, global: Boolean, daznForced: Boolean = false): Boolean =
        isEnabledForUrl(url, favoriteOverride, global) ||
                WebViewScriptRouter.isDesktopRequired(url) ||
                (daznForced && url?.contains("dazn.com", ignoreCase = true) == true)

    fun removeOverride(context: Context, host: String) {
        val key = siteKey(host) ?: return
        val store = prefs ?: context.applicationContext
            .getSharedPreferences("aa_browser_settings", Context.MODE_PRIVATE)
            .also { prefs = it }
        synchronized(overrides) {
            overrides.removeIf { entryHost(it) == key }
            store.edit { putStringSet(KEY_OVERRIDES, HashSet(overrides)) }
        }
        _version.intValue++
    }

    /**
     * Salva la scelta per il sito. [baseline] è quello che il sito avrebbe senza scelta sua (preferito, altrimenti
     * impostazione generale): se la scelta coincide non si salva niente, altrimenti un preferito "desktop" non si
     * poteva più riportare a mobile (la scelta mobile sembrava uguale al generale e veniva scartata).
     */
    fun setOverride(context: Context, host: String, enabled: Boolean, baseline: Boolean) {
        val key = siteKey(host) ?: return
        val store = prefs ?: context.applicationContext
            .getSharedPreferences("aa_browser_settings", Context.MODE_PRIVATE)
            .also { prefs = it }
        synchronized(overrides) {
            overrides.removeIf { entryHost(it) == key }
            if (enabled != baseline) {
                overrides.add("${if (enabled) 1 else 0}|$key")
            }
            store.edit { putStringSet(KEY_OVERRIDES, HashSet(overrides)) }
        }
        _version.intValue++
    }

    fun siteKey(host: String?): String? =
        host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() }

    internal fun resolveOverride(host: String?, entries: Collection<String>): Boolean? {
        val key = siteKey(host) ?: return null
        return entries.mapNotNull { entry ->
            val separator = entry.indexOf('|')
            if (separator != 1) return@mapNotNull null
            val enabled = when (entry.first()) {
                '1' -> true
                '0' -> false
                else -> return@mapNotNull null
            }
            val savedHost = siteKey(entry.substring(separator + 1)) ?: return@mapNotNull null
            if (key == savedHost || key.endsWith(".$savedHost")) savedHost to enabled else null
        }.maxByOrNull { it.first.length }?.second
    }

    /** La decisione di [wantsDesktop] senza Android: sito, scelte salvate, preferito, impostazione generale, obblighi. */
    internal fun decide(
        host: String?,
        entries: Collection<String>,
        favoriteOverride: Boolean?,
        global: Boolean,
        required: Boolean = false,
        daznForced: Boolean = false
    ): Boolean = (resolveOverride(host, entries) ?: favoriteOverride ?: global) || required || daznForced

    private fun entryHost(entry: String): String? {
        val separator = entry.indexOf('|')
        return if (separator == 1) siteKey(entry.substring(separator + 1)) else null
    }
}
