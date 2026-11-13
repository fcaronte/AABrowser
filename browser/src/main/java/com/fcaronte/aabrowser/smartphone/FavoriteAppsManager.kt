package com.fcaronte.aabrowser.smartphone

import android.content.Context
import android.content.SharedPreferences

/**
 * Le app del telefono con la stella (pagina "Altre App"): solo i nomi dei pacchetti, nell'ordine in cui sono state scelte.
 * Si aprono dalla home di CarWeb (Main e Wide). Elenco, nomi e icone stanno in [VirtualApps].
 */
class FavoriteAppsManager(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FAVORITES_PREFS_NAME, Context.MODE_PRIVATE)

    fun loadFavoritePackageNames(): List<String> {
        val count = prefs.getInt(KEY_FAV_COUNT, 0)
        val list = mutableListOf<String>()
        for (i in 0 until count) {
            prefs.getString("$KEY_FAV_PREFIX$i", null)?.takeIf { it.isNotBlank() && it !in list }?.let(list::add)
        }
        return list
    }

    fun saveFavoritePackageNames(packages: List<String>) {
        val oldCount = prefs.getInt(KEY_FAV_COUNT, 0).coerceAtLeast(0)
        prefs.edit().apply {
            repeat(oldCount) { remove("$KEY_FAV_PREFIX$it") }
            putInt(KEY_FAV_COUNT, packages.size)
            packages.forEachIndexed { index, pkg -> putString("$KEY_FAV_PREFIX$index", pkg) }
            apply()
        }
    }

    /** Aggiunge o toglie la stella; restituisce true se ora l'app è tra i preferiti. */
    fun setFavorite(packageName: String, favorite: Boolean): Boolean {
        val current = loadFavoritePackageNames().toMutableList()
        val present = current.contains(packageName)
        if (favorite && !present) current.add(packageName)
        if (!favorite && present) current.remove(packageName)
        if (favorite != present) saveFavoritePackageNames(current)
        return favorite
    }

    fun isFavorite(packageName: String): Boolean = loadFavoritePackageNames().contains(packageName)

    companion object {
        const val FAVORITES_PREFS_NAME = "SMARTPHONE_FAVORITES"
        private const val KEY_FAV_COUNT = "FavCount"
        private const val KEY_FAV_PREFIX = "FavPkg_"
    }
}
