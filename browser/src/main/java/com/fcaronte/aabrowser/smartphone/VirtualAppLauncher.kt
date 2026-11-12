package com.fcaronte.aabrowser.smartphone

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.util.Log

class VirtualAppLauncher(private val context: Context) {
    private val TAG = "VirtualAppLauncher"

    /**
     * Launches an app on the specified virtual display ID.
     */
    fun launchAppOnDisplay(packageName: String, displayId: Int): Boolean {
        if (displayId < 0) {
            Log.e(TAG, "Invalid display ID: $displayId")
            return false
        }

        try {
            val pm = context.packageManager
            val hiddenIntent = pm.getLaunchIntentForPackage(packageName)
            if (hiddenIntent == null) {
                Log.w(TAG, "No launch intent found for package: $packageName")
                return false
            }

            hiddenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

            val options = ActivityOptions.makeBasic().apply {
                launchDisplayId = displayId
            }

            context.startActivity(hiddenIntent, options.toBundle())
            Log.i(TAG, "Successfully launched $packageName on display $displayId")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Error launching $packageName on display $displayId", e)
            return false
        }
    }
}
