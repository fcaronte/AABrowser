package com.fcaronte.aabrowser.car

import android.net.Uri
import android.text.TextUtils
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Template
import androidx.car.app.model.SearchTemplate

/**
 * Home in HTML generata a runtime dai preferiti dell'app principale.
 * Nessun ponte JavaScript: le tessere sono normali link <a href>.
 * Carica con:
 *   webView.loadDataWithBaseURL(HomePage.BASE_URL, HomePage.build(items), "text/html", "UTF-8", null)
 */
object HomePage {

    const val BASE_URL = "https://home.local/"

    data class Item(val name: String, val url: String)

    fun build(items: List<Item>): String {
        val tiles = items.joinToString("\n") { item ->
            val name = TextUtils.htmlEncode(item.name)
            val url = TextUtils.htmlEncode(item.url)
            val letter = TextUtils.htmlEncode(item.name.trim().take(1).uppercase().ifEmpty { "?" })
            """<a class="tile" href="$url"><div class="ico">$letter</div><div class="name">$name</div></a>"""
        }

        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  html, body { margin:0; height:100%; background:#101010; color:#fff;
               font-family: sans-serif; }
  body { padding:24px; box-sizing:border-box; }
  h1 { font-size:22px; font-weight:500; margin:0 0 20px 0; opacity:.8; }
  .grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(150px,1fr)); gap:16px; }
  .tile { display:flex; flex-direction:column; align-items:center; justify-content:center;
          min-height:130px; padding:12px; border-radius:20px; background:#1e1e1e;
          text-decoration:none; color:#fff; }
  .tile:active { background:#333; }
  .ico { width:64px; height:64px; border-radius:16px; background:#3b82f6;
         display:flex; align-items:center; justify-content:center; font-size:32px; font-weight:600; }
  .name { margin-top:10px; font-size:16px; text-align:center; overflow:hidden;
          text-overflow:ellipsis; white-space:nowrap; max-width:100%; }
  .empty { opacity:.6; font-size:18px; }
</style>
</head>
<body>
  <h1>Preferiti</h1>
  <div class="grid">
    ${if (items.isEmpty()) "<div class=\"empty\">Nessun preferito. Usa la lente per cercare.</div>" else tiles}
  </div>
</body>
</html>
""".trimIndent()
    }

    /** Testo digitato/dettato -> URL. Se sembra un dominio lo apre, altrimenti cerca su Google. */
    fun queryToUrl(query: String): String {
        val q = query.trim()
        return when {
            q.startsWith("http://") || q.startsWith("https://") -> q
            q.contains('.') && !q.contains(' ') -> "https://$q"
            else -> "https://www.google.com/search?q=" + Uri.encode(q)
        }
    }
}

/**
 * Schermata di ricerca: l'host di Android Auto mostra tastiera e microfono (dettatura).
 * A veicolo in movimento la tastiera può essere disabilitata dall'host.
 */
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