package com.fcaronte.aabrowser.ui

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.fcaronte.aabrowser.CarInputManager
import com.fcaronte.aabrowser.R

@Composable
fun SearchOverlay(
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
    carInputManager: CarInputManager? = null,
    inputView: View? = null,
) {
    var queryValue by remember { mutableStateOf(value = TextFieldValue("")) }
    val context = LocalContext.current
    var isListening by remember { mutableStateOf(value = false) }
    val focusRequester = remember { FocusRequester() }

    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }

    DisposableEffect(Unit) {
        onDispose {
            speechRecognizer.destroy()
            carInputManager?.clearListeners()
        }
    }

    LaunchedEffect(queryValue) {
        carInputManager?.updateState(
            text = queryValue.text,
            selectionStart = queryValue.selection.start,
            selectionEnd = queryValue.selection.end,
        )
    }

    var lastCommitTime by remember { mutableLongStateOf(0L) }
    var lastText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (carInputManager != null && carInputManager.isValid) {
            carInputManager.setOnInputEventListener(
                onText = { text ->
                    val currentTime = System.currentTimeMillis()
                    // Debouncing: filtra caratteri identici troppo veloci (effetto burst tastiera remota)
                    if (currentTime - lastCommitTime < 100 && text == lastText) {
                        return@setOnInputEventListener
                    }
                    lastCommitTime = currentTime
                    lastText = text

                    // Se il cursore è in mezzo, il testo viene inserito lì
                    val selection = queryValue.selection
                    val currentText = queryValue.text
                    val newText =
                        StringBuilder(currentText).replace(selection.min, selection.max, text)
                            .toString()
                    queryValue = TextFieldValue(
                        text = newText,
                        selection = TextRange(selection.min + text.length)
                    )
                },
                onDelete = { length ->
                    val selection = queryValue.selection
                    val currentText = queryValue.text

                    val start = (selection.start - length).coerceAtLeast(0)
                    val newText =
                        StringBuilder(currentText).delete(start, selection.start).toString()
                    queryValue = TextFieldValue(text = newText, selection = TextRange(start))
                },
                onSelection = { start, end ->
                    // Sincronizzazione atomica: aggiorniamo solo se differente per evitare loop
                    if (queryValue.selection.start != start || queryValue.selection.end != end) {
                        queryValue = queryValue.copy(selection = TextRange(start, end))
                    }
                }
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = Color.Black.copy(alpha = 0.6f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.TopCenter,
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .padding(horizontal = 16.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(id = R.string.search_button),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = queryValue,
                        onValueChange = { queryValue = it },
                        label = {
                            if (isListening) {
                                Text(text = stringResource(id = R.string.voice_listening))
                            } else {
                                Text(text = stringResource(id = R.string.search_button))
                            }
                        },
                        modifier = Modifier
                            .weight(weight = 1f)
                            .focusRequester(focusRequester = focusRequester)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused && carInputManager != null && carInputManager.isValid) {
                                    inputView?.let { view ->
                                        view.requestFocus()
                                        carInputManager.startInput(view)
                                    }
                                }
                            },
                        trailingIcon = {
                            if (queryValue.text.isNotEmpty()) {
                                IconButton(onClick = { queryValue = TextFieldValue("") }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = null
                                    )
                                }
                            }
                        },
                    )

                    IconButton(
                        onClick = {
                            isListening = true
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(
                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                )
                            }
                            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                                override fun onReadyForSpeech(params: Bundle?) {}
                                override fun onBeginningOfSpeech() {}
                                override fun onRmsChanged(rmsdB: Float) {}
                                override fun onBufferReceived(buffer: ByteArray?) {}
                                override fun onEndOfSpeech() {
                                    isListening = false
                                }

                                override fun onError(error: Int) {
                                    isListening = false
                                }

                                override fun onResults(results: Bundle?) {
                                    val matches =
                                        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    matches?.firstOrNull()?.let { spokenText ->
                                        val selection = queryValue.selection
                                        val currentText = queryValue.text
                                        val newText = StringBuilder(currentText).insert(
                                            selection.start,
                                            spokenText
                                        ).toString()
                                        queryValue = TextFieldValue(
                                            text = newText,
                                            selection = TextRange(index = selection.start + spokenText.length)
                                        )
                                    }
                                    isListening = false
                                }

                                override fun onPartialResults(partialResults: Bundle?) {}
                                override fun onEvent(eventType: Int, params: Bundle?) {}
                            })
                            speechRecognizer.startListening(intent)
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice Search",
                            tint = if (isListening) Color.Red else MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onDismiss() }) {
                        Text(text = stringResource(id = R.string.cancel_button))
                    }
                    Button(onClick = {
                        if (queryValue.text.isNotBlank()) {
                            onSearch(queryValue.text)
                        }
                    }) {
                        Text(text = stringResource(id = R.string.confirm_button))
                    }
                }
            }
        }
    }
}