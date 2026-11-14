package com.fcaronte.aabrowser.smartphone

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fcaronte.aabrowser.StandbyActivity
import com.fcaronte.aabrowser.settings.AppSettings
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Il telefono mentre un'app sua gira sullo schermo dell'auto.
 *
 * Su molti telefoni (Android 17 compreso) il display virtuale sta nel gruppo di display del telefono: se il telefono dorme o
 * si blocca, le app sul display si fermano e l'auto non riceve più fotogrammi ("Apro l'app…" per sempre, o un'immagine
 * ferma). Finché un'app è davanti sull'auto il telefono deve quindi restare sveglio. Per non tenerlo acceso e luminoso
 * sulla gamba, sopra c'è la schermata di attesa ([StandbyActivity]): nera, luminosità bassa, "CarWeb attivo, scorri per
 * sbloccare". Scorrendo si torna al telefono normale (senza blocco: vale il timeout dello schermo del sistema); quando il
 * sistema lo spegne per inattività si riaccende subito sulla schermata di attesa.
 *
 * Il tasto di blocco invece vince sul blocco dello schermo (un blocco "tiene acceso" solo contro il timeout): il telefono
 * si addormenta e l'auto resta nera. Allora: (1) allo spegnimento (ACTION_SCREEN_OFF) il servizio della shell lo riaccende,
 * con il blocco dello schermo e con PowerManager.wakeUp, ritentando se serve; (2) a ogni tocco sullo schermo dell'auto, se il
 * telefono dorme, si riaccende (è quello che fa anche AnyAuto); (3) le viste dell'auto dicono "telefono in standby: tocca per
 * riattivarlo" ([asleep]) invece di restare nere senza spiegazione; (4) acceso di nuovo (ACTION_SCREEN_ON, con o senza blocco
 * schermo) la schermata di attesa si rimette sopra. Se lo schermo si spegne troppe volte di seguito (un telefono che non
 * vuole restare acceso) non lo si riaccende più da soli, solo con un tocco sull'auto: niente sfarfallio.
 * Finita l'ultima app sull'auto (con qualche secondo di tolleranza: l'auto cambia schermata di continuo) tutto torna normale.
 *
 * Se invece il display ha un gruppo suo (Android lo permette solo su alcuni telefoni) il telefono non c'entra e qui non si
 * fa niente. Il blocco dello schermo lo prende il servizio di Shizuku (la shell), che dallo sfondo può anche aprire la
 * schermata di attesa sul telefono bloccato.
 */
object VirtualScreenPower {
    private const val TAG = "VirtualScreenPower"

    // L'auto passa da una schermata all'altra: si lascia andare il telefono solo se resta fuori per tutto questo tempo
    private const val RELEASE_GRACE_MS = 8_000L

    // Riaccendere: subito, poi dopo 0,6 e dopo 1,2 s ancora se lo schermo è spento (un risveglio durante lo spegnimento si perde)
    private const val WAKE_RETRY_MS = 600L
    private const val MAX_WAKE_ATTEMPTS = 3

    // Quattro spegnimenti in meno di 15 s: il telefono non vuole restare acceso, si smette di riaccenderlo da soli
    private const val FLICKER_COUNT = 4
    private const val FLICKER_WINDOW_MS = 15_000L

    // Un tocco sull'auto riaccende al massimo una volta al secondo
    private const val TOUCH_WAKE_MIN_INTERVAL_MS = 1_000L

    private val main = Handler(Looper.getMainLooper())
    private val users = LinkedHashSet<Any>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val offTimes = ArrayDeque<Long>()
    private var appContext: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var wakeAttempt = 0
    private var lastTouchWake = 0L

    /** Un'app del telefono è davanti sull'auto: la schermata di attesa deve esserci (o essere pronta a tornare). */
    @Volatile
    var active = false
        private set

    /** Il telefono è spento (tasto di blocco o timeout) mentre un'app è sull'auto: le viste lo dicono, un tocco lo riaccende. */
    var asleep by mutableStateOf(false)
        private set

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Una sessione comincia o smette di avere bisogno del telefono acceso (app davanti sull'auto e display nel gruppo del
     * telefono). Dal thread principale.
     */
    fun setInUse(context: Context, user: Any, inUse: Boolean) {
        appContext = context.applicationContext
        if (inUse) users.add(user) else users.remove(user)
        if (users.isNotEmpty()) {
            main.removeCallbacks(releaseLater)
            if (!active) activate()
        } else if (active) {
            main.removeCallbacks(releaseLater)
            main.postDelayed(releaseLater, RELEASE_GRACE_MS)
        }
    }

    /** L'utente ha scorso la schermata di attesa: il telefono è suo, vale il timeout normale dello schermo. */
    fun userTookPhone() {
        val context = appContext ?: return
        if (!active) return
        Log.i(TAG, "the user took the phone: the system screen timeout applies again")
        main.removeCallbacks(wakeStep)
        ShizukuAppBridge.keepPhoneAwake(context, false)
    }

    /**
     * Un tocco sullo schermo dell'auto (la Main o la Wide, con un'app davanti): se il telefono dorme si riaccende, come
     * quando lo schermo si è spento da solo ma il risveglio non è riuscito. Dal thread principale.
     */
    fun touchOnCar() {
        val context = appContext ?: return
        if (!active) return
        val now = SystemClock.uptimeMillis()
        if (now - lastTouchWake < TOUCH_WAKE_MIN_INTERVAL_MS) return
        if (isInteractive(context)) return
        lastTouchWake = now
        // L'utente interviene: la guardia contro lo sfarfallio ricomincia
        offTimes.clear()
        Log.i(TAG, "tap on the car screen while the phone is asleep: waking it")
        startWaking()
    }

    private val releaseLater = Runnable {
        if (users.isEmpty() && active) deactivate()
    }

    private val wakeStep = Runnable { wakeUpNow() }

    private fun activate() {
        val context = appContext ?: return
        active = true
        offTimes.clear()
        asleep = !isInteractive(context)
        Log.i(TAG, "an app is in front on the car: the phone stays awake (asleep now: $asleep)")
        registerReceiver(context)
        // Il blocco dello schermo, che con ACQUIRE_CAUSES_WAKEUP accende anche un telefono spento
        ShizukuAppBridge.keepPhoneAwake(context, true)
        startStandby()
        notifyListeners()
    }

    private fun deactivate() {
        val context = appContext ?: return
        active = false
        asleep = false
        Log.i(TAG, "no app in front on the car any more: the phone goes back to normal")
        main.removeCallbacks(wakeStep)
        unregisterReceiver(context)
        ShizukuAppBridge.keepPhoneAwake(context, false)
        notifyListeners()
    }

    /** La schermata di attesa sul telefono (se l'utente la vuole), aperta dalla shell: anche sopra il blocco schermo. */
    private fun startStandby() {
        val context = appContext ?: return
        if (AppSettings.virtualStandby.value) {
            ShizukuAppBridge.startOnPhone(context, ComponentName(context, StandbyActivity::class.java))
        }
    }

    private fun isInteractive(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    // ------------------------------------------------------------------ spegnimento e risveglio

    private fun onScreenOff() {
        if (!active) return
        asleep = true
        notifyListeners()
        val now = SystemClock.uptimeMillis()
        offTimes.addLast(now)
        while (offTimes.size > FLICKER_COUNT) offTimes.removeFirst()
        if (offTimes.size >= FLICKER_COUNT && now - offTimes.first() < FLICKER_WINDOW_MS) {
            Log.w(TAG, "the screen keeps turning off: not waking it by itself until the car screen is tapped")
            return
        }
        Log.i(TAG, "screen off while an app is on the car: waking the phone again")
        startWaking()
    }

    private fun onScreenOn() {
        main.removeCallbacks(wakeStep)
        if (!active) return
        asleep = false
        notifyListeners()
        // Il blocco schermo (o la home) è tornato davanti: la schermata di attesa si rimette sopra
        startStandby()
    }

    private fun startWaking() {
        wakeAttempt = 0
        main.removeCallbacks(wakeStep)
        wakeUpNow()
    }

    private fun wakeUpNow() {
        val context = appContext ?: return
        if (!active || isInteractive(context)) return
        wakeAttempt++
        Log.i(TAG, "waking the phone (try $wakeAttempt)")
        ShizukuAppBridge.wakePhone(context)
        if (wakeAttempt < MAX_WAKE_ATTEMPTS) main.postDelayed(wakeStep, WAKE_RETRY_MS * wakeAttempt)
    }

    private fun registerReceiver(context: Context) {
        if (receiver != null) return
        val screen = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                    Intent.ACTION_SCREEN_ON -> onScreenOn()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        context.registerReceiver(screen, filter)
        receiver = screen
    }

    private fun unregisterReceiver(context: Context) {
        val current = receiver ?: return
        receiver = null
        try {
            context.unregisterReceiver(current)
        } catch (_: IllegalArgumentException) {
            // già tolto
        }
    }

    private fun notifyListeners() {
        for (listener in listeners) listener()
    }
}
