package com.fcaronte.aabrowser.mediaservice

import com.fcaronte.aabrowser.utils.AppLog
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import kotlin.math.abs

class MediaSessionManager(private val context: Context) {

    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null
    private var connectionRequested = false
    private var connectionInProgress = false
    private var reconnectJob: Job? = null
    private var cachedPlaybackState: PlaybackStateCompat? = null
    private var cachedMetadata: MediaMetadataCompat? = null

    var onPlay: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onSkipToNext: (() -> Unit)? = null
    var onSkipToPrevious: (() -> Unit)? = null
    var onSeekTo: ((Long) -> Unit)? = null
    var onHeartTapped: (() -> Unit)? = null

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            connectionInProgress = false
            mediaBrowser?.let {
                if (it.isConnected) {
                    try {
                        mediaController = MediaControllerCompat(context, it.sessionToken).apply {
                            registerCallback(controllerCallback)
                        }
                        reconnectJob?.cancel()
                        reconnectJob = null
                        publishCachedSession()
                        AppLog.d(TAG, "MediaBrowser connesso e MediaController inizializzato.")
                    } catch (e: Exception) {
                        AppLog.e(TAG, "Errore durante l'inizializzazione del MediaController", e)
                    }
                }
            }
        }

        override fun onConnectionSuspended() {
            connectionInProgress = false
            AppLog.w(TAG, "Connessione al MediaBrowser sospesa.")
            mediaController?.unregisterCallback(controllerCallback)
            mediaController = null
            scheduleReconnect()
        }

        override fun onConnectionFailed() {
            connectionInProgress = false
            AppLog.e(TAG, "Connessione al MediaBrowser fallita.")
            mediaController = null
            scheduleReconnect()
        }
    }

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onSessionEvent(event: String?, extras: Bundle?) {
            if (event == "PlaybackAction") {
                val action = extras?.getLong("PlaybackAction") ?: 0
                AppLog.d("AABrowserPlayback", "Received PlaybackAction: $action")
                when (action) {
                    PlaybackStateCompat.ACTION_PLAY -> {
                        onPlay?.invoke()
                    }

                    PlaybackStateCompat.ACTION_PAUSE -> {
                        onPause?.invoke()
                    }

                    PlaybackStateCompat.ACTION_STOP -> {
                        onStop?.invoke()
                    }

                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT -> onSkipToNext?.invoke()
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS -> onSkipToPrevious?.invoke()
                    PlaybackStateCompat.ACTION_SEEK_TO -> {
                        val pos = extras?.getLong("SeekPosition") ?: 0L
                        onSeekTo?.invoke(pos)
                    }
                }
            } else if (event == "CUSTOM_ACTION") {
                val actionStr = extras?.getString("CUSTOM_ACTION")
                if (actionStr == "ACTION_SPOTIFY_HEART") {
                    onHeartTapped?.invoke()
                }
            }
        }
    }

    fun connect() {
        connectionRequested = true
        val currentBrowser = mediaBrowser
        if (currentBrowser?.isConnected == true || connectionInProgress) return

        currentBrowser?.disconnect()
        connectionInProgress = true
        mediaBrowser = MediaBrowserCompat(
            context,
            ComponentName(context, CarMediaService::class.java),
            connectionCallback,
            null
        ).apply {
            connect()
        }
    }

    fun disconnect() {
        connectionRequested = false
        connectionInProgress = false
        reconnectJob?.cancel()
        reconnectJob = null
        mediaController?.unregisterCallback(controllerCallback)
        mediaBrowser?.disconnect()
        mediaBrowser = null
        mediaController = null
    }

    private fun scheduleReconnect() {
        if (!connectionRequested || reconnectJob?.isActive == true) return
        reconnectJob = metadataScope.launch {
            delay(1_000)
            reconnectJob = null
            if (connectionRequested) {
                mediaController?.unregisterCallback(controllerCallback)
                mediaController = null
                mediaBrowser?.disconnect()
                mediaBrowser = null
                connectionInProgress = false
                connect()
            }
        }
    }

    private fun publishCachedSession() {
        cachedMetadata?.let(::sendMetadataUpdate)
        cachedPlaybackState?.let(::sendPlaybackStateUpdate)
    }

    private var lastState: Int = PlaybackStateCompat.STATE_NONE
    private var lastPosition: Long = -1
    private var lastSpeed: Float = 1.0f
    private var isLiveStream: Boolean = false

    fun updatePlaybackState(state: Int, position: Long, speed: Float = 1.0f) {
        AppLog.d("AABrowserPlayback", "updatePlaybackState: state=$state, pos=$position")
        val positionDiff = abs(position - lastPosition)
        val isCoherent = state == lastState && speed == lastSpeed && positionDiff < 1000

        if (isCoherent && cachedPlaybackState != null) return

        lastState = state
        lastPosition = position
        lastSpeed = speed

        var actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_PLAY_PAUSE

        // Aggiungi SEEK_TO solo se non è un flusso live (durata > 0)
        if (!isLiveStream) {
            actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
        }

        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, position, speed)
            .setActions(actions)
            .build()

        cachedPlaybackState = playbackState
        sendPlaybackStateUpdate(playbackState)
    }

    private fun sendPlaybackStateUpdate(playbackState: PlaybackStateCompat) {
        val browser = mediaBrowser
        if (browser == null || !browser.isConnected) {
            AppLog.w(TAG, "Impossibile aggiornare lo stato: MediaBrowser non connesso.")
            return
        }

        val bundle = Bundle().apply {
            putParcelable("PlaybackStateCompat", playbackState)
        }
        browser.sendCustomAction("PlaybackStateCompat", bundle, null)
    }

    private val metadataScope = CoroutineScope(Dispatchers.Main)
    private var lastArtUrl: String? = null
    private var lastBitmap: Bitmap? = null
    private var lastDuration: Long = 0

    fun updateMetadata(title: String, artist: String?, artUrl: String?, duration: Long = 0) {
        AppLog.d("AABrowserPlayback", "updateMetadata: title='$title', artist='$artist', artUrl='$artUrl', duration=$duration, lastBitmap=${lastBitmap != null}")
        if (title.isBlank()) {
            AppLog.w("AABrowserPlayback", "Title is blank, clearing metadata")
            lastArtUrl = null
            lastBitmap = null
            lastDuration = 0
            isLiveStream = false
            sendMetadata("", null, null, 0)
            return
        }

        val wasLive = isLiveStream
        isLiveStream = duration <= 0
        lastDuration = duration

        // Se non c'è uno stato attivo (STATE_NONE), imposta STATE_PAUSED per far apparire il player su Android Auto
        if (lastState == PlaybackStateCompat.STATE_NONE) {
            AppLog.d("AABrowserPlayback", "No active state, setting STATE_PAUSED to show player")
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, 0, 1.0f)
        }

        // Se passiamo da live a non-live o viceversa, forziamo l'aggiornamento dello stato di riproduzione
        // per aggiornare le azioni disponibili (es. mostrare/nascondere la seekbar)
        if (wasLive != isLiveStream) {
            updatePlaybackState(lastState, lastPosition, lastSpeed)
        }

        // Se l'URL è lo stesso, usa l'ultimo bitmap per evitare che l'icona sparisca
        if (!artUrl.isNullOrBlank() && artUrl == lastArtUrl && lastBitmap != null) {
            sendMetadata(title, artist, lastBitmap, duration)
            return
        }

        // Se non c'è un nuovo URL dell'artwork, mantieni l'icona precedente se esiste
        if (artUrl.isNullOrBlank()) {
            sendMetadata(title, artist, lastBitmap, duration)
            lastArtUrl = null
            return
        }

        // Se l'URL è nuovo, invia intanto il testo con l'icona precedente (se esiste)
        sendMetadata(title, artist, lastBitmap, duration)

        // Scarica la nuova immagine
        if (artUrl != lastArtUrl) {
            lastArtUrl = artUrl
            metadataScope.launch {
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        URL(artUrl).openStream().use {
                            BitmapFactory.decodeStream(it)
                        }
                    }
                    if (bitmap != null) {
                        lastBitmap = bitmap
                        sendMetadata(title, artist, bitmap, lastDuration)
                    }
                } catch (e: Exception) {
                    AppLog.e(TAG, "Errore download artwork: ${e.message}")
                }
            }
        }
    }

    private fun sendMetadata(title: String, artist: String?, icon: Bitmap?, duration: Long) {
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist ?: "")
        
        // Se la durata è <= 0, non la impostiamo per segnalare al sistema che si tratta di un contenuto Live
        if (duration > 0) {
            metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
        }

        icon?.let {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
        }

        sendMetadataUpdate(metadataBuilder.build())
    }

    private fun sendMetadataUpdate(metadata: MediaMetadataCompat) {
        cachedMetadata = metadata
        val browser = mediaBrowser
        if (browser == null || !browser.isConnected) {
            AppLog.w(TAG, "Impossibile aggiornare i metadati: MediaBrowser non connesso.")
            return
        }

        val bundle = Bundle().apply {
            putParcelable("MediaMetadataCompat", metadata)
        }
        browser.sendCustomAction("MediaMetadataCompat", bundle, null)
    }

    companion object {
        private const val TAG = "MediaSessionManager"
    }
}