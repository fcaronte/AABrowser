package com.fcaronte.aabrowser.car

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

    private fun faviconFor(url: String): String? {
        return try {
            val host = url.toUri().host ?: return null
            when {
                // Per WhatsApp e domini simili usiamo il favicon finder di DuckDuckGo che non dà 403
                host.contains("whatsapp.com") || host.contains("wa.me") ->
                    "https://icons.duckduckgo.com/ip3/whatsapp.com.ico"

                host.contains("youtubekids.com") ->
                    "https://www.google.com/s2/favicons?domain=youtube.com&sz=128"

                else ->
                    "https://www.google.com/s2/favicons?domain=$host&sz=128"
            }
        } catch (_: Exception) {
            null
        }
    }

    fun build(context: android.content.Context, items: List<Item>): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }

        val tiles = items.joinToString("\n") { item ->

            val name = item.name.htmlEncode()
            val url = item.url.htmlEncode()

            val letter = item.name.trim()
                .take(1)
                .uppercase()
                .ifEmpty { "?" }.htmlEncode()

            val iconUrl = item.iconUrl ?: faviconFor(item.url)

            val iconHtml =
                if (!iconUrl.isNullOrBlank()) {
                    """
                    <img class="ico-img" src="${iconUrl.htmlEncode()}">
                    """.trimIndent()
                } else {
                    """
                    <div class="ico">$letter</div>
                    """.trimIndent()
                }

            """
            <a class="tile" href="$url">
                $iconHtml
                <div class="name">$name</div>
            </a>
            """.trimIndent()
        }

        val titleText = "AABrowser v$version"

        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">

<style>

html, body {
    margin:0;
    height:100%;
    background:#101010;
    color:#ffffff;
    font-family:sans-serif;
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

/* Griglia più densa: ridotto minmax a 115px per far stare più preferiti per riga */
.grid {
    display:grid;
    grid-template-columns:repeat(auto-fill,minmax(115px,1fr));
    gap:12px;
}

/* Tile più compatti */
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
</head>

<body>

<h1>$titleText</h1>

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

    fun queryToUrl(query: String): String {
        val q = query.trim()

        return when {
            q.startsWith("http://") ||
                    q.startsWith("https://") ->
                q

            q.contains('.') && !q.contains(' ') ->
                "https://$q"

            else ->
                "https://www.google.com/search?q=" + Uri.encode(q)
        }
    }
}

class WideSearchScreen(
    carContext: CarContext,
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
            .setShowKeyboardByDefault(true)
            .build()
    }
}