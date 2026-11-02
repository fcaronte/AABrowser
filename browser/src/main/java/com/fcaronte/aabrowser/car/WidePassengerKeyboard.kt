package com.fcaronte.aabrowser.car

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.core.widget.TextViewCompat
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.utils.PassengerKeyboard

/**
 * Tastiera passeggero della Wide, disegnata sulla superficie dell'auto al posto della tastiera a schermo intero di
 * Android Auto (che in movimento si blocca): in alto la riga col testo, sotto i tasti, in tutta l'area libera
 * dall'interfaccia di Android Auto. Riceve i tocchi dalla Wide (onClick: solo tocchi singoli, niente pressione
 * lunga). Invio passa il testo a chi l'ha aperta, ✕ chiude senza scrivere.
 */
class WidePassengerKeyboard(private val context: Context) {
    private val dp = context.resources.displayMetrics.density
    private val keyboard = PassengerKeyboard()
    private var typed = ""
    private var hint = ""
    private var onDone: ((String) -> Unit)? = null

    /** Aperta o chiusa (la Wide ci lega il tasto Indietro dell'auto). */
    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    /** Tutta la superficie: i tocchi fuori dai tasti si fermano qui. */
    val view: FrameLayout = FrameLayout(context).apply {
        visibility = View.GONE
        setBackgroundColor("#E6000000".toColorInt())
        isClickable = true
    }

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (6 * dp).toInt()
        setPadding(pad, pad, pad, pad)
        background = rounded(PANEL_COLOR, 16f)
    }

    private val textView = TextView(context).apply {
        maxLines = 1
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.START // si vede sempre la fine del testo
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(Color.WHITE)
        setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 14, 26, 1, TypedValue.COMPLEX_UNIT_SP)
    }

    private val keyRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    val isVisible: Boolean
        get() = view.visibility == View.VISIBLE

    init {
        val topRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        topRow.addView(key("✕", TOP_KEY_COLOR) { hide() }, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = gap() })
        topRow.addView(textView, LinearLayout.LayoutParams(0, -1, 6.5f).apply { rightMargin = gap() })
        topRow.addView(
            key(context.getString(R.string.passenger_keyboard_clear), TOP_KEY_COLOR) {
                typed = ""
                updateText()
            },
            LinearLayout.LayoutParams(0, -1, 2.5f)
        )
        textView.background = rounded(TEXT_COLOR, 8f)

        // Riga del testo alta quanto una riga di tasti: 6 righe uguali
        panel.addView(topRow, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = gap() })
        panel.addView(keyRows, LinearLayout.LayoutParams(-1, 0, 5f))
        view.addView(panel, FrameLayout.LayoutParams(-1, -1))
    }

    fun show(initialText: String, hint: String, onDone: (String) -> Unit) {
        typed = initialText
        this.hint = hint
        this.onDone = onDone
        keyboard.reset()
        buildKeys()
        updateText()
        view.visibility = View.VISIBLE
        onVisibilityChanged?.invoke(true)
    }

    fun hide() {
        view.visibility = View.GONE
        onDone = null
        onVisibilityChanged?.invoke(false)
    }

    /** Tutta l'area [area] libera dall'interfaccia di Android Auto, come la sua tastiera a schermo intero. */
    fun layout(area: Rect) {
        val margin = (8 * dp).toInt()
        val lp = panel.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = area.left + margin
        lp.topMargin = area.top + margin
        lp.width = (area.width() - 2 * margin).coerceAtLeast(0)
        lp.height = (area.height() - 2 * margin).coerceAtLeast(0)
        panel.layoutParams = lp
    }

    private fun onKey(key: PassengerKeyboard.Key) {
        val page = keyboard.page
        val shift = keyboard.shift
        when (val action = keyboard.press(key)) {
            is PassengerKeyboard.Action.Type -> typed += action.text
            PassengerKeyboard.Action.Delete -> typed = PassengerKeyboard.deleteLast(typed)
            PassengerKeyboard.Action.Enter -> {
                val done = onDone
                hide()
                done?.invoke(typed)
                return
            }
            PassengerKeyboard.Action.None -> Unit
        }
        if (keyboard.page != page || keyboard.shift != shift) buildKeys()
        updateText()
    }

    private fun updateText() {
        textView.text = if (typed.isEmpty()) {
            SpannableStringBuilder(hint).apply {
                setSpan(ForegroundColorSpan(HINT_COLOR.toColorInt()), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            SpannableStringBuilder(typed).append("▏").apply {
                setSpan(ForegroundColorSpan(ACCENT_COLOR.toColorInt()), length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun buildKeys() {
        keyRows.removeAllViews()
        keyboard.rows.forEachIndexed { index, row ->
            val rowView = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            // Le righe più corte restano al centro
            val side = (PassengerKeyboard.ROW_WEIGHT - row.sumOf { PassengerKeyboard.weight(it).toDouble() }.toFloat()) / 2
            if (side > 0f) rowView.addView(View(context), LinearLayout.LayoutParams(0, -1, side))
            row.forEachIndexed { i, key ->
                val color = if (keyboard.isHighlighted(key)) ACCENT_COLOR else KEY_COLOR
                rowView.addView(
                    key(keyboard.label(key), color) { onKey(key) },
                    LinearLayout.LayoutParams(0, -1, PassengerKeyboard.weight(key)).apply {
                        if (i < row.lastIndex) rightMargin = gap()
                    }
                )
            }
            if (side > 0f) rowView.addView(View(context), LinearLayout.LayoutParams(0, -1, side))
            keyRows.addView(rowView, LinearLayout.LayoutParams(-1, 0, 1f).apply {
                if (index < keyboard.rows.lastIndex) bottomMargin = gap()
            })
        }
    }

    private fun key(label: String, color: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        maxLines = 1
        setTextColor(Color.WHITE)
        background = rounded(color, 8f)
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 12, 28, 1, TypedValue.COMPLEX_UNIT_SP)
        isClickable = true
        setOnClickListener { v ->
            // Android Auto manda il tocco già finito: senza questo lampo non si vede che il tasto l'ha preso
            v.alpha = 0.5f
            v.postDelayed({ v.alpha = 1f }, KEY_FLASH_MS)
            onClick()
        }
    }

    private fun gap(): Int = (5 * dp).toInt()

    private fun rounded(color: String, radiusDp: Float) = GradientDrawable().apply {
        setColor(color.toColorInt())
        cornerRadius = radiusDp * dp
    }

    private companion object {
        const val PANEL_COLOR = "#202124"
        const val TEXT_COLOR = "#303134"
        const val KEY_COLOR = "#3C4043"
        const val TOP_KEY_COLOR = "#5F6368"
        const val ACCENT_COLOR = "#3B82F6"
        const val HINT_COLOR = "#9AA0A6"
        const val KEY_FLASH_MS = 120L
    }
}
