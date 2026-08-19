package com.fcaronte.aabrowser

import android.content.Context
import com.fcaronte.aabrowser.settings.AdBlockSettings
import java.net.URL

/**
 * Gestore leggero per il blocco delle pubblicità basato su liste di host.
 * Sostituisce il vecchio AdBlock Plus che causava problemi di dimensioni e prestazioni.
 */
object SubscriptionsManager {

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
        "vibrantmedia.com"
    )

    fun init(context: Context) {
        // Log rimosso o silenziato
        blockedHosts.addAll(defaultAdHosts)
    }

    fun shouldBlock(url: String): Boolean {
        if (!AdBlockSettings.isEnabled.value) return false

        return try {
            val host = URL(url).host.lowercase()
            // Controlla se l'host o i suoi sottodomini sono nella lista nera
            blockedHosts.any { adHost ->
                host == adHost || host.endsWith(".$adHost")
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Permette di aggiungere dinamicamente nuovi host alla lista di blocco.
     */
    fun addBlockedHost(host: String) {
        blockedHosts.add(host.lowercase())
    }
}
