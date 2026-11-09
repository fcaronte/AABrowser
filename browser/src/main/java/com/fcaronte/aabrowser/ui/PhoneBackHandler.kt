package com.fcaronte.aabrowser.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable

/**
 * Tasto o gesto "indietro" del telefono: tra quelli attivi risponde l'ultimo composto, quindi i riquadri aperti prima
 * della schermata sotto. In auto non fa niente: MainCarActivity non ha il dispatcher di androidx.activity, e senza
 * BackHandler lancerebbe un'eccezione.
 */
@Composable
fun PhoneBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    if (LocalOnBackPressedDispatcherOwner.current != null) BackHandler(enabled = enabled, onBack = onBack)
}
