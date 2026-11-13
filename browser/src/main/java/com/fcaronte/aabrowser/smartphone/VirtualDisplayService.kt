package com.fcaronte.aabrowser.smartphone

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.UiAutomation
import android.content.AttributionSource
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Bundle
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.Keep
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Servizio nel processo di Shizuku, con i diritti dell'utente shell (uid 2000): quelli di "adb shell" e la tecnica
 * di `scrcpy --new-display`. Qui non valgono i limiti delle API nascoste, che valgono solo per le app.
 *
 * Fa quattro cose per i display delle app del telefono (più display insieme: uno per ogni scheda o schermata):
 *  - crea display virtuali **fidati e pubblici** (ADD_TRUSTED_DISPLAY), su cui qualunque app può aprire le sue attività,
 *    con un gruppo di display proprio, "sempre sbloccato" e il suo focus dove Android lo permette;
 *  - ci avvia l'app e ne chiude i task;
 *  - ci inietta tocchi e tasti con il display giusto (INJECT_EVENTS), un evento alla volta, senza lanciare processi;
 *  - regola la tastiera del display (criterio IME) e tiene sveglio il telefono quando serve.
 *
 * La tecnica e le chiamate nascoste sono quelle di AnyAuto (GitMag, GPL-3.0), che le ha provate su Android 10-17;
 * il codice è riscritto per CarWeb. Il processo muore con quello dell'app (daemon spento) e con lui i display.
 */
class VirtualDisplayService @Keep constructor(base: Context?) : IVirtualDisplayService.Stub() {

    // Shizuku prova prima il costruttore con il Context, poi questo
    @Keep
    constructor() : this(null)

    private val context: Context = ShellContext(base ?: systemContext())

    private class Entry(
        val display: VirtualDisplay,
        var surface: Surface?,
        var width: Int,
        var height: Int,
        var dpi: Int,
        val flags: Int,
        val keyboardOnDisplay: Boolean
    )

    private val entries = HashMap<Int, Entry>()
    private var displayManager: DisplayManager? = null

    // Evento di input: i metodi si cercano una volta sola
    private var inputManager: Any? = null
    private var injectMethod: Method? = null
    private var setDisplayIdMethod: Method? = null

    private var a11yThread: HandlerThread? = null
    private val a11yLock = Any()
    private var phoneLock: PowerManager.WakeLock? = null

    override fun destroy() {
        synchronized(this) { releaseAll() }
        System.exit(0)
    }

    // ------------------------------------------------------------------ display

