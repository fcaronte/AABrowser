package com.fcaronte.aabrowser.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object BackupRestoreUtils {

    fun exportSettingsAndBookmarksToFile(context: Context, file: File): Boolean {
        try {
            val root = JSONObject()

            // Export BOOKMARKS prefs
            val bookmarksPrefs = context.getSharedPreferences("BOOKMARKS", Context.MODE_PRIVATE)
            val bookmarksJson = JSONObject()
            for ((key, value) in bookmarksPrefs.all) {
                try {
                    if (value is Set<*>) {
                        val arr = JSONArray()
                        value.forEach { item -> if (item != null) arr.put(item) }
                        bookmarksJson.put(key, arr)
                    } else if (value != null) {
                        bookmarksJson.put(key, value)
                    }
                } catch (_: Exception) {}
            }
            root.put("bookmarks", bookmarksJson)

            // Export aa_browser_settings prefs
            val settingsPrefs = context.getSharedPreferences("aa_browser_settings", Context.MODE_PRIVATE)
            val settingsJson = JSONObject()
            for ((key, value) in settingsPrefs.all) {
                try {
                    if (value is Set<*>) {
                        val arr = JSONArray()
                        value.forEach { item -> if (item != null) arr.put(item) }
                        settingsJson.put(key, arr)
                    } else if (value != null) {
                        settingsJson.put(key, value)
                    }
                } catch (_: Exception) {}
            }
            root.put("settings", settingsJson)

            file.parentFile?.mkdirs()
            file.writeText(root.toString(4))
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun importSettingsAndBookmarksFromFile(context: Context, file: File): Boolean {
        try {
            if (!file.exists()) return false
            val jsonString = file.readText()
            val root = JSONObject(jsonString)

            // Import BOOKMARKS
            if (root.has("bookmarks")) {
                val bookmarksJson = root.getJSONObject("bookmarks")
                val bookmarksPrefs = context.getSharedPreferences("BOOKMARKS", Context.MODE_PRIVATE).edit()
                bookmarksPrefs.clear()
                for (key in bookmarksJson.keys()) {
                    try {
                        val value = bookmarksJson.get(key)
                        when (value) {
                            is String -> bookmarksPrefs.putString(key, value)
                            is Int -> bookmarksPrefs.putInt(key, value)
                            is Long -> bookmarksPrefs.putLong(key, value)
                            is Boolean -> bookmarksPrefs.putBoolean(key, value)
                            is Float -> bookmarksPrefs.putFloat(key, value)
                            is Double -> bookmarksPrefs.putFloat(key, value.toFloat())
                            is Number -> bookmarksPrefs.putFloat(key, value.toFloat())
                            is JSONArray -> {
                                val set = mutableSetOf<String>()
                                for (i in 0 until value.length()) {
                                    value.optString(i)?.let { set.add(it) }
                                }
                                bookmarksPrefs.putStringSet(key, set)
                            }
                        }
                    } catch (_: Exception) {}
                }
                bookmarksPrefs.apply()
            }

            // Import Settings
            if (root.has("settings")) {
                val settingsJson = root.getJSONObject("settings")
                val settingsPrefs = context.getSharedPreferences("aa_browser_settings", Context.MODE_PRIVATE).edit()
                settingsPrefs.clear()
                for (key in settingsJson.keys()) {
                    try {
                        val value = settingsJson.get(key)
                        when (value) {
                            is String -> settingsPrefs.putString(key, value)
                            is Int -> settingsPrefs.putInt(key, value)
                            is Long -> settingsPrefs.putLong(key, value)
                            is Boolean -> settingsPrefs.putBoolean(key, value)
                            is Float -> settingsPrefs.putFloat(key, value)
                            is Double -> settingsPrefs.putFloat(key, value.toFloat())
                            is Number -> settingsPrefs.putFloat(key, value.toFloat())
                            is JSONArray -> {
                                val set = mutableSetOf<String>()
                                for (i in 0 until value.length()) {
                                    value.optString(i)?.let { set.add(it) }
                                }
                                settingsPrefs.putStringSet(key, set)
                            }
                        }
                    } catch (_: Exception) {}
                }
                settingsPrefs.apply()
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}
