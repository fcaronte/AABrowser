package com.fcaronte.aabrowser.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.utils.BackupRestoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup e ripristino in un file scelto con il selettore di Android (solo sul telefono: in auto non si apre).
 * [onRestored]: il ripristino è riuscito, l'app va riavviata per rileggere tutto.
 */
@Composable
internal fun BackupCard(onRestored: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmImport by remember { mutableStateOf(false) }
    LaunchedEffect(confirmImport) {
        if (confirmImport) {
            delay(4000)
            confirmImport = false
        }
    }

    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { BackupRestoreUtils.export(context, it) } == true
                }.getOrDefault(false)
            }
            Toast.makeText(context, if (saved) R.string.backup_success else R.string.backup_error, Toast.LENGTH_LONG).show()
        }
    }

    // Un file .json non ha un tipo su cui contare: dipende da chi l'ha salvato
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val restored = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { BackupRestoreUtils.import(context, it) } == true
                }.getOrDefault(false)
            }
            Toast.makeText(context, if (restored) R.string.import_success else R.string.import_error, Toast.LENGTH_LONG).show()
            if (restored) onRestored()
        }
    }

    SettingsCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.export_settings_label),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.export_settings_desc),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                        exportFile.launch("carweb_backup_$day.json")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.export_button))
                }
                // Il ripristino sostituisce tutto: prima un secondo tocco di conferma
                OutlinedButton(
                    onClick = {
                        if (confirmImport) {
                            confirmImport = false
                            importFile.launch(arrayOf("*/*"))
                        } else {
                            confirmImport = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (confirmImport) MaterialTheme.colorScheme.errorContainer else Color.Transparent,
                        contentColor = if (confirmImport) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (confirmImport) stringResource(R.string.are_you_sure) else stringResource(R.string.import_button))
                }
            }
        }
    }
}
