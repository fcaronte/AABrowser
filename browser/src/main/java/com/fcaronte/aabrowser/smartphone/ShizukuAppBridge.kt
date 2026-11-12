package com.fcaronte.aabrowser.smartphone

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import rikka.shizuku.Shizuku
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Async client for the Shizuku-owned display and selected-app input operations. */
object ShizukuAppBridge {
    private const val TAG = "ShizukuAppBridge"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var remote: IShizukuAppLauncher? = null
    private var connection: ServiceConnection? = null
    private var serviceArgs: Shizuku.UserServiceArgs? = null
    private var connectionFuture: CompletableFuture<IShizukuAppLauncher>? = null

    fun createDisplay(
        context: Context, surface: Surface, width: Int, height: Int, density: Int,
        onResult: (Int, String) -> Unit
    ) {
        executor.execute {
            val result = call(context) { it.createDisplay(surface, width, height, density) }
            val id = result.removePrefix("OK:").toIntOrNull() ?: -1
            mainHandler.post { onResult(id, result.removePrefix("OK:").removePrefix("ERROR:")) }
        }
    }

    fun updateDisplay(
        context: Context, displayId: Int, surface: Surface?, width: Int, height: Int, density: Int,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        executor.execute {
            val result = call(context) { it.updateDisplay(displayId, surface, width, height, density) }
            val success = result.startsWith("OK")
            if (!success) Log.w(TAG, "Display update failed: $result")
            onResult?.let { callback -> mainHandler.post { callback(success, result.removePrefix("ERROR:")) } }
        }
    }

    fun releaseDisplay(context: Context, displayId: Int) {
        if (displayId <= 0) return
        executor.execute {
            val result = call(context) { it.releaseDisplay(displayId) }
            if (!result.startsWith("OK")) Log.w(TAG, "Display release failed: $result")
        }
    }

    fun launchFavoriteApp(
        context: Context, packageName: String, displayId: Int,
        onResult: (Boolean, String) -> Unit
    ) {
        executor.execute {
            val result = call(context) { it.launchFavoriteApp(packageName, displayId) }
            val succeeded = result.startsWith("OK")
            mainHandler.post {
                onResult(succeeded, result.removePrefix("OK:").removePrefix("OK").removePrefix("ERROR:").trim())
            }
        }
    }

    fun tap(context: Context, displayId: Int, width: Int, height: Int, x: Int, y: Int) {
        executor.execute {
            val result = call(context) { it.tapOnActiveFavorite(displayId, width, height, x, y) }
            if (!result.startsWith("OK")) Log.w(TAG, "Tap injection failed: $result")
        }
    }

    fun swipe(
        context: Context, displayId: Int, width: Int, height: Int,
        x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int
    ) {
        executor.execute {
            val result = call(context) {
                it.swipeOnActiveFavorite(displayId, width, height, x1, y1, x2, y2, durationMs)
            }
            if (!result.startsWith("OK")) Log.w(TAG, "Swipe injection failed: $result")
        }
    }

    fun back(context: Context, displayId: Int) {
        executor.execute {
            val result = call(context) { it.backOnActiveFavorite(displayId) }
            if (!result.startsWith("OK")) Log.w(TAG, "Back injection failed: $result")
        }
    }

    private fun call(context: Context, operation: (IShizukuAppLauncher) -> String): String = try {
        operation(obtainRemote(context.applicationContext))
    } catch (error: Throwable) {
        Log.e(TAG, "Shizuku bridge operation failed", error)
        "ERROR:${error.javaClass.simpleName}: ${error.message.orEmpty()}"
    }

    private fun obtainRemote(context: Context): IShizukuAppLauncher {
        if (!ShizukuManager.isShizukuRunning()) throw IllegalStateException("Shizuku non è in esecuzione")
        if (!ShizukuManager.hasPermission()) throw SecurityException("Permesso Shizuku non concesso")

        val future: CompletableFuture<IShizukuAppLauncher> = synchronized(lock) {
            remote?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
            remote = null
            connectionFuture ?: CompletableFuture<IShizukuAppLauncher>().also { pending ->
                connectionFuture = pending
                pending.whenComplete { _, error ->
                    if (error != null) synchronized(lock) {
                        if (connectionFuture === pending) connectionFuture = null
                        remote = null
                    }
                }
                val args = Shizuku.UserServiceArgs(
                    ComponentName(context, ShizukuAppBridgeService::class.java)
                ).processNameSuffix("app_bridge").daemon(false)
                val serviceConnection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        val service = binder?.let(IShizukuAppLauncher.Stub::asInterface)
                        if (service == null) {
                            pending.completeExceptionally(IllegalStateException("Shizuku binder nullo"))
                        } else {
                            synchronized(lock) { remote = service }
                            pending.complete(service)
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        synchronized(lock) {
                            remote = null
                            connectionFuture = null
                        }
                    }
                }
                serviceArgs = args
                connection = serviceConnection
                try {
                    Shizuku.bindUserService(args, serviceConnection)
                } catch (error: Throwable) {
                    pending.completeExceptionally(error)
                }
            }
        }
        return future.get(20, TimeUnit.SECONDS)
    }
}