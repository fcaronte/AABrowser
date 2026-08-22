package com.fcaronte.aabrowser.ui

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fcaronte.aabrowser.CarInputManager
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.settings.FABLocation
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
    onSearchClick: () -> Unit,
    onSearchOverlayDismiss: () -> Unit,
    onExit: () -> Unit,
    onAddToFavorites: () -> Unit = {},
    showSearchDialogOverride: Boolean = false,
    carInputManager: CarInputManager? = null,
    webView: WebView? = null,
    isVisible: Boolean = true,
    showMenu: Boolean = false,
    onShowMenuChange: (Boolean) -> Unit = {},
    onInteraction: () -> Unit = {},
) {
    var showQRCode by remember { mutableStateOf(value = false) }

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
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .align(alignment)
                        .offset { IntOffset(x = offsetX.roundToInt(), y = offsetY.roundToInt()) }
                        .padding(all = 16.dp),
                ) {
                    Box {
                        val menuItems = remember(currentUrl) {
                            listOf(
                                MenuItem(
                                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                                    labelRes = R.string.back_button
                                ) { onInteraction(); onGoBack(); onShowMenuChange(false) },
                                MenuItem(
                                    icon = Icons.Default.Home,
                                    labelRes = R.string.home_button
                                ) { onInteraction(); onGoHome(); onShowMenuChange(false) },
                                MenuItem(
                                    icon = Icons.Default.Layers,
                                    labelRes = R.string.tabs_button
                                ) { onInteraction(); onOpenTabManager(); onShowMenuChange(false) },
                                MenuItem(
                                    icon = Icons.Default.Star,
                                    labelRes = R.string.add_to_favorites
                                ) { onInteraction(); onAddToFavorites(); onShowMenuChange(false) },
                                MenuItem(
                                    icon = Icons.Default.Refresh,
                                    labelRes = R.string.reload_button
                                ) { onInteraction(); onReload(); onShowMenuChange(false) },
                                MenuItem(
                                    icon = Icons.Default.Search,
                                    labelRes = R.string.search_button
                                ) { onInteraction(); onSearchClick(); onShowMenuChange(false) },
                                MenuItem(
                                    icon = Icons.Default.Share,
                                    labelRes = R.string.share_button
                                ) { onInteraction(); showQRCode = true; onShowMenuChange(false) },
                                MenuItem(
                                    icon = Icons.Default.Settings,
                                    labelRes = R.string.settings_button
                                ) { onInteraction(); onOpenSettings(); onShowMenuChange(false) },
                                MenuItem(
                                    icon = Icons.AutoMirrored.Filled.ExitToApp,
                                    labelRes = R.string.exit_button
                                ) {
                                    onInteraction()
                                    onShowMenuChange(false)
                                    onExit()
                                },
                            )
                        }

                        val isLeft =
                            (fabLocation == FABLocation.BOTTOM_LEFT) || (fabLocation == FABLocation.TOP_LEFT)
                        val isTop =
                            (fabLocation == FABLocation.TOP_LEFT) || (fabLocation == FABLocation.TOP_RIGHT)

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

                            val angle =
                                startAngle + (sweepAngle / (arcTotal - 1).coerceAtLeast(1)) * arcIndex
                            val angleRad = Math.toRadians(angle.toDouble())

                            val targetX = if (showMenu) (cos(angleRad) * radius.value).dp else 0.dp
                            val targetY = if (showMenu) (sin(angleRad) * radius.value).dp else 0.dp

                            val animatedOffsetX by animateDpAsState(
                                targetValue = targetX,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                label = "offsetX",
                            )
                            val animatedOffsetY by animateDpAsState(
                                targetValue = targetY,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                label = "offsetY",
                            )
                            val alpha by animateFloatAsState(
                                targetValue = if (showMenu) 1f else 0f,
                                label = "alpha"
                            )

                            if (alpha > 0f) {
                                FloatingActionButton(
                                    onClick = {
                                        onInteraction()
                                        item.onClick()
                                    },
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = alpha
                                    ),
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .size(46.dp)
                                        .graphicsLayer(alpha = alpha)
                                        .offset {
                                            IntOffset(
                                                x = animatedOffsetX.roundToPx(),
                                                y = animatedOffsetY.roundToPx()
                                            )
                                        },
                                ) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = stringResource(id = item.labelRes),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }

                        LargeFloatingActionButton(
                            onClick = {
                                onInteraction()
                                onShowMenuChange(!showMenu)
                            },
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
                                                onInteraction()

                                                // Calcola i nuovi offset
                                                val newX = offsetX + dragAmount.x
                                                val newY = offsetY + dragAmount.y

                                                // Confina l'offset per evitare che il tasto esca dallo schermo
                                                // Usiamo valori approssimativi basati sulla densità se possibile,
                                                // o limiti di sicurezza generosi.
                                                // Poiché l'allineamento è dinamico, i limiti dipendono da 'alignment'

                                                val maxW = 400f // Valore di sicurezza per schermi AA
                                                val maxH = 300f

                                                offsetX = when(alignment) {
                                                    Alignment.BottomEnd, Alignment.TopEnd -> newX.coerceIn(-maxW, 0f)
                                                    Alignment.BottomStart, Alignment.TopStart -> newX.coerceIn(0f, maxW)
                                                    else -> newX
                                                }

                                                offsetY = when(alignment) {
                                                    Alignment.BottomEnd, Alignment.BottomStart -> newY.coerceIn(-maxH, 0f)
                                                    Alignment.TopEnd, Alignment.TopStart -> newY.coerceIn(0f, maxH)
                                                    else -> newY
                                                }
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
        if (showSearchDialogOverride) {
            SearchOverlay(
                onSearch = { query ->
                    onSearch(query)
                    onSearchOverlayDismiss()
                },
                onDismiss = { onSearchOverlayDismiss() },
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