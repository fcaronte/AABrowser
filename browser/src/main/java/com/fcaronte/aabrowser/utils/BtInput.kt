package com.fcaronte.aabrowser.utils

import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.hypot

/**
 * Mouse e tastiera Bluetooth collegati al telefono, per comandare gli schermi dell'auto (la Main e la Wide): qui le regole
 * che non hanno bisogno di Android in funzione (tasti rapidi, movimento e limiti del puntatore, pulsanti, rotellina), così si
 * provano con i test. Il resto sta in [BtInputRouter] (smista) e in `BtInputActivity` (la schermata del telefono che riceve).
 */
object BtInput {

    /** I comandi di navigazione che i tasti rapidi e i pulsanti del mouse chiedono allo schermo dell'auto. */
    enum class Shortcut { BACK, FORWARD, RELOAD, HOME, SEARCH, NEXT_TAB, PREVIOUS_TAB, CLOSE_TAB, NEW_TAB }

    /**
     * Il tasto rapido di una pressione ([keyCode] con i modificatori), come nei browser per computer: Esc e Alt+← indietro,
     * Alt+→ avanti, F5 e Ctrl+R ricarica, Ctrl+L, Ctrl+K e F6 la ricerca, Ctrl+Tab e Ctrl+Pag↓ scheda dopo, con Maiusc o
     * Ctrl+Pag↑ scheda prima, Ctrl+W chiude la scheda, Ctrl+T ne apre una, Alt+Home la home. Null = non è un tasto rapido e va
     * alla pagina (compresi Ctrl+A, Ctrl+C e Ctrl+V, che servono a scrivere).
     */
    fun shortcutFor(keyCode: Int, ctrl: Boolean, alt: Boolean, shift: Boolean): Shortcut? = when {
        keyCode == KeyEvent.KEYCODE_ESCAPE && !ctrl && !alt && !shift -> Shortcut.BACK
        keyCode == KeyEvent.KEYCODE_DPAD_LEFT && alt && !ctrl -> Shortcut.BACK
        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && alt && !ctrl -> Shortcut.FORWARD
        keyCode == KeyEvent.KEYCODE_F5 && !alt -> Shortcut.RELOAD
        keyCode == KeyEvent.KEYCODE_R && ctrl && !alt -> Shortcut.RELOAD
        keyCode == KeyEvent.KEYCODE_L && ctrl && !alt && !shift -> Shortcut.SEARCH
        keyCode == KeyEvent.KEYCODE_K && ctrl && !alt && !shift -> Shortcut.SEARCH
        keyCode == KeyEvent.KEYCODE_F6 && !ctrl && !alt -> Shortcut.SEARCH
        keyCode == KeyEvent.KEYCODE_TAB && ctrl && !alt -> if (shift) Shortcut.PREVIOUS_TAB else Shortcut.NEXT_TAB
        keyCode == KeyEvent.KEYCODE_PAGE_DOWN && ctrl && !alt -> Shortcut.NEXT_TAB
        keyCode == KeyEvent.KEYCODE_PAGE_UP && ctrl && !alt -> Shortcut.PREVIOUS_TAB
        keyCode == KeyEvent.KEYCODE_W && ctrl && !alt && !shift -> Shortcut.CLOSE_TAB
        keyCode == KeyEvent.KEYCODE_F4 && ctrl && !alt -> Shortcut.CLOSE_TAB
        keyCode == KeyEvent.KEYCODE_T && ctrl && !alt && !shift -> Shortcut.NEW_TAB
        keyCode == KeyEvent.KEYCODE_MOVE_HOME && alt && !ctrl -> Shortcut.HOME
        else -> null
    }

