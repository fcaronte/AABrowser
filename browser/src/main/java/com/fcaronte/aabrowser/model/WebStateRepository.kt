package com.fcaronte.aabrowser.model

import android.content.Context
import android.content.SharedPreferences

object WebStateRepository {
    private const val PREFS_NAME = "WEB_STATE_PREFS"
    private const val KEY_CURRENT_URL = "current_url"
    private const val KEY_CURRENT_TITLE = "current_title"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    var currentUrl: String
        get() = prefs?.getString(KEY_CURRENT_URL, "") ?: ""
        set(value) {
            prefs?.edit()?.putString(KEY_CURRENT_URL, value)?.apply()
        }

    var currentTitle: String
        get() = prefs?.getString(KEY_CURRENT_TITLE, "") ?: ""
        set(value) {
            prefs?.edit()?.putString(KEY_CURRENT_TITLE, value)?.apply()
        }
}
