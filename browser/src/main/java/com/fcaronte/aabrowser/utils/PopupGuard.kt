package com.fcaronte.aabrowser.utils

import android.os.SystemClock
import com.fcaronte.aabrowser.settings.AdBlockSettings
import java.net.URI

/**
 * Pubblicità che si aprono a ogni tocco (pop-up e pop-under dei siti di streaming): uno script apre una finestra verso
 * un altro sito, che nella Main diventa una scheda e nella Wide prende il posto della pagina; spesso subito dopo la
 * pagina stessa va sulla pubblicità. Una finestra aperta da uno script passa solo verso lo stesso sito della pagina,
 * verso il sito del link toccato o per un accesso (login, pagamento). Il link toccato lo dice lo script che intercetta
 * window.open (GoogleLoginManager). Con l'AdBlock spento, o spento per il sito con lo scudo, tutto come prima.
 */
object PopupGuard {

    // Dopo una finestra aperta da uno script, per così la pagina non va su un altro sito (pop-under "tab-under")
    private const val TAB_UNDER_MS = 2_000L

    // Accessi che si aprono in un'altra finestra: parole all'inizio di un pezzo di host o percorso (login.…, /oauth2/,
    // /authorize), non in mezzo ("rossoporpora" non è "sso")
    private val SIGN_IN_WORDS = listOf("oauth", "login", "signin", "auth", "sso", "account")
    private val WORD_SPLIT = Regex("[^a-z0-9]+")
    private val PAYMENT_SITES = setOf("paypal.com", "stripe.com", "klarna.com", "satispay.com", "scalapay.com")

    // Domini di secondo livello dei Paesi: bbc.co.uk e bbc.com.br sono siti, co.uk no
    private val COUNTRY_SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "edu", "ac", "gob", "or", "ne", "go")

    /** Ultima finestra aperta da uno script in una WebView (una per scheda della Main, una per la Wide). */
    class Attempts {
        @Volatile
        var at = 0L
            private set

        @Volatile
        var tappedLink: String? = null
            private set

        fun note(link: String?) {
            tappedLink = link?.takeIf { it.isNotBlank() }
            at = SystemClock.uptimeMillis()
        }

        fun isRecent(): Boolean = at != 0L && SystemClock.uptimeMillis() - at <= TAB_UNDER_MS
    }

    /** Finestra aperta da uno script, o link cliccato da uno script: true = si apre. */
    fun allowsPopup(url: String, pageHost: String?, tappedLink: String?, attempts: Attempts, isWindow: Boolean): Boolean {
        if (!isActive(pageHost)) return true
        val allowed = decide(url, pageHost, tappedLink, AdBlockHost::isAdHost)
        if (isWindow || !allowed) attempts.note(tappedLink)
        if (!allowed) AppLog.d(TAG, "Popup blocked: ${hostOf(url)}")
        return allowed
    }

    /**
     * La pagina principale va a [url]: false = si resta dove si è. Le pagine pubblicitarie (liste dell'AdBlock) mai;
     * subito dopo una finestra aperta da uno script, solo verso il sito della pagina, del link toccato o un accesso.
     */
    fun allowsNavigation(url: String, pageHost: String?, hasGesture: Boolean, isRedirect: Boolean, attempts: Attempts): Boolean {
        if (!isActive(pageHost)) return true
        val host = hostOf(url) ?: return true
        if (AdBlockHost.isAdHost(host)) {
            AppLog.d(TAG, "Ad page blocked: $host")
            return false
        }
        if (isRedirect || !hasGesture || !attempts.isRecent()) return true
        val allowed = decide(url, pageHost, attempts.tappedLink) { false }
        if (!allowed) AppLog.d(TAG, "Page moved away after a popup, blocked: $host")
        return allowed
    }

    private fun isActive(pageHost: String?): Boolean =
        AdBlockSettings.isEnabled.value && !AdBlockHost.isWhitelisted(pageHost)

    /** La scelta, senza stato: [isAdHost] = host nelle liste dell'AdBlock. */
    fun decide(url: String, pageHost: String?, tappedLink: String?, isAdHost: (String) -> Boolean): Boolean {
        // Non è un indirizzo web (about:blank, blob:): non porta su un altro sito
        val host = hostOf(url) ?: return true
        if (isAdHost(host)) return false
        val site = siteOf(host)
        if (pageHost != null && siteOf(pageHost) == site) return true
        val tapped = tappedLink?.let(::hostOf)
        if (tapped != null && siteOf(tapped) == site) return true
        return isSignIn(url, host)
    }

    private fun isSignIn(url: String, host: String): Boolean {
        if (PAYMENT_SITES.contains(siteOf(host))) return true
        val path = try {
            URI(url).rawPath.orEmpty()
        } catch (_: Exception) {
            ""
        }
        val words = "$host/$path".lowercase().split(WORD_SPLIT)
        if ("sign-in" in path.lowercase()) return true
        return words.any { word -> SIGN_IN_WORDS.any { word.startsWith(it) } }
    }

    private fun hostOf(url: String): String? {
        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        return uri.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
    }

    /** Il sito di un host: "m.youtube.com" e "accounts.youtube.com" sono youtube.com, "news.bbc.co.uk" è bbc.co.uk. */
    fun siteOf(host: String): String {
        val name = host.lowercase().trimEnd('.')
        if (name.all { it.isDigit() || it == '.' } || ':' in name || name.startsWith("[")) return name
        val labels = name.split('.')
        if (labels.size <= 2) return name
        val countryLevel = labels.last().length == 2 && labels[labels.size - 2] in COUNTRY_SECOND_LEVEL
        return labels.takeLast(if (countryLevel) 3 else 2).joinToString(".")
    }

    private const val TAG = "PopupGuard"
}