    /**
     * Se un tasto va allo schermo dell'auto. Restano al telefono i tasti del telefono (Indietro, Home, Recenti, accensione, volume,
     * fotocamera, chiamate, assistente) e i tasti dei contenuti multimediali, che il sistema manda alla sessione media di CarWeb
     * come sempre: uno schermo che li prendesse toglierebbe al volante e agli auricolari la loro strada.
     */
    fun forwardable(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE, KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_CALL,
        KeyEvent.KEYCODE_ENDCALL, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_CLOSE,
        KeyEvent.KEYCODE_MEDIA_EJECT, KeyEvent.KEYCODE_MEDIA_RECORD -> false

        else -> true
    }

    // --- Il puntatore ---

    const val MIN_SPEED = 50
    const val MAX_SPEED = 300
    const val SPEED_STEP = 25
    const val DEFAULT_SPEED = 100

    /** La velocità del puntatore in percentuale (50-300, a passi di 25): un valore salvato fuori misura si porta al più vicino. */
    fun clampSpeed(percent: Int): Int =
        ((percent.coerceIn(MIN_SPEED, MAX_SPEED) - MIN_SPEED + SPEED_STEP / 2) / SPEED_STEP) * SPEED_STEP + MIN_SPEED

    class Point(val x: Float, val y: Float)

    /**
     * Il puntatore in ([x], [y]) spostato di ([dx], [dy]) alla velocità scelta, tenuto dentro uno schermo di [width] per
     * [height] pixel (il punto più in basso a destra è width-1, height-1): non esce mai e non si incastra fuori dal bordo.
     */
    fun moved(x: Float, y: Float, dx: Float, dy: Float, speedPercent: Int, width: Int, height: Int): Point {
        val speed = clampSpeed(speedPercent) / 100f
        return Point(
            (x + dx * speed).coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat()),
            (y + dy * speed).coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())
        )
    }

    /** Il punto al centro di uno schermo di [width] per [height] pixel: da qui parte il puntatore. */
    fun centre(width: Int, height: Int): Point = Point((width - 1).coerceAtLeast(0) / 2f, (height - 1).coerceAtLeast(0) / 2f)

    /** Quanto scorre la pagina per ogni scatto della rotellina, in dp: più o meno una riga di una lista. */
    const val WHEEL_STEP_DP = 56f

    /** Lo scorrimento in pixel di [notches] scatti (positivi o negativi) su una superficie con la [density] data. */
    fun wheelDistance(notches: Float, density: Float): Float = notches * WHEEL_STEP_DP * density

    /** Un clic e non un trascinamento: dal punto in cui si è premuto a quello in cui si è lasciato c'è meno di [slop] pixel. */
    fun isClick(downX: Float, downY: Float, upX: Float, upY: Float, slop: Float): Boolean =
        hypot(upX - downX, upY - downY) <= slop

    // --- I pulsanti del mouse ---

    enum class Button(val mask: Int) {
        PRIMARY(MotionEvent.BUTTON_PRIMARY),
        SECONDARY(MotionEvent.BUTTON_SECONDARY),
        MIDDLE(MotionEvent.BUTTON_TERTIARY),
        BACK(MotionEvent.BUTTON_BACK),
        FORWARD(MotionEvent.BUTTON_FORWARD)
    }

    class Change(val button: Button, val pressed: Boolean)

    /** I pulsanti premuti o lasciati passando da [before] a [after] (la maschera `buttonState` degli eventi del mouse). */
    fun changes(before: Int, after: Int): List<Change> = Button.entries.mapNotNull { button ->
        val was = before and button.mask != 0
        val now = after and button.mask != 0
        if (was == now) null else Change(button, now)
    }

    /** Il comando a cui porta un pulsante del mouse appena premuto: destro e indietro tornano indietro, avanti va avanti. */
    fun shortcutFor(button: Button): Shortcut? = when (button) {
        Button.SECONDARY, Button.BACK -> Shortcut.BACK
        Button.FORWARD -> Shortcut.FORWARD
        Button.PRIMARY, Button.MIDDLE -> null
    }

    /** Dopo tanto senza muoversi il puntatore sparisce (riappare al primo movimento). */
    const val CURSOR_HIDE_MS = 8_000L
}
