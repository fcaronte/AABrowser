package com.fcaronte.aabrowser.smartphone

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

object ShizukuManager {
    private const val TAG = "ShizukuManager"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST_CODE = 1001

    /**
     * Checks whether Shizuku app is installed on the device via PackageManager or API check.
     */
    fun isShizukuInstalled(context: Context? = null): Boolean {
        try {
            if (context != null) {
                try {
                    context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
                    return true
                } catch (_: Throwable) {}
            }
            rikka.shizuku.Shizuku.getVersion()
            return true
        } catch (e: Throwable) {
            return false
        }
    }

    /**
     * Checks whether the Shizuku service is running and binder is alive.
     */
    fun isShizukuRunning(): Boolean {
        return try {
            rikka.shizuku.Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Checks whether permission has been granted to use Shizuku.
     */
    fun hasPermission(): Boolean {
        if (!isShizukuRunning()) return false
        return try {
            if (rikka.shizuku.Shizuku.isPreV11()) {
                false
            } else {
                rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error checking Shizuku permission", e)
            false
        }
    }

    /**
     * Requests Shizuku permission from the user.
     */
    fun requestPermission(requestCode: Int = PERMISSION_REQUEST_CODE) {
        try {
            if (isShizukuRunning() && !rikka.shizuku.Shizuku.isPreV11()) {
                if (rikka.shizuku.Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    rikka.shizuku.Shizuku.requestPermission(requestCode)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error requesting Shizuku permission", e)
        }
    }

    /**
     * Registers listeners for Shizuku binder and permission updates.
     */
    fun registerListeners(
        onPermissionResult: (Int, Int) -> Unit,
        onBinderReceived: () -> Unit
    ) {
        try {
            val permListener = object : rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    onPermissionResult(requestCode, grantResult)
                }
            }
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(permListener)

            val binderRecListener = rikka.shizuku.Shizuku.OnBinderReceivedListener {
                onBinderReceived()
            }
            rikka.shizuku.Shizuku.addBinderReceivedListener(binderRecListener)
        } catch (e: Throwable) {
            Log.e(TAG, "Error registering Shizuku listeners", e)
        }
    }
}
