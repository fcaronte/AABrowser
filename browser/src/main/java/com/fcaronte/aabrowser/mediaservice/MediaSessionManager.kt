package com.fcaronte.aabrowser.mediaservice

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.utils.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Collega UNA WebView (una scheda della Main oppure la Wide) al player di Android Auto.
 * Ogni WebView ha la sua istanza e MediaFocusManager decide quale istanza controlla il player:
 * solo quella pubblica metadati/stato e riceve i comandi (play, pausa, avanti, ...).
 *
 * I metodi pubblici si possono chiamare da qualunque thread (anche dal thread del ponte JavaScript):
 * lo stato viene sempre aggiornato sul main thread, nell'ordine in cui arrivano le chiamate.
 */
class MediaSessionManager(
    private val context: Context,
    override val source: MediaFocusManager.Source = MediaFocusManager.Source.MAIN_APP
) : MediaFocusManager.MediaClient {

    override fun pauseMedia() {
        // Già in pausa: niente script, su alcuni siti il tasto play/pausa è un interruttore e la farebbe ripartire
        if (!isPlayingState(lastState)) return
        AppLog.d(TAG, "pauseMedia triggered by MediaFocusManager for source $source")
        onPause?.invoke()
    }

    override fun onFocusLost() {
        AppLog.d(TAG, "Focus lost for source $source")
        // Un PLAYING in attesa non deve più arrivare al player: ora appartiene a un altro client
        cancelPendingPlaying()
    }

    override fun onFocusGained() {
        AppLog.d(TAG, "Focus gained for source $source")
        syncWithMediaService()
    }

    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null
    var onPlay: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onSkipToNext: (() -> Unit)? = null
    var onSkipToPrevious: (() -> Unit)? = null
    var onSeekTo: ((Long) -> Unit)? = null
    var onHeartTapped: (() -> Unit)? = null

    // false quando la pagina non è visibile (scheda in background): una riproduzione partita da sola
    // non può prendersi il player e viene messa in pausa
    var canTakeFocus: () -> Boolean = { true }

    // Titolo provvisorio (es. titolo della pagina) da mostrare finché la pagina non invia i metadati
    var fallbackTitle: () -> String? = { null }

    private var canSeek = true
    private var canSkipNext = false
    private var canSkipPrevious = false

    private var lastState: Int = PlaybackStateCompat.STATE_NONE
    private var lastPosition: Long = -1
    private var lastSpeed: Float = 1.0f
    private var isLiveStream: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val metadataScope = CoroutineScope(Dispatchers.Main)
    private var pendingPlayingJob: Job? = null
    private var lastUnwantedPauseTime = 0L
    private var lastTitle: String = ""
    private var lastArtist: String? = null
    private var lastArtUrl: String? = null
    private var lastBitmap: Bitmap? = null
    private var lastDuration: Long = 0

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {

        override fun onConnected() {
            mediaBrowser?.let {
                if (it.isConnected) {
                    try {
                        mediaController =
                            MediaControllerCompat(context, it.sessionToken).apply {
                                registerCallback(controllerCallback)
                            }

                        AppLog.d(
                            TAG,
                            "MediaBrowser connected & MediaController initialized for $source."
                        )

                        if (MediaFocusManager.isFocusOwner(this@MediaSessionManager)) {
                            syncWithMediaService()
                        }

                    } catch (e: Exception) {
                        AppLog.e(
                            TAG,
                            "Error initializing MediaController for $source",
                            e
                        )
                    }
                }
            }
        }

        override fun onConnectionSuspended() {
            AppLog.w(TAG, "MediaBrowser connection suspended for $source.")
            mediaController?.unregisterCallback(controllerCallback)
            mediaController = null
        }

        override fun onConnectionFailed() {
            AppLog.e(TAG, "MediaBrowser connection failed for $source.")
            mediaController = null
        }
    }

    private val controllerCallback = object : MediaControllerCompat.Callback() {

        override fun onSessionEvent(event: String?, extras: Bundle?) {

            if (event == "PlaybackAction") {

                if (!MediaFocusManager.isFocusOwner(this@MediaSessionManager)) {
                    AppLog.d(TAG, "Ignoring PlaybackAction for $source: focus owned by ${MediaFocusManager.getActiveSource()}")
                    return
                }

                val action = extras?.getLong("PlaybackAction") ?: 0L

                AppLog.d(
                    "AABrowserPlayback",
                    "Received PlaybackAction ($source): $action"
                )

                when (action) {

                    PlaybackStateCompat.ACTION_PLAY -> {
                        metadataScope.launch(Dispatchers.Main) { onPlay?.invoke() }
                    }

                    PlaybackStateCompat.ACTION_PAUSE -> {
                        metadataScope.launch(Dispatchers.Main) { onPause?.invoke() }
                    }

                    PlaybackStateCompat.ACTION_STOP -> {
                        metadataScope.launch(Dispatchers.Main) { onStop?.invoke() }
                    }

                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT -> {
                        metadataScope.launch(Dispatchers.Main) { onSkipToNext?.invoke() }
                    }

                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS -> {
                        metadataScope.launch(Dispatchers.Main) { onSkipToPrevious?.invoke() }
                    }

                    PlaybackStateCompat.ACTION_SEEK_TO -> {
                        val pos = extras?.getLong("SeekPosition") ?: 0L
                        metadataScope.launch(Dispatchers.Main) { onSeekTo?.invoke(pos) }
                    }
                }

            } else if (event == "CUSTOM_ACTION") {

                if (!MediaFocusManager.isFocusOwner(this@MediaSessionManager)) return

                val actionStr = extras?.getString("CUSTOM_ACTION")

                if (actionStr == "ACTION_SPOTIFY_HEART") {
                    metadataScope.launch(Dispatchers.Main) { onHeartTapped?.invoke() }
                }
            }
        }
    }

    fun connect() {
        if (mediaBrowser == null) {
            AppLog.d(TAG, "Connecting MediaBrowser for $source...")
            mediaBrowser = MediaBrowserCompat(
                context,
                ComponentName(
                    context,
                    CarMediaService::class.java
                ),
                connectionCallback,
                null
            ).apply {
                connect()
            }
        }
    }

    fun disconnect() {
        runOnMain {
            AppLog.d(TAG, "Disconnecting MediaBrowser for $source...")
            // Se il player era di questa WebView lo azzera prima di staccarsi
            releaseMediaFocus()
            mediaController?.unregisterCallback(controllerCallback)
            mediaBrowser?.disconnect()
            mediaBrowser = null
            mediaController = null
        }
    }

    /**
     * Il media di questa WebView non esiste più (pagina cambiata, ricaricata o chiusa):
     * se il player era suo viene azzerato e liberato.
     */
    fun releaseMediaFocus() {
        runOnMain {
            cancelPendingPlaying()
            val wasOwner = MediaFocusManager.isFocusOwner(this)
            resetMediaState()
            if (wasOwner) {
                AppLog.d(TAG, "Releasing media focus for $source and clearing the player")
                actualUpdatePlaybackState(PlaybackStateCompat.STATE_NONE, 0L, 1.0f)
                sendMetadata("", null, null, 0)
            }
            MediaFocusManager.releaseMediaFocus(this)
        }
    }

    fun setPlaybackCapabilities(
        canSeek: Boolean,
        canSkipNext: Boolean,
        canSkipPrevious: Boolean
    ) {
        runOnMain {
            AppLog.d(
                "AABrowserPlayback",
                "SET CAPABILITIES ($source): seek=$canSeek, next=$canSkipNext, previous=$canSkipPrevious"
            )

            val changed =
                this.canSeek != canSeek ||
                        this.canSkipNext != canSkipNext ||
                        this.canSkipPrevious != canSkipPrevious

            this.canSeek = canSeek
            this.canSkipNext = canSkipNext
            this.canSkipPrevious = canSkipPrevious

            if (changed && MediaFocusManager.isFocusOwner(this)) {
                publishPlaybackState()
            }
        }
    }

    /**
     * [isPlaybackStart] = true quando la chiamata arriva da un vero avvio della riproduzione
     * (evento "play" della pagina) e non da un aggiornamento periodico della posizione.
     */
    fun updatePlaybackState(
        state: Int,
        position: Long,
        speed: Float = 1.0f,
        isPlaybackStart: Boolean = false
    ) {
        runOnMain {
            AppLog.d(TAG, "updatePlaybackState ($source): state=$state, pos=$position, speed=$speed, start=$isPlaybackStart, isFocusOwner=${MediaFocusManager.isFocusOwner(this)}")

            val wasPlaying = isPlayingState(lastState)
            val isPlaying = isPlayingState(state)

            lastState = state
            lastPosition = position
            lastSpeed = speed

            if (!MediaFocusManager.isFocusOwner(this)) {
                // Una pausa di chi non ha il player non cambia nulla
                if (!isPlaying) return@runOnMain

                // Riproduzione appena avviata qui: il player passa a questa WebView e la precedente
                // viene messa in pausa (onFocusGained ha già inviato metadati e stato)
                val isNewPlayback = isPlaybackStart || !wasPlaying
                if (isNewPlayback && canTakeFocus() && MediaFocusManager.requestMediaFocus(this)) {
                    return@runOnMain
                }

                // Riproduzione che non può avere il player (scheda in background, aggiornamento
                // arrivato in ritardo da una pagina già sganciata): la fermiamo per non avere due audio
                pauseUnwantedPlayback()
                return@runOnMain
            }

            publishPlaybackState()
        }
    }

    fun updateMetadata(
        title: String,
        artist: String?,
        artUrl: String?,
        duration: Long = 0
    ) {
        runOnMain {
            val mediaChanged = (title != lastTitle || artist != lastArtist)
            lastTitle = title
            lastArtist = artist
            lastDuration = duration

            val wasLive = isLiveStream
            isLiveStream = duration <= 0
            val isOwner = MediaFocusManager.isFocusOwner(this)

            if (mediaChanged) {
                AppLog.d(TAG, "Media changed ($source): title='$title', artist='$artist', duration=$duration")
            }

            AppLog.d(
                "AABrowserPlayback",
                "updateMetadata ($source): title='$title', artist='$artist', artUrl=$artUrl, duration=$duration, isFocusOwner=$isOwner"
            )

            if (title.isBlank()) {
                lastArtUrl = null
                lastBitmap = null
                if (isOwner) {
                    sendMetadata("", null, null, 0)
                }
                return@runOnMain
            }

            if (wasLive != isLiveStream && isOwner) {
                publishPlaybackState()
            }

            if (!artUrl.isNullOrBlank() && artUrl == lastArtUrl && lastBitmap != null) {
                if (isOwner) {
                    sendMetadataAndForceRefresh(title, artist, lastBitmap, duration)
                }
                return@runOnMain
            }

            if (isOwner) {
                sendMetadataAndForceRefresh(title, artist, null, duration)
            }

            if (!artUrl.isNullOrBlank() && artUrl != lastArtUrl) {
                lastArtUrl = artUrl
                lastBitmap = null
                metadataScope.launch {
                    try {
                        val bitmap = withContext(Dispatchers.IO) {
                            URL(artUrl).openStream().use {
                                BitmapFactory.decodeStream(it)
                            }
                        }
                        // La copertina vale solo se nel frattempo il brano non è cambiato
                        if (bitmap != null && artUrl == lastArtUrl) {
                            lastBitmap = bitmap
                            if (MediaFocusManager.isFocusOwner(this@MediaSessionManager)) {
                                sendMetadataAndForceRefresh(lastTitle, lastArtist, bitmap, lastDuration)
                            }
                        }
                    } catch (e: Exception) {
                        AppLog.e(TAG, "Error downloading artwork ($source): ${e.message}")
                    }
                }
            } else if (artUrl.isNullOrBlank()) {
                lastArtUrl = null
                lastBitmap = null
            }
        }
    }

    fun syncWithMediaService() {
        if (!MediaFocusManager.isFocusOwner(this)) return
        AppLog.d(TAG, "Syncing media service for $source with title='$lastTitle', state=$lastState")
        if (lastTitle.isNotBlank()) {
            sendMetadata(lastTitle, lastArtist, lastBitmap, lastDuration)
        } else {
            // La pagina non ha ancora inviato i metadati: il player non deve restare sul titolo
            // della riproduzione precedente
            val placeholder = fallbackTitle()?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name)
            sendMetadata(placeholder, null, null, 0)
        }
        if (lastState != PlaybackStateCompat.STATE_NONE) {
            actualUpdatePlaybackState(lastState, lastPosition, lastSpeed)
        }
    }

    private fun publishPlaybackState() {
        if (lastState == PlaybackStateCompat.STATE_NONE) return

        if (lastState == PlaybackStateCompat.STATE_PLAYING) {
            // Breve attesa prima di mostrare PLAYING. Gli aggiornamenti che arrivano nel frattempo
            // vengono accorpati (partono i valori più recenti), così il PLAYING non viene rimandato
            // all'infinito e non può arrivare dopo una pausa successiva
            if (pendingPlayingJob?.isActive == true) return
            pendingPlayingJob = metadataScope.launch {
                delay(PLAYING_PUBLISH_DELAY_MS)
                pendingPlayingJob = null
                if (lastState == PlaybackStateCompat.STATE_PLAYING &&
                    MediaFocusManager.isFocusOwner(this@MediaSessionManager)
                ) {
                    actualUpdatePlaybackState(lastState, lastPosition, lastSpeed)
                }
            }
        } else {
            cancelPendingPlaying()
            actualUpdatePlaybackState(lastState, lastPosition, lastSpeed)
        }
    }

    private fun actualUpdatePlaybackState(
        state: Int,
        position: Long,
        speed: Float
    ) {
        val browser = mediaBrowser
        if (browser == null || !browser.isConnected) {
            AppLog.w(TAG, "actualUpdatePlaybackState ($source): MediaBrowser not connected yet.")
            return
        }

        AppLog.d(TAG, "actualUpdatePlaybackState ($source): sending state=$state, title='$lastTitle'")

        var actions =
            PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE

        if (canSkipNext) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if (canSkipPrevious) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        if (!isLiveStream && canSeek) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO

        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, position, speed)
            .setActions(actions)
            .build()

        val bundle = Bundle().apply {
            putParcelable("PlaybackStateCompat", playbackState)
            putString("title", lastTitle)
            putString("artist", lastArtist)
        }

        browser.sendCustomAction("PlaybackStateCompat", bundle, null)
    }

    private fun sendMetadataAndForceRefresh(
        title: String,
        artist: String?,
        icon: Bitmap?,
        duration: Long
    ) {
        sendMetadata(title, artist, icon, duration)
        if (title.isNotBlank()) {
            // Ripubblica lo stato reale (mai forzato a PLAYING: una pagina in pausa resta in pausa)
            publishPlaybackState()
        }
    }

    private fun sendMetadata(
        title: String,
        artist: String?,
        icon: Bitmap?,
        duration: Long
    ) {
        val browser = mediaBrowser
        if (browser == null || !browser.isConnected) {
            AppLog.w(TAG, "sendMetadata ($source): MediaBrowser not connected yet.")
            return
        }

        AppLog.d(TAG, "sendMetadata to CarMediaService ($source): title='$title', artist='$artist'")

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist ?: "")

        if (duration > 0) {
            metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
        }

        icon?.let {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
        }

        val bundle = Bundle().apply {
            putParcelable("MediaMetadataCompat", metadataBuilder.build())
            putString("title", title)
            putString("artist", artist)
        }

        browser.sendCustomAction("MediaMetadataCompat", bundle, null)
    }

    private fun pauseUnwantedPlayback() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUnwantedPauseTime < UNWANTED_PAUSE_INTERVAL_MS) return
        lastUnwantedPauseTime = now
        AppLog.d(TAG, "Pausing playback of $source: the Android Auto player belongs to ${MediaFocusManager.getActiveSource()}")
        onPause?.invoke()
    }

    private fun cancelPendingPlaying() {
        pendingPlayingJob?.cancel()
        pendingPlayingJob = null
    }

    private fun resetMediaState() {
        lastTitle = ""
        lastArtist = null
        lastArtUrl = null
        lastBitmap = null
        lastDuration = 0
        lastState = PlaybackStateCompat.STATE_NONE
        lastPosition = -1
        lastSpeed = 1.0f
        isLiveStream = false
    }

    private fun isPlayingState(state: Int): Boolean =
        state == PlaybackStateCompat.STATE_PLAYING || state == PlaybackStateCompat.STATE_BUFFERING

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        private const val TAG = "MediaSessionManager"
        private const val PLAYING_PUBLISH_DELAY_MS = 500L
        private const val UNWANTED_PAUSE_INTERVAL_MS = 1000L
    }
}
