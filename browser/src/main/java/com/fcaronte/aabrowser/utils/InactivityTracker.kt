package com.fcaronte.aabrowser.utils

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

object InactivityTracker {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var timerJob: Job? = null

    private val _isVisible = mutableStateOf(true)
    val isVisible: State<Boolean> = _isVisible

    var isMenuOpen: Boolean = false
        set(value) {
            field = value
            if (value) {
                _isVisible.value = true
                timerJob?.cancel()
            } else {
                notifyInteraction(5000L, false)
            }
        }

    fun notifyInteraction(timeoutMillis: Long = 5000L, isPersistent: Boolean = false) {
        _isVisible.value = true
        timerJob?.cancel()

        if (!isPersistent && !isMenuOpen) {
            timerJob = scope.launch {
                delay(timeoutMillis.milliseconds)
                _isVisible.value = false
            }
        }
    }

    fun reset() {
        timerJob?.cancel()
        _isVisible.value = true
    }
}