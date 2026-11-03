package com.fcaronte.aabrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.utils.PassengerKeyboard

/**
 * Altezza delle righe di tasti: al massimo [share] dello spazio [available] (le righe sono sempre 5), ma mai tasti
 * troppo piccoli da toccare in auto o così grandi da coprire tutta la pagina.
 */
fun passengerKeyHeight(available: Dp, share: Float): Dp = (available * share / 5).coerceIn(36.dp, 56.dp)

/**
 * Tasti della tastiera passeggero (Main in auto, al posto di quella di Android Auto). Lettere, maiuscola e pagine
 * dei simboli li gestisce lei; cosa fanno testo, cancellazione e invio lo decide chi la usa.
 */
@Composable
fun PassengerKeyboardKeys(
    onType: (String) -> Unit,
    onDelete: () -> Unit,
    onEnter: () -> Unit,
    keyHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboard = remember { PassengerKeyboard.create(context) }
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainer)
            // I tocchi tra un tasto e l'altro non arrivano a quello che c'è sotto
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        keyboard.rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().height(keyHeight),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Le righe più corte restano al centro
                val side = (keyboard.rowWeight - row.sumOf { keyboard.weight(it, row).toDouble() }.toFloat()) / 2
                if (side > 0f) Spacer(Modifier.weight(side))
                row.forEach { key ->
                    KeyboardKey(
                        label = keyboard.label(key),
                        highlighted = keyboard.isHighlighted(key),
                        modifier = Modifier.weight(keyboard.weight(key, row)).fillMaxHeight(),
                    ) {
                        when (val action = keyboard.press(key)) {
                            is PassengerKeyboard.Action.Type -> onType(action.text)
                            PassengerKeyboard.Action.Delete -> onDelete()
                            PassengerKeyboard.Action.Enter -> onEnter()
                            PassengerKeyboard.Action.None -> Unit
                        }
                    }
                }
                if (side > 0f) Spacer(Modifier.weight(side))
            }
        }
    }
}

@Composable
private fun KeyboardKey(label: String, highlighted: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlighted) colors.primary else colors.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (highlighted) colors.onPrimary else colors.onSurface,
            fontSize = if (label.length > 1) 16.sp else 22.sp,
            maxLines = 1,
        )
    }
}

/**
 * Tastiera passeggero per un campo della pagina (Main in auto): in alto la riga col testo, già quello del campo, e
 * sotto i tasti. Invio lo scrive nel campo ([onDone]), ✕ chiude senza scrivere. Il testo si vede sempre, anche se
 * la tastiera copre il campo.
 */
@Composable
fun PassengerKeyboardPanel(initialText: String, hint: String, onDone: (String) -> Unit, onClose: () -> Unit) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.BottomCenter,
    ) {
        val keyHeight = passengerKeyHeight(maxHeight, 0.6f)
        Column(modifier = Modifier.fillMaxWidth()) {
            PassengerTextLine(text = text, hint = hint, onClear = { text = "" }, onClose = onClose)
            PassengerKeyboardKeys(
                onType = { text += it },
                onDelete = { text = PassengerKeyboard.deleteLast(text) },
                onEnter = { onDone(text) },
                keyHeight = keyHeight,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PassengerTextLine(text: String, hint: String, onClear: () -> Unit, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    // Sempre sull'ultima parte scritta: a destra, o a sinistra per l'arabo (da destra a sinistra)
    val rtl by rememberUpdatedState(PassengerKeyboard.isRtl(text))
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.maxValue to rtl }.collect { (end, isRtl) -> scroll.scrollTo(if (isRtl) 0 else end) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerHigh)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close_button))
        }
        Box(modifier = Modifier.weight(1f).horizontalScroll(scroll)) {
            Text(
                text = buildAnnotatedString {
                    if (text.isEmpty()) {
                        withStyle(SpanStyle(color = colors.onSurfaceVariant)) { append(hint) }
                    } else {
                        append(text)
                        withStyle(SpanStyle(color = colors.primary)) { append("▏") }
                    }
                },
                color = colors.onSurface,
                fontSize = 22.sp,
                maxLines = 1,
                softWrap = false,
            )
        }
        TextButton(onClick = onClear, enabled = text.isNotEmpty()) {
            Text(stringResource(R.string.passenger_keyboard_clear))
        }
    }
}

/** Testo della tastiera passeggero al posto della selezione, o al cursore. */
fun TextFieldValue.typed(text: String): TextFieldValue {
    val newText = StringBuilder(this.text).replace(selection.min, selection.max, text).toString()
    return TextFieldValue(newText, TextRange(selection.min + text.length))
}

/** ⌫ della tastiera passeggero: la selezione, oppure il carattere prima del cursore. */
fun TextFieldValue.deletedBack(): TextFieldValue {
    if (!selection.collapsed) {
        val newText = StringBuilder(text).delete(selection.min, selection.max).toString()
        return TextFieldValue(newText, TextRange(selection.min))
    }
    val end = selection.start
    if (end == 0) return this
    val start = text.offsetByCodePoints(end, -1)
    return TextFieldValue(StringBuilder(text).delete(start, end).toString(), TextRange(start))
}
