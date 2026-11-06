package com.fcaronte.aabrowser.utils

import java.net.URLDecoder

/**
 * Header che una lista M3U chiede per aprire un canale: senza, certi server rispondono 403. Si scrivono in più modi:
 * #EXTVLCOPT:http-user-agent=… (VLC, le liste IPTV-org), attributi di #EXTINF, #EXTHTTP:{"User-Agent":"…"} (TiviMate),
 * #KODIPROP:inputstream.adaptive.stream_headers=User-Agent=…&Referer=… (Kodi) e indirizzo|User-Agent=…&Referer=….
 */
internal object IptvHeaders {
    private val JSON_PAIR = Regex(""""([^"]+)"\s*:\s*"([^"]*)"""")

    private val NAMES = mapOf(
        "http-user-agent" to "User-Agent",
        "user-agent" to "User-Agent",
        "http-referrer" to "Referer",
        "http-referer" to "Referer",
        "referrer" to "Referer",
        "referer" to "Referer",
        "http-origin" to "Origin",
        "origin" to "Origin",
        "http-cookie" to "Cookie",
        "cookie" to "Cookie"
    )

    private fun named(pairs: List<Pair<String, String>>): Map<String, String> = pairs.mapNotNull { (key, value) ->
        val name = NAMES[key.trim().lowercase()] ?: return@mapNotNull null
        value.trim().takeIf { it.isNotEmpty() }?.let { name to it }
    }.toMap()

    /** Attributi di #EXTINF, già con le chiavi in minuscolo. */
    fun fromAttributes(attributes: Map<String, String>): Map<String, String> = named(attributes.toList())

    /** Una riga che comincia con #: gli header che dichiara (nessuno se è un'altra direttiva). */
    fun fromDirective(line: String): Map<String, String> {
        val value = line.substringAfter(':', "")
        return when {
            line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                named(listOf(value.substringBefore('=') to value.substringAfter('=', "")))
            }
            line.startsWith("#EXTHTTP:", ignoreCase = true) -> {
                named(JSON_PAIR.findAll(value).map { it.groupValues[1] to it.groupValues[2] }.toList())
            }
            line.startsWith("#KODIPROP:", ignoreCase = true) && value.contains("_headers=", ignoreCase = true) -> {
                query(value.substringAfter('=', ""))
            }
            else -> emptyMap()
        }
    }

    /** "indirizzo|User-Agent=…&Referer=…": l'indirizzo e i suoi header. */
    fun splitAddress(line: String): Pair<String, Map<String, String>> {
        val bar = line.indexOf('|')
        if (bar < 0) return line to emptyMap()
        return line.substring(0, bar).trim() to query(line.substring(bar + 1))
    }

    // Valori codificati come in un indirizzo, ma un "+" è spesso proprio un "+"
    private fun query(text: String): Map<String, String> = named(
        text.split('&').map { pair ->
            val value = pair.substringAfter('=', "")
            pair.substringBefore('=') to try {
                URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
            } catch (_: IllegalArgumentException) {
                value
            }
        }
    )
}
