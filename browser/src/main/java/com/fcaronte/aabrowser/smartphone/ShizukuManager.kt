package com.fcaronte.aabrowser.smartphone

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

object ShizukuManager {
    private const val TAG = "ShizukuManager"
    private const val PERMISSION_REQUEST_CODE = 1001

    private val SHIZUKU_PACKAGES = arrayOf(
        "moe.shizuku.privileged.api",
        "moe.shizuku.manager",
        "moe.shizuku.next",
        "ri.shizuku.server",
        "rikka.shizuku"
    )

    private val CONTENT_URIS = arrayOf(
        "content://moe.shizuku.privileged.api",
        "content://moe.shizuku.manager",
        "content://moe.shizuku.next",
        "content://ri.shizuku.server",
        "content://rikka.shizuku"
    )

    /**
     * Attempts to query Shizuku content providers directly to obtain and attach the live IBinder.
     */
    fun tryBindShizuku(context: Context): Boolean {
        if (isShizukuRunning()) return true
        val cr = context.contentResolver
        for (uriString in CONTENT_URIS) {
            try {
                val uri = Uri.parse(uriString)
                val reply = cr.call(uri, "BINDER", null, null)
                if (reply != null) {
                    reply.classLoader = ShizukuManager::class.java.classLoader
                    val binder = reply.getBinder("shizuku:binder")
                    if (binder != null && binder.isBinderAlive) {
                        try {
                            val method = Shizuku::class.java.getDeclaredMethod("attachBinder", IBinder::class.java)
                            method.isAccessible = true
                            method.invoke(null, binder)
                            Log.i(TAG, "Successfully attached Shizuku binder from $uriString")
                            return true
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error attaching Shizuku binder via reflection", e)
                        }
                    }
                }
            } catch (e: Throwable) {
                // try next authority
            }
        }
        return isShizukuRunning()
    }

    /**
     * Checks whether Shizuku or Shizuku Next app is installed on the device via PackageManager or API check.
     */
    fun isShizukuInstalled(context: Context? = null): Boolean {
        if (context != null) {
            val pm = context.packageManager
            for (pkg in SHIZUKU_PACKAGES) {
                try {
                    pm.getPackageInfo(pkg, 0)
                    return true
                } catch (_: Throwable) {}
            }
            tryBindShizuku(context)
        }
        return try {
            Shizuku.getVersion() > 0
        } catch (_: Throwable) {
            try {
                Shizuku.pingBinder()
            } catch (_: Throwable) {
                false
            }
        }
    }

    /**
     * Checks whether the Shizuku service is running and binder is alive.
     */
    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Checks whether permission has been granted to use Shizuku.
     */
    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
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
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(requestCode)
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
            val permListener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    onPermissionResult(requestCode, grantResult)
                }
            }
            Shizuku.addRequestPermissionResultListener(permListener)

            val binderRecListener = Shizuku.OnBinderReceivedListener {
                onBinderReceived()
            }
            Shizuku.addBinderReceivedListener(binderRecListener)
        } catch (e: Throwable) {
            Log.e(TAG, "Error registering Shizuku listeners", e)
        }
    }
}
