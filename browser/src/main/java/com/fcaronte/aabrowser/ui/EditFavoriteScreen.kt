package com.fcaronte.aabrowser.ui

import android.view.View
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fcaronte.aabrowser.CarInputManager
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.model.FavoriteSite
import com.fcaronte.aabrowser.settings.AppSettings
import java.net.URI

@Composable
fun EditFavoriteScreen(
    site: FavoriteSite? = null,
    currentWebView: WebView? = null,
    carInputManager: CarInputManager? = null,
    inputHostView: View? = null,
    onBack: () -> Unit,
    onConfirm: (String, String, Long, String?, Boolean?, Float?, Float?) -> Unit,
) {
    var nameValue by remember { mutableStateOf(TextFieldValue(site?.name ?: "")) }
    var urlValue by remember { mutableStateOf(TextFieldValue(site?.url ?: "")) }
    var faviconValue by remember { mutableStateOf(TextFieldValue(site?.faviconUrl ?: "")) }

    var isDesktopOverride by remember { mutableStateOf(site?.isDesktopMode) }
    var mobileZoomOverride by remember { mutableStateOf(site?.mobileZoom) }
    var desktopZoomOverride by remember { mutableStateOf(site?.desktopZoom) }
    var siteDataExpanded by remember { mutableStateOf(site == null) }
    var displaySettingsExpanded by remember { mutableStateOf(site != null) }

    val dynamicPrimaryColor = MaterialTheme.colorScheme.primary.toArgb().toLong() and 0xFFFFFFFFL
    var color by remember { mutableLongStateOf(site?.color ?: dynamicPrimaryColor) }
    val colorOptions = listOf(dynamicPrimaryColor, 0xFFFF0000, 0xFF34A853, 0xFFFBBC05, 0xFF24292E)

    var focusedField by remember { mutableStateOf(0) } // 0: none, 1: name, 2: url, 3: favicon

    DisposableEffect(Unit) {
        onDispose {
            carInputManager?.clearListeners()
        }
    }

    LaunchedEffect(focusedField, nameValue, urlValue, faviconValue) {
        if (carInputManager != null && focusedField > 0) {
            val currentValue = when (focusedField) {
                1 -> nameValue
                2 -> urlValue
                3 -> faviconValue
                else -> TextFieldValue("")
            }
            carInputManager.updateState(
                text = currentValue.text,
                selectionStart = currentValue.selection.start,
                selectionEnd = currentValue.selection.end
            )
        }
    }

    var lastCommitTime by remember { mutableLongStateOf(0L) }
    var lastText by remember { mutableStateOf("") }

    LaunchedEffect(focusedField) {
        if (focusedField > 0 && carInputManager != null) {
            carInputManager.setOnInputEventListener(
                onText = { text ->
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastCommitTime < 100 && text == lastText) {
                        return@setOnInputEventListener
                    }
                    lastCommitTime = currentTime
                    lastText = text

                    when (focusedField) {
                        1 -> {
                            val selection = nameValue.selection
                            val newText = StringBuilder(nameValue.text).replace(selection.min, selection.max, text).toString()
                            nameValue = nameValue.copy(text = newText, selection = TextRange(selection.min + text.length))
                        }
                        2 -> {
                            val selection = urlValue.selection
                            val newText = StringBuilder(urlValue.text).replace(selection.min, selection.max, text).toString()
                            urlValue = urlValue.copy(text = newText, selection = TextRange(selection.min + text.length))
                        }
                        3 -> {
                            val selection = faviconValue.selection
                            val newText = StringBuilder(faviconValue.text).replace(selection.min, selection.max, text).toString()
                            faviconValue = faviconValue.copy(text = newText, selection = TextRange(selection.min + text.length))
                        }
                    }
                },
                onDelete = { length ->
                    when (focusedField) {
                        1 -> {
                            val selection = nameValue.selection
                            val start = (selection.start - length).coerceAtLeast(0)
                            val newText = StringBuilder(nameValue.text).delete(start, selection.start).toString()
                            nameValue = nameValue.copy(text = newText, selection = TextRange(start))
                        }
                        2 -> {
                            val selection = urlValue.selection
                            val start = (selection.start - length).coerceAtLeast(0)
                            val newText = StringBuilder(urlValue.text).delete(start, selection.start).toString()
                            urlValue = urlValue.copy(text = newText, selection = TextRange(start))
                        }
                        3 -> {
                            val selection = faviconValue.selection
                            val start = (selection.start - length).coerceAtLeast(0)
                            val newText = StringBuilder(faviconValue.text).delete(start, selection.start).toString()
                            faviconValue = faviconValue.copy(text = newText, selection = TextRange(start))
                        }
                    }
                },
                onSelection = { start, end ->
                    val currentValue = when (focusedField) {
                        1 -> nameValue
                        2 -> urlValue
                        3 -> faviconValue
                        else -> null
                    }
                    currentValue?.let {
                        if (it.selection.start != start || it.selection.end != end) {
                            val newVal = it.copy(selection = TextRange(start, end))
                            when (focusedField) {
                                1 -> nameValue = newVal
                                2 -> urlValue = newVal
                                3 -> faviconValue = newVal
                            }
                        }
                    }
                }
            )
        } else {
            carInputManager?.clearListeners()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back_button),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    text = if (site == null) stringResource(R.string.add_favorite_title) else stringResource(R.string.edit_favorite_title),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    SettingsSectionHeader(
                        title = stringResource(R.string.site_info_title),
                        isExpanded = siteDataExpanded,
                        onClick = { siteDataExpanded = !siteDataExpanded }
                    )
                }

                item {
                    AnimatedVisibility(visible = siteDataExpanded) {
                        SettingsCard {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedTextField(
                                    value = nameValue,
                                    onValueChange = { nameValue = it },
                                    label = { Text(stringResource(R.string.field_name)) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { focusState ->
                                            if (focusState.isFocused) {
                                                focusedField = 1
                                                inputHostView?.let { view ->
                                                    view.requestFocus()
                                                    carInputManager?.startInput(view)
                                                }
                                            }
                                        }
                                )

                                OutlinedTextField(
                                    value = urlValue,
                                    onValueChange = { urlValue = it },
                                    label = { Text(stringResource(R.string.field_url)) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { focusState ->
                                            if (focusState.isFocused) {
                                                focusedField = 2
                                                inputHostView?.let { view ->
                                                    view.requestFocus()
                                                    carInputManager?.startInput(view)
                                                }
                                            }
                                        }
                                )

                                OutlinedTextField(
                                    value = faviconValue,
                                    onValueChange = { faviconValue = it },
                                    label = { Text(stringResource(R.string.field_favicon)) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { focusState ->
                                            if (focusState.isFocused) {
                                                focusedField = 3
                                                inputHostView?.let { view ->
                                                    view.requestFocus()
                                                    carInputManager?.startInput(view)
                                                }
                                            }
                                        }
                                )

                                Text(
                                    text = stringResource(R.string.field_color),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    colorOptions.forEach { c ->
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .background(Color(c), RoundedCornerShape(4.dp))
                                                .clickable { color = c }
                                                .padding(4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (color == c) Icon(
                                                Icons.Default.Check,
                                                null,
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    SettingsSectionHeader(
                        title = stringResource(R.string.display_settings_title),
                        isExpanded = displaySettingsExpanded,
                        onClick = { displaySettingsExpanded = !displaySettingsExpanded }
                    )
                }

                item {
                    AnimatedVisibility(visible = displaySettingsExpanded) {
                        val isDesktop = isDesktopOverride ?: AppSettings.desktopMode.value
                        SettingsCard {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                SettingsSwitchItem(
                                    label = stringResource(R.string.desktop_mode_label),
                                    description = stringResource(R.string.desktop_mode_desc_site),
                                    checked = isDesktop,
                                    onCheckedChange = { isDesktopOverride = it }
                                )

                                Column {
                                    val currentZoom =
                                        if (isDesktop) (desktopZoomOverride ?: AppSettings.desktopScale.value)
                                        else (mobileZoomOverride ?: AppSettings.displayScale.value)

                                    Text(
                                        text = (if (isDesktop) stringResource(R.string.desktop_zoom_label, (currentZoom * 100).toInt())
                                        else stringResource(R.string.mobile_zoom_label, (currentZoom * 100).toInt())),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Slider(
                                        value = currentZoom,
                                        onValueChange = {
                                            if (isDesktop) desktopZoomOverride = it
                                            else mobileZoomOverride = it
                                        },
                                        valueRange = 0.25f..1.5f,
                                        steps = 24
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    val finalUrl =
                        if (!urlValue.text.startsWith("http://") && !urlValue.text.startsWith("https://")) "https://${urlValue.text}" else urlValue.text

                    if (faviconValue.text.isBlank()) {
                        if (currentWebView != null && currentWebView.url?.contains(URI(finalUrl).host ?: "") == true) {
                            currentWebView.evaluateJavascript(
                                "(function() { const icon = document.querySelector('link[rel=\"apple-touch-icon\"]') || document.querySelector('link[rel=\"icon\"]'); return icon ? icon.href : ''; })();"
                            ) { result ->
                                val extracted = result?.removeSurrounding("\"")?.takeIf { it.isNotBlank() }
                                val fallback = "https://www.google.com/s2/favicons?domain=${URI(finalUrl).host ?: finalUrl}&sz=128"
                                onConfirm(
                                    nameValue.text,
                                    finalUrl,
                                    color,
                                    extracted ?: fallback,
                                    isDesktopOverride,
                                    mobileZoomOverride,
                                    desktopZoomOverride
                                )
                            }
                        } else {
                            val fallback = "https://www.google.com/s2/favicons?domain=${URI(finalUrl).host ?: finalUrl}&sz=128"
                            onConfirm(
                                nameValue.text,
                                finalUrl,
                                color,
                                fallback,
                                isDesktopOverride,
                                mobileZoomOverride,
                                desktopZoomOverride
                            )
                        }
                    } else {
                        onConfirm(
                            nameValue.text,
                            finalUrl,
                            color,
                            faviconValue.text,
                            isDesktopOverride,
                            mobileZoomOverride,
                            desktopZoomOverride
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(stringResource(R.string.confirm_button), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}