package com.fcaronte.aabrowser.utils

import android.content.Context
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebSettings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Proxy per le chiamate di autenticazione/playback di DAZN (usato dallo script
 * DaznManager.getAuthProxyScript tramite AndroidBridge.proxyFetch).
 * Va chiamato da un thread di @JavascriptInterface: è bloccante (non sul thread principale).
 * Condivisibile tra app principale e wide.
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
        val startedAt = SystemClock.elapsedRealtime()
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
                AppLog.e("DaznProxy", "Error saving cookies", e)
            }

            val inputStream = if (statusCode >= 400) conn.errorStream else conn.inputStream
            val resText = inputStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (statusCode >= 400 && url.host.equals("api.playback.indazn.com", ignoreCase = true)) {
                AppLog.e(
                    "DaznProxy",
                    "Playback HTTP failure status=$statusCode " +
                            "authorizationPresent=$hasAuthorizationHeader " +
                            "cookiePresent=$hasCookieHeader " +
                            "desktopUserAgent=${userAgent.contains("Windows NT", ignoreCase = true)}"
                )
            }
            AppLog.d(
                "DaznProxy",
                "DAZN API request host=${url.host} path=${url.path} method=$method status=$statusCode " +
                        "responseBytes=${resText.toByteArray(Charsets.UTF_8).size} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )

            JSONObject().apply {
                put("status", statusCode)
                put("statusText", statusText)
                put("text", resText)
                put("headers", responseHeaders)
            }.toString()
        } catch (e: Exception) {
            AppLog.e(
                "DaznProxy",
                "proxyFetch failed (${e.javaClass.simpleName}) elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
            JSONObject().apply {
                put("status", 500)
                put("statusText", e.message ?: "Error")
                put("text", "")
            }.toString()
        }
    }
}