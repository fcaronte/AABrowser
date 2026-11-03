package com.fcaronte.aabrowser.utils

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.fcaronte.aabrowser.settings.AppSettings
import java.util.Locale

/**
 * Lingua scelta per l'app ("Forza inglese", o la lingua per app di Android 13+): in auto Android Auto passa alle
 * schermate la configurazione dell'auto, con la lingua del telefono, e i testi restavano in quella (Wide, Main in
 * auto, tastiera passeggero, pagine di AAB Media nella Wide).
 */
object AppLocale {
    /** Lingue dell'app; null se segue il telefono. */
    fun locales(context: Context): LocaleList? {
        if (AppSettings.forceEnglish.value) return LocaleList(Locale.ENGLISH)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val app = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (app != null && !app.isEmpty) return app
        }
        return null
    }

    /** [context] con la lingua dell'app, per i testi (stesso context se è già quella o se l'app segue il telefono). */
    fun wrap(context: Context): Context {
        val locales = locales(context) ?: return context
        val current = context.resources.configuration
        if (current.locales == locales) return context
        val config = Configuration(current).apply { setLocales(locales) }
        return context.createConfigurationContext(config)
    }
}
