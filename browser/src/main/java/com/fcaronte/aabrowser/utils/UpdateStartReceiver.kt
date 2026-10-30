package com.fcaronte.aabrowser.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * "Scarica" nella notifica di aggiornamento. Non esportato: lo raggiunge solo il PendingIntent dell'app, mentre
 * prima qualunque app poteva chiedere di scaricare (e passare a KingInstaller) una release qualsiasi.
 */
class UpdateStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateManager.ACTION_START_UPDATE_DOWNLOAD) return
        val url = intent.getStringExtra(UpdateManager.EXTRA_DOWNLOAD_URL) ?: return
        val version = intent.getStringExtra(UpdateManager.EXTRA_VERSION) ?: ""
        UpdateManager.startDownload(
            context,
            UpdateManager.UpdateInfo(isAvailable = true, latestVersion = version, downloadUrl = url)
        )
    }
}
