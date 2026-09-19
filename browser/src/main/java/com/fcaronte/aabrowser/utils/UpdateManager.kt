package com.fcaronte.aabrowser.utils

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.fcaronte.aabrowser.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {
    private const val TAG = "UpdateManager"
    private const val REPO_URL = "https://api.github.com/repos/fcaronte/AABrowser/releases/latest"
    private const val DOWNLOAD_PAGE = "https://github.com/fcaronte/AABrowser/releases"
    private const val CHANNEL_ID = "com.fcaronte.aabrowser.updates"
    private const val NOTIFICATION_ID = 1001

    const val ACTION_START_UPDATE_DOWNLOAD = "com.fcaronte.aabrowser.START_UPDATE_DOWNLOAD"
    const val EXTRA_DOWNLOAD_URL = "extra_download_url"
    const val EXTRA_VERSION = "extra_version"
    private const val KING_INSTALLER_PACKAGE = "com.example.kinginstaller"

    private const val PREFS_NAME = "app_updates"
    private const val KEY_PENDING_DOWNLOAD_ID = "pending_download_id"
    private const val KEY_PENDING_APK_PATH = "pending_apk_path"

    private var isNotificationAlreadyShown = false
    var isBannerDismissed = false

    data class UpdateInfo(
        val isAvailable: Boolean,
        val latestVersion: String,
        val downloadUrl: String
    )

    suspend fun checkForUpdates(context: Context): UpdateInfo {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(REPO_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connect()

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val latestVersion = json.getString("tag_name").replace("v", "")

                    val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    val currentVersion = pInfo.versionName ?: "1.0"

                    // Recupera l'URL dell'APK se disponibile negli assets
                    var downloadUrl = DOWNLOAD_PAGE
                    val assets = json.optJSONArray("assets")
                    if (assets != null && assets.length() > 0) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            if (asset.getString("name").endsWith(".apk")) {
                                downloadUrl = asset.getString("browser_download_url")
                                break
                            }
                        }
                    }

                    UpdateInfo(
                        isAvailable = isVersionNewer(latestVersion, currentVersion),
                        latestVersion = "v$latestVersion",
                        downloadUrl = downloadUrl
                    )
                } else {
                    UpdateInfo(false, "", "")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for updates: ${e.message}")
                UpdateInfo(false, "", "")
            }
        }
    }

    private fun isVersionNewer(latest: String, current: String): Boolean {
        try {
            val latestParts = latest.split(".").map { it.toInt() }
            val currentParts = current.split(".").map { it.toInt() }

            val maxLength = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxLength) {
                val l = if (i < latestParts.size) latestParts[i] else 0
                val c = if (i < currentParts.size) currentParts[i] else 0
                if (l > c) return true
                if (l < c) return false
            }
        } catch (_: Exception) {
        }
        return false
    }

    private fun getApkFileName(url: String, version: String): String {
        val extracted = url.substringAfterLast('/').substringBefore('?')
        return if (extracted.endsWith(".apk", ignoreCase = true)) {
            extracted
        } else {
            val clean = version.replace("v", "")
            "AABrowser-v$clean.apk"
        }
    }

    fun startDownload(context: Context, info: UpdateInfo) {
        if (info.downloadUrl.isBlank()) {
            Log.e(TAG, "Cannot start download: downloadUrl is blank")
            return
        }

        try {
            val downloadManager =
                context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // 1. Rimuovi vecchio download da DownloadManager se ancora registrato
            val oldDownloadId = prefs.getLong(KEY_PENDING_DOWNLOAD_ID, -1L)
            if (oldDownloadId != -1L) {
                try {
                    downloadManager.remove(oldDownloadId)
                } catch (e: Exception) {
                    Log.w(TAG, "Impossibile rimuovere vecchio download ID $oldDownloadId: ${e.message}")
                }
            }

            // 2. Mantiene il nome file originale dall'URL di GitHub (es. AABrowser-v1.6.apk)
            val fileName = getApkFileName(info.downloadUrl, info.latestVersion)
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir.exists() && downloadsDir.isDirectory) {
                downloadsDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("AABrowser") && file.name.endsWith(".apk")) {
                        try {
                            file.delete()
                        } catch (e: Exception) {
                            Log.w(TAG, "Impossibile eliminare vecchio file APK: ${file.name}")
                        }
                    }
                }
            }

            val destinationFile = File(downloadsDir, fileName)
            if (destinationFile.exists()) {
                destinationFile.delete()
            }

            val request = DownloadManager.Request(info.downloadUrl.toUri()).apply {
                setTitle(fileName)
                setDescription(context.getString(R.string.downloading_update))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setMimeType("application/vnd.android.package-archive")
            }

            val downloadId = downloadManager.enqueue(request)

            prefs.edit()
                .putLong(KEY_PENDING_DOWNLOAD_ID, downloadId)
                .putString(KEY_PENDING_APK_PATH, destinationFile.absolutePath)
                .apply()

            Toast.makeText(
                context,
                context.getString(R.string.update_download_started),
                Toast.LENGTH_SHORT
            ).show()

            Log.d(TAG, "Enqueued download ID: $downloadId for file $fileName at ${destinationFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start download: ${e.message}", e)
            Toast.makeText(
                context,
                context.getString(R.string.update_download_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun handleDownloadComplete(context: Context, downloadId: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pendingId = prefs.getLong(KEY_PENDING_DOWNLOAD_ID, -2L)

        if (downloadId != pendingId) {
            Log.d(TAG, "Completed download $downloadId does not match pending update $pendingId")
            return
        }

        val apkPath = prefs.getString(KEY_PENDING_APK_PATH, null)
        prefs.edit().remove(KEY_PENDING_DOWNLOAD_ID).apply()

        val downloadManager =
            context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query)

        if (cursor != null && cursor.moveToFirst()) {
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (statusIndex != -1 && cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL) {
                val apkFile = if (apkPath != null) File(apkPath) else null

                val apkUri: Uri? = if (apkFile != null && apkFile.exists()) {
                    try {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            apkFile
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "FileProvider URI error: ${e.message}", e)
                        downloadManager.getUriForDownloadedFile(downloadId)
                    }
                } else {
                    downloadManager.getUriForDownloadedFile(downloadId)
                }

                if (apkUri != null) {
                    launchKingInstaller(context, apkUri)
                } else {
                    Log.e(TAG, "Failed to generate Uri for downloaded APK")
                }
            } else {
                Log.e(TAG, "Download finished but status was not successful")
            }
            cursor.close()
        }
    }

    fun launchKingInstaller(context: Context, apkUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            setClassName(KING_INSTALLER_PACKAGE, "$KING_INSTALLER_PACKAGE.MainActivity")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            Log.d(TAG, "Opening KingInstaller ($KING_INSTALLER_PACKAGE.MainActivity) for URI: $apkUri")
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "MainActivity component not found, trying general package intent...")
            val genericIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                setPackage(KING_INSTALLER_PACKAGE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(genericIntent)
            } catch (ex: Exception) {
                Log.e(TAG, "KingInstaller ($KING_INSTALLER_PACKAGE) not installed", ex)
                Toast.makeText(
                    context,
                    context.getString(R.string.kinginstaller_not_found),
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening KingInstaller: ${e.message}", e)
        }
    }

    fun showUpdateNotification(context: Context, info: UpdateInfo) {
        if (!info.isAvailable || isNotificationAlreadyShown) return

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Verifica permesso su Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Missing POST_NOTIFICATIONS permission")
                return
            }
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "App Updates",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for new app versions"
        }
        notificationManager.createNotificationChannel(channel)

        val intent = Intent(context, UpdateDownloadReceiver::class.java).apply {
            action = ACTION_START_UPDATE_DOWNLOAD
            putExtra(EXTRA_DOWNLOAD_URL, info.downloadUrl)
            putExtra(EXTRA_VERSION, info.latestVersion)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.update_available_title, info.latestVersion))
            .setContentText(context.getString(R.string.update_available_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_play_arrow_black_24dp,
                context.getString(R.string.download_button),
                pendingIntent
            )

        notificationManager.notify(NOTIFICATION_ID, builder.build())
        isNotificationAlreadyShown = true
    }
}
