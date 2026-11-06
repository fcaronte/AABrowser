package com.fcaronte.aabrowser.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        AppLog.d(TAG, "onReceive action: $action")

        // Esportato perché DOWNLOAD_COMPLETE arriva dal sistema; il download si avvia da UpdateStartReceiver
        when (action) {
            DownloadManager.ACTION_DOWNLOAD_COMPLETE -> {
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (downloadId != -1L) {
                    // Un APK da 60 MB da copiare e controllare: non sul thread principale
                    val pending = goAsync()
                    val appContext = context.applicationContext
                    thread {
                        try {
                            UpdateManager.handleDownloadComplete(appContext, downloadId)
                        } finally {
                            pending.finish()
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "UpdateDownloadReceiver"
    }
}
