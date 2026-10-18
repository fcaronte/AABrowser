package com.fcaronte.aabrowser.utils

import android.net.Uri

object UrlUtils {

    // dominio.tld, con eventuale porta, path, query, fragment
    private val domainRegex = Regex(
        """^(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}(?::\d{1,5})?(?:[/?#].*)?$"""
    )
    private val ipRegex = Regex(
        """^\d{1,3}(?:\.\d{1,3}){3}(?::\d{1,5})?(?:[/?#].*)?$"""
    )

    private fun looksLikeUrl(input: String): Boolean {
        if (input.isBlank() || input.any { it.isWhitespace() }) return false
        return domainRegex.matches(input) || ipRegex.matches(input)
    }

    private fun looksLikeLocalhost(input: String): Boolean =
        input.startsWith("localhost", ignoreCase = true) ||
                input.startsWith("127.0.0.1") ||
                input.startsWith("10.") ||
                input.startsWith("192.168.") ||
                input.startsWith("[::1]")

    /**
     * Converte l'input dell'utente in un URL caricabile.
     * - URL con schema       → invariato
     * - sembra un dominio/IP → aggiunge https://
     * - altrimenti           → ricerca sul motore indicato
     */
    fun normalizeInput(rawInput: String, searchBaseUrl: String): String {
        val input = rawInput.trim()
        if (input.isEmpty()) return "about:blank"

        return when {
            input.startsWith("http://", true) ||
                    input.startsWith("https://", true) -> input

            input.startsWith("about:", true) ||
                    input.startsWith("file:", true) ||
                    input.startsWith("intent:", true) ||
                    input.startsWith("data:", true) ||
                    input.startsWith("mailto:", true) ||
                    input.startsWith("tel:", true) -> input

            looksLikeLocalhost(input) || looksLikeUrl(input) -> "https://$input"

            else -> searchBaseUrl + Uri.encode(input)
        }
    }
}