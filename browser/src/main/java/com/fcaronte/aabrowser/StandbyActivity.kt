package com.fcaronte.aabrowser

import android.app.KeyguardManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
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
 * La schermata di attesa del telefono mentre un'app del telefono gira sullo schermo dell'auto: tutta nera, luminosità al minimo,
 * "CarWeb attivo / Scorri per sbloccare". Tiene il telefono sveglio (serve ai display delle app, vedi [VirtualScreenPower]) senza
 * tenerlo luminoso, compare anche sopra il blocco schermo e torna da sola quando il sistema lo spegne per inattività.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!VirtualScreenPower.active) {
            finishAndRemoveTask()
            return
        }
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Luminosità al minimo, qualunque sia quella scelta dall'utente: sullo schermo OLED i pixel neri non consumano
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF }
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
            setPadding(0, (top * density).toInt(), 0, 0)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val wrap = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(label(strings.getString(R.string.standby_title), 26f, 0x66FFFFFF), wrap)
            addView(label("↑", 20f, 0x44FFFFFF, top = 16), wrap)
            addView(label(strings.getString(R.string.standby_hint), 15f, 0x44FFFFFF, top = 4), wrap)
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        )
        VirtualScreenPower.addListener(onPowerChanged)
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
        super.onDestroy()
    }
}
