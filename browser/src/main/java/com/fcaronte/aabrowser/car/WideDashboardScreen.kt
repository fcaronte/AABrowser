package com.fcaronte.aabrowser.car

import android.content.Context
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.core.net.toUri
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.settings.SearchEngine
import com.fcaronte.aabrowser.utils.UrlUtils

/**
 * La home della Wide è una pagina dell'app (HomeApi, assets/home) servita a [BASE_URL]: qui restano l'indirizzo e i comandi che
 * la pagina dà alla Wide con i suoi collegamenti "about:…", accettati solo dalla home stessa (WideScreen.shouldOverrideUrlLoading).
 */
object HomePage {

    const val BASE_URL = "https://home.local/"

    // Link dell'avviso di aggiornamento (gestiti dalla Wide come gli interruttori "about:toggle_…")
    const val UPDATE_DOWNLOAD = "about:update_download"
    const val UPDATE_DISMISS = "about:update_dismiss"

    // Apre CarWeb (la Main), anche se nascosta dal menu di Android Auto
    const val OPEN_MAIN = "about:toggle_open_main"

    // I tasti in alto della home, come quelli della home della Main: la ricerca (la lente) e il microfono dei comandi vocali
    const val SEARCH = "about:toggle_search"
    const val VOICE = "about:toggle_voice"
}

/**
 * Ricerca "sul sito che stai guardando": YouTube cerca su YouTube, Amazon su Amazon, ecc.
 * Sulla home o su un sito sconosciuto cerca su Google.
 */
object SiteSearch {

    data class Target(val label: String, val template: String?)

    private class Site(val match: (String) -> Boolean, val label: String, val template: String)

    // %s = testo cercato (già codificato), {host} = host della pagina corrente
    private val sites = listOf(
        Site({ it.contains("music.youtube.com") }, "YT Music", "https://music.youtube.com/search?q=%s"),
        Site({ it.contains("youtube.com") }, "YouTube", "https://www.youtube.com/results?search_query=%s"),
        Site({ it.contains("spotify.com") }, "Spotify", "https://open.spotify.com/search/%s"),
        Site({ it.contains("wikipedia.org") }, "Wikipedia", "https://{host}/w/index.php?search=%s"),
        Site({ it.contains("amazon.") }, "Amazon", "https://{host}/s?k=%s"),
        Site({ it.contains("ebay.") }, "eBay", "https://{host}/sch/i.html?_nkw=%s"),
        Site({ it.contains("reddit.com") }, "Reddit", "https://www.reddit.com/search/?q=%s"),
        Site({ it.contains("imdb.com") }, "IMDb", "https://www.imdb.com/find/?q=%s"),
        Site({ it.contains("twitch.tv") }, "Twitch", "https://www.twitch.tv/search?term=%s"),
        Site({ it.contains("netflix.com") }, "Netflix", "https://www.netflix.com/search?q=%s"),
        Site({ it.contains("maps.google.") || it.contains("google.com/maps") }, "Google Maps", "https://www.google.com/maps/search/%s"),
        Site({ it.contains("dazn.com") }, "DAZN", "https://www.dazn.com/search/%s"),
    )

    private fun hostOf(url: String?): String? =
        try {
            url?.toUri()?.host?.lowercase()
        } catch (_: Exception) { null }

    fun targetFor(currentUrl: String?): Target {
        val host = hostOf(currentUrl)
        val site = host?.let { h -> sites.firstOrNull { it.match(h) } }
        if (site != null) {
            return Target(site.label, site.template)
        }
        val defaultLabel = when (AppSettings.effectiveSearchEngine) {
            SearchEngine.GOOGLE -> "Google"
            SearchEngine.DUCKDUCKGO -> "DuckDuckGo"
            SearchEngine.BING -> "Bing"
            SearchEngine.YAHOO -> "Yahoo"
        }
        return Target(defaultLabel, null)
    }

    fun resolve(context: Context, currentUrl: String?, query: String): String {
        // Un indirizzo si apre direttamente (stessa regola della Main)
        UrlUtils.asUrlOrNull(query)?.let { return it }
        val host = hostOf(currentUrl)
        val site = host?.let { h -> sites.firstOrNull { it.match(h) } }
        val encoded = Uri.encode(query.trim())
        if (site != null) {
            return site.template.replace("{host}", host ?: "").replace("%s", encoded)
        }
        val searchEngine = AppSettings.effectiveSearchEngine
        val baseUrl = context.getString(searchEngine.baseUrlRes)
        return "$baseUrl$encoded"
    }
}

/**
 * Schermata di ricerca/testo: l'host di Android Auto mostra tastiera e microfono.
 * A veicolo in movimento la tastiera può essere disabilitata dall'host.
 */
class WideSearchScreen(
    carContext: CarContext,
    private val hint: String,
    // Testo già presente nel campo della pagina, così si modifica invece di riscriverlo
    private val initialText: String = "",
    // Per i campi delle pagine anche il testo vuoto è valido (svuota il campo)
    private val allowEmpty: Boolean = false,
    private val onSubmit: (String) -> Unit
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val callback = object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {}

            override fun onSearchSubmitted(searchText: String) {
                if (searchText.isNotBlank() || allowEmpty) {
                    screenManager.pop()
                    onSubmit(searchText)
                }
            }
        }
        return SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setSearchHint(hint)
            .setInitialSearchText(initialText)
            .setShowKeyboardByDefault(true)
            .build()
    }
}