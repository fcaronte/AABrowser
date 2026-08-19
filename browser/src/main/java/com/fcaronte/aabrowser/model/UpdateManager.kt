package com.fcaronte.aabrowser.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fcaronte.aabrowser.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {
    private const val TAG = "UpdateManager"
    private const val REPO_URL = "https://api.github.com/repos/fcaronte/AABrowser/releases/latest"
    private const val DOWNLOAD_PAGE = "https://github.com/fcaronte/AABrowser/releases"
    private const val CHANNEL_ID = "com.fcaronte.aabrowser.updates"
    private const val NOTIFICATION_ID = 1001

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
        } catch (_: Exception) {}
        return false
    }

    fun openDownloadPage(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening download page: ${e.message}")
        }
    }

    fun showUpdateNotification(context: Context, info: UpdateInfo) {
        if (!info.isAvailable || isNotificationAlreadyShown) return

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Verifica permesso su Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Missing POST_NOTIFICATIONS permission")
                return
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new app versions"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        val pendingIntent = PendingIntent.getActivity(
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
