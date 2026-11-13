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

    /** Stato di Shizuku per chi deve usarlo: la pagina "Altre App" lo mostra (verde solo con [READY]). */
    enum class Status { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    internal fun statusOf(installed: Boolean, running: Boolean, permission: Boolean): Status = when {
        running && permission -> Status.READY
        running -> Status.NO_PERMISSION
        installed -> Status.NOT_RUNNING
        else -> Status.NOT_INSTALLED
    }

    /**
     * Stato attuale. Se Shizuku gira basta una chiamata leggera; altrimenti si cerca se è installato e si prova ad
     * agganciare il suo binder (le chiamate al suo provider sono più pesanti).
     */
    fun status(context: Context): Status {
        if (isShizukuRunning()) return statusOf(installed = true, running = true, permission = hasPermission())
        val installed = isShizukuInstalled(context)
        val running = isShizukuRunning()
        return statusOf(installed, running, permission = running && hasPermission())
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
     * Ascolta il permesso dato o negato e l'arrivo del binder di Shizuku (quando lo si avvia). Restituisce la funzione
     * che toglie gli ascoltatori: va chiamata quando la schermata sparisce.
     */
    fun registerListeners(
        onPermissionResult: (Int, Int) -> Unit,
        onBinderReceived: () -> Unit
    ): () -> Unit {
        val permListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            onPermissionResult(requestCode, grantResult)
        }
        val binderListener = Shizuku.OnBinderReceivedListener { onBinderReceived() }
        try {
            Shizuku.addRequestPermissionResultListener(permListener)
            Shizuku.addBinderReceivedListener(binderListener)
        } catch (e: Throwable) {
            Log.e(TAG, "Error registering Shizuku listeners", e)
        }
        val remove: () -> Unit = {
            try {
                Shizuku.removeRequestPermissionResultListener(permListener)
                Shizuku.removeBinderReceivedListener(binderListener)
            } catch (e: Throwable) {
                Log.w(TAG, "Error removing Shizuku listeners", e)
            }
        }
        return remove
    }
}
