package com.fcaronte.aabrowser.car

import android.content.Context
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.core.net.toUri
import androidx.core.text.htmlEncode

object HomePage {

    const val BASE_URL = "https://home.local/"

    data class Item(
        val name: String,
        val url: String,
        val iconUrl: String? = null
    )

    private fun hostOf(url: String): String? =
        try { url.toUri().host } catch (_: Exception) { null }

    private fun faviconFor(url: String): String? {
        val host = hostOf(url) ?: return null
        return when {
            // WhatsApp: il favicon di Google dà 403, usiamo DuckDuckGo
            host.contains("whatsapp.com") || host.contains("wa.me") ->
                "https://icons.duckduckgo.com/ip3/whatsapp.com.ico"

            host.contains("youtubekids.com") ->
                "https://www.google.com/s2/favicons?domain=youtube.com&sz=128"

            else ->
                "https://www.google.com/s2/favicons?domain=$host&sz=128"
        }
    }

    /**
     * Catena di icone da provare in ordine:
     * icona salvata -> Google -> DuckDuckGo -> /favicon.ico del sito -> lettera (gestita in JS).
     */
    private fun iconCandidates(item: Item): List<String> {
        val list = mutableListOf<String>()
        item.iconUrl?.takeIf { it.isNotBlank() }?.let { list.add(it) }
        faviconFor(item.url)?.let { list.add(it) }
        val uri = try { item.url.toUri() } catch (_: Exception) { null }
        val host = uri?.host
        if (host != null) {
            list.add("https://icons.duckduckgo.com/ip3/$host.ico")
            val scheme = uri.scheme ?: "https"
            list.add("$scheme://$host/favicon.ico")
        }
        return list.distinct()
    }

    fun build(context: Context, items: List<Item>, reopenLastPage: Boolean): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }

        val toggleText = if (reopenLastPage) "ON" else "OFF"
        val toggleColor = if (reopenLastPage) "#22c55e" else "#ef4444"

        val tiles = items.joinToString("\n") { item ->
            val name = item.name.htmlEncode()
            val url = item.url.htmlEncode()
            val letter = item.name.trim().take(1).uppercase().ifEmpty { "?" }.htmlEncode()
            val candidates = iconCandidates(item)

            val iconHtml = if (candidates.isNotEmpty()) {
                val all = candidates.joinToString("|").htmlEncode()
                val first = candidates.first().htmlEncode()
                """<img class="ico-img" src="$first" data-s="$all" data-i="0" onerror="fb(this)" onload="ld(this)"><div class="ico" style="display:none">$letter</div>"""
            } else {
                """<div class="ico">$letter</div>"""
            }

            """<a class="tile" href="$url">$iconHtml<div class="name">$name</div></a>"""
        }

        val titleText = "AABrowser v$version"

        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">

<style>

html, body {
    margin:0;
    height:100%;
    background:#101010;
    color:#ffffff;
    font-family:sans-serif;
    user-select: none;
    -webkit-user-select: none;
    touch-action: pan-x pan-y;
}

body {
    padding:16px;
    box-sizing:border-box;
    background-image: linear-gradient(rgba(16, 16, 16, 0.8), rgba(16, 16, 16, 0.9)), url('https://images.unsplash.com/photo-1614850523296-d8c1af93d400?q=80&w=2070&auto=format&fit=crop');
    background-size: cover;
    background-position: center;
    background-attachment: fixed;
}

h1 {
    font-size:20px;
    font-weight:500;
    margin:0 0 14px 0;
    opacity:.9;
}

.settings {
    margin-bottom: 16px;
}
.toggle {
    display:inline-block;
    background: rgba(30, 30, 30, 0.85);
    color: white;
    padding: 10px 18px;
    border-radius: 8px;
    border: 1px solid #444;
    font-size: 14px;
    text-decoration:none;
}
.toggle:active { background: rgba(51, 51, 51, 0.95); }

.grid {
    display:grid;
    grid-template-columns:repeat(auto-fill,minmax(115px,1fr));
    gap:12px;
}

.tile {
    display:flex;
    flex-direction:column;
    align-items:center;
    justify-content:center;
    min-height:100px;
    padding:8px;
    border-radius:16px;
    background:rgba(30, 30, 30, 0.85);
    backdrop-filter: blur(10px);
    text-decoration:none;
    color:#ffffff;
    border: 1px solid rgba(255, 255, 255, 0.08);
}

.tile:active {
    background:rgba(51, 51, 51, 0.95);
}

.ico {
    width:48px;
    height:48px;
    border-radius:12px;
    background:#3b82f6;
    display:flex;
    align-items:center;
    justify-content:center;
    font-size:24px;
    font-weight:600;
}

