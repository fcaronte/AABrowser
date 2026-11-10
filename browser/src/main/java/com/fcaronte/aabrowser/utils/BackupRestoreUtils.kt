package com.fcaronte.aabrowser.utils

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * File di backup delle Impostazioni, nel posto scelto dall'utente: lo si rilegge anche dopo una reinstallazione o su
 * un altro telefono (un file lasciato in Download da un'installazione precedente non si può più leggere). Contiene
 * preferiti, impostazioni, AdBlock, permessi dei siti, liste di TV, Radio e Podcast (con indirizzi e password: il file
 * lo tiene l'utente, il backup di Google no) e lo stato delle pagine (MediaPages). Fuori i certificati accettati e
 * quello che vale solo su questo telefono (cartella scelta per i file, aggiornamenti).
 * Ogni valore porta il suo tipo: senza, un Long piccolo tornava Int e l'app non lo leggeva più. Si leggono anche i
 * file di prima (solo preferiti e impostazioni, senza tipi).
 */
object BackupRestoreUtils {
    private const val TAG = "BackupRestore"
    private const val FORMAT = 2
    private val PREFS = listOf(
        "BOOKMARKS", "aa_browser_settings", "adblock_prefs", "site_permissions", "iptv_sources", MediaPages.PREFS
    )
    private val LEGACY = mapOf("bookmarks" to "BOOKMARKS", "settings" to "aa_browser_settings")

    fun export(context: Context, output: OutputStream): Boolean = try {
        val prefs = JSONObject()
        PREFS.forEach { name -> prefs.put(name, typed(context.getSharedPreferences(name, Context.MODE_PRIVATE))) }
        val root = JSONObject().put("format", FORMAT).put("prefs", prefs)
        output.bufferedWriter().use { it.write(root.toString(2)) }
        true
    } catch (e: Exception) {
        AppLog.e(TAG, "Backup not written: ${e.javaClass.simpleName}", e)
        false
    }

    /** false se il file non è un backup dell'app o non si legge. Dopo, l'app va riavviata. */
    fun import(context: Context, input: InputStream): Boolean = try {
        val root = JSONObject(input.bufferedReader().use { it.readText() })
        val prefs = root.optJSONObject("prefs")
        val restored = if (root.optInt("format") >= FORMAT && prefs != null) {
            PREFS.count { name -> prefs.optJSONObject(name)?.let { restoreTyped(context, name, it) } != null }
        } else {
            LEGACY.count { (key, name) -> root.optJSONObject(key)?.let { restoreUntyped(context, name, it) } != null }
        }
        restored > 0
    } catch (e: Exception) {
        AppLog.e(TAG, "Backup not read: ${e.javaClass.simpleName}", e)
        false
    }

    private fun typed(prefs: SharedPreferences): JSONObject {
        val values = JSONObject()
        for ((key, value) in prefs.all) {
            val entry = when (value) {
                is String -> JSONArray().put("s").put(value)
                is Boolean -> JSONArray().put("b").put(value)
                is Int -> JSONArray().put("i").put(value)
                is Long -> JSONArray().put("l").put(value)
                is Float -> JSONArray().put("f").put(value.toDouble())
                is Set<*> -> JSONArray().put("ss").put(JSONArray(value.filterIsInstance<String>()))
                else -> null
            }
            if (entry != null) values.put(key, entry)
        }
        return values
    }

    // commit e non apply: subito dopo l'app si riavvia
    private fun restoreTyped(context: Context, name: String, values: JSONObject) {
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        for (key in values.keys()) {
            val entry = values.optJSONArray(key) ?: continue
            try {
                when (entry.getString(0)) {
                    "s" -> editor.putString(key, entry.getString(1))
                    "b" -> editor.putBoolean(key, entry.getBoolean(1))
                    "i" -> editor.putInt(key, entry.getInt(1))
                    "l" -> editor.putLong(key, entry.getLong(1))
                    "f" -> editor.putFloat(key, entry.getDouble(1).toFloat())
                    "ss" -> {
                        val items = entry.getJSONArray(1)
                        editor.putStringSet(key, (0 until items.length()).map { items.getString(it) }.toSet())
                    }
                }
            } catch (_: Exception) {}
        }
        editor.commit()
    }

    // File di prima, senza tipi: un numero intero torna Long se l'app lo tiene come Long
    private fun restoreUntyped(context: Context, name: String, values: JSONObject) {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val current = prefs.all
        val editor = prefs.edit().clear()
        for (key in values.keys()) {
            try {
                when (val value = values.get(key)) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> if (current[key] is Long) editor.putLong(key, value.toLong()) else editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Number -> editor.putFloat(key, value.toFloat())
                    is JSONArray -> editor.putStringSet(key, (0 until value.length()).map { value.optString(it) }.toSet())
                }
            } catch (_: Exception) {}
        }
        editor.commit()
    }
}
