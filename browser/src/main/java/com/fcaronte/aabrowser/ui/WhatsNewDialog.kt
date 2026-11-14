package com.fcaronte.aabrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.utils.WhatsNew

/**
 * Le novità della versione, la prima volta che si apre l'app dopo un aggiornamento (WhatsNew). Sul telefono un riquadro,
 * sullo schermo dell'auto una scheda sopra la pagina (una finestra di dialogo non si vedrebbe). Lo stato è unico per l'app:
 * chiuse in un posto spariscono dall'altro, e chi le ha già lette sul telefono non le rivede in auto. Sullo schermo
 * dell'auto in movimento le copre l'avviso di guida (sta sopra).
 */
@Composable
fun WhatsNewHost(inCar: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { WhatsNew.check(context) }
    val notes = WhatsNew.current ?: return
    if (inCar) {
        WhatsNewOverlay(notes) { WhatsNew.dismiss(context) }
    } else {
        WhatsNewDialog(notes) { WhatsNew.dismiss(context) }
    }
}

/** Il testo delle note: righe vuote = paragrafi, "• " = elenco. */
@Composable
private fun WhatsNewText(notes: WhatsNew.Notes, size: Int = 14) {
    notes.text.lines().forEach { line ->
        if (line.isBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            Text(
                text = line,
                fontSize = size.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
    }
}

/** Il riquadro del telefono: titolo con la versione, testo che scorre, OK. */
@Composable
fun WhatsNewDialog(notes: WhatsNew.Notes, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.whats_new_title, notes.versionName)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                WhatsNewText(notes)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok_button)) }
        }
    )
}

/**
 * La stessa cosa come scheda dello schermo, per l'auto (e le Impostazioni in auto): al centro, il testo scorre e OK resta in
 * vista; un tocco fuori o Indietro la chiudono. Non è una finestra di dialogo: sullo schermo dell'auto non si vedrebbe.
 */
@Composable
fun WhatsNewOverlay(notes: WhatsNew.Notes, onDismiss: () -> Unit) {
    PhoneBackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(90f)
            .background(color = Color.Black.copy(alpha = 0.6f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 560.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { },
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.whats_new_title, notes.versionName),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    WhatsNewText(notes, size = 15)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = onDismiss) { Text(stringResource(R.string.ok_button)) }
                }
            }
        }
    }
}
