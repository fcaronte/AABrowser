package com.fcaronte.aabrowser.utils

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.fcaronte.aabrowser.BuildConfig

/**
 * Le novità di una versione, mostrate la prima volta che si apre l'app dopo un aggiornamento: sul telefono in un riquadro,
 * nella Main in auto in una scheda sopra la pagina, nella Wide in una schermata di Android Auto. Una volta sola in tutto: lo
 * stato è unico per l'app ([current]), quindi chi le legge sul telefono non le rivede in auto, e chi le legge in auto non le
 * rivede sul telefono; chiuse in un posto spariscono dagli altri. Si segnano come lette solo quando l'utente le chiude
 * ([dismiss]): se l'auto si spegne prima, la prossima volta ricompaiono.
 *
 * I testi sono file di assets, uno per lingua: `whatsnew/<versione>/<lingua>.txt` (it.txt, en.txt, ru.txt, ...); si usa la
 * lingua dell'app e, se manca, l'inglese. Per ogni versione nuova basta aggiungere la sua cartella con almeno en.txt e it.txt
 * (le note di rilascio sono bilingui, italiano per primo). Una versione senza cartella non mostra niente. Righe vuote
 * separano i paragrafi, "• " apre un elenco.
 *
 * Una nuova installazione non mostra niente (non c'è una versione prima): lo si riconosce perché l'app non è mai stata
 * aggiornata (prima installazione = ultimo aggiornamento).
 */
object WhatsNew {
    private const val PREFS = "aa_browser_settings"
    private const val KEY_SEEN = "whats_new_seen_code"
    private const val DIR = "whatsnew"

    // Prima installazione e "ultimo aggiornamento" possono differire di qualche istante anche su un'installazione nuova
    private const val FRESH_INSTALL_MS = 10_000L

    data class Notes(val versionName: String, val text: String)

    /** Le note da mostrare adesso, ovunque (telefono, Main in auto, Wide); null se non ce ne sono o sono già state lette. */
    var current by mutableStateOf<Notes?>(null)
        private set

    private var checked = false

    /** Guarda, una volta per processo, se ci sono novità da mostrare dopo un aggiornamento. Dal thread principale. */
    fun check(context: Context) {
        if (checked) return
        checked = true
        current = pending(context.applicationContext)
    }

    /** L'utente le ha lette (o chiuse): la versione si segna come vista e le note spariscono da ogni schermata. */
    fun dismiss(context: Context) {
        markSeen(context.applicationContext)
        current = null
    }

    /**
     * Le novità da mostrare ora: la prima apertura dopo un aggiornamento a una versione che ha le sue note. Quando non c'è
     * niente da dire (installazione nuova, versione senza note, versione più vecchia di quella già vista) la versione si
     * segna subito come vista; quando ci sono note si segna solo alla chiusura.
     */
    internal fun pending(app: Context): Notes? {
        val seen = seenCode(app)
        val current = BuildConfig.VERSION_CODE
        if (seen == current) return null
        // Una versione più vecchia di quella già vista (si è tornati indietro): niente
        if (seen > current) {
            markSeen(app)
            return null
        }
        val info = try {
            app.packageManager.getPackageInfo(app.packageName, 0)
        } catch (_: Exception) {
            return null
        }
        // Prima installazione: non c'è niente di cui dire "novità"
        if (info.lastUpdateTime - info.firstInstallTime < FRESH_INSTALL_MS) {
            markSeen(app)
            return null
        }
        val notes = notesFor(app, BuildConfig.VERSION_NAME)
        if (notes == null) markSeen(app)
        return notes
    }

    private fun seenCode(app: Context): Int =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SEEN, -1)

    private fun markSeen(app: Context) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY_SEEN, BuildConfig.VERSION_CODE) }
    }

    /** Le note di una versione nella lingua dell'app (o in inglese); null se la versione non ne ha. */
    fun notesFor(context: Context, versionName: String): Notes? {
        val assets = context.assets
        val language = (AppLocale.locales(context)?.takeIf { !it.isEmpty }?.get(0)
            ?: context.resources.configuration.locales[0])?.language.orEmpty()
        for (name in listOf(language, "en")) {
            if (name.isEmpty()) continue
            val text = try {
                assets.open("$DIR/$versionName/$name.txt").bufferedReader().use { it.readText() }.trim()
            } catch (_: Exception) {
                continue
            }
            if (text.isNotEmpty()) return Notes(versionName, text)
        }
        return null
    }

    /** Le note della versione più alta che ne ha (per rileggerle dalle Impostazioni); null se non ce n'è nessuna. */
    fun latest(context: Context): Notes? {
        val versions = try {
            context.assets.list(DIR)?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        return versions.sortedWith { a, b -> compareVersions(b, a) }.firstNotNullOfOrNull { notesFor(context, it) }
    }

    /** "2.10" dopo "2.9": si confrontano i numeri a uno a uno, non le stringhe. */
    internal fun compareVersions(a: String, b: String): Int {
        val left = a.split('.').map { it.toIntOrNull() ?: 0 }
        val right = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val difference = (left.getOrElse(i) { 0 }).compareTo(right.getOrElse(i) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }
}
