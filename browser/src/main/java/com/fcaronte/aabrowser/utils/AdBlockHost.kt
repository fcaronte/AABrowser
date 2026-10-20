package com.fcaronte.aabrowser.utils

import android.content.Context
import android.net.Uri
import com.fcaronte.aabrowser.settings.AdBlockSettings

/**
 * Gestore leggero per il blocco delle pubblicità basato su liste di host.
 * Sostituisce il vecchio AdBlock Plus che causava problemi di dimensioni e prestazioni.
 */
object AdBlockHost {

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

    fun init(context: Context) {
        blockedHosts.addAll(defaultAdHosts)
    }

    /** [pageHost] = sito della pagina principale della WebView (null se non si sa). */
    fun shouldBlock(url: String, pageHost: String? = null): Boolean {
        if (!AdBlockSettings.isEnabled.value) return false
        // Uri e non URL: niente eccezioni con blob:, data:, about: e javascript:
        val host = Uri.parse(url).host?.lowercase() ?: return false
        if (matches(host, scriptManagedHosts) || matches(pageHost?.lowercase(), scriptManagedHosts)) return false
        // L'host o un suo sottodominio è nella lista nera
        return matches(host, blockedHosts)
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