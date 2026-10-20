package com.fcaronte.aabrowser.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.utils.LocalPlayer
import kotlinx.coroutines.delay

private const val ICON_RETRY_DELAY_MS = 2000L
private const val MAX_ICON_RETRIES = 5

/**
 * Cosa caricare come icona di un sito (preferiti, barra e gestione schede).
 * AAB Media ha la sua: player.local esiste solo nelle WebView dell'app e il servizio di Google non lo conosce.
 */
fun siteIconModel(url: String, faviconUrl: String?, domain: String): Any = when {
    LocalPlayer.isPlayerUrl(url) -> R.drawable.ic_aab_media
    !faviconUrl.isNullOrEmpty() -> faviconUrl
    else -> "https://www.google.com/s2/favicons?domain=$domain&sz=128"
}

/**
 * Icona di un sito che riprova da sola se non si carica. Al primo avvio le richieste partono mentre l'app chiede
 * ancora i permessi (su Android 17 la rete locale, che serve anche se DNS o proxy sono in casa): Coil non le
 * ripeteva e le icone restavano vuote fino al riavvio dell'app.
 */
@Composable
fun SiteIconImage(
    model: Any,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    fallback: Painter? = null
) {
    val context = LocalContext.current
    var attempt by remember(model) { mutableIntStateOf(0) }
    var failed by remember(model) { mutableStateOf(false) }
    val request = remember(model, attempt) {
        ImageRequest.Builder(context)
            .data(model)
            // Una richiesta diversa la fa ripartire; il parametro non entra nella chiave della cache
            .setParameter("aab_retry", attempt, memoryCacheKey = null)
            .build()
    }

    fun retry() {
        if (failed && attempt < MAX_ICON_RETRIES) {
            failed = false
            attempt++
        }
    }

    // Attese crescenti, e subito quando l'app torna in primo piano (es. chiusa la richiesta di un permesso)
    LaunchedEffect(failed, attempt) {
        if (failed) {
            delay(ICON_RETRY_DELAY_MS * (attempt + 1))
            retry()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { retry() }

    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale,
        placeholder = fallback,
        error = fallback,
        onError = { failed = true },
        onSuccess = { failed = false }
    )
}
