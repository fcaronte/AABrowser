package com.fcaronte.aabrowser.utils

import com.fcaronte.aabrowser.BuildConfig
import java.net.URI

/**
 * Il repository di CarWeb su GitHub: nato come fcaronte/AABrowser, da rinominare in fcaronte/CarWeb qualche giorno dopo il
 * rilascio che li conosce tutti e due. Aggiornamenti, link e identificazione verso i servizi funzionano prima, durante e
 * dopo il cambio:
 * - prima esiste solo il nome vecchio e quello nuovo risponde 404;
 * - dopo, GitHub manda il nome vecchio al nuovo (finché nessuno ricrea un repository con quel nome), ma gli indirizzi
 *   degli APK che restituisce (browser_download_url) hanno già il nome nuovo.
 * Per questo gli aggiornamenti si cercano prima sul nome nuovo e poi sul vecchio, e si accettano gli APK di tutti e due.
 * Il nome del pacchetto (com.fcaronte.aabrowser) non cambia: servirebbe reinstallare l'app.
 *
 * Quando nessuna installazione conosce più soltanto il nome vecchio basta togliere quello da [NAMES].
 */
object GitHubRepo {
    private const val OWNER = "fcaronte"

    /** Dal nome più nuovo al più vecchio. */
    val NAMES = listOf("CarWeb", "AABrowser")

    // Il repository che ha risposto per ultimo al controllo degli aggiornamenti
    @Volatile
    private var live: String? = null

    /** Il controllo degli aggiornamenti ha trovato il repository [name]: i link da aprire usano quello. */
    fun markLive(name: String) {
        if (name in NAMES) live = name
    }

    /** Solo per i test. */
    internal fun forgetLive() {
        live = null
    }

    // Finché non si sa quale risponde, il vecchio: è l'unico che funziona sia prima del cambio sia dopo (GitHub lo manda al nuovo)
    private fun name(): String = live ?: NAMES.last()

    /** Pagina del progetto, da aprire o da dire ai servizi che lo chiedono. */
    fun pageUrl(): String = "https://github.com/$OWNER/${name()}"

    /** La guida (README) del progetto. */
    fun readmeUrl(): String = "${pageUrl()}/blob/main/README.md"

    /** Come l'app si presenta ai servizi che lo chiedono (Radio Browser, LRCLIB, Apple): nome, versione e dove trovarla. */
    fun userAgent(): String = "CarWeb/${BuildConfig.VERSION_NAME} (${pageUrl()})"

    /** API di GitHub: l'ultima release del repository chiamato [name]. */
    fun latestReleaseApiUrl(name: String): String = "https://api.github.com/repos/$OWNER/$name/releases/latest"

    /** Pagina delle release del repository chiamato [name]. */
    fun releasesPageUrl(name: String): String = "https://github.com/$OWNER/$name/releases"

    /**
     * Indirizzo di un APK delle release di CarWeb (browser_download_url di GitHub), con uno qualsiasi dei suoi nomi, letto
     * come indirizzo e non come testo: https, github.com, né porta né credenziali, percorso /fcaronte/<nome>/releases/download/…
     * senza "." o ".." (nemmeno scritti %2e). Maiuscole e minuscole non contano: per GitHub utente e repository sono lo stesso.
     */
    fun isOfficialReleaseUrl(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        if (!"https".equals(uri.scheme, ignoreCase = true) || !"github.com".equals(uri.host, ignoreCase = true)) return false
        if (uri.port != -1 || uri.userInfo != null) return false
        // I pezzi del percorso come sono scritti, senza decodificarli ("" iniziale, utente, repository, "releases", "download", …)
        val written = uri.rawPath?.split('/') ?: return false
        val decoded = uri.path?.split('/') ?: return false
        return written.size > 5 &&
            written[0].isEmpty() &&
            written[1].equals(OWNER, ignoreCase = true) &&
            NAMES.any { it.equals(written[2], ignoreCase = true) } &&
            written[3] == "releases" &&
            written[4] == "download" &&
            decoded.none { it == "." || it == ".." }
    }
}
