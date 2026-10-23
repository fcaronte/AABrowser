package com.fcaronte.aabrowser.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.fcaronte.aabrowser.utils.AppLog

/**
 * Microfono dei comandi vocali della Main (barra delle schede e menu): ascolta una frase e passa a [onResult]
 * le trascrizioni (la più probabile per prima). [onError] riceve il codice di SpeechRecognizer
 * (ERROR_INSUFFICIENT_PERMISSIONS anche senza il permesso del microfono, ERROR_CLIENT se il riconoscimento manca).
 */
class VoiceCommandInput internal constructor(
    private val context: Context,
    private val onResult: (List<String>) -> Unit,
    private val onError: (Int) -> Unit
) {
    var isListening by mutableStateOf(false)
        private set

    /** Quello che si sta capendo mentre si parla. */
    var partialText by mutableStateOf<String?>(null)
        private set

    private var recognizer: SpeechRecognizer? = null

    fun toggle() {
        if (isListening) cancel() else start()
    }

    fun start() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        val speech = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        partialText = null
        isListening = true
        try {
            speech.cancel()
            speech.setRecognitionListener(listener)
            speech.startListening(intent)
        } catch (e: Exception) {
            AppLog.e(TAG, "startListening failed", e)
            isListening = false
            onError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    fun cancel() {
        isListening = false
        partialText = null
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
    }

    internal fun release() {
        cancel()
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            // Annullato da noi (secondo tocco, schermata chiusa): niente avvisi
            if (!isListening) return
            isListening = false
            partialText = null
            onError(error)
        }

        override fun onResults(results: Bundle?) {
            if (!isListening) return
            isListening = false
            partialText = null
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                .filter { it.isNotBlank() }
            if (texts.isEmpty()) onError(SpeechRecognizer.ERROR_NO_MATCH) else onResult(texts)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull { it.isNotBlank() }
                ?.let { partialText = it }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private companion object {
        const val TAG = "VoiceCommandInput"
    }
}

@Composable
fun rememberVoiceCommandInput(onResult: (List<String>) -> Unit, onError: (Int) -> Unit): VoiceCommandInput {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnError by rememberUpdatedState(onError)
    val input = remember(context) {
        VoiceCommandInput(context, onResult = { currentOnResult(it) }, onError = { currentOnError(it) })
    }
    DisposableEffect(input) {
        onDispose { input.release() }
    }
    return input
}
