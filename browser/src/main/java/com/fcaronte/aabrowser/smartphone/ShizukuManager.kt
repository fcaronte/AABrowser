package com.fcaronte.aabrowser.smartphone

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

object ShizukuManager {
    private const val TAG = "ShizukuManager"
    private const val PERMISSION_REQUEST_CODE = 1001

    private var permissionListener: Shizuku.OnRequestPermissionResultListener? = null
    private var binderReceivedListener: Shizuku.OnBinderReceivedListener? = null
    private var binderDeadListener: Shizuku.OnBinderDeadListener? = null

    /**
     * Checks whether Shizuku API is available (i.e. Shizuku app is installed on the device).
     */
    fun isShizukuInstalled(): Boolean {
        return try {
            Shizuku.getVersion()
            true
        } catch (e: Throwable) {
            false
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
        if (!isShizukuInstalled() || !isShizukuRunning()) return false
        return try {
            if (Shizuku.isPreV11()) {
                false
            } else {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
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
            if (isShizukuInstalled() && isShizukuRunning() && !Shizuku.isPreV11()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(requestCode)
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
        onBinderReceived: () -> Unit,
        onBinderDead: () -> Unit
    ) {
        try {
            val permListener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    onPermissionResult(requestCode, grantResult)
                }
            }
            permissionListener = permListener
            Shizuku.addRequestPermissionResultListener(permListener)

            val binderRecListener = Shizuku.OnBinderReceivedListener {
                onBinderReceived()
            }
            binderReceivedListener = binderRecListener
            Shizuku.addBinderReceivedListener(binderRecListener)

            val binderDListener = Shizuku.OnBinderDeadListener {
                onBinderDead()
            }
            binderDeadListener = binderDListener
            Shizuku.addBinderDeadListener(binderDListener)
        } catch (e: Throwable) {
            Log.e(TAG, "Error registering Shizuku listeners", e)
        }
    }

    /**
     * Unregisters Shizuku listeners to prevent memory leaks.
     */
    fun unregisterListeners() {
        try {
            permissionListener?.let { Shizuku.removeRequestPermissionResultListener(it) }
            binderReceivedListener?.let { Shizuku.removeBinderReceivedListener(it) }
            binderDeadListener?.let { Shizuku.removeBinderDeadListener(it) }
        } catch (e: Throwable) {
            Log.e(TAG, "Error unregistering Shizuku listeners", e)
        }
    }
}
