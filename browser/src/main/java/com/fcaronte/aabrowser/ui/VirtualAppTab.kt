package com.fcaronte.aabrowser.ui

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.smartphone.ShizukuManager
import com.fcaronte.aabrowser.smartphone.VirtualAppSession
import com.fcaronte.aabrowser.smartphone.VirtualApps

/**
 * Una scheda della Main con un'app del telefono sul suo display virtuale (indirizzo `app://pacchetto`): sta nella barra delle
 * schede come una pagina web. Il display disegna in una TextureView e i tocchi sulla vista vanno all'app così come sono.
 * Una TextureView e non una SurfaceView: disegna dentro la finestra come ogni altra vista, quindi funziona anche nella
 * finestra proiettata sull'auto e sotto gli strati trasparenti di Compose (una SurfaceView sull'auto restava vuota).
 *
 * Sopra l'app, la barra laterale (indietro, altre app, tastiera, chiudi) e in alto a destra un tasto che la mostra o la
 * nasconde: la barra copre un po' dell'app invece di restringerla, così il display non cambia misura a ogni tocco e le
 * app non si ridisegnano. Con la scheda non scelta la superficie si toglie e il display resta fermo, l'app continua a
 * girare (audio compreso).
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun VirtualAppTab(
    tabId: String,
    packageName: String,
    isActive: Boolean,
    inCar: Boolean,
    onOpenApps: () -> Unit,
    onClose: () -> Unit,
    onShowFeedback: (String) -> Unit,
) {
    val context = LocalContext.current
    val session = remember(tabId) { VirtualAppSession(context, packageName, inCar) }
    DisposableEffect(session) {
        VirtualApps.register(tabId, session)
        onDispose {
            VirtualApps.unregister(tabId, session)
            session.close()
        }
    }

    // Shizuku acceso o permesso dato mentre si guarda l'errore: riprova da solo
    DisposableEffect(session) {
        val remove = ShizukuManager.registerListeners(
            onPermissionResult = { _, _ -> session.retry() },
            onBinderReceived = { session.retry() }
        )
        onDispose(remove)
    }

    val texture = remember(session) { TextureInfo() }
    val density = LocalConfiguration.current.densityDpi
    val currentDensity by rememberUpdatedState(density)
    val active by rememberUpdatedState(isActive)

    val listener = remember(session) {
        object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                texture.surface?.release()
                val surface = Surface(surfaceTexture)
                texture.surface = surface
                texture.width = width
                texture.height = height
                if (active) session.attach(surface, width, height, currentDensity)
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                texture.width = width
                texture.height = height
                texture.surface?.let { if (active) session.attach(it, width, height, currentDensity) }
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                session.detach()
                texture.surface?.release()
                texture.surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
        }
    }

    // La scheda torna scelta: l'app si porta davanti (se si era chiusa riparte); non scelta: il display si ferma
    LaunchedEffect(isActive) {
        val current = texture.surface
        if (isActive) {
            if (current != null && current.isValid) {
                session.attach(current, texture.width, texture.height, currentDensity)
                session.bringToFront()
            }
        } else {
            session.detach()
        }
    }

    var sidebarVisible by rememberSaveable(tabId) { mutableStateOf(true) }
    val noField = stringResource(R.string.vapp_keyboard_no_field)
    val oldAndroid = stringResource(R.string.vapp_keyboard_old)
    val keyboardError = stringResource(R.string.vapp_keyboard_error, "")
    val toggleKeyboard: () -> Unit = {
        session.toggleKeyboard { result ->
            when {
                result == "no field" -> onShowFeedback(noField)
                result == "old android" -> onShowFeedback(oldAndroid)
                result.startsWith("error") -> onShowFeedback(keyboardError.trimEnd() + " " + result.removePrefix("error: "))
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = listener
                    // Il display ha le misure della vista: le coordinate del tocco sono già quelle dell'app
                    setOnTouchListener { _, event ->
                        session.touch(event)
                        true
                    }
                }
            }
        )

        VirtualAppStatus(session = session, onClose = onClose)

        if (sidebarVisible) {
            VirtualAppSidebar(
                modifier = Modifier.align(Alignment.CenterStart),
                onBack = { session.key(android.view.KeyEvent.KEYCODE_BACK) },
                onApps = onOpenApps,
                onKeyboard = toggleKeyboard,
                onClose = onClose
            )
        }

        // In alto a destra: mostra o nasconde la barra. Senza non si potrebbe tornare indietro dall'app a schermo intero
        IconButton(
            onClick = { sidebarVisible = !sidebarVisible },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(44.dp)
                .background(Color(0x99000000), CircleShape)
        ) {
            Icon(
                imageVector = if (sidebarVisible) Icons.AutoMirrored.Filled.MenuOpen else Icons.Default.Menu,
                contentDescription = stringResource(if (sidebarVisible) R.string.vapp_sidebar_hide else R.string.vapp_sidebar_show),
                tint = Color.White
            )
        }
    }
}

/** Superficie della vista: serve alla scheda quando torna scelta. */
private class TextureInfo {
    var surface: Surface? = null
    var width = 0
    var height = 0
}

@Composable
private fun VirtualAppSidebar(
    modifier: Modifier,
    onBack: () -> Unit,
    onApps: () -> Unit,
    onKeyboard: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(64.dp)
            .background(Color(0xE61E1E1E))
            // I tocchi sulla barra non arrivano all'app sotto, nemmeno fra un tasto e l'altro
            .pointerInput(Unit) {}
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SidebarButton(Icons.AutoMirrored.Filled.ArrowBack, R.string.vapp_back, onBack)
        SidebarButton(Icons.Default.Apps, R.string.apps_title, onApps)
        SidebarButton(Icons.Default.Keyboard, R.string.vapp_keyboard, onKeyboard)
        SidebarButton(Icons.Default.Close, R.string.vapp_close, onClose)
    }
}

@Composable
private fun SidebarButton(icon: ImageVector, label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Icon(imageVector = icon, contentDescription = stringResource(label), tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

/** Mentre l'app parte, o se non riesce: lo spiega (Shizuku spento o senza permesso, app non apribile) e propone cosa fare. */
@Composable
private fun VirtualAppStatus(session: VirtualAppSession, onClose: () -> Unit) {
    val problem = session.problem
    when {
        session.phase == VirtualAppSession.Phase.RUNNING -> Unit
        problem == null -> Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(color = Color.White)
            // Il passo (n/3) dice dove si ferma l'apertura, se non parte: 1 vista pronta, 2 display, 3 app
            Text(
                text = stringResource(R.string.vapp_starting) + if (session.step > 0) " (${session.step}/3)" else "",
                color = Color.White,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        else -> Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 88.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = when (problem.kind) {
                    VirtualAppSession.Kind.SHIZUKU_MISSING -> stringResource(R.string.vapp_error_missing)
                    VirtualAppSession.Kind.SHIZUKU_STOPPED -> stringResource(R.string.vapp_error_stopped)
                    VirtualAppSession.Kind.SHIZUKU_PERMISSION -> stringResource(R.string.vapp_error_permission)
                    VirtualAppSession.Kind.NO_APP -> stringResource(R.string.vapp_error_no_app)
                    VirtualAppSession.Kind.FAILED -> stringResource(R.string.vapp_error_failed, problem.detail)
                },
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (problem.kind == VirtualAppSession.Kind.SHIZUKU_PERMISSION) {
                    Button(onClick = { ShizukuManager.requestPermission() }) { Text(stringResource(R.string.apps_authorize)) }
                }
                Button(onClick = session::retry) { Text(stringResource(R.string.vapp_retry)) }
                OutlinedButton(onClick = onClose) { Text(stringResource(R.string.vapp_close), color = Color.White) }
            }
            if (problem.kind != VirtualAppSession.Kind.NO_APP && problem.kind != VirtualAppSession.Kind.FAILED) {
                Text(
                    text = stringResource(R.string.apps_hint_phone),
                    color = Color(0xFFBDC1C6),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}
