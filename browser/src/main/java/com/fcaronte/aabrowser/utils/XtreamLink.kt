package com.fcaronte.aabrowser.utils

import java.net.URLDecoder

/**
 * Link completo di un account Xtream, come lo danno i fornitori: la lista M3U (`get.php?username=…&password=…`),
 * l'API (`player_api.php?…`), la guida (`xmltv.php?…`), un canale (`/live/utente/password/123.ts`, anche movie,
 * series, timeshift, o quello breve `/utente/password/123`) o l'indirizzo con le credenziali
 * (`http://utente:password@host`). Ne ricava server, utente e password per il modulo Xtream delle Impostazioni.
 * Solo Kotlin, senza android.net.Uri: si prova con i test JVM.
 */
object XtreamLink {
    /** [server]: http(s)://host:porta più l'eventuale cartella del pannello, senza "/" finale. */
    data class Login(val server: String, val username: String, val password: String)

    // Cartelle dei link a un contenuto: /live/utente/password/…
    private val CONTENT_FOLDERS = setOf("live", "movie", "series", "timeshift")

    // Ultima parte del link breve di un canale (/utente/password/123, anche 123.ts)
    private val STREAM_ID = Regex("""\d+(\.\w+)?""")

    /** Server, utente e password del link; null se il testo non è un link http(s) con utente e password. */
    fun parse(text: String): Login? {
        val link = text.trim()
        if (link.isEmpty() || link.any { it.isWhitespace() }) return null
        val schemeEnd = link.indexOf("://")
        val scheme = if (schemeEnd < 0) "http" else link.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = (if (schemeEnd < 0) link else link.substring(schemeEnd + 3)).substringBefore('#')
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        val host = authority.substringAfterLast('@')
        if (host.isEmpty()) return null
        val segments = rest.substring(authorityEnd).substringBefore('?').split('/').filter { it.isNotEmpty() }
        // Cartella del pannello: il percorso senza il file in fondo (get.php, player_api.php…)
        val folder = if (segments.lastOrNull()?.contains('.') == true) segments.dropLast(1) else segments

        val query = rest.substring(authorityEnd).substringAfter('?', "")
        val username = parameter(query, "username")
        val password = parameter(query, "password")
        if (username != null && password != null) return login(scheme, host, folder, username, password)

        val content = segments.indexOfFirst { it.lowercase() in CONTENT_FOLDERS }
        if (content >= 0 && segments.size >= content + 4) {
            val user = decodePath(segments[content + 1])
            return login(scheme, host, segments.take(content), user, decodePath(segments[content + 2]))
        }
        if (segments.size == 3 && STREAM_ID.matches(segments[2])) {
            return login(scheme, host, emptyList(), decodePath(segments[0]), decodePath(segments[1]))
        }
        val userInfo = authority.substringBeforeLast('@', "")
        if (userInfo.contains(':')) {
            val user = decodePath(userInfo.substringBefore(':'))
            return login(scheme, host, folder, user, decodePath(userInfo.substringAfter(':')))
        }
        return null
    }

    private fun login(scheme: String, host: String, folder: List<String>, username: String, password: String): Login? {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) return null
        return Login("$scheme://$host" + folder.joinToString("") { "/$it" }, user, password)
    }

    // Primo valore del parametro, decodificato come lo legge il pannello (PHP: "+" è uno spazio)
    private fun parameter(query: String, name: String): String? = query.split('&')
        .firstOrNull { it.contains('=') && it.substringBefore('=').equals(name, ignoreCase = true) }
        ?.let { decode(it.substringAfter('=')) }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: IllegalArgumentException) {
        value
    }

    // Nel percorso e nelle credenziali dell'indirizzo "+" resta "+"
    private fun decodePath(value: String): String = decode(value.replace("+", "%2B"))
}
