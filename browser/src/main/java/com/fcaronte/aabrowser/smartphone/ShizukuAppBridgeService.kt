package com.fcaronte.aabrowser.smartphone

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.Surface
import androidx.annotation.Keep
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/** Shizuku user service that owns trusted virtual displays and their selected app launches. */
class ShizukuAppBridgeService : IShizukuAppLauncher.Stub {
    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()
    private val activePackages = ConcurrentHashMap<Int, String>()
    private val appContext: Context?
    private val shellContext: Context = createShellContext()
    @Volatile private var displayManager: DisplayManager? = null

    @Keep
    constructor() : super() {
        appContext = currentApplicationContext()
    }

    @Keep
    constructor(context: Context) : super() {
        appContext = context.applicationContext
    }

    override fun createDisplay(surface: Surface?, width: Int, height: Int, density: Int): String {
        if (surface == null || !surface.isValid) return "ERROR: display surface is invalid"
        if (width !in 320..4096 || height !in 320..4096 || density !in 80..1000) {
            return "ERROR: invalid display dimensions"
        }
        return try {
            val base = FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH
            val candidates = buildList {
                if (Build.VERSION.SDK_INT >= 34) {
                    add(base or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED or FLAG_OWN_FOCUS)
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    add(base or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED)
                }
                add(base or FLAG_TRUSTED)
                add(base)
            }
            var failure: Throwable? = null
            for (flags in candidates) {
                try {
                    val display = getDisplayManager().createVirtualDisplay(
                        "AABrowserShizukuDisplay", width, height, density, surface, flags
                    ) ?: continue
                    val id = display.display?.displayId
                    if (id == null || id <= 0) {
                        display.release()
                        continue
                    }
                    displays[id] = display
                    Log.i(TAG, "Created display id=$id flags=0x${flags.toString(16)} ${width}x$height@$density")
                    return "OK:$id"
                } catch (error: SecurityException) {
                    failure = error
                    Log.w(TAG, "Display flags 0x${flags.toString(16)} rejected", error)
                } catch (error: IllegalArgumentException) {
                    failure = error
                    Log.w(TAG, "Display flags 0x${flags.toString(16)} rejected", error)
                }
            }
            "ERROR: unable to create display: ${failure?.let(::describe).orEmpty()}"
        } catch (error: Throwable) {
            Log.e(TAG, "Create display failed", error)
            "ERROR:${describe(error)}"
        }
    }

    override fun updateDisplay(displayId: Int, surface: Surface?, width: Int, height: Int, density: Int): String {
        val display = displays[displayId] ?: return "ERROR: CarWeb display is not active"
        if (width !in 320..4096 || height !in 320..4096 || density !in 80..1000) {
            return "ERROR: invalid display dimensions"
        }
        return try {
            display.setSurface(surface?.takeIf { it.isValid })
            display.resize(width, height, density)
            "OK"
        } catch (error: Throwable) {
            Log.e(TAG, "Update display failed id=$displayId", error)
            "ERROR:${describe(error)}"
        }
    }

    override fun releaseDisplay(displayId: Int): String {
        val display = displays.remove(displayId) ?: return "OK"
        return try {
            display.release()
            activePackages.remove(displayId)
            Log.i(TAG, "Released display id=$displayId")
            "OK"
        } catch (error: Throwable) {
            Log.e(TAG, "Release display failed id=$displayId", error)
            "ERROR:${describe(error)}"
        }
    }

