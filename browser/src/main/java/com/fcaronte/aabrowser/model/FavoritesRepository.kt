package com.fcaronte.aabrowser.model

import android.content.Context
import android.content.SharedPreferences
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.utils.LocalPlayer
import java.util.UUID

class FavoritesRepository(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("BOOKMARKS", Context.MODE_PRIVATE)

    fun loadFavorites(): List<FavoriteSite> {
        val count = prefs.getInt("BookmarksCount", 0)
        val list = mutableListOf<FavoriteSite>()
        var migrationNeeded = false
        fun readZoom(key: String): Float? {
            val storedValue = prefs.all[key] ?: return null
            val zoom = storedValue as? Number
                ?: throw IllegalStateException("Stored favorite zoom '$key' is not numeric")
            if (storedValue !is Float) migrationNeeded = true
            return zoom.toFloat()
        }

        for (i in 0 until count) {
            val idFromPrefs = prefs.getString("BookmarkId$i", null)
            val id = idFromPrefs ?: UUID.randomUUID().toString()
            if (idFromPrefs == null) migrationNeeded = true
            
            var name = prefs.getString("BookmarkName$i", "") ?: ""
            var url = prefs.getString("BookmarkUrl$i", "") ?: ""
            val color = prefs.getLong("BookmarkColor$i", 0xFF2196F3)
            var faviconUrl = prefs.getString("BookmarkFavicon$i", null)

            // Il lettore di prova su streamlit è stato sostituito dal lettore locale dell'app
            if (url.startsWith(TEST_PLAYER_URL)) {
                url = LocalPlayer.BASE_URL
                name = context.getString(R.string.favorite_default_name_1)
                faviconUrl = null
                migrationNeeded = true
            }
            // Il lettore locale si chiamava "AABrowser Player" o "CarWeb Media"
            if (url == LocalPlayer.BASE_URL && (name == OLD_PLAYER_NAME || name == "CarWeb Media")) {
                name = context.getString(R.string.favorite_default_name_1)
                migrationNeeded = true
            }

            // Indirizzi che non sono pagine (javascript:, file:, about:, ...) non restano tra i preferiti, nemmeno
            // se arrivano da un backup importato
            if (isUnsafeUrl(url)) {
                migrationNeeded = true
                continue
            }

            val isDesktopMode = if (prefs.contains("BookmarkDesktop$i")) prefs.getBoolean("BookmarkDesktop$i", false) else null
            val mobileZoom = readZoom("BookmarkMobileZoom$i")
            val desktopZoom = readZoom("BookmarkDesktopZoom$i")

            list.add(FavoriteSite(id, name, url, color, faviconUrl, isDesktopMode, mobileZoom, desktopZoom))
        }

        // If empty, provide defaults
        if (list.isEmpty()) {
            val defaults = getDefaultFavorites()
            save(defaults, DEFAULTS_VERSION)
            return defaults
        }

        val knownVersion = storedDefaultsVersion()
        val defaultsAdded = addNewDefaults(list, knownVersion)
        if (migrationNeeded || defaultsAdded || knownVersion < DEFAULTS_VERSION) {
            save(list, maxOf(knownVersion, DEFAULTS_VERSION))
        }

        return list
    }

    /**
     * Preferiti predefiniti arrivati con gli aggiornamenti: chi ha già la sua lista se li trova una volta in fondo
     * (se poi li toglie non tornano). I preferiti fissi ([isFixed]) invece tornano sempre. true se ha aggiunto.
     */
    private fun addNewDefaults(list: MutableList<FavoriteSite>, knownVersion: Int): Boolean {
        val present = list.map { normalizeUrl(it.url) }.toMutableSet()
        var added = false
        getDefaultFavorites().forEach { default ->
            val isNew = (DEFAULTS_ADDED_IN[default.id] ?: FIRST_DEFAULTS_VERSION) > knownVersion
            if ((isNew || isFixed(default)) && present.add(normalizeUrl(default.url))) {
                // Id nuovo: quello del predefinito può già esistere tra i preferiti dell'utente
                insertDefaultFavorite(list, default.copy(id = UUID.randomUUID().toString()))
                added = true
            }
        }
        return added
    }

    private fun storedDefaultsVersion(): Int =
        (prefs.all[KEY_DEFAULTS_VERSION] as? Number)?.toInt() ?: FIRST_DEFAULTS_VERSION

    fun getDefaultFavorites(): List<FavoriteSite> {
        return listOf(
            FavoriteSite("0", context.getString(R.string.favorite_default_name_0), context.getString(R.string.favorite_default_url_0), 0xFF00BCD4),
            FavoriteSite("1", context.getString(R.string.favorite_default_name_1), context.getString(R.string.favorite_default_url_1), 0xFFE91E63),
            FavoriteSite("2", context.getString(R.string.favorite_default_name_2), context.getString(R.string.favorite_default_url_2), 0xFF00838F),
            FavoriteSite("3", context.getString(R.string.favorite_default_name_3), context.getString(R.string.favorite_default_url_3), 0xFF4285F4),
            FavoriteSite("4", context.getString(R.string.favorite_default_name_4), context.getString(R.string.favorite_default_url_4), 0xFFFF0000),
            FavoriteSite("5", context.getString(R.string.favorite_default_name_5), context.getString(R.string.favorite_default_url_5), 0xFFFF0000),
            FavoriteSite("6", context.getString(R.string.favorite_default_name_6), context.getString(R.string.favorite_default_url_6), 0xFF25D366, null, true, null, 0.9f),
            FavoriteSite("7", context.getString(R.string.favorite_default_name_7), context.getString(R.string.favorite_default_url_7), 0xFF1DB954, null, false, null, 1.0f),
            FavoriteSite("8", context.getString(R.string.favorite_default_name_8), context.getString(R.string.favorite_default_url_8), 0xFF0088CC, null, true, null, 0.9f),
            FavoriteSite("9", context.getString(R.string.favorite_default_name_9), context.getString(R.string.favorite_default_url_9), 0xFFFF0000),
            FavoriteSite("10", context.getString(R.string.favorite_default_name_10), context.getString(R.string.favorite_default_url_10), 0xFFFF9800),
            FavoriteSite("11", context.getString(R.string.favorite_default_name_11), context.getString(R.string.favorite_default_url_11), 0xFF9C27B0)
        )
    }

    fun saveFavorites(favorites: List<FavoriteSite>) = save(favorites, storedDefaultsVersion())

    private fun save(favorites: List<FavoriteSite>, defaultsVersion: Int) {
        prefs.edit().apply {
            clear()
            putInt(KEY_DEFAULTS_VERSION, defaultsVersion)
            putInt("BookmarksCount", favorites.size)
            favorites.forEachIndexed { i, site ->
                putString("BookmarkId$i", site.id)
                putString("BookmarkName$i", site.name)
                putString("BookmarkUrl$i", site.url)
                putLong("BookmarkColor$i", site.color)
                putString("BookmarkFavicon$i", site.faviconUrl)
                site.isDesktopMode?.let { putBoolean("BookmarkDesktop$i", it) }
                site.mobileZoom?.let { putFloat("BookmarkMobileZoom$i", it) }
                site.desktopZoom?.let { putFloat("BookmarkDesktopZoom$i", it) }
            }
            apply()
        }
    }

    fun resetToDefaults(): List<FavoriteSite> {
        val defaults = getDefaultFavorites()
        save(defaults, DEFAULTS_VERSION)
        return defaults
    }

    companion object {
        private const val TEST_PLAYER_URL = "https://web-player.streamlit.app"
        private const val OLD_PLAYER_NAME = "AABrowser Player"

        // I preferiti predefiniti usano ID numerici consecutivi nell'ordine della dashboard; l'aggiunta o il
        // riordino può rinumerarli. La migrazione riconosce le voci tramite URL normalizzato e versione, non l'ID.
        // Il preferito DAZN è stato rimosso; chi lo aveva già mantiene la propria voce salvata.
        private const val KEY_DEFAULTS_VERSION = "DefaultsVersion"
        private const val FIRST_DEFAULTS_VERSION = 1 // predefiniti fino alla 2.2 (e liste salvate senza versione)
        private const val DEFAULTS_VERSION = 6
        private val DEFAULTS_ADDED_IN = mapOf(
            "0" to 5, // Media (2.5)
            "1" to 5, // IPTV (2.5)
            "2" to 6 // Radio
        )

        private val UNSAFE_SCHEMES = listOf("javascript:", "vbscript:", "data:", "file:", "content:", "intent:", "about:")

        fun isUnsafeUrl(url: String): Boolean = url.trim().lowercase().let { u -> UNSAFE_SCHEMES.any { u.startsWith(it) } }

        /** CarWeb Media è fisso: non si può eliminare né cambiare indirizzo, e se manca torna. */
        fun isFixed(site: FavoriteSite): Boolean = LocalPlayer.isPlayerUrl(site.url)

        fun insertDefaultFavorite(favorites: MutableList<FavoriteSite>, site: FavoriteSite) {
            val isRadio = normalizeUrl(site.url) == normalizeUrl("${LocalPlayer.BASE_URL}radio")
            if (!isRadio) {
                favorites.add(site)
                return
            }
            val mediaUrls = setOf(
                normalizeUrl(LocalPlayer.BASE_URL),
                normalizeUrl("${LocalPlayer.BASE_URL}tv")
            )
            val insertionIndex = favorites.indices
                .filter { normalizeUrl(favorites[it].url) in mediaUrls }
                .maxOrNull()
                ?.plus(1)
                ?: favorites.size
            favorites.add(insertionIndex, site)
        }

        fun normalizeUrl(url: String): String {
            return url.lowercase()
                .replace("https://", "")
                .replace("http://", "")
                .replace("www.", "")
                .trimEnd('/')
        }
    }
}
