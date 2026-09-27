package com.fcaronte.aabrowser.utils

import com.fcaronte.aabrowser.utils.AppLog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        AppLog.d(TAG, "onReceive action: $action")

        when (action) {
            UpdateManager.ACTION_START_UPDATE_DOWNLOAD -> {
                val url = intent.getStringExtra(UpdateManager.EXTRA_DOWNLOAD_URL) ?: return
                val version = intent.getStringExtra(UpdateManager.EXTRA_VERSION) ?: ""
                val info = UpdateManager.UpdateInfo(
                    isAvailable = true,
                    latestVersion = version,
                    downloadUrl = url
                )
                UpdateManager.startDownload(context, info)
            }
            DownloadManager.ACTION_DOWNLOAD_COMPLETE -> {
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (downloadId != -1L) {
                    UpdateManager.handleDownloadComplete(context, downloadId)
                }
            }
        }
    }

    companion object {
        private const val TAG = "UpdateDownloadReceiver"
    }
}
