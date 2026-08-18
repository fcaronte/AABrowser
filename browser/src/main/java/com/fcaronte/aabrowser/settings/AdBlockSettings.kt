package com.fcaronte.aabrowser.settings

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

object AdBlockSettings {
    private const val PREFS_NAME = "adblock_prefs"
    private const val KEY_ENABLED = "adblock_enabled"

    private val _isEnabled = mutableStateOf(true)
    val isEnabled: State<Boolean> = _isEnabled

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isEnabled.value = prefs.getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context?, enabled: Boolean) {
        _isEnabled.value = enabled
        context?.let {
            val prefs = it.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
    }
}
