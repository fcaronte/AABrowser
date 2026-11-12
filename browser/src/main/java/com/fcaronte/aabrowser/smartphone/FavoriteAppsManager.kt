package com.fcaronte.aabrowser.smartphone

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log

data class SmartphoneApp(
    val packageName: String,
    val appName: String,
    val icon: Drawable?
)

class FavoriteAppsManager(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("SMARTPHONE_FAVORITES", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "FavoriteAppsManager"
        private const val KEY_FAV_COUNT = "FavCount"
        private const val KEY_FAV_PREFIX = "FavPkg_"
    }

    /**
     * Loads the list of favorite package names.
     */
    fun loadFavoritePackageNames(): List<String> {
        val count = prefs.getInt(KEY_FAV_COUNT, 0)
        val list = mutableListOf<String>()
        for (i in 0 until count) {
            prefs.getString("$KEY_FAV_PREFIX$i", null)?.let { pkg ->
                if (pkg.isNotBlank()) {
                    list.add(pkg)
                }
            }
        }
        return list
    }

    /**
     * Saves the list of favorite package names.
     */
    fun saveFavoritePackageNames(packages: List<String>) {
        val editor = prefs.edit()
        editor.clear()
        editor.putInt(KEY_FAV_COUNT, packages.size)
        packages.forEachIndexed { index, pkg ->
            editor.putString("$KEY_FAV_PREFIX$index", pkg)
        }
        editor.apply()
    }

    /**
     * Adds or removes a package from favorites.
     */
    fun toggleFavorite(packageName: String): Boolean {
        val current = loadFavoritePackageNames().toMutableList()
        val isFavorite = current.contains(packageName)
        if (isFavorite) {
            current.remove(packageName)
        } else {
            current.add(packageName)
        }
        saveFavoritePackageNames(current)
        return !isFavorite
    }

    /**
     * Checks if an app package is in favorites.
     */
    fun isFavorite(packageName: String): Boolean {
        return loadFavoritePackageNames().contains(packageName)
    }

    /**
     * Retrieves all installed apps on the device that have a launcher intent.
     */
    fun getInstalledApps(): List<SmartphoneApp> {
        val pm = context.packageManager
        val mainIntent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }

        val appsMap = mutableMapOf<String, SmartphoneApp>()
        for (info in resolveInfos) {
            val packageName = info.activityInfo.packageName
            // Exclude our own app package to avoid recursion/launch loops
            if (packageName == context.packageName) continue

            if (!appsMap.containsKey(packageName)) {
                try {
                    val appName = info.loadLabel(pm)?.toString() ?: packageName
                    val icon = info.loadIcon(pm)
                    appsMap[packageName] = SmartphoneApp(packageName, appName, icon)
                } catch (e: Throwable) {
                    Log.e(TAG, "Error loading app info for $packageName", e)
                }
            }
        }

        return appsMap.values.sortedBy { it.appName.lowercase() }
    }
}
