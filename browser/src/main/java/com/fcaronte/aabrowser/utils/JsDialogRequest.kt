package com.fcaronte.aabrowser.utils

import android.net.Uri
import android.webkit.JsPromptResult
import android.webkit.JsResult

/**
 * Finestra aperta da una pagina con alert, confirm o prompt, in attesa della risposta dell'utente (Main e Wide).
 * Prima l'app rispondeva "OK" da sola, anche a domande come "Eliminare la chat?". La pagina resta ferma finché non
 * arriva la risposta, che si dà una volta sola.
 */
class JsDialogRequest(
    val kind: Kind,
    pageUrl: String?,
    message: String?,
    val defaultValue: String?,
    private val result: JsResult,
) {
    enum class Kind { ALERT, CONFIRM, PROMPT }

    /** Sito che chiede, come in Chrome ("google.com dice"); null se non è una pagina web. */
    val site: String? = pageUrl?.let { Uri.parse(it).host }?.removePrefix("www.")
    val message: String = message.orEmpty()
    private var answered = false

    fun confirm(text: String? = null) {
        if (answered) return
        answered = true
        if (result is JsPromptResult) result.confirm(text ?: defaultValue.orEmpty()) else result.confirm()
    }

    fun cancel() {
        if (answered) return
        answered = true
        result.cancel()
    }
}
