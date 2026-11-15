package com.fcaronte.aabrowser.utils

import android.content.Context
import java.io.File
import java.text.DecimalFormat

/** Quanto pesa la cache dell'app e della WebView, scritto come "12,3 MB" (Impostazioni > Cancella dati). */
object CacheSize {
    // Le cartelle di cache pura della WebView: gli stessi percorsi che AppSettings.clearCache svuota
    private val WEBVIEW_CACHE_DIRS = listOf(
        "Cache", "Code Cache", "GPUCache", "ShaderCache", "GrShaderCache",
        "Default/Cache", "Default/Code Cache", "Default/GPUCache", "Default/Service Worker/CacheStorage",
        "blob_storage"
    )

    fun calculate(context: Context): String {
        return try {
            var size = 0L

            // 1. Cache standard di Android (interna ed esterna) e codice compilato
            context.cacheDir?.let { size += dirSize(it) }
            context.externalCacheDir?.let { size += dirSize(it) }
            context.codeCacheDir?.let { size += dirSize(it) }

            // 2. Cache di Chromium / WebView
            val webViewDir = File(context.applicationInfo.dataDir, "app_webview")
            if (webViewDir.exists()) {
                for (path in WEBVIEW_CACHE_DIRS) {
                    val dir = File(webViewDir, path)
                    if (dir.exists()) size += dirSize(dir)
                }
            }
            format(size)
        } catch (_: Exception) {
            "..."
        }
    }

    internal fun format(size: Long): String {
        val df = DecimalFormat("#.##")
        return when {
            size <= 0 -> "0 B"
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${df.format(size / 1024.0)} KB"
            size < 1024L * 1024 * 1024 -> "${df.format(size / (1024.0 * 1024.0))} MB"
            else -> "${df.format(size / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }

    private fun dirSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach { file ->
            if (file.isFile) size += file.length() else if (file.isDirectory) size += dirSize(file)
        }
        return size
    }
}
