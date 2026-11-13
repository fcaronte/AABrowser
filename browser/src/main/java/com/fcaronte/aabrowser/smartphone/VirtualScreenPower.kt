package com.fcaronte.aabrowser.smartphone

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.fcaronte.aabrowser.StandbyActivity
import com.fcaronte.aabrowser.settings.AppSettings
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Il telefono mentre un'app sua gira sullo schermo dell'auto.
 *
 * Su molti telefoni (Android 17 compreso) il display virtuale sta nel gruppo di display del telefono: se il telefono dorme o
 * si blocca, le app sul display si fermano e l'auto non riceve più fotogrammi ("Apro l'app…" per sempre, o un'immagine
 * ferma). Finché un'app è davanti sull'auto il telefono deve quindi restare sveglio. Per non tenerlo acceso e luminoso
 * sulla gamba, sopra c'è la schermata di attesa ([StandbyActivity]): nera, luminosità al minimo, "CarWeb attivo, scorri per
 * sbloccare". Scorrendo si torna al telefono normale (senza blocco: vale il timeout dello schermo del sistema); quando il
 * sistema lo spegne per inattività, o con il tasto di accensione, lo si riaccende subito sulla schermata di attesa.
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

    // Dopo averlo acceso, un nuovo "schermo spento" entro questo tempo non lo riaccende di nuovo (niente ping-pong)
    private const val ENGAGE_MIN_INTERVAL_MS = 1_000L

    private val main = Handler(Looper.getMainLooper())
    private val users = LinkedHashSet<Any>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var appContext: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var lastEngage = 0L

    /** Un'app del telefono è davanti sull'auto: la schermata di attesa deve esserci (o essere pronta a tornare). */
    @Volatile
    var active = false
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
        ShizukuAppBridge.keepPhoneAwake(context, false)
    }

    private val releaseLater = Runnable {
        if (users.isEmpty() && active) deactivate()
    }

    private fun activate() {
        val context = appContext ?: return
        active = true
        Log.i(TAG, "an app is in front on the car: the phone stays awake")
        registerScreenOff(context)
        engage(context)
        notifyListeners()
    }

    private fun deactivate() {
        val context = appContext ?: return
        active = false
        Log.i(TAG, "no app in front on the car any more: the phone goes back to normal")
        unregisterScreenOff(context)
        ShizukuAppBridge.keepPhoneAwake(context, false)
        notifyListeners()
    }

    /** Telefono sveglio (anche da spento o bloccato) e, se l'utente vuole, la schermata di attesa davanti. */
    private fun engage(context: Context) {
        lastEngage = SystemClock.uptimeMillis()
        ShizukuAppBridge.keepPhoneAwake(context, true)
        if (AppSettings.virtualStandby.value) {
            ShizukuAppBridge.startOnPhone(context, ComponentName(context, StandbyActivity::class.java))
        }
    }

    private fun registerScreenOff(context: Context) {
        if (receiver != null) return
        val screenOff = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (!active) return
                // Spento dal timeout del sistema (dopo che l'utente ha preso il telefono) o dal tasto di accensione: il display
                // delle app si fermerebbe, quindi si riaccende subito, sulla schermata di attesa
                if (SystemClock.uptimeMillis() - lastEngage < ENGAGE_MIN_INTERVAL_MS) return
                Log.i(TAG, "screen off while an app is on the car: waking the phone again")
                engage(c.applicationContext)
            }
        }
        context.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        receiver = screenOff
    }

    private fun unregisterScreenOff(context: Context) {
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
