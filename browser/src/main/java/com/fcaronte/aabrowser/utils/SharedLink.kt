package com.fcaronte.aabrowser.utils

import java.net.URI

/**
 * Il link dentro un testo condiviso dal telefono ("Guarda questo video https://youtu.be/…", titolo e indirizzo su due
 * righe). Solo indirizzi web. I link di YouTube perdono i parametri di tracciamento e passano alla versione mobile, la
 * stessa della scheda di YouTube già aperta.
 */
object SharedLink {

    private val URL_IN_TEXT = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    // Punteggiatura che chiude la frase, non l'indirizzo
    private const val TRAILING = ".,;:!?)]»"

    private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com")
    private val YOUTUBE_TRACKING = setOf("si", "feature", "pp")

    fun find(text: String?): String? {
        val link = text?.let { URL_IN_TEXT.find(it)?.value }?.let(::trimTrailing) ?: return null
        val uri = try {
            URI(link)
        } catch (_: Exception) {
            // Caratteri che URI non accetta ma la WebView sì
            return link.takeIf { it.substringAfter("://").substringBefore('/').isNotBlank() }
        }
        val host = uri.host?.lowercase()
        if (host.isNullOrBlank()) return null
        return youTube(uri, host) ?: link
    }

    private fun trimTrailing(link: String): String {
        var end = link.length
        while (end > 0 && link[end - 1] in TRAILING) {
            val last = link[end - 1]
            // "…/Foo_(bar)": la parentesi è dell'indirizzo
            if (last == ')' && link.take(end).count { it == '(' } >= link.take(end).count { it == ')' }) break
            end--
        }
        return link.take(end)
    }

    private fun youTube(uri: URI, host: String): String? {
        val params = uri.rawQuery?.split('&')
            ?.filter { it.isNotEmpty() && it.substringBefore('=') !in YOUTUBE_TRACKING }
            .orEmpty()
        return when (host) {
            "youtu.be" -> {
                val id = uri.rawPath?.trim('/')?.substringBefore('/')?.takeIf { it.isNotEmpty() } ?: return null
                "https://m.youtube.com/watch?" + (listOf("v=$id") + params).joinToString("&")
            }
            in YOUTUBE_HOSTS, "music.youtube.com" -> {
                val target = if (host == "music.youtube.com") host else "m.youtube.com"
                val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
                "https://$target${uri.rawPath.orEmpty()}$query"
            }
            else -> null
        }
    }
}
