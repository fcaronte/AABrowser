package com.fcaronte.aabrowser.mediaservice

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media.session.MediaButtonReceiver

class CarMediaButtonReceiver : MediaButtonReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        try {
            super.onReceive(context, intent)
        } catch (e: Exception) {
            Log.d(TAG, "onReceive exception : $e")
        }
    }

    companion object {
        private const val TAG = "CarMediaButtonReceiver"
    }
}
