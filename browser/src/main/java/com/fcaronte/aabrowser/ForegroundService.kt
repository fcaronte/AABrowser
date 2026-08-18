package com.fcaronte.aabrowser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class ForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        startNotification()
    }

    override fun onDestroy() {
        stopNotification()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.getAction() != null && intent.getAction() == "STOP") stopSelf()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startNotification() {
        try {
            val notificationManager =
                getSystemService<NotificationManager?>(NotificationManager::class.java)
            if (notificationManager != null &&
                notificationManager.getNotificationChannel(CHANNEL_ID) == null
            ) {
                val channelName: CharSequence = getString(R.string.notification_channel_name)
                val channelDescription = getString(R.string.notification_channel_description)

                val channel = NotificationChannel(
                    CHANNEL_ID,
                    channelName,
                    NotificationManager.IMPORTANCE_LOW
                )
                channel.setDescription(channelDescription)
                channel.setShowBadge(false)
                notificationManager.createNotificationChannel(channel)
            }

            val intent = Intent(this, javaClass)
            intent.setAction("STOP")
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            flags =
                flags or PendingIntent.FLAG_IMMUTABLE

            val pendingIntent = PendingIntent.getService(this, 0, intent, flags)

            val builder: NotificationCompat.Builder = NotificationCompat.Builder(this, CHANNEL_ID)

            builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getText(R.string.notification_title))
                .setContentText(getText(R.string.notification_text))
                .setContentIntent(pendingIntent)
                .setShowWhen(true)

            startForeground(
                ONGOING_NOTIFICATION_ID,
                builder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            )
        } catch (e: Exception) {
            Log.d(TAG, "startNotification exception : " + e.toString())
        }
    }

    private fun stopNotification() {
        try {
            stopForeground(true)
        } catch (e: Exception) {
            Log.d(TAG, "stopNotification exception : " + e.toString())
        }
    }

    companion object {
        private const val TAG = "ForegroundService"
        private const val CHANNEL_ID = "com.fcaronte.aabrowser"
        private const val ONGOING_NOTIFICATION_ID = 1

        fun startForegroundService(context: Context) {
            val intentService = Intent(context, ForegroundService::class.java)
            context.startForegroundService(
                intentService
            )
        }

        fun stopForegroundService(context: Context) {
            context.stopService(Intent(context, ForegroundService::class.java))
        }
    }
}
