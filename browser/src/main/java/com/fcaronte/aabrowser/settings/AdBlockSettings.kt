package com.fcaronte.aabrowser.settings

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit

object AdBlockSettings {
    private const val PREFS_NAME = "adblock_prefs"
    private const val KEY_ENABLED = "adblock_enabled"
    private const val KEY_YOUTUBE_ENABLED = "adblock_youtube_enabled"

    private val _isEnabled = mutableStateOf(true)
    val isEnabled: State<Boolean> = _isEnabled

    private val _isYouTubeEnabled = mutableStateOf(false)
    val isYouTubeEnabled: State<Boolean> = _isYouTubeEnabled

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isEnabled.value = prefs.getBoolean(KEY_ENABLED, true)
        _isYouTubeEnabled.value = prefs.getBoolean(KEY_YOUTUBE_ENABLED, false)
    }

    fun setEnabled(context: Context?, enabled: Boolean) {
        _isEnabled.value = enabled
        context?.let {
            val prefs = it.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit { putBoolean(KEY_ENABLED, enabled) }
        }
    }

    fun setYouTubeEnabled(context: Context?, enabled: Boolean) {
        android.util.Log.d("AdBlockSettings", "Setting YouTube AdBlock to: $enabled")
        _isYouTubeEnabled.value = enabled
        context?.let {
            val prefs = it.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit { putBoolean(KEY_YOUTUBE_ENABLED, enabled) }
        }
    }
}
