package com.fcaronte.aabrowser

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.fcaronte.aabrowser.utils.AppLocale
import com.fcaronte.aabrowser.utils.BtInputRouter

/**
 * La schermata del telefono per usare un mouse e una tastiera Bluetooth sullo schermo dell'auto (Impostazioni, solo dal
 * telefono). Un mouse o una tastiera collegati al telefono mandano i loro eventi all'app che ha lo schermo davanti, e con
 * Android Auto in uso davanti non c'è CarWeb: serve questa schermata, aperta e lasciata sul telefono. Finché è in primo piano
 * cattura il puntatore (movimenti relativi, pulsanti, rotellina: `requestPointerCapture`) e i tasti, e li passa a
 * [BtInputRouter], che li manda alla Main o alla Wide, quale delle due è davanti sull'auto.
 *
 * Nera e a luminosità bassa come la schermata di attesa, tiene lo schermo acceso e dice quale schermo dell'auto riceve. Si
 * esce con Indietro o con il pulsante Chiudi (toccando: con il mouse catturato il puntatore del telefono non c'è); un tocco
 * sullo schermo riprova a catturare il mouse se il sistema l'ha tolto (per esempio dopo una notifica).
 */
class BtInputActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private var strings: Context? = null

    private val refresh = object : Runnable {
        override fun run() {
            val text = strings ?: return
            val front = BtInputRouter.frontLabel()
            status.text = if (front != null) text.getString(R.string.bt_input_on, front) else text.getString(R.string.bt_input_off)
            status.setTextColor(if (front != null) ON_COLOR else OFF_COLOR)
            root.postDelayed(this, STATUS_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Luminosità bassa, qualunque sia quella scelta: il telefono resta acceso per ore e la schermata è quasi tutta nera
        window.attributes = window.attributes.apply { screenBrightness = BRIGHTNESS }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        val text = AppLocale.wrap(this)
        strings = text
        val density = resources.displayMetrics.density
        fun label(value: String, size: Float, color: Int, top: Int = 0) = TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding((24 * density).toInt(), (top * density).toInt(), (24 * density).toInt(), 0)
        }
        status = label("", 20f, OFF_COLOR, top = 14)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val wrap = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(label(text.getString(R.string.bt_input_title), 26f, TITLE_COLOR), wrap)
            addView(status, wrap)
            addView(label(text.getString(R.string.bt_input_hint), 15f, HINT_COLOR, top = 18), wrap)
            addView(
                Button(this@BtInputActivity).apply {
                    this.text = text.getString(R.string.close_button)
                    setOnClickListener { finish() }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (24 * density).toInt()
                }
            )
        }

        // La vista che cattura il puntatore: deve avere il focus, e riceve tutti i movimenti, i pulsanti e la rotellina
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnCapturedPointerListener { _, event -> BtInputRouter.onPointer(event) }
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        BtInputRouter.startCapture()
        root.postDelayed(refresh, 0)
        capture()
    }

    override fun onPause() {
        root.removeCallbacks(refresh)
        root.releasePointerCapture()
        BtInputRouter.stopCapture()
        super.onPause()
    }

    // Il sistema dà la cattura del puntatore solo a una finestra con il focus: si chiede ogni volta che lo si riprende
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) capture() else root.releasePointerCapture()
    }

    private fun capture() {
        root.post {
            root.requestFocus()
            root.requestPointerCapture()
        }
    }

    // Un tocco riprova a catturare il mouse, se il sistema l'ha tolto
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !root.hasPointerCapture()) capture()
        return super.dispatchTouchEvent(event)
    }

    // I tasti della tastiera vanno all'auto, tranne quelli del telefono (Indietro, volume, multimedia: BtInput.forwardable)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (BtInputRouter.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        const val STATUS_MS = 500L
        const val BRIGHTNESS = 0.2f
        val TITLE_COLOR = Color.argb(230, 255, 255, 255)
        val HINT_COLOR = Color.argb(180, 255, 255, 255)
        val ON_COLOR = Color.rgb(138, 180, 248)
        val OFF_COLOR = Color.argb(200, 255, 190, 120)
    }
}