    @Synchronized
    override fun createDisplay(surface: Surface?, width: Int, height: Int, dpi: Int, keyboardOnDisplay: Boolean): String {
        if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE || dpi !in 60..1000) return "ERROR:invalid display size"
        return try {
            val sdk = Build.VERSION.SDK_INT
            // Prima l'insieme completo, poi si tolgono un passo alla volta i flag nuovi o che chiedono permessi
            val candidates = intArrayOf(
                if (sdk >= 34) BASE or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED or OWN_FOCUS else -1,
                if (sdk >= 33) BASE or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED else -1,
                BASE or TRUSTED,
                BASE
            )
            var failure: Exception? = null
            for (flags in candidates) {
                if (flags < 0) continue
                try {
                    val virtualDisplay = displayManager().createVirtualDisplay(
                        "CarWebApps", width, height, dpi, surface?.takeIf { it.isValid }, flags
                    ) ?: continue
                    val id = virtualDisplay.display?.displayId ?: -1
                    if (id <= 0) {
                        virtualDisplay.release()
                        continue
                    }
                    entries[id] = Entry(virtualDisplay, surface, width, height, dpi, flags, keyboardOnDisplay)
                    keepOrientation(id)
                    applyKeyboardPolicy(id, keyboardOnDisplay)
                    Log.i(TAG, "display $id ${width}x$height@$dpi flags 0x${flags.toString(16)}")
                    return "OK:$id"
                } catch (error: SecurityException) {
                    failure = error
                    Log.i(TAG, "flags 0x${flags.toString(16)} refused: $error")
                } catch (error: IllegalArgumentException) {
                    failure = error
                    Log.i(TAG, "flags 0x${flags.toString(16)} refused: $error")
                }
            }
            "ERROR:${failure?.let(::describe) ?: "createVirtualDisplay returned null"}"
        } catch (error: Throwable) {
            Log.w(TAG, "createDisplay failed", error)
            "ERROR:${describe(error)}"
        }
    }

    @Synchronized
    override fun updateDisplay(displayId: Int, surface: Surface?, width: Int, height: Int, dpi: Int): String {
        val entry = entries[displayId] ?: return "ERROR:display $displayId is not open"
        if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE || dpi !in 60..1000) return "ERROR:invalid display size"
        return try {
            val old = entry.surface
            val next = surface?.takeIf { it.isValid }
            entry.display.setSurface(next)
            entry.surface = next
            // La copia di questo processo della superficie di prima: quella dell'app la rilascia l'app
            if (old != null && old !== next) old.release()
            if (width != entry.width || height != entry.height || dpi != entry.dpi) {
                entry.display.resize(width, height, dpi)
                entry.width = width
                entry.height = height
                entry.dpi = dpi
            }
            "OK"
        } catch (error: Throwable) {
            Log.w(TAG, "updateDisplay failed", error)
            "ERROR:${describe(error)}"
        }
    }

    @Synchronized
    override fun releaseDisplay(displayId: Int): String {
        val entry = entries.remove(displayId) ?: return "OK"
        return try {
            entry.display.release()
            entry.surface?.release()
            Log.i(TAG, "display $displayId released")
            "OK"
        } catch (error: Throwable) {
            Log.w(TAG, "releaseDisplay failed", error)
            "ERROR:${describe(error)}"
        }
    }

    private fun releaseAll() {
        keepPhoneAwake(false)
        for ((id, entry) in entries.toMap()) {
            runCatching { entry.display.release() }
            runCatching { entry.surface?.release() }
            Log.i(TAG, "display $id released (service closing)")
        }
        entries.clear()
    }

    // ------------------------------------------------------------------ avvio e chiusura delle app

    /**
     * Avvia l'attività sul display, o porta lì il suo task (verificato su Android 17). Con [displayId] 0 o meno
     * parte sullo schermo del telefono: serve anche per la schermata di attesa dell'app, che dalla shell non incontra
     * i limiti degli avvii dallo sfondo.
     */
    @Synchronized
    override fun launch(displayId: Int, component: ComponentName?): String {
        if (component == null) return "ERROR:no component"
        if (displayId > 0 && !entries.containsKey(displayId)) return "ERROR:display $displayId is not open"
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val options: Bundle = ActivityOptions.makeBasic().apply { if (displayId > 0) launchDisplayId = displayId }.toBundle()
        return try {
            val service = activityTaskService()
            for (method in service.javaClass.methods) {
                if (method.name != "startActivity") continue
                val p = method.parameterTypes
                val args: Array<Any?> = when {
                    // API 30+: (caller, callingPackage, callingFeatureId, intent, resolvedType, resultTo, resultWho,
                    //           requestCode, flags, profilerInfo, options)
                    p.size == 11 && p[3] == Intent::class.java ->
                        arrayOf(null, SHELL, null, intent, null, null, null, 0, 0, null, options)
                    // API 29: lo stesso senza callingFeatureId
                    p.size == 10 && p[2] == Intent::class.java ->
                        arrayOf(null, SHELL, intent, null, null, null, 0, 0, null, options)
                    else -> continue
                }
                // 0 avviata; 1, 2 e 3 portata davanti o consegnata all'istanza in cima
                val result = method.invoke(service, *args) as Int
                return if (result >= 0) "OK" else "ERROR:start ${component.flattenToShortString()}: $result"
            }
            "ERROR:no usable startActivity on ${service.javaClass.name}"
        } catch (error: Throwable) {
            Log.w(TAG, "launch failed", error)
            "ERROR:${describe(error)}"
        }
    }

    /**
     * Toglie tutti i task del display, come uno scorrimento nelle recenti (removeTask, l'utente shell ha REMOVE_TASKS).
     * I task si trovano con getTasks, i cui parametri sono cambiati: (max) su 10, (max, soloVisibili, extra) su 12,
     * più il display su 13+.
     */
    @Synchronized
    override fun closeApp(displayId: Int): String {
        if (!entries.containsKey(displayId)) return "none"
        return try {
            val service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
            var tasks: List<*>? = null
            for (method in service.javaClass.methods) {
                if (method.name != "getTasks") continue
                val p = method.parameterTypes
                val args = arrayOfNulls<Any>(p.size)
                for (i in p.indices) {
                    when (p[i]) {
                        Int::class.javaPrimitiveType -> args[i] = if (i == 0) 100 else displayId
                        Boolean::class.javaPrimitiveType -> args[i] = false
                    }
                }
                tasks = method.invoke(service, *args) as? List<*>
                break
            }
            if (tasks == null) return "ERROR:no usable getTasks"
            val remove = service.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType)
            val displayField = android.app.TaskInfo::class.java.getField("displayId")
            var removed = 0
            for (task in tasks) {
                val info = task as? android.app.TaskInfo ?: continue
                if (displayField.getInt(info) != displayId) continue
                if (remove.invoke(service, info.taskId) as Boolean) removed++
            }
            if (removed > 0) "ok" else "none"
        } catch (error: Throwable) {
            Log.w(TAG, "closeApp failed", error)
            "ERROR:${describe(error)}"
        }
    }

    // ------------------------------------------------------------------ input

    override fun touch(displayId: Int, event: MotionEvent?) {
        if (event == null) return
        try {
            if (displayId >= 0) inject(event, displayId)
        } finally {
            event.recycle()
        }
    }

    override fun key(displayId: Int, keyCode: Int) {
        if (displayId < 0) return
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            inject(
                KeyEvent(now, now, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD),
                displayId
            )
        }
    }

    @SuppressLint("PrivateApi")
    private fun inject(event: InputEvent, displayId: Int) {
        try {
            if (injectMethod == null) {
                // injectInputEvent sta in InputManagerGlobal da Android 14, prima in InputManager
                val owner = try {
                    Class.forName("android.hardware.input.InputManagerGlobal")
                } catch (_: ClassNotFoundException) {
                    Class.forName("android.hardware.input.InputManager")
                }
                inputManager = owner.getMethod("getInstance").invoke(null)
                injectMethod = owner.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                setDisplayIdMethod = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            }
            setDisplayIdMethod!!.invoke(event, displayId)
            injectMethod!!.invoke(inputManager, event, 0) // INJECT_INPUT_EVENT_MODE_ASYNC
        } catch (error: Throwable) {
            Log.w(TAG, "input injection failed: ${describe(error)}")
        }
    }

    // ------------------------------------------------------------------ orientamento e tastiera

    /** IWindowManager dal ServiceManager: WindowManagerGlobal su Android 17 vuole una memoria condivisa che qui non c'è. */
    @SuppressLint("PrivateApi")
    private fun windowManager(): Any {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, Context.WINDOW_SERVICE)
        return Class.forName("android.view.IWindowManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    /**
     * Il display non ruota mai per un'app: un'app solo verticale lo girava (270°) e la superficie orizzontale
     * dell'auto mostrava il display rimpicciolito in una colonna, con i tocchi nei posti sbagliati. Così l'app resta
     * dritta, a tutta altezza, in una colonna al centro. Ognuna delle tre impostazioni basta su Android 14; tutte
     * sono facoltative (servono SET_ORIENTATION, che la shell ha).
     */
    private fun keepOrientation(displayId: Int) {
        val wm = try {
            windowManager()
        } catch (error: Throwable) {
            Log.i(TAG, "orientation: no window manager: ${describe(error)}")
            return
        }
        val int = Int::class.javaPrimitiveType
        val boolean = Boolean::class.javaPrimitiveType
        for (method in wm.javaClass.methods) {
            val p = method.parameterTypes
            try {
                when (method.name) {
                    // Android 12+: (displayId, ignore)
                    "setIgnoreOrientationRequest" -> if (p.size == 2 && p[0] == int && p[1] == boolean) method.invoke(wm, displayId, true)
                    // Android 10+: (displayId, FIXED_TO_USER_ROTATION_ENABLED = 2)
                    "setFixedToUserRotation" -> if (p.size == 2 && p[0] == int && p[1] == int) method.invoke(wm, displayId, 2)
                    // (displayId, rotation), su 14+ anche il chiamante
                    "freezeDisplayRotation" -> when {
                        p.size == 2 && p[0] == int && p[1] == int -> method.invoke(wm, displayId, 0)
                        p.size == 3 && p[0] == int && p[1] == int && p[2] == String::class.java -> method.invoke(wm, displayId, 0, "CarWeb")
                    }
                }
            } catch (error: Throwable) {
                Log.i(TAG, "orientation ${method.name}: ${describe(error)}")
            }
        }
    }

    /**
     * Dove si apre la tastiera del display (criterio IME, come `scrcpy --display-ime-policy`): LOCAL sul display stesso
     * (l'auto), FALLBACK_DISPLAY sullo schermo del telefono. Serve un display fidato; se è rifiutato il display funziona
     * lo stesso e la tastiera resta sul telefono.
     */
    private fun applyKeyboardPolicy(displayId: Int, onDisplay: Boolean) {
        val entry = entries[displayId] ?: return
        if (entry.flags and TRUSTED == 0) return
        try {
            val wm = windowManager()
            val int = Int::class.javaPrimitiveType
            if (Build.VERSION.SDK_INT >= 31) {
                // DISPLAY_IME_POLICY_LOCAL = 0, DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1
                wm.javaClass.getMethod("setDisplayImePolicy", int, int).invoke(wm, displayId, if (onDisplay) 0 else 1)
            } else {
                wm.javaClass.getMethod("setShouldShowIme", int, Boolean::class.javaPrimitiveType).invoke(wm, displayId, onDisplay)
            }
            Log.i(TAG, "keyboard of display $displayId opens ${if (onDisplay) "on it" else "on the phone"}")
        } catch (error: Throwable) {
            Log.i(TAG, "keyboard stays on the phone: ${describe(error)}")
        }
    }

    /**
     * Il tasto della tastiera. Lo stato si legge con l'accessibilità (un UiAutomation come quello di `uiautomator`, che
     * l'utente shell può collegare), solo per questa pressione: tastiera aperta → Indietro la chiude; campo di testo con
     * il focus → un tocco sul campo, come un dito, la apre. Le pagine web costruiscono l'albero di accessibilità a
     * richiesta: si riprova per poco.
     */
    override fun toggleKeyboard(displayId: Int): String {
        val onDisplay: Boolean
        synchronized(this) {
            val entry = entries[displayId] ?: return "error: no display"
            onDisplay = entry.keyboardOnDisplay
        }
        if (Build.VERSION.SDK_INT < 30) return "old android" // getWindowsOnAllDisplays
        synchronized(a11yLock) {
            var automation: UiAutomation? = null
            return try {
                automation = connectAutomation()
                var shown = false
                var field: AccessibilityNodeInfo? = null
                for (attempt in 0 until 4) {
                    if (attempt > 0) SystemClock.sleep(200)
                    val all = automation.getWindowsOnAllDisplays()
                    shown = hasKeyboard(all.get(displayId)) || (!onDisplay && hasKeyboard(all.get(Display.DEFAULT_DISPLAY)))
                    if (shown) break
                    field = focusedField(all.get(displayId))
                    if (field != null) break
                }
                when {
                    shown -> {
                        key(displayId, KeyEvent.KEYCODE_BACK)
                        "hidden"
                    }
                    field == null -> "no field"
                    else -> {
                        val bounds = Rect()
                        field.getBoundsInScreen(bounds)
                        tap(displayId, bounds.centerX(), bounds.centerY())
                        "shown"
                    }
                }
            } catch (error: Throwable) {
                Log.w(TAG, "toggleKeyboard failed", error)
                "error: ${describe(error)}"
            } finally {
                disconnectAutomation(automation)
            }
        }
    }

    private fun hasKeyboard(windows: List<AccessibilityWindowInfo>?): Boolean =
        windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true

    /** Il campo di testo con il focus di input nelle finestre del display, dalla più in alto. */
    private fun focusedField(windows: List<AccessibilityWindowInfo>?): AccessibilityNodeInfo? {
        windows ?: return null
        for (window in windows) {
            val root = window.root ?: continue
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) return focused
        }
        return null
    }

    @SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
    private fun connectAutomation(): UiAutomation {
        val thread = a11yThread ?: HandlerThread("carweb-a11y").also {
            it.start()
            a11yThread = it
        }
        val connectionClass = Class.forName("android.app.IUiAutomationConnection")
        val connection = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance()
        val automation: UiAutomation = try {
            // (Looper, connessione): da Android 7 a 17, sul nostro thread
            UiAutomation::class.java.getDeclaredConstructor(Looper::class.java, connectionClass)
                .apply { isAccessible = true }.newInstance(thread.looper, connection)
        } catch (_: NoSuchMethodException) {
            UiAutomation::class.java.getDeclaredConstructor(Context::class.java, connectionClass)
                .apply { isAccessible = true }.newInstance(context, connection)
        }
        UiAutomation::class.java.getDeclaredMethod("connect", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val info = automation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        automation.serviceInfo = info
        return automation
    }

    private fun disconnectAutomation(automation: UiAutomation?) {
        automation ?: return
        try {
            UiAutomation::class.java.getDeclaredMethod("disconnect").apply { isAccessible = true }.invoke(automation)
        } catch (error: Throwable) {
            Log.i(TAG, "accessibility disconnect: ${describe(error)}")
        }
    }

    /** Un tocco di dito nel punto (x, y) in pixel del display. */
    private fun tap(displayId: Int, x: Int, y: Int) {
        val entry = synchronized(this) { entries[displayId] } ?: return
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x.coerceIn(0, entry.width - 1).toFloat()
            this.y = y.coerceIn(0, entry.height - 1).toFloat()
            pressure = 1f
            size = 1f
        })
        val downTime = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, 1, properties, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            inject(event, displayId)
            event.recycle()
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(60)
        }
    }

    // ------------------------------------------------------------------ telefono

    /** 1 = il display divide il gruppo del telefono (se il telefono dorme il display si ferma), 0 = ha il suo, -1 = non si sa. */
    @Synchronized
    override fun phoneGroup(displayId: Int): Int {
        if (!entries.containsKey(displayId) || Build.VERSION.SDK_INT < 31) return -1
        return try {
            val global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)!!
            val info = global.javaClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
            val mine = info.invoke(global, displayId) ?: return -1
            val phone = info.invoke(global, Display.DEFAULT_DISPLAY) ?: return -1
            val group = mine.javaClass.getField("displayGroupId")
            if (group.getInt(mine) != group.getInt(phone)) 0 else 1
        } catch (error: Throwable) {
            Log.i(TAG, "display group unknown: ${describe(error)}")
            -1
        }
    }

    /**
     * Blocco dello schermo preso dalla shell: tiene il telefono sveglio e, a ogni richiesta, lo riaccende (anche dal blocco
     * schermo). Il blocco chiaro, non quello "dim": con quello il sistema abbassa la luce prima di spegnere.
     */
    @Synchronized
    override fun keepPhoneAwake(on: Boolean) {
        try {
            if (on) {
                val lock = phoneLock ?: run {
                    val pm = context.createPackageContext(SHELL, 0).getSystemService(PowerManager::class.java)
                    @Suppress("DEPRECATION")
                    val level = PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    pm.newWakeLock(level or PowerManager.ACQUIRE_CAUSES_WAKEUP, "CarWeb:virtual-apps")
                        .apply { setReferenceCounted(false) }
                        .also { phoneLock = it }
                }
                // Prenderlo di nuovo con ACQUIRE_CAUSES_WAKEUP riaccende lo schermo; se è già preso non succede niente
                if (lock.isHeld) lock.release()
                lock.acquire()
            } else {
                phoneLock?.takeIf { it.isHeld }?.release()
            }
        } catch (error: Throwable) {
            Log.w(TAG, "keepPhoneAwake($on) failed: ${describe(error)}")
        }
    }

    @Synchronized
    override fun info(): String =
        "Android ${Build.VERSION.SDK_INT}, uid ${Process.myUid()}, displays ${entries.entries.joinToString { "${it.key}:${it.value.width}x${it.value.height}" }}"

    // ------------------------------------------------------------------ aiuti

    @SuppressLint("DiscouragedPrivateApi")
    private fun displayManager(): DisplayManager {
        displayManager?.let { return it }
        // DisplayManager(Context) è nascosto; con il contesto shell le chiamate arrivano da com.android.shell
        val constructor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(context).also { displayManager = it }
    }

    @SuppressLint("PrivateApi")
    private fun activityTaskService(): Any =
        if (Build.VERSION.SDK_INT >= 29) {
            Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
        } else {
            ActivityManager::class.java.getMethod("getService").invoke(null)!!
        }

    private fun describe(error: Throwable): String {
        val actual = if (error is InvocationTargetException && error.cause != null) error.cause!! else error
        return "${actual.javaClass.simpleName}: ${actual.message.orEmpty()}"
    }

    /** Le chiamate ai servizi di sistema risultano di com.android.shell, il pacchetto dell'uid 2000. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL
        override fun getOpPackageName(): String = SHELL

        @SuppressLint("NewApi")
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(SHELL).build()

        override fun getApplicationContext(): Context = this
    }

    companion object {
        private const val TAG = "CarWebDisplayService"
        private const val SHELL = "com.android.shell"
        private const val MAX_SIDE = 8192

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (oltre ai primi tre sono nascosti)
        private const val PUBLIC = 1
        private const val PRESENTATION = 1 shl 1
        private const val OWN_CONTENT_ONLY = 1 shl 3
        private const val SUPPORTS_TOUCH = 1 shl 6
        private const val TRUSTED = 1 shl 10
        private const val OWN_DISPLAY_GROUP = 1 shl 11
        private const val ALWAYS_UNLOCKED = 1 shl 12
        private const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val OWN_FOCUS = 1 shl 14
        private const val BASE = PUBLIC or PRESENTATION or OWN_CONTENT_ONLY or SUPPORTS_TOUCH

        @SuppressLint("PrivateApi")
        private fun systemContext(): Context {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getMethod("currentActivityThread").invoke(null)
                ?: threadClass.getMethod("systemMain").invoke(null)
            return threadClass.getMethod("getSystemContext").invoke(thread) as Context
        }
    }
}