.ico-img {
    width:48px;
    height:48px;
    border-radius:12px;
    object-fit:contain;
    background:#ffffff;
    padding:4px;
    box-sizing:border-box;
}

.name {
    margin-top:8px;
    font-size:13px;
    text-align:center;
    overflow:hidden;
    text-overflow:ellipsis;
    white-space:nowrap;
    max-width:100%;
}

.empty {
    opacity:.6;
    font-size:16px;
}

</style>

<script>
  // Icone: se una sorgente fallisce (o e' solo il globo 16px di default) prova la successiva,
  // alla fine mostra la lettera iniziale.
  function nxt(img) {
    var l = (img.dataset.s || '').split('|').filter(Boolean);
    var i = parseInt(img.dataset.i || '0') + 1;
    return { l: l, i: i };
  }
  function fb(img) {
    var n = nxt(img);
    if (n.i < n.l.length) {
      img.dataset.i = n.i;
      img.src = n.l[n.i];
    } else {
      img.style.display = 'none';
      var p = img.nextElementSibling;
      if (p) p.style.display = 'flex';
    }
  }
  function ld(img) {
    var n = nxt(img);
    if (img.naturalWidth <= 16 && n.i < n.l.length) { fb(img); }
  }
</script>
</head>

<body>

<h1>$titleText</h1>

<div class="settings">
    <a class="toggle" href="about:toggle_reopen">
        Riapri ultima pagina: <span style="color:$toggleColor">$toggleText</span>
    </a>
</div>

<div class="grid">
${
            if (items.isEmpty())
                """<div class="empty">Nessun preferito. Usa la lente per cercare.</div>"""
            else
                tiles
        }
</div>

</body>
</html>
        """.trimIndent()
    }

    /** Se il testo sembra un indirizzo restituisce l'URL, altrimenti null. */
    fun asUrlOrNull(query: String): String? {
        val q = query.trim()
        return when {
            q.startsWith("http://") || q.startsWith("https://") -> q
            q.contains('.') && !q.contains(' ') -> "https://$q"
            else -> null
        }
    }
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
        // Cambiato .endsWith() con .contains() per catturare anche music.youtube.com
        Site({ it.contains("youtube.com") }, "YouTube", "https://www.youtube.com/results?search_query=%s"),
        Site({ it.contains("music.youtube.com") }, "YT Music", "https://music.youtube.com/search?q=%s"),
        Site({ it.contains("spotify.com") }, "Spotify", "https://open.spotify.com/search/%s"),
        Site({ it.contains("wikipedia.org") }, "Wikipedia", "https://{host}/w/index.php?search=%s"),
        Site({ it.contains("amazon.") }, "Amazon", "https://{host}/s?k=%s"),
        Site({ it.contains("ebay.") }, "eBay", "https://{host}/sch/i.html?_nkw=%s"),
        Site({ it.contains("reddit.com") }, "Reddit", "https://www.reddit.com/search/?q=%s"),
        Site({ it.contains("imdb.com") }, "IMDb", "https://www.imdb.com/find/?q=%s"),
        Site({ it.contains("twitch.tv") }, "Twitch", "https://www.twitch.tv/search?term=%s"),
        Site({ it.contains("netflix.com") }, "Netflix", "https://www.netflix.com/search?q=%s"),
    )

    private fun hostOf(url: String?): String? =
        try {
            url?.toUri()?.host?.lowercase()
        } catch (_: Exception) { null }

    fun targetFor(currentUrl: String?): Target {
        val host = hostOf(currentUrl) ?: return Target("Google", null)
        val site = sites.firstOrNull { it.match(host) } ?: return Target("Google", null)
        return Target(site.label, site.template)
    }

    fun resolve(currentUrl: String?, query: String): String {
        HomePage.asUrlOrNull(query)?.let { return it }
        val host = hostOf(currentUrl)
        val site = host?.let { h -> sites.firstOrNull { it.match(h) } }
        val encoded = Uri.encode(query.trim())
        return site?.template?.replace("{host}", host)?.replace("%s", encoded)
            ?: "https://www.google.com/search?q=$encoded"
    }
}

/**
 * Schermata di ricerca/testo: l'host di Android Auto mostra tastiera e microfono.
 * A veicolo in movimento la tastiera può essere disabilitata dall'host.
 */
class WideSearchScreen(
    carContext: CarContext,
    private val hint: String,
    private val onSubmit: (String) -> Unit
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val callback = object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {}

            override fun onSearchSubmitted(searchText: String) {
                if (searchText.isNotBlank()) {
                    screenManager.pop()
                    onSubmit(searchText)
                }
            }
        }
        return SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setSearchHint(hint)
            .setShowKeyboardByDefault(true)
            .build()
    }
}