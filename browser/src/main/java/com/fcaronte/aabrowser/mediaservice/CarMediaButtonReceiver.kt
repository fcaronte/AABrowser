package com.fcaronte.aabrowser.mediaservice

import com.fcaronte.aabrowser.utils.AppLog
import android.content.Context
import android.content.Intent
import androidx.media.session.MediaButtonReceiver

class CarMediaButtonReceiver : MediaButtonReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        try {
            super.onReceive(context, intent)
        } catch (e: Exception) {
            AppLog.d(TAG, "onReceive exception : $e")
        }
    }

    companion object {
        private const val TAG = "CarMediaButtonReceiver"
    }
}
