package com.fcaronte.aabrowser.utils

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * Quello che le pagine di TV, Radio e Podcast tengono per sé: preferiti, podcast seguiti, punto d'ascolto, ultima
 * lista. La pagina lo salva nel suo localStorage, che sta tra i dati della WebView, fuori da ogni backup: qui ne resta
 * una copia (assets/player/store.js), che va nel backup di Google e nel file delle Impostazioni e che la pagina
 * riprende all'apertura (config.saved). Niente indirizzi né password: quelli restano nelle liste (IptvSources).
 */
internal object MediaPages {
    const val PREFS = "media_pages"
    const val TV = "tv"
    const val RADIO = "radio"
    const val PODCAST = "podcast"
    private val KEYS = setOf(TV, RADIO, PODCAST)
    private const val MAX_CHARS = 512 * 1024

    /** Lo stato salvato di una pagina, o null. */
    fun saved(context: Context, key: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)

    /** /api/pages/save?key=…&v=…: la pagina manda il suo stato. false = rifiutato. */
    fun save(context: Context, url: Uri): Boolean {
        val key = url.getQueryParameter("key")?.takeIf { it in KEYS } ?: return false
        val value = url.getQueryParameter("v")?.takeIf { it.length <= MAX_CHARS } ?: return false
        try {
            JSONObject(value)
        } catch (_: Exception) {
            return false
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, value).apply()
        return true
    }
}
