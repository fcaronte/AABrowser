package com.fcaronte.aabrowser.utils

import android.net.Uri
import android.util.Log
import com.fcaronte.aabrowser.BuildConfig

object AppLog {
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, sanitize(message))
    }

    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.i(tag, sanitize(message))
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) Log.w(tag, sanitize(message), throwable)
            else Log.w(tag, sanitize(message))
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) Log.e(tag, sanitize(message), throwable)
            else Log.e(tag, sanitize(message))
        }
    }

    fun safeUrlForLog(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return "unknown"

        return try {
            val host = Uri.parse(rawUrl).host ?: Uri.decode(rawUrl)
                .substringBefore('?')
                .substringAfter("//")
                .substringBefore('/')
                .ifBlank { "unknown" }
            redactSensitive(host)
        } catch (_: Exception) {
            redactSensitive(rawUrl)
                .substringBefore('?')
                .substringAfter("//")
                .substringBefore('/')
                .ifBlank { "unknown" }
        }
    }

    private fun sanitize(message: String): String {
        var sanitized = message
        sanitized = sanitized.replace(
            Regex("""(?i)(authorization|cookie|set-cookie|token|jwt|session|password|secret)=([^&\s]+)"""),
            "$1=[REDACTED]"
        )
        sanitized = sanitized.replace(
            Regex("""(?i)(https?://)([^\s/?#]+)([^\s]*)"""),
            "$1$2[URL]"
        )
        sanitized = sanitized.replace(
            Regex("""(?i)(track|artist|title|cover|url|uri|href|location|referer)=(\S+)"""),
            "$1=[REDACTED]"
        )
        return sanitized
    }

    private fun redactSensitive(value: String): String = value
        .replace(
            Regex("""(?i)(authorization|cookie|set-cookie|token|jwt|session|password|secret)=([^&\s]+)"""),
            "$1=[REDACTED]"
        )
        .replace(
            Regex("""(?i)(https?://)([^\s/?#]+)([^\s]*)"""),
            "$1$2[URL]"
        )
}
