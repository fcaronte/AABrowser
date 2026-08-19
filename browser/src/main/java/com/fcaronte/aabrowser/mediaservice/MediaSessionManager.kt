package com.fcaronte.aabrowser.mediaservice

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

@OptIn(UnstableApi::class)
class MediaSessionManager(private val context: Context) {

    private var mediaBrowser: MediaBrowser? = null

    var onPlay: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onSkipToNext: (() -> Unit)? = null
    var onSkipToPrevious: (() -> Unit)? = null
    var onSeekTo: ((Long) -> Unit)? = null

    private val metadataScope = CoroutineScope(Dispatchers.Main)
    private var lastArtUrl: String? = null
    private var lastBitmap: Bitmap? = null
    private var lastDuration: Long = 0

    fun connect() {
        Log.e("MediaSessionManager", "@@@ connect() called @@@")
        if (mediaBrowser != null) {
            Log.e("MediaSessionManager", "@@@ mediaBrowser is already NOT null @@@")
            return
        }

        Log.e("MediaSessionManager", "@@@ Connecting to CarMediaService... @@@")
        val serviceIntent = android.content.Intent(context, CarMediaService::class.java)
        try {
            context.startService(serviceIntent)
        } catch (e: Exception) {
            Log.e("MediaSessionManager", "@@@ Error starting service @@@", e)
        }

        val sessionToken = SessionToken(context, ComponentName(context, CarMediaService::class.java))
        val browserFuture = MediaBrowser.Builder(context, sessionToken)
            .setListener(browserListener)
            .buildAsync()
        browserFuture.addListener({
            try {
                mediaBrowser = browserFuture.get()
                Log.e("MediaSessionManager", "@@@ MediaBrowser connesso. @@@")
            } catch (e: Exception) {
                Log.e("MediaSessionManager", "@@@ Errore durante la connessione al MediaBrowser @@@", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private val browserListener = object : MediaBrowser.Listener {
        override fun onCustomCommand(
            controller: MediaController,
            command: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            Log.d(TAG, "onCustomCommand: action=${command.customAction}")
            if (command.customAction == "PlaybackAction") {
                val action = args.getLong("PlaybackAction")
                Log.d(TAG, "Received PlaybackAction: $action")
                when (action) {
                    ACTION_PLAY -> onPlay?.invoke()
                    ACTION_PAUSE -> onPause?.invoke()
                    ACTION_STOP -> onStop?.invoke()
                    ACTION_SKIP_TO_NEXT -> onSkipToNext?.invoke()
                    ACTION_SKIP_TO_PREVIOUS -> onSkipToPrevious?.invoke()
                    ACTION_SEEK_TO -> {
                        val pos = args.getLong("SeekPosition")
                        onSeekTo?.invoke(pos)
                    }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    fun disconnect() {
        mediaBrowser?.release()
        mediaBrowser = null
    }

    fun resetPlaybackState() {
        val browser = mediaBrowser
        if (browser == null || !browser.isConnected) return
        
        Log.e(TAG, "@@@ resetPlaybackState() @@@")
        androidx.core.content.ContextCompat.getMainExecutor(context).execute {
            try {
                browser.sendCustomCommand(SessionCommand("ResetCommand", Bundle.EMPTY), Bundle.EMPTY)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending reset command", e)
            }
        }
    }

    fun updatePlaybackState(state: Int, position: Long) {
        val browser = mediaBrowser
        if (browser == null || !browser.isConnected) {
            Log.w(TAG, "Impossibile aggiornare lo stato: MediaBrowser non connesso.")
            return
        }

        Log.d(TAG, "updatePlaybackState: state=$state, position=$position")
        
        val bundle = Bundle().apply {
            putInt("state", state)
            putLong("position", position)
        }

        androidx.core.content.ContextCompat.getMainExecutor(context).execute {
            try {
                browser.sendCustomCommand(SessionCommand("PlaybackStateCompat", Bundle.EMPTY), bundle)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating playback state", e)
            }
        }
    }

    fun updateMetadata(title: String, artist: String?, artUrl: String?, duration: Long = 0) {
        Log.d(TAG, "updateMetadata: $title - $artist (Art: $artUrl, Duration: $duration)")
        val browser = mediaBrowser
        if (browser == null || !browser.isConnected) {
            Log.w(TAG, "Impossibile aggiornare i metadati: MediaBrowser non connesso.")
            return
        }

        lastDuration = duration

        if (!artUrl.isNullOrBlank() && artUrl == lastArtUrl && lastBitmap != null) {
            sendMetadata(title, artist, lastBitmap, duration)
            return
        }

        sendMetadata(title, artist, null, duration)

        if (!artUrl.isNullOrBlank() && artUrl != lastArtUrl) {
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
                    Log.e(TAG, "Errore download artwork: ${e.message}")
                }
            }
        } else if (artUrl.isNullOrBlank()) {
            lastArtUrl = null
            lastBitmap = null
        }
    }

    private fun sendMetadata(title: String, artist: String?, icon: Bitmap?, duration: Long) {
        val browser = mediaBrowser ?: return
        Log.e(TAG, "@@@ sendMetadata: $title - $artist @@@")
        
        val bundle = Bundle().apply {
            putString("title", title)
            putString("artist", artist ?: "")
            putLong("duration", duration)
            putParcelable("icon", icon)
        }
        
        androidx.core.content.ContextCompat.getMainExecutor(context).execute {
            try {
                browser.sendCustomCommand(SessionCommand("MediaMetadataCompat", Bundle.EMPTY), bundle)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending metadata", e)
            }
        }
    }

    companion object {
        private const val TAG = "MediaSessionManager"
        
        private const val ACTION_PAUSE = 2L
        private const val ACTION_PLAY = 4L
        private const val ACTION_STOP = 1L
        private const val ACTION_SKIP_TO_NEXT = 32L
        private const val ACTION_SKIP_TO_PREVIOUS = 16L
        private const val ACTION_SEEK_TO = 256L
    }
}