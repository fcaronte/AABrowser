package com.fcaronte.aabrowser

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.fcaronte.aabrowser.smartphone.VirtualScreenPower
import com.fcaronte.aabrowser.utils.AppLocale
import kotlin.math.abs

/**
 * La schermata di attesa del telefono mentre un'app del telefono gira sullo schermo dell'auto: nera, con la scritta
 * "CarWeb attivo / Scorri per sbloccare" ben leggibile (la luminosità è bassa ma non al minimo: al minimo la scritta non si
 * vedeva e il telefono sembrava bloccato). Tiene il telefono sveglio (serve ai display delle app, vedi [VirtualScreenPower])
 * senza tenerlo luminoso, compare anche sopra il blocco schermo e torna da sola quando il sistema lo spegne per inattività.
 * La freccia pulsa piano, così si vede che non è un telefono fermo, e il blocco di testo si sposta ogni tanto di qualche
 * millimetro, perché sullo schermo OLED la stessa scritta ferma per ore lascerebbe il segno.
 *
 * Scorrere (o Home, o Indietro) restituisce il telefono: con un blocco schermo prima si chiede lo sblocco. Finita l'ultima app
 * sull'auto la schermata si chiude da sola.
 *
 * È esportata (senza filtri) perché la avvia la shell di Shizuku dallo sfondo, dove l'app non può aprire attività; se non c'è
 * nessuna app sull'auto si chiude subito, quindi chi la aprisse da fuori vedrebbe un lampo nero.
 */
class StandbyActivity : ComponentActivity() {

    private val onPowerChanged: () -> Unit = {
        if (!VirtualScreenPower.active) finishAndRemoveTask()
    }

    private var downX = 0f
    private var downY = 0f
    private var leaving = false
    private var column: LinearLayout? = null
    private var pulse: ObjectAnimator? = null
    private var driftStep = 0

    // Ogni tanto il blocco di testo cambia posto (poco): niente scritta ferma per ore sugli stessi pixel
    private val drift = object : Runnable {
        override fun run() {
            val view = column ?: return
            driftStep = (driftStep + 1) % DRIFT_SPOTS.size
            val density = resources.displayMetrics.density
            val (dx, dy) = DRIFT_SPOTS[driftStep]
            view.animate().translationX(dx * density).translationY(dy * density).setDuration(1500).start()
            view.postDelayed(this, DRIFT_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!VirtualScreenPower.active) {
            finishAndRemoveTask()
            return
        }
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Luminosità bassa, qualunque sia quella scelta dall'utente: sullo schermo OLED i pixel neri non consumano. Non al
        // minimo di Android (BRIGHTNESS_OVERRIDE_OFF): su molti telefoni è quasi buio e la scritta non si legge
        window.attributes = window.attributes.apply { screenBrightness = STANDBY_BRIGHTNESS }
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        // Indietro: come scorrere (su un telefono nero non c'è altro da fare)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                unlock()
            }
        })

        val strings = AppLocale.wrap(this)
        val density = resources.displayMetrics.density
        fun label(text: String, size: Float, color: Int, top: Int = 0) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding((24 * density).toInt(), (top * density).toInt(), (24 * density).toInt(), 0)
        }
        val arrow = label("↑", 30f, HINT_COLOR, top = 20)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val wrap = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(label(strings.getString(R.string.standby_title), 30f, TITLE_COLOR), wrap)
            addView(arrow, wrap)
            addView(label(strings.getString(R.string.standby_hint), 18f, HINT_COLOR, top = 6), wrap)
        }
        column = content
        pulse = ObjectAnimator.ofFloat(arrow, View.ALPHA, 0.3f, 1f).apply {
            duration = 1400
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        )
        VirtualScreenPower.addListener(onPowerChanged)
    }

    override fun onStart() {
        super.onStart()
        pulse?.start()
        column?.postDelayed(drift, DRIFT_MS)
    }

    // Con lo schermo spento o l'attività dietro, niente animazioni
    override fun onStop() {
        pulse?.cancel()
        column?.removeCallbacks(drift)
        super.onStop()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }

            MotionEvent.ACTION_UP -> {
                // Uno scorrimento lungo almeno 72 dp, più in verticale che in orizzontale
                val threshold = 72 * resources.displayMetrics.density
                val dy = event.y - downY
                val dx = event.x - downX
                if (abs(dy) > threshold && abs(dy) > abs(dx)) unlock()
            }
        }
        return true
    }

    private fun unlock() {
        if (leaving) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard != null && keyguard.isKeyguardLocked) {
            // Con un blocco schermo: prima lo sblocco (PIN, impronta); annullato si resta qui
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    leave()
                }
            })
        } else {
            leave()
        }
    }

    private fun leave() {
        if (leaving) return
        leaving = true
        VirtualScreenPower.userTookPhone()
        finishAndRemoveTask()
    }

    // Home: il telefono è dell'utente
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!leaving && VirtualScreenPower.active) {
            leaving = true
            VirtualScreenPower.userTookPhone()
        }
    }

    override fun onDestroy() {
        VirtualScreenPower.removeListener(onPowerChanged)
        pulse?.cancel()
        column?.removeCallbacks(drift)
        super.onDestroy()
    }

    companion object {
        // Abbastanza per leggere la scritta anche al buio dell'auto, poco per non scaldare né consumare
        private const val STANDBY_BRIGHTNESS = 0.15f

        // Bianco quasi pieno (non pieno: meno segno sull'OLED) per il titolo, un po' meno per il resto
        private const val TITLE_COLOR = 0xE6FFFFFF.toInt()
        private const val HINT_COLOR = 0xB3FFFFFF.toInt()

        private const val DRIFT_MS = 45_000L

        // Spostamenti del blocco di testo in dp (x, y), a giro
        private val DRIFT_SPOTS = arrayOf(0f to 0f, 28f to -36f, -28f to 36f, 20f to 56f, -24f to -56f)
    }
}
