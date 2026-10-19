package com.fcaronte.aabrowser.ui

import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.utils.LocalPlayer

/**
 * Cosa caricare come icona di un sito (preferiti, barra e gestione schede).
 * AAB Media ha la sua: player.local esiste solo nelle WebView dell'app e il servizio di Google non lo conosce.
 */
fun siteIconModel(url: String, faviconUrl: String?, domain: String): Any = when {
    LocalPlayer.isPlayerUrl(url) -> R.drawable.ic_aab_media
    !faviconUrl.isNullOrEmpty() -> faviconUrl
    else -> "https://www.google.com/s2/favicons?domain=$domain&sz=128"
}
