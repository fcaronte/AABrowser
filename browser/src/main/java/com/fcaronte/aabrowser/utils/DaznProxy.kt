package com.fcaronte.aabrowser.utils

import android.content.Context
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebSettings
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Proxy per le chiamate di autenticazione/playback di DAZN, usato dallo script
 * DaznManager.getAuthProxyScript tramite AndroidBridge.proxyFetch.
 * Condiviso da app principale (BrowserScreen) e wide (WideScreen).
 *
 * È bloccante: va chiamato dal thread di un @JavascriptInterface, non dal thread principale.
 */
object DaznProxy {

    fun proxyFetch(
        context: Context,
        urlString: String,
        method: String,
        headersJson: String,
        body: String?,
        requestUserAgent: String
    ): String {
        val requestStartedAt = SystemClock.elapsedRealtime()
        return try {
            val url = URL(urlString)
            val userAgent = requestUserAgent.ifBlank { WebSettings.getDefaultUserAgent(context) }
            var hasAuthorizationHeader = false
            var hasCookieHeader = false

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = if (method.isBlank()) "GET" else method
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
                doInput = true

                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Origin", "https://www.dazn.com")
                setRequestProperty("Referer", "https://www.dazn.com/")

                val cm = CookieManager.getInstance()
                val combinedCookie = listOf(
                    cm.getCookie("https://www.dazn.com") ?: "",
                    cm.getCookie("https://www.indazn.com") ?: "",
                    cm.getCookie(urlString) ?: ""
                )
                    .flatMap { it.split(";") }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .joinToString("; ")
                if (combinedCookie.isNotEmpty()) {
                    hasCookieHeader = true
                    setRequestProperty("Cookie", combinedCookie)
                }

                try {
                    val headersObj = JSONObject(headersJson)
                    val keys = headersObj.keys()
                    val restricted = setOf(
                        "user-agent", "content-length", "host", "connection",
                        "accept-encoding", "expect", "if-modified-since"
                    )
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val lowerK = k.lowercase()
                        if (!lowerK.startsWith("sec-") && !restricted.contains(lowerK)) {
                            if (lowerK == "authorization") {
                                hasAuthorizationHeader = headersObj.optString(k).isNotBlank()
                            }
                            if (lowerK == "cookie") {
                                val headerCookie = headersObj.getString(k)
                                hasCookieHeader = hasCookieHeader || headerCookie.isNotBlank()
                                val combined =
                                    if (combinedCookie.isEmpty()) headerCookie else "$combinedCookie; $headerCookie"
                                setRequestProperty("Cookie", combined)
                            } else {
                                setRequestProperty(k, headersObj.getString(k))
                            }
                        }
                    }
                } catch (_: Exception) {
                }

                if (requestMethod == "POST" || requestMethod == "PUT" || requestMethod == "PATCH") {
                    doOutput = true
                    if (!body.isNullOrEmpty()) {
                        outputStream.use { os -> os.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                }
            }

            val statusCode = conn.responseCode
            val statusText = conn.responseMessage ?: "OK"

            val responseHeaders = JSONObject()
            try {
                conn.headerFields?.let { headerFields ->
                    for ((key, values) in headerFields) {
                        if (key == null) continue
                        if (key.equals("Set-Cookie", ignoreCase = true)) {
                            values?.forEach { cookieValue ->
                                if (!cookieValue.isNullOrEmpty()) {
                                    CookieManager.getInstance().setCookie(urlString, cookieValue)
                                }
                            }
                        } else if (!key.equals("Set-Cookie2", ignoreCase = true)) {
                            responseHeaders.put(key, values?.joinToString(",") ?: "")
                        }
                    }
                    CookieManager.getInstance().flush()
                }
            } catch (e: Exception) {
                AppLog.e("ProxyFetch", "Error saving cookies", e)
            }

            val inputStream = if (statusCode >= 400) conn.errorStream else conn.inputStream
            val resText = inputStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (
                statusCode >= 400 &&
                url.host.equals("api.playback.indazn.com", ignoreCase = true) &&
                url.path.equals("/v5/Playback", ignoreCase = true)
            ) {
                val diagnosticHeaders = JSONObject()
                val safeHeaderNames = setOf(
                    "content-type", "server", "via", "x-cache",
                    "x-request-id", "x-correlation-id", "traceparent",
                    "x-amz-cf-id", "x-amz-cf-pop", "x-amz-cf-error",
                    "x-amz-cf-error-code"
                )
                val headerKeys = responseHeaders.keys()
                while (headerKeys.hasNext()) {
                    val key = headerKeys.next()
                    if (key.lowercase() in safeHeaderNames) {
                        diagnosticHeaders.put(key, responseHeaders.opt(key))
                    }
                }
                val requestHeaderNames = try {
                    JSONObject(headersJson).keys().asSequence()
                        .map { it.lowercase() }
                        .sorted()
                        .joinToString(",")
                } catch (_: Exception) {
                    ""
                }
                AppLog.e(
                    "DaznPlayback",
                    "Playback HTTP failure status=$statusCode " +
                            "authorizationPresent=$hasAuthorizationHeader " +
                            "cookiePresent=$hasCookieHeader " +
                            "desktopUserAgent=${userAgent.contains("Windows NT", ignoreCase = true)} " +
                            "requestHeaderNames=$requestHeaderNames " +
                            "responseHeaders=$diagnosticHeaders " +
                            "error=${summarizeDaznErrorBody(resText)}"
                )
            }
            AppLog.d(
                "ProxyFetch",
                "DAZN API request host=${url.host} path=${url.path} method=$method status=$statusCode " +
                        "responseBytes=${resText.toByteArray(Charsets.UTF_8).size} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - requestStartedAt}"
            )

            JSONObject().apply {
                put("status", statusCode)
                put("statusText", statusText)
                put("text", resText)
                put("headers", responseHeaders)
            }.toString()
        } catch (e: Exception) {
            AppLog.e(
                "AndroidBridge",
                "proxyFetch failed for host=${AppLog.safeUrlForLog(urlString)} (${e.javaClass.simpleName}) " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - requestStartedAt}"
            )
            JSONObject().apply {
                put("status", 500)
                put("statusText", e.message ?: "Error")
                put("text", "")
            }.toString()
        }
    }

    /** Riassume il corpo di un errore DAZN tenendo solo campi in whitelist e mascherando i dati sensibili. */
    private fun summarizeDaznErrorBody(body: String): String {
        val errorFields = setOf(
            "code", "error_code", "errorcode", "error_description", "message",
            "description", "reason", "type", "status", "request_id", "requestid",
            "correlation_id", "correlationid"
        )
        val containerFields = setOf("error", "errors", "details", "data", "cause", "metadata")

        fun sanitizeValue(value: String): String = value
            .replace(Regex("""(?is)<(script|style)\b[^>]*>.*?</\1>"""), " ")
            .replace(Regex("""(?s)<!--.*?-->"""), " ")
            .replace(Regex("""<[^>]*>"""), " ")
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace(
                Regex("""(?i)(bearer\s+)[A-Za-z0-9._~+/-]+=*"""),
                "$1[REDACTED]"
            )
            .replace(
                Regex("""(?i)("?(?:access[_-]?token|refresh[_-]?token|id[_-]?token|authorization|cookie|password|secret)"?\s*[:=]\s*"?)[^",}\s]+"""),
                "$1[REDACTED]"
            )
            .replace(Regex("""[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}"""), "[REDACTED_EMAIL]")
            .replace(Regex("""\s+"""), " ")
            .take(800)

        fun filter(value: Any?, depth: Int): Any? {
            if (depth > 6) return null
            return when (value) {
                is JSONObject -> JSONObject().apply {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val normalizedKey = key.lowercase()
                        val child = value.opt(key)
                        when {
                            normalizedKey in errorFields && child !is JSONObject && child !is JSONArray ->
                                put(key, sanitizeValue(child.toString()))
                            normalizedKey in containerFields ->
                                filter(child, depth + 1)?.let { put(key, it) }
                        }
                    }
                }
                is JSONArray -> JSONArray().apply {
                    for (index in 0 until minOf(value.length(), 10)) {
                        filter(value.opt(index), depth + 1)?.let { put(it) }
                    }
                }
                else -> null
            }
        }

        return try {
            val filtered = filter(JSONObject(body), 0)?.toString().orEmpty()
            filtered.ifBlank { "No allowlisted error fields" }.take(1000)
        } catch (_: JSONException) {
            sanitizeValue(body).ifBlank { "Empty error body" }
        }
    }
}