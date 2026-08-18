package com.fcaronte.aabrowser.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.WebView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.settings.FABLocation
import androidx.compose.runtime.mutableFloatStateOf
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import com.fcaronte.aabrowser.CarInputManager

data class MenuItem(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val labelRes: Int,
    val onClick: () -> Unit,
)

@Composable
fun NavigationOverlay(
    currentUrl: String,
    onGoBack: () -> Unit,
    onGoHome: () -> Unit,
    onReload: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTabManager: () -> Unit,
    onSearch: (String) -> Unit,
    onAddToFavorites: () -> Unit = {},
    carInputManager: CarInputManager? = null,
    webView: WebView? = null,
) {
    var showMenu by remember { mutableStateOf(value = false) }
    var showQRCode by remember { mutableStateOf(value = false) }
    var showSearchDialog by remember { mutableStateOf(value = false) }
    
    val context = LocalContext.current
    val fabLocation by AppSettings.fabLocation

    val alignment = when (fabLocation) {
        FABLocation.BOTTOM_RIGHT -> Alignment.BottomEnd
        FABLocation.BOTTOM_LEFT -> Alignment.BottomStart
        FABLocation.TOP_RIGHT -> Alignment.TopEnd
        FABLocation.TOP_LEFT -> Alignment.TopStart
    }

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(alignment)
                .offset { IntOffset(x = offsetX.roundToInt(), y = offsetY.roundToInt()) }
                .padding(all = 16.dp),
        ) {
            Box {
                val menuItems = remember(currentUrl) {
                    listOf(
                        MenuItem(icon = Icons.AutoMirrored.Filled.ArrowBack, labelRes = R.string.back_button) { onGoBack(); showMenu = false },
                        MenuItem(icon = Icons.Default.Home, labelRes = R.string.home_button) { onGoHome(); showMenu = false },
                        MenuItem(icon = Icons.Default.Layers, labelRes = R.string.tabs_button) { onOpenTabManager(); showMenu = false },
                        MenuItem(icon = Icons.Default.Star, labelRes = R.string.add_to_favorites) { onAddToFavorites(); showMenu = false },
                        MenuItem(icon = Icons.Default.Refresh, labelRes = R.string.reload_button) { onReload(); showMenu = false },
                        MenuItem(icon = Icons.Default.Search, labelRes = R.string.search_button) { showSearchDialog = true; showMenu = false },
                        MenuItem(icon = Icons.Default.Share, labelRes = R.string.share_button) { showQRCode = true; showMenu = false },
                        MenuItem(icon = Icons.Default.Settings, labelRes = R.string.settings_button) { onOpenSettings(); showMenu = false },
                        MenuItem(icon = Icons.AutoMirrored.Filled.ExitToApp, labelRes = R.string.exit_button) {
                            showMenu = false
                            try {
                                (context as? Activity)?.finishAndRemoveTask()
                            } catch (_: Exception) {
                                (context as? Activity)?.finish()
                            }
                        },
                    )
                }

                if (showMenu) {
                    val isLeft = (fabLocation == FABLocation.BOTTOM_LEFT) || (fabLocation == FABLocation.TOP_LEFT)
                    val isTop = (fabLocation == FABLocation.TOP_LEFT) || (fabLocation == FABLocation.TOP_RIGHT)
                    
                    val startAngle = when {
                        isLeft && isTop -> 0f
                        !isLeft && isTop -> 90f
                        !isLeft && !isTop -> 180f
                        else -> 270f
                    }
                    
                    val sweepAngle = 90f
                    val innerRadius = 105.dp
                    val outerRadius = 180.dp
                    
                    menuItems.forEachIndexed { index, item ->
                        val isOuter = index >= 3
                        val radius = if (isOuter) outerRadius else innerRadius
                        val arcIndex = if (isOuter) index - 3 else index
                        val arcTotal = if (isOuter) 6 else 3
                        
                        val angle = startAngle + (sweepAngle / (arcTotal - 1).coerceAtLeast(1)) * arcIndex
                        val angleRad = Math.toRadians(angle.toDouble())
                        val targetX = (cos(angleRad) * radius.value).dp
                        val targetY = (sin(angleRad) * radius.value).dp
                        
                        val animatedOffsetX by animateDpAsState(
                            targetValue = targetX,
                            animationSpec = androidx.compose.animation.core.spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy),
                            label = "offsetX",
                        )
                        val animatedOffsetY by animateDpAsState(
                            targetValue = targetY,
                            animationSpec = androidx.compose.animation.core.spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy),
                            label = "offsetY",
                        )

                        FloatingActionButton(
                            onClick = item.onClick,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            shape = CircleShape,
                            modifier = Modifier
                                .size(46.dp)
                                .offset { IntOffset(x = animatedOffsetX.roundToPx(), y = animatedOffsetY.roundToPx()) },
                        ) {
                            Icon(imageVector = item.icon, contentDescription = stringResource(id = item.labelRes), modifier = Modifier.size(22.dp))
                        }
                    }
                }

                LargeFloatingActionButton(
                    onClick = { showMenu = !showMenu },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(58.dp)
                        .then(
                            if (!showMenu) {
                                Modifier.pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        offsetX += dragAmount.x
                                        offsetY += dragAmount.y
                                    }
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Icon(
                        imageVector = if (showMenu) Icons.Default.Close else Icons.Default.Menu,
                        contentDescription = stringResource(R.string.menu_button),
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }

        // Overlay QR Code
        if (showQRCode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = Color.Black.copy(alpha = 0.6f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { showQRCode = false },
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .size(320.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(id = R.string.qr_code_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        val qrBitmap = remember(currentUrl) {
                            QRCodeUtils.generateQRCode(currentUrl, 512)
                        }
                        
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "QR Code",
                                modifier = Modifier.size(180.dp),
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        TextButton(onClick = { showQRCode = false }) {
                            Text(text = stringResource(id = R.string.close_button))
                        }
                    }
                }
            }
        }
        
        // Overlay Ricerca
        if (showSearchDialog) {
            SearchOverlay(
                onSearch = { query ->
                    onSearch(query)
                    showSearchDialog = false
                },
                onDismiss = { showSearchDialog = false },
                carInputManager = carInputManager,
                inputView = webView,
            )
        }
    }
}

@Composable
fun SearchOverlay(
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
    carInputManager: CarInputManager? = null,
    inputView: android.view.View? = null,
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

    var isInternalUpdate by remember { mutableStateOf(value = false) }

    LaunchedEffect(queryValue) {
        if (!isInternalUpdate) {
            carInputManager?.updateState(
                text = queryValue.text,
                selectionStart = queryValue.selection.start,
                selectionEnd = queryValue.selection.end,
            )
        }
        isInternalUpdate = false
    }

    LaunchedEffect(Unit) {
        if (carInputManager != null && carInputManager.isValid) {
            carInputManager.setOnInputEventListener(
                onText = { text -> 
                    isInternalUpdate = true
                    val selection = queryValue.selection
                    val currentText = queryValue.text
                    val newText = StringBuilder(currentText).insert(selection.start, text).toString()
                    val newSelection = TextRange(index = selection.start + text.length)
                    queryValue = TextFieldValue(text = newText, selection = newSelection)
                },
                onDelete = { length -> 
                    isInternalUpdate = true
                    val selection = queryValue.selection
                    val currentText = queryValue.text
                    if (selection.start > 0) {
                        val start = (selection.start - length).coerceAtLeast(0)
                        val newText = StringBuilder(currentText).delete(start, selection.start).toString()
                        queryValue = TextFieldValue(text = newText, selection = TextRange(index = start))
                    }
                },
            ) { start, end ->
                isInternalUpdate = true
                queryValue = queryValue.copy(selection = TextRange(start = start, end = end))
            }
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
                                    Icon(imageVector = Icons.Default.Close, contentDescription = null)
                                }
                            }
                        },
                    )
                    
                    IconButton(
                        onClick = {
                            isListening = true
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
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
                                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    matches?.firstOrNull()?.let { spokenText ->
                                        val selection = queryValue.selection
                                        val currentText = queryValue.text
                                        val newText = StringBuilder(currentText).insert(selection.start, spokenText).toString()
                                        queryValue = TextFieldValue(text = newText, selection = TextRange(index = selection.start + spokenText.length))
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
