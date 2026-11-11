package com.fcaronte.aabrowser.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.edit

object DesktopModeSites {
    private const val KEY_OVERRIDES = "desktop_mode_site_overrides"

    private val overrides = LinkedHashSet<String>()
    private var prefs: SharedPreferences? = null
    private val _version = mutableIntStateOf(0)
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

    fun setOverride(
        context: Context,
        host: String,
        enabled: Boolean,
        globalDefault: Boolean = com.fcaronte.aabrowser.settings.AppSettings.desktopMode.value
    ) {
        val key = siteKey(host) ?: return
        val store = prefs ?: context.applicationContext
            .getSharedPreferences("aa_browser_settings", Context.MODE_PRIVATE)
            .also { prefs = it }
        synchronized(overrides) {
            overrides.removeIf { entryHost(it) == key }
            if (enabled != globalDefault) {
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

    private fun entryHost(entry: String): String? {
        val separator = entry.indexOf('|')
        return if (separator == 1) siteKey(entry.substring(separator + 1)) else null
    }
}
