package com.fcaronte.aabrowser.ui

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.view.MotionEvent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.VerticalSplit
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
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.smartphone.ShizukuManager
import com.fcaronte.aabrowser.smartphone.VirtualAppSession
import com.fcaronte.aabrowser.smartphone.VirtualApps
import com.fcaronte.aabrowser.smartphone.VirtualScreenPower

/**
 * Una scheda della Main con un'app del telefono sul suo display virtuale (indirizzo `app://pacchetto`): sta nella barra delle
 * schede come una pagina web. Il display disegna in una TextureView e i tocchi sulla vista vanno all'app così come sono.
 * Una TextureView e non una SurfaceView: disegna dentro la finestra come ogni altra vista, quindi funziona anche nella
 * finestra proiettata sull'auto e sotto gli strati trasparenti di Compose (una SurfaceView sull'auto restava vuota).
 *
 * La barra laterale (indietro, altre app, tastiera, chiudi) e in alto a destra un tasto che la mostra o la nasconde.
 * Di serie la barra sta sopra l'app e ne copre un po', così il display non cambia misura a ogni tocco e le app non si
 * ridisegnano; il primo tasto della barra la mette invece accanto all'app (il display si restringe, niente è coperto: una
 * scelta che resta). Con la scheda non scelta la superficie si toglie e il display resta fermo, l'app continua a girare
 * (audio compreso). I tocchi della vista vanno all'app rifatti puliti (RawTouch).
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

    // I contenuti protetti chiesti ma negati dal sistema (banche, Poste): l'utente lo deve sapere, altrimenti vede solo nero
    val secureRefused = stringResource(R.string.vapp_secure_refused)
    val feedback by rememberUpdatedState(onShowFeedback)
    DisposableEffect(session) {
        session.onNotice = { notice ->
            if (notice == VirtualAppSession.Notice.SECURE_REFUSED) feedback(secureRefused)
        }
        onDispose { session.onNotice = null }
    }

    // La barra sta sopra l'app (la copre un po', il display non cambia misura) o accanto (il display si restringe)
    val docked by AppSettings.virtualSidebarDocked
    val sidebar: @Composable (Modifier) -> Unit = { modifier ->
        VirtualAppSidebar(
            modifier = modifier,
            docked = docked,
            onToggleDock = { AppSettings.setVirtualSidebarDocked(context, !docked) },
            onBack = { session.key(android.view.KeyEvent.KEYCODE_BACK) },
            onApps = onOpenApps,
            onKeyboard = toggleKeyboard,
            onClose = onClose
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (sidebarVisible && docked) sidebar(Modifier.fillMaxHeight())

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = listener
                            // Il display ha le misure della vista: le coordinate del tocco sono già quelle dell'app
                            setOnTouchListener { _, event ->
                                // Un tocco sull'auto con il telefono spento (tasto di blocco) lo riaccende
                                if (event.actionMasked == MotionEvent.ACTION_DOWN) VirtualScreenPower.touchOnCar()
                                session.touchRaw(event)
                                true
                            }
                        }
                    }
                )

                VirtualAppStatus(session = session, onClose = onClose)

                if (sidebarVisible && !docked) sidebar(Modifier.align(Alignment.CenterStart).fillMaxHeight())

                // Il telefono dorme (tasto di blocco): il display è fermo e l'auto resta nera. Lo si dice, e un tocco lo riaccende
                if (VirtualScreenPower.asleep) {
                    Text(
                        text = stringResource(R.string.vapp_phone_asleep),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 72.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                    )
                }
            }
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

// Stretta: i tasti da 40 dp bastano e all'app resta più spazio
private val SIDEBAR_WIDTH = 44.dp

@Composable
private fun VirtualAppSidebar(
    modifier: Modifier,
    docked: Boolean,
    onToggleDock: () -> Unit,
    onBack: () -> Unit,
    onApps: () -> Unit,
    onKeyboard: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(SIDEBAR_WIDTH)
            .background(Color(0xE61E1E1E))
            // I tocchi sulla barra non arrivano all'app sotto, nemmeno fra un tasto e l'altro
            .pointerInput(Unit) {}
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Barra accanto all'app (il display si restringe e niente è coperto) o sopra: acceso quando sta accanto
        SidebarButton(
            Icons.Default.VerticalSplit,
            if (docked) R.string.vapp_sidebar_overlay else R.string.vapp_sidebar_dock,
            onToggleDock,
            tint = if (docked) Color(0xFF8AB4F8) else Color.White
        )
        SidebarButton(Icons.AutoMirrored.Filled.ArrowBack, R.string.vapp_back, onBack)
        SidebarButton(Icons.Default.Apps, R.string.apps_title, onApps)
        SidebarButton(Icons.Default.Keyboard, R.string.vapp_keyboard, onKeyboard)
        SidebarButton(Icons.Default.Close, R.string.vapp_close, onClose)
    }
}

@Composable
private fun SidebarButton(icon: ImageVector, label: Int, onClick: () -> Unit, tint: Color = Color.White) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(imageVector = icon, contentDescription = stringResource(label), tint = tint, modifier = Modifier.size(24.dp))
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
                .padding(horizontal = 72.dp),
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
