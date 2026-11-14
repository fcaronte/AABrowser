package com.fcaronte.aabrowser.utils

import android.content.Context
import androidx.core.content.edit
import com.fcaronte.aabrowser.BuildConfig

/**
 * Le novità di una versione, mostrate in un riquadro la prima volta che si apre l'app dopo un aggiornamento (e dalle
 * Impostazioni, quando si vuole rileggerle).
 *
 * I testi sono file di assets, uno per lingua: `whatsnew/<versione>/<lingua>.txt` (it.txt, en.txt, ru.txt, ...); si usa la
 * lingua dell'app e, se manca, l'inglese. Per ogni versione nuova basta aggiungere la sua cartella con almeno en.txt e it.txt
 * (le note di rilascio sono bilingui, italiano per primo): chi la riceve le vede una volta sola. Una versione senza cartella
 * non mostra niente. Righe vuote separano i paragrafi, "• " apre un elenco.
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

    /**
     * Le novità da mostrare adesso: la prima apertura dopo un aggiornamento a una versione che ha le sue note. La versione si
     * segna come vista subito, anche se non ci sono note o se è un'installazione nuova: il riquadro compare una volta sola.
     */
    fun pending(context: Context): Notes? {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = BuildConfig.VERSION_CODE
        val seen = prefs.getInt(KEY_SEEN, -1)
        if (seen == current) return null
        prefs.edit { putInt(KEY_SEEN, current) }
        // Una versione più vecchia di quella già vista (si è tornati indietro): niente
        if (seen > current) return null
        val info = try {
            app.packageManager.getPackageInfo(app.packageName, 0)
        } catch (_: Exception) {
            return null
        }
        // Prima installazione: non c'è niente di cui dire "novità"
        if (info.lastUpdateTime - info.firstInstallTime < FRESH_INSTALL_MS) return null
        return notesFor(app, BuildConfig.VERSION_NAME)
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
