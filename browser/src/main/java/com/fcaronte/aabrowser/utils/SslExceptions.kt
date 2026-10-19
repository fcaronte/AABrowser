package com.fcaronte.aabrowser.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import java.security.MessageDigest

/**
 * Avviso di certificato in attesa della scelta dell'utente: la pagina resta ferma finché non risponde.
 * Le altre richieste dello stesso sito con lo stesso certificato si accodano allo stesso avviso.
 */
class PendingSslError(private val error: SslError, val key: String) {
    val host: String = SslExceptions.hostOf(error)
    private val handlers = mutableListOf<SslErrorHandler>()

    fun add(handler: SslErrorHandler) {
        handlers.add(handler)
    }

    fun resolve(proceed: Boolean) {
        if (proceed) SslExceptions.allow(error)
        handlers.forEach { if (proceed) it.proceed() else it.cancel() }
        handlers.clear()
    }
}

/**
 * Avvisi di certificato di una WebView, mostrati uno alla volta. Tutto gira sul main thread
 * (callback della WebView e UI). [onChanged] riceve l'avviso da mostrare ora (null = nessuno).
 */
class SslErrorPrompts(context: Context, private val onChanged: (PendingSslError?) -> Unit) {
    private val queue = mutableListOf<PendingSslError>()

    init {
        SslExceptions.init(context)
    }

    val current: PendingSslError? get() = queue.firstOrNull()

    /** Da `WebViewClient.onReceivedSslError`: prosegue da solo se il certificato è già stato accettato. */
    fun onSslError(handler: SslErrorHandler, error: SslError) {
        if (SslExceptions.isAllowed(error)) {
            handler.proceed()
            return
        }
        AppLog.w("SslErrorPrompts", "Certificato non valido (errore ${error.primaryError}): chiedo all'utente")
        val key = SslExceptions.keyOf(error)
        val same = queue.firstOrNull { it.key == key }
        if (same != null) {
            same.add(handler)
            return
        }
        queue.add(PendingSslError(error, key).also { it.add(handler) })
        if (queue.size == 1) onChanged(current)
    }

    /** Scelta dell'utente; ignorata se nel frattempo l'avviso è stato annullato da una nuova pagina. */
    fun answer(prompt: PendingSslError, proceed: Boolean) {
        if (current !== prompt) return
        queue.removeAt(0)
        prompt.resolve(proceed)
        onChanged(current)
    }

    /** Nuova pagina o WebView chiusa: le richieste in attesa non servono più. */
    fun cancelAll() {
        if (queue.isEmpty()) return
        val pending = queue.toList()
        queue.clear()
        pending.forEach { it.resolve(false) }
        onChanged(null)
    }
}

/**
 * Certificati non validi accettati dall'utente (es. server di casa autofirmato, Proxmox, proxy locale).
 *
 * Come in Chrome, la pagina si carica solo dopo una conferma esplicita. La scelta resta salvata (anche dopo la
 * chiusura dell'app) per quel sito e quel certificato ed è condivisa tra Main e Wide. Un certificato diverso sullo
 * stesso sito (rinnovato, o di qualcuno che si spaccia per il server) va confermato di nuovo. Si dimenticano
 * dalle impostazioni (Cancella dati).
 */
object SslExceptions {
    private const val PREFS_NAME = "ssl_exceptions"
    private const val KEY_ALLOWED = "allowed"

    private val allowed = mutableSetOf<String>()
    private var prefs: SharedPreferences? = null

    /** Carica le scelte salvate, una volta per processo (la chiamano sia la Main sia la Wide). */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val stored = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = stored
        allowed.addAll(stored.getStringSet(KEY_ALLOWED, null).orEmpty())
    }

    @Synchronized
    fun count(context: Context): Int {
        init(context)
        return allowed.size
    }

    @Synchronized
    fun clear(context: Context) {
        init(context)
        allowed.clear()
        prefs?.edit()?.remove(KEY_ALLOWED)?.apply()
    }

    /** Sito da mostrare all'utente nell'avviso. */
    fun hostOf(error: SslError): String = Uri.parse(error.url).host ?: error.url

    /** Sito (con porta) + impronta SHA-256 del certificato. */
    fun keyOf(error: SslError): String {
        val uri = Uri.parse(error.url)
        val fingerprint = try {
            error.certificate.x509Certificate?.encoded?.let { encoded ->
                MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
            }
        } catch (_: Exception) {
            null
        }
        return "${uri.host}:${uri.port}|${fingerprint ?: error.certificate.toString()}"
    }

    @Synchronized
    fun isAllowed(error: SslError): Boolean = keyOf(error) in allowed

    @Synchronized
    fun allow(error: SslError) {
        if (allowed.add(keyOf(error))) {
            prefs?.edit()?.putStringSet(KEY_ALLOWED, HashSet(allowed))?.apply()
        }
    }
}
