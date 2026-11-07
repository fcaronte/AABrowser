package com.fcaronte.aabrowser.utils

import android.content.Context
import com.fcaronte.aabrowser.settings.AppSettings
import org.json.JSONArray

/**
 * SponsorBlock su YouTube: lo script (assets/scripts/sponsorblock.js, all'inizio delle pagine di YouTube nella Main e
 * nella Wide) chiede all'app quali parti saltare. Tre livelli nelle Impostazioni.
 */
object SponsorBlock {

    const val LEVEL_SPONSOR = "sponsor"
    const val LEVEL_DEFAULT = "default"
    const val LEVEL_ALL = "all"
    val LEVELS = listOf(LEVEL_SPONSOR, LEVEL_DEFAULT, LEVEL_ALL)

    @Volatile
    private var script: String? = null

    /** Categorie di SponsorBlock per un livello: sponsor; più autopromozione e richieste di iscrizione; più il resto. */
    fun categories(level: String): List<String> = when (level) {
        LEVEL_SPONSOR -> listOf("sponsor")
        LEVEL_ALL -> listOf("sponsor", "selfpromo", "interaction", "intro", "outro", "preview", "music_offtopic")
        else -> listOf("sponsor", "selfpromo", "interaction")
    }

    /** Per lo script nella pagina: le categorie in JSON, "[]" con SponsorBlock spento. */
    fun categoriesJson(): String =
        if (!AppSettings.sponsorBlock.value) "[]" else JSONArray(categories(AppSettings.sponsorBlockLevel.value)).toString()

    fun script(context: Context): String =
        script ?: context.assets.open("scripts/sponsorblock.js").bufferedReader().use { it.readText() }.also { script = it }
}
