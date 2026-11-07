package com.fcaronte.aabrowser.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.edit
import com.fcaronte.aabrowser.settings.AdBlockSettings
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Gestore leggero per il blocco delle pubblicità basato su liste di host.
 * Sostituisce il vecchio AdBlock Plus che causava problemi di dimensioni e prestazioni.
 */
object AdBlockHost {

    // Stesso file delle impostazioni dell'AdBlock (AdBlockSettings)
    private const val PREFS_NAME = "adblock_prefs"
    private const val KEY_WHITELIST = "adblock_whitelist"

    private val blockedHosts = mutableSetOf<String>()

    // Lista iniziale di domini comuni da bloccare
    private val defaultAdHosts = setOf(
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "moatads.com",
        "adservice.google.com",
        "ad-delivery.net",
        "adnxs.com",
        "outbrain.com",
        "taboola.com",
        "scorecardresearch.com",
        "quantserve.com",
        "amazon-adsystem.com",
        "adnxs.com",
        "casalemedia.com",
        "pubmatic.com",
        "rubiconproject.com",
        "openx.net",
        "appnexus.com",
        "bidswitch.net",
        "criteo.com",
        "smartadserver.com",
        "exponential.com",
        "lijit.com",
        "media.net",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "revcontent.com",
        "yieldmo.com",
        "adform.net",
        "adtech.de",
        "advertising.com",
        "atdmt.com",
        "betrad.com",
        "bluekai.com",
        "dotomi.com",
        "everesttech.net",
        "facade.com",
        "imrworldwide.com",
        "insightexpressai.com",
        "mookie1.com",
        "nexac.com",
        "questionmarket.com",
        "ru4.com",
        "sharethrough.com",
        "specificclick.net",
        "tapad.com",
        "turn.com",
        "undertone.com",
        "vibrantmedia.com",
        "adcolony.com",
        "applovin.com",
        "unityads.unity3d.com",
        "ironsrc.com",
        "vungle.com",
        "ad.doubleclick.net",
        "googleads.g.doubleclick.net",
        "pagead2.googlesyndication.com",
        "ade.googlesyndication.com",
        "spclient.wg.spotify.com",
        "audio-ads.spotify.com",
        "ads-fa.spotify.com",
        "gads.spotify.com"
    )

    // YouTube gestisce le pubblicità dentro il player: se la rete le blocca (doubleclick, googlesyndication...)
    // il player va in errore e non mette "ad-showing", e lo script (AdBlockJavascript) non le salta più.
    // Sulle pagine di YouTube, e per le sue risorse ovunque, decide solo lo script
    private val scriptManagedHosts = setOf("youtube.com", "youtu.be", "youtubekids.com", "googlevideo.com", "ytimg.com")

    // Siti in cui l'utente ha spento l'AdBlock: host senza "www.", vale anche per i sottodomini.
    // Letta dal thread di rete delle WebView, cambiata dal thread principale
    private val whitelist = CopyOnWriteArraySet<String>()
    private var prefs: SharedPreferences? = null

    // Cambia a ogni modifica della whitelist: gli scudi della Main (barra schede e menu) si aggiornano insieme
    private val _whitelistVersion = mutableIntStateOf(0)
    val whitelistVersion: State<Int> = _whitelistVersion

    fun init(context: Context) {
        blockedHosts.addAll(defaultAdHosts)
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also {
            whitelist.addAll(it.getStringSet(KEY_WHITELIST, emptySet()).orEmpty())
        }
    }

    /** [pageHost] = sito della pagina principale della WebView (null se non si sa). */
    fun shouldBlock(url: String, pageHost: String? = null): Boolean {
        if (!AdBlockSettings.isEnabled.value) return false
        // Uri e non URL: niente eccezioni con blob:, data:, about: e javascript:
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: return false
        if (matches(host, scriptManagedHosts) || matches(pageHost?.lowercase(), scriptManagedHosts)) return false
        if (isWhitelisted(pageHost) || isWhitelisted(host)) return false
        // L'host o un suo sottodominio è nella lista nera
        return isAdHost(host) || isRotatingAdScript(uri.path, host, pageHost)
    }

    /**
     * L'host (o un dominio che lo contiene) è nei domini scritti qui o nella lista scaricata (AdBlockLists). Mai quelli
     * di YouTube ([scriptManagedHosts]).
     */
    fun isAdHost(host: String?): Boolean {
        val name = host?.lowercase() ?: return false
        if (matches(name, scriptManagedHosts)) return false
        return matches(name, blockedHosts) || AdBlockLists.contains(name)
    }

    // Script dei circuiti pubblicitari che cambiano dominio di continuo (pop-under come quelli di Adsterra): sempre
    // "/c7/93/5c/c7935c….js", le cartelle sono l'inizio del nome; da un sito diverso da quello della pagina
    private val rotatingAdScript = Regex("/([0-9a-f]{2})/([0-9a-f]{2})/([0-9a-f]{2})/([0-9a-f]{32})\\.js")

    fun isRotatingAdScript(path: String?, host: String, pageHost: String?): Boolean {
        val match = rotatingAdScript.matchEntire(path ?: return false) ?: return false
        val (first, second, third, name) = match.destructured
        if (!name.startsWith(first + second + third)) return false
        return pageHost == null || PopupGuard.siteOf(pageHost) != PopupGuard.siteOf(host)
    }

    fun isWhitelisted(host: String?): Boolean = matches(siteKey(host), whitelist)

    fun addToWhitelist(host: String) {
        val key = siteKey(host) ?: return
        whitelist.add(key)
        saveWhitelist()
    }

    /** Toglie anche le voci che comprendono il sito (aggiunto da example.com, tolto da m.example.com). */
    fun removeFromWhitelist(host: String) {
        val key = siteKey(host) ?: return
        // removeIf: l'iteratore di CopyOnWriteArraySet non toglie elementi (removeAll di Kotlin lo userebbe)
        whitelist.removeIf { key == it || key.endsWith(".$it") }
        saveWhitelist()
    }

    /** Nome del sito per la whitelist e per i messaggi: host senza "www.". */
    fun siteKey(host: String?): String? = host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() }

    private fun saveWhitelist() {
        prefs?.edit { putStringSet(KEY_WHITELIST, HashSet(whitelist)) }
        _whitelistVersion.intValue++
    }

    private fun matches(host: String?, hosts: Collection<String>): Boolean =
        host != null && hosts.any { host == it || host.endsWith(".$it") }

    /**
     * Permette di aggiungere dinamicamente nuovi host alla lista di blocco.
     */
    fun addBlockedHost(host: String) {
        blockedHosts.add(host.lowercase())
    }
}