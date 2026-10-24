package com.fcaronte.aabrowser.settings

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import com.fcaronte.aabrowser.utils.AdBlockHost
import com.fcaronte.aabrowser.utils.AppLog

object AdBlockSettings {
    private const val PREFS_NAME = "adblock_prefs"
    private const val KEY_ENABLED = "adblock_enabled"
    private const val KEY_YOUTUBE_ENABLED = "adblock_youtube_enabled"
    private const val KEY_YOUTUBE_PRUNE = "adblock_youtube_prune"

    private val _isEnabled = mutableStateOf(true)
    val isEnabled: State<Boolean> = _isEnabled

    private val _isYouTubeEnabled = mutableStateOf(false)
    val isYouTubeEnabled: State<Boolean> = _isYouTubeEnabled

    // Rimozione anticipata (dati del player, AdBlockJavascript.getYouTubeAdPruneScript). Spenta di serie: con le
    // pubblicità che YouTube inserisce nel video lascia lo schermo nero per la loro durata (confermato dall'utente),
    // mentre 16x, muto e Salta le chiudono in pochi secondi. Accesa solo da chi la sceglie
    private val _isYouTubePruneEnabled = mutableStateOf(false)
    val isYouTubePruneEnabled: State<Boolean> = _isYouTubePruneEnabled

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isEnabled.value = prefs.getBoolean(KEY_ENABLED, true)
        _isYouTubeEnabled.value = prefs.getBoolean(KEY_YOUTUBE_ENABLED, false)
        _isYouTubePruneEnabled.value = prefs.getBoolean(KEY_YOUTUBE_PRUNE, false)
    }

    fun setEnabled(context: Context?, enabled: Boolean) {
        _isEnabled.value = enabled
        context?.let {
            val prefs = it.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit { putBoolean(KEY_ENABLED, enabled) }
        }
    }

    fun setYouTubePruneEnabled(context: Context?, enabled: Boolean) {
        _isYouTubePruneEnabled.value = enabled
        context?.let {
            val prefs = it.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit { putBoolean(KEY_YOUTUBE_PRUNE, enabled) }
        }
    }

    /** Rimozione anticipata attiva sul sito [host]: AdBlock di YouTube e rimozione accesi, sito fuori dalla whitelist. */
    fun isYouTubePruneOn(host: String?): Boolean =
        _isYouTubeEnabled.value && _isYouTubePruneEnabled.value && !AdBlockHost.isWhitelisted(host)

    fun setYouTubeEnabled(context: Context?, enabled: Boolean) {
        AppLog.d("AdBlockSettings", "Setting YouTube AdBlock to: $enabled")
        _isYouTubeEnabled.value = enabled
        context?.let {
            val prefs = it.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit { putBoolean(KEY_YOUTUBE_ENABLED, enabled) }
        }
    }
}
