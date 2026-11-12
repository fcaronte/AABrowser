package com.fcaronte.aabrowser.smartphone

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.util.Log

class VirtualAppLauncher(private val context: Context) {
    private val TAG = "VirtualAppLauncher"

    /**
     * Launches an app on the specified virtual display ID, falling back to direct launch if needed.
     */
    fun launchAppOnDisplay(packageName: String, displayId: Int = 0): Boolean {
        try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                Log.w(TAG, "No launch intent found for package: $packageName")
                return false
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

            val options = if (displayId > 0) {
                ActivityOptions.makeBasic().apply {
                    launchDisplayId = displayId
                }
            } else null

            context.startActivity(intent, options?.toBundle())
            Log.i(TAG, "Successfully launched $packageName on display $displayId")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Error launching $packageName on display $displayId", e)
            return false
        }
    }
}
