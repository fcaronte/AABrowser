package com.fcaronte.aabrowser.utils

import android.content.Context

object AudioBoost {
    @Volatile
    private var source: String? = null

    fun script(context: Context): String =
        source ?: context.assets.open("scripts/audio-boost.js").bufferedReader().use { it.readText() }
            .also { source = it }
}