    override fun launchFavoriteApp(packageName: String, displayId: Int): String {
        val display = displays[displayId] ?: return "ERROR: CarWeb display is not active"
        val context = appContext ?: return "ERROR: Shizuku user service has no app context"
        if (!PACKAGE_NAME.matcher(packageName).matches()) return "ERROR: invalid package name"
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return "ERROR: no launcher activity found for $packageName"
            val component = intent.component
                ?: return "ERROR: launcher activity is unresolved for $packageName"
            intent.setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(display.display.displayId).toBundle()
            val result = startActivityAsShell(intent, options)
            if (result < 0) return "ERROR: activity manager rejected launch ($result)"
            activePackages[displayId] = packageName
            "OK"
        } catch (error: Throwable) {
            Log.e(TAG, "Launch failed package=$packageName display=$displayId", error)
            "ERROR:${describe(error)}"
        }
    }

    override fun tapOnActiveFavorite(displayId: Int, width: Int, height: Int, x: Int, y: Int): String {
        if (!hasActivePackage(displayId)) return "ERROR: no selected app on CarWeb display"
        if (width !in 1..4096 || height !in 1..4096) return "ERROR: invalid display size"
        return runFixedCommand(
            "/system/bin/input", "-d", displayId.toString(), "tap",
            x.coerceIn(0, width - 1).toString(), y.coerceIn(0, height - 1).toString()
        )
    }

    override fun swipeOnActiveFavorite(
        displayId: Int, width: Int, height: Int,
        x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int
    ): String {
        if (!hasActivePackage(displayId)) return "ERROR: no selected app on CarWeb display"
        if (width !in 1..4096 || height !in 1..4096) return "ERROR: invalid display size"
        return runFixedCommand(
            "/system/bin/input", "-d", displayId.toString(), "swipe",
            x1.coerceIn(0, width - 1).toString(), y1.coerceIn(0, height - 1).toString(),
            x2.coerceIn(0, width - 1).toString(), y2.coerceIn(0, height - 1).toString(),
            durationMs.coerceIn(30, 1200).toString()
        )
    }

    override fun backOnActiveFavorite(displayId: Int): String {
        if (!hasActivePackage(displayId)) return "ERROR: no selected app on CarWeb display"
        return runFixedCommand("/system/bin/input", "-d", displayId.toString(), "keyevent", "KEYCODE_BACK")
    }

    private fun hasActivePackage(displayId: Int): Boolean {
        return displays.containsKey(displayId) && activePackages.containsKey(displayId)
    }

    private fun getDisplayManager(): DisplayManager {
        displayManager?.let { return it }
        return synchronized(this) {
            displayManager ?: DisplayManager::class.java.getDeclaredConstructor(Context::class.java).let { constructor ->
                constructor.isAccessible = true
                constructor.newInstance(shellContext).also { displayManager = it }
            }
        }
    }

    private fun startActivityAsShell(intent: Intent, options: android.os.Bundle): Int {
        val activityTaskManager = if (Build.VERSION.SDK_INT >= 29) {
            Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        } else {
            ActivityManager::class.java.getMethod("getService").invoke(null)
        }
        for (method in activityTaskManager.javaClass.methods) {
            if (method.name != "startActivity") continue
            val parameters = method.parameterTypes
            val arguments: Array<Any?> = when {
                parameters.size == 11 && parameters[3] == Intent::class.java ->
                    arrayOf(null, SHELL_PACKAGE, null, intent, null, null, null, 0, 0, null, options)
                parameters.size == 10 && parameters[2] == Intent::class.java ->
                    arrayOf(null, SHELL_PACKAGE, intent, null, null, null, 0, 0, null, options)
                else -> continue
            }
            return method.invoke(activityTaskManager, *arguments) as Int
        }
        error("No compatible ActivityTaskManager.startActivity method")
    }

    private fun runFixedCommand(vararg args: String): String = try {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        if (exitCode == 0 && !output.startsWith("Error:") && !output.contains("Permission Denial")) "OK:$output"
        else "ERROR:${output.ifBlank { "command failed ($exitCode)" }}"
    } catch (error: Throwable) {
        Log.e(TAG, "Restricted input operation failed", error)
        "ERROR:${describe(error)}"
    }

    private fun createShellContext(): Context {
        val base = runCatching {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getMethod("currentActivityThread").invoke(null)
                ?: threadClass.getMethod("systemMain").invoke(null)
            threadClass.getMethod("getSystemContext").invoke(thread) as Context
        }.getOrElse { error -> throw IllegalStateException("Cannot obtain system context for shell display", error) }
        return object : ContextWrapper(base) {
            override fun getPackageName(): String = SHELL_PACKAGE
            override fun getOpPackageName(): String = SHELL_PACKAGE
            @android.annotation.SuppressLint("NewApi")
            override fun getAttributionSource(): AttributionSource =
                if (Build.VERSION.SDK_INT >= 31) {
                    AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
                } else {
                    super.getAttributionSource()
                }
            override fun getApplicationContext(): Context = this
        }
    }

    private fun currentApplicationContext(): Context? = runCatching {
        val threadClass = Class.forName("android.app.ActivityThread")
        threadClass.getDeclaredMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()

    private fun describe(error: Throwable): String {
        val actual = if (error is InvocationTargetException && error.cause != null) error.cause!! else error
        return "${actual.javaClass.simpleName}: ${actual.message.orEmpty()}"
    }

    companion object {
        private const val TAG = "ShizukuAppBridge"
        private const val SHELL_PACKAGE = "com.android.shell"
        private const val FLAG_PUBLIC = 1
        private const val FLAG_PRESENTATION = 1 shl 1
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val FLAG_OWN_FOCUS = 1 shl 14
        private val PACKAGE_NAME = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
    }
}