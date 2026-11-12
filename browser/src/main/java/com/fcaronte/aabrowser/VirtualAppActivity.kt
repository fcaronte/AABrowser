package com.fcaronte.aabrowser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fcaronte.aabrowser.ui.AABrowserTheme
import com.fcaronte.aabrowser.smartphone.FavoriteAppsManager
import com.fcaronte.aabrowser.smartphone.NormalVirtualDisplayRenderer
import com.fcaronte.aabrowser.smartphone.ShizukuAppBridge
import com.fcaronte.aabrowser.smartphone.ShizukuManager
import kotlin.math.hypot

class VirtualAppActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        if (packageName.isNullOrBlank()) {
            finish()
            return
        }
        setContent {
            AABrowserTheme {
                VirtualAppViewer(packageName = packageName, onClose = ::finish)
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "virtual_app_package"

        fun open(context: Context, packageName: String) {
            val intent = Intent(context, VirtualAppActivity::class.java)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)
            if (context !is ComponentActivity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}

@Composable
fun VirtualAppViewer(
    packageName: String,
    onClose: () -> Unit,
    handleSystemBack: Boolean = true,
) {
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val renderWidth = metrics.widthPixels.coerceIn(320, 4096)
    val renderHeight = metrics.heightPixels.coerceIn(320, 4096)
    val renderDensity = metrics.densityDpi.coerceAtLeast(120)
    var bitmap by remember(packageName) { mutableStateOf<Bitmap?>(null) }
    var displayId by remember(packageName) { mutableIntStateOf(-1) }
    var launchError by remember(packageName) { mutableStateOf<String?>(null) }
    var launched by remember(packageName) { mutableStateOf(false) }
    var viewSize by remember(packageName) { mutableStateOf(IntSize.Zero) }
    var touchStart by remember(packageName) { mutableStateOf<Pair<Float, Float>?>(null) }

    val renderer = remember(packageName) {
        NormalVirtualDisplayRenderer(
            width = renderWidth,
            height = renderHeight,
            onFrameAvailable = { bitmap = it }
        )
    }
    val latestDisplayId = rememberUpdatedState(displayId)
    val activityAlive = remember(packageName) { java.util.concurrent.atomic.AtomicBoolean(true) }

    LaunchedEffect(renderer) {
        if (!ShizukuManager.isShizukuRunning() || !ShizukuManager.hasPermission()) {
            launchError = "Avvia Shizuku e concedi l'autorizzazione a CarWeb."
            return@LaunchedEffect
        }
        val displaySurface = renderer.start()
        if (displaySurface == null) {
            launchError = "Impossibile preparare la superficie virtuale."
            return@LaunchedEffect
        }
        ShizukuAppBridge.createDisplay(context, displaySurface, renderWidth, renderHeight, renderDensity) { createdId, message ->
            if (createdId <= 0) {
                launchError = message.ifBlank { "Impossibile creare il display virtuale." }
                return@createDisplay
            }
            if (!activityAlive.get()) {
                ShizukuAppBridge.releaseDisplay(context, createdId)
                return@createDisplay
            }
            displayId = createdId
            ShizukuAppBridge.launchFavoriteApp(context, packageName, createdId) { success, launchMessage ->
                if (!activityAlive.get()) {
                    ShizukuAppBridge.releaseDisplay(context, createdId)
                    return@launchFavoriteApp
                }
                launched = success
                if (!success) launchError = launchMessage.ifBlank { "Avvio dell'app non riuscito." }
            }
        }
    }

    DisposableEffect(renderer) {
        onDispose {
            activityAlive.set(false)
            val activeDisplayId = latestDisplayId.value
            if (activeDisplayId > 0) {
                ShizukuAppBridge.releaseDisplay(context, activeDisplayId)
            }
            renderer.stop()
        }
    }

    if (handleSystemBack) BackHandler(onBack = onClose)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                }
                Text(
                    text = runCatching {
                        context.packageManager.getApplicationLabel(
                            context.packageManager.getApplicationInfo(packageName, 0)
                        ).toString()
                    }.getOrDefault(packageName),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .onSizeChanged { viewSize = it }
                    .pointerInteropFilter { event ->
                        if (displayId <= 0 || viewSize.width <= 0 || viewSize.height <= 0) return@pointerInteropFilter true
                        val x = (event.x * renderWidth / viewSize.width).toInt().coerceIn(0, renderWidth - 1)
                        val y = (event.y * renderHeight / viewSize.height).toInt().coerceIn(0, renderHeight - 1)
                        when (event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> touchStart = event.x to event.y
                            android.view.MotionEvent.ACTION_UP -> {
                                val start = touchStart
                                if (launched && start != null) {
                                    val startX = (start.first * renderWidth / viewSize.width).toInt().coerceIn(0, renderWidth - 1)
                                    val startY = (start.second * renderHeight / viewSize.height).toInt().coerceIn(0, renderHeight - 1)
                                    if (hypot(event.x - start.first, event.y - start.second) < 18f) {
                                        ShizukuAppBridge.tap(context, displayId, renderWidth, renderHeight, x, y)
                                    } else {
                                        ShizukuAppBridge.swipe(
                                            context, displayId, renderWidth, renderHeight,
                                            startX, startY, x, y,
                                            (event.eventTime - event.downTime).toInt().coerceIn(80, 1000)
                                        )
                                    }
                                }
                                touchStart = null
                            }
                            android.view.MotionEvent.ACTION_CANCEL -> touchStart = null
                        }
                        true
                    },
                contentAlignment = Alignment.Center
            ) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )
                }
                if (bitmap == null && launchError == null) CircularProgressIndicator()
                if (launchError != null) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(launchError.orEmpty(), color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(12.dp))
                        if (!ShizukuManager.hasPermission()) {
                            Button(onClick = { ShizukuManager.requestPermission() }) {
                                Text("Autorizza Shizuku")
                            }
                        }
                    }
                }
            }
        }
    }
}
