package com.fcaronte.aabrowser.mediaservice

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.fcaronte.aabrowser.utils.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import kotlin.math.abs

class MediaSessionManager(private val context: Context) {

    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null
    var onPlay: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onSkipToNext: (() -> Unit)? = null
    var onSkipToPrevious: (() -> Unit)? = null
    var onSeekTo: ((Long) -> Unit)? = null
    var onHeartTapped: (() -> Unit)? = null

    /**
     * Capacità del player corrente.
     *
     * Default:
     * - seek disponibile
     * - next/previous non disponibili
     */
    private var canSeek = true
    private var canSkipNext = false
    private var canSkipPrevious = false

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
                            "MediaBrowser connesso e MediaController inizializzato."
                        )

                    } catch (e: Exception) {
                        AppLog.e(
                            TAG,
                            "Errore durante l'inizializzazione del MediaController",
                            e
                        )
                    }
                }
            }
        }

        override fun onConnectionSuspended() {
            AppLog.w(TAG, "Connessione al MediaBrowser sospesa.")
            mediaController?.unregisterCallback(controllerCallback)
            mediaController = null
        }

        override fun onConnectionFailed() {
            AppLog.e(TAG, "Connessione al MediaBrowser fallita.")
            mediaController = null
        }
    }

    private val controllerCallback = object : MediaControllerCompat.Callback() {

        override fun onSessionEvent(event: String?, extras: Bundle?) {

            if (event == "PlaybackAction") {

                val action =
                    extras?.getLong("PlaybackAction") ?: 0L

                AppLog.d(
                    "AABrowserPlayback",
                    "Received PlaybackAction: $action"
                )

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

                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT -> {
                        onSkipToNext?.invoke()
                    }

                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS -> {
                        onSkipToPrevious?.invoke()
                    }

                    PlaybackStateCompat.ACTION_SEEK_TO -> {
                        val pos =
                            extras?.getLong("SeekPosition") ?: 0L

                        onSeekTo?.invoke(pos)
                    }
                }

            } else if (event == "CUSTOM_ACTION") {

                val actionStr =
                    extras?.getString("CUSTOM_ACTION")

                if (actionStr == "ACTION_SPOTIFY_HEART") {
                    onHeartTapped?.invoke()
                }
            }
        }
    }

    fun connect() {

        if (mediaBrowser == null) {

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

        mediaController?.unregisterCallback(controllerCallback)

        mediaBrowser?.disconnect()

        mediaBrowser = null
        mediaController = null
    }

    private var lastState: Int =
        PlaybackStateCompat.STATE_NONE

    private var lastPosition: Long = -1

    private var lastSpeed: Float = 1.0f

    private var isLiveStream: Boolean = false

    /**
     * Configura i controlli disponibili per il player corrente.
     *
     * YouTube playlist:
     *     canSeek = false
     *     canSkipNext = true
     *     canSkipPrevious = true
     *
     * Player normale:
     *     canSeek = true
     *     canSkipNext = false
     *     canSkipPrevious = false
     *
     * Live:
     *     canSeek = false
     */
    fun setPlaybackCapabilities(
        canSeek: Boolean,
        canSkipNext: Boolean,
        canSkipPrevious: Boolean
    ) {
        AppLog.d(
            "AABrowserPlayback",
            "SET CAPABILITIES: " +
                    "seek=$canSeek, " +
                    "next=$canSkipNext, " +
                    "previous=$canSkipPrevious"
        )

        val changed =
            this.canSeek != canSeek ||
                    this.canSkipNext != canSkipNext ||
                    this.canSkipPrevious != canSkipPrevious

        this.canSeek = canSeek
        this.canSkipNext = canSkipNext
        this.canSkipPrevious = canSkipPrevious

        if (changed) {
            updatePlaybackState(
                lastState,
                lastPosition,
                lastSpeed,
                force = true
            )
        }
    }

    fun updatePlaybackState(
        state: Int,
        position: Long,
        speed: Float = 1.0f,
        force: Boolean = false
    ) {
        AppLog.d(
            "AABrowserPlayback",
            "updatePlaybackState: " +
                    "state=$state, " +
                    "pos=$position, " +
                    "seek=$canSeek, " +
                    "next=$canSkipNext, " +
                    "previous=$canSkipPrevious, " +
                    "live=$isLiveStream"
        )

        val positionDiff = abs(position - lastPosition)

        val isCoherent =
            state == lastState &&
                    speed == lastSpeed &&
                    positionDiff < 1000

        if (isCoherent && !force) {
            return
        }

        lastState = state
        lastPosition = position
        lastSpeed = speed

        val browser = mediaBrowser

        if (browser == null || !browser.isConnected) {
            AppLog.w(
                TAG,
                "Impossibile aggiornare lo stato: MediaBrowser non connesso."
            )
            return
        }

        var actions =
            PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE

        if (canSkipNext) {
            actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        }

        if (canSkipPrevious) {
            actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        }

        if (!isLiveStream && canSeek) {
            actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
        }

        AppLog.d(
            "AABrowserPlayback",
            "MediaSession actions=$actions"
        )

        val playbackState =
            PlaybackStateCompat.Builder()
                .setState(
                    state,
                    position,
                    speed
                )
                .setActions(actions)
                .build()

        val bundle = Bundle().apply {
            putParcelable(
                "PlaybackStateCompat",
                playbackState
            )
        }

        browser.sendCustomAction(
            "PlaybackStateCompat",
            bundle,
            null
        )
    }

    private val metadataScope =
        CoroutineScope(Dispatchers.Main)

    private var lastArtUrl: String? = null
    private var lastBitmap: Bitmap? = null
    private var lastDuration: Long = 0

    fun updateMetadata(
        title: String,
        artist: String?,
        artUrl: String?,
        duration: Long = 0
    ) {

        AppLog.d(
            "AABrowserPlayback",
            "updateMetadata: " +
                    "title=$title, " +
                    "artist=$artist, " +
                    "artUrl=$artUrl, " +
                    "duration=$duration"
        )

        val browser = mediaBrowser

        if (browser == null || !browser.isConnected) {

            AppLog.w(
                TAG,
                "Impossibile aggiornare i metadati: MediaBrowser non connesso."
            )

            return
        }

        if (title.isBlank()) {

            lastArtUrl = null
            lastBitmap = null
            lastDuration = 0

            isLiveStream = false

            sendMetadata(
                "",
                null,
                null,
                0
            )

            return
        }

        val wasLive = isLiveStream

        isLiveStream = duration <= 0

        lastDuration = duration

        /*
         * Se cambiamo da live a non-live o viceversa,
         * aggiorniamo le action disponibili.
         */
        if (wasLive != isLiveStream) {

            updatePlaybackState(
                lastState,
                lastPosition,
                lastSpeed,
                force = true
            )
        }

        /*
         * Se l'URL dell'artwork è uguale,
         * riutilizziamo il bitmap già scaricato.
         */
        if (
            !artUrl.isNullOrBlank() &&
            artUrl == lastArtUrl &&
            lastBitmap != null
        ) {

            sendMetadata(
                title,
                artist,
                lastBitmap,
                duration
            )

            return
        }

        /*
         * Aggiornamento immediato del testo.
         */
        sendMetadata(
            title,
            artist,
            null,
            duration
        )

        /*
         * Download artwork.
         */
        if (
            !artUrl.isNullOrBlank() &&
            artUrl != lastArtUrl
        ) {

            lastArtUrl = artUrl

            metadataScope.launch {

                try {

                    val bitmap =
                        withContext(Dispatchers.IO) {

                            URL(artUrl)
                                .openStream()
                                .use {
                                    BitmapFactory.decodeStream(it)
                                }
                        }

                    if (bitmap != null) {

                        lastBitmap = bitmap

                        sendMetadata(
                            title,
                            artist,
                            bitmap,
                            lastDuration
                        )
                    }

                } catch (e: Exception) {

                    AppLog.e(
                        TAG,
                        "Errore download artwork: ${e.message}"
                    )
                }
            }

        } else if (artUrl.isNullOrBlank()) {

            lastArtUrl = null
            lastBitmap = null
        }
    }

    private fun sendMetadata(
        title: String,
        artist: String?,
        icon: Bitmap?,
        duration: Long
    ) {

        val browser =
            mediaBrowser ?: return

        val metadataBuilder =
            MediaMetadataCompat.Builder()
                .putString(
                    MediaMetadataCompat.METADATA_KEY_TITLE,
                    title
                )
                .putString(
                    MediaMetadataCompat.METADATA_KEY_ARTIST,
                    artist ?: ""
                )

        /*
         * Per i live non impostiamo la durata.
         */
        if (duration > 0) {

            metadataBuilder.putLong(
                MediaMetadataCompat.METADATA_KEY_DURATION,
                duration
            )
        }

        icon?.let {

            metadataBuilder.putBitmap(
                MediaMetadataCompat.METADATA_KEY_ALBUM_ART,
                it
            )

            metadataBuilder.putBitmap(
                MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON,
                it
            )
        }

        val bundle =
            Bundle().apply {

                putParcelable(
                    "MediaMetadataCompat",
                    metadataBuilder.build()
                )
            }

        browser.sendCustomAction(
            "MediaMetadataCompat",
            bundle,
            null
        )
    }

    companion object {

        private const val TAG =
            "MediaSessionManager"
    }
}