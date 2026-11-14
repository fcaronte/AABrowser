package com.fcaronte.aabrowser.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.utils.WhatsNew
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Le novità della versione, la prima volta che si apre l'app dopo un aggiornamento (WhatsNew.pending): un riquadro con le
 * note, chiuso con OK. Solo sul telefono: sullo schermo dell'auto una finestra non si vedrebbe.
 */
@Composable
fun WhatsNewHost() {
    val context = LocalContext.current
    var notes by remember { mutableStateOf<WhatsNew.Notes?>(null) }
    LaunchedEffect(Unit) {
        notes = withContext(Dispatchers.IO) { WhatsNew.pending(context) }
    }
    notes?.let { WhatsNewDialog(it) { notes = null } }
}

/** Il riquadro con le note: titolo con la versione, testo che scorre, OK. Righe vuote = paragrafi, "• " = elenco. */
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
                notes.text.lines().forEach { line ->
                    if (line.isBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                    } else {
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok_button)) }
        }
    )
}
