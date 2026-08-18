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
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class MediaSessionManager(private val context: Context) {

    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null

    var onPlay: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onSkipToNext: (() -> Unit)? = null
    var onSkipToPrevious: (() -> Unit)? = null
    var onSeekTo: ((Long) -> Unit)? = null

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            mediaBrowser?.let {
                if (it.isConnected) {
                    try {
                        mediaController = MediaControllerCompat(context, it.sessionToken).apply {
                            registerCallback(controllerCallback)
                        }
                        Log.d(TAG, "MediaBrowser connesso e MediaController inizializzato.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Errore durante l'inizializzazione del MediaController", e)
                    }
                }
            }
        }

        override fun onConnectionSuspended() {
            Log.w(TAG, "Connessione al MediaBrowser sospesa.")
            mediaController?.unregisterCallback(controllerCallback)
            mediaController = null
        }

        override fun onConnectionFailed() {
            Log.e(TAG, "Connessione al MediaBrowser fallita.")
            mediaController = null
        }
    }

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onSessionEvent(event: String?, extras: Bundle?) {
            if (event == "PlaybackAction") {
                val action = extras?.getLong("PlaybackAction") ?: 0
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
            }
        }
    }

    fun connect() {
        if (mediaBrowser == null) {
            mediaBrowser = MediaBrowserCompat(
                context,
                ComponentName(context, CarMediaService::class.java),
                connectionCallback,
                null
            ).apply {
                connect()
            }
        }
    }

    fun disconnect() {
        mediaController?.let {
            it.unregisterCallback(controllerCallback)
        }
        mediaBrowser?.disconnect()
        mediaBrowser = null
        mediaController = null
    }

    fun updatePlaybackState(state: Int, position: Long) {
        val browser = mediaBrowser
        if (browser == null || !browser.isConnected) {
            Log.w(TAG, "Impossibile aggiornare lo stato: MediaBrowser non connesso.")
            return
        }

        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, position, 1.0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .build()

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
        Log.d(TAG, "updateMetadata: $title - $artist (Art: $artUrl)")
        val browser = mediaBrowser
        if (browser == null || !browser.isConnected) {
            Log.w(TAG, "Impossibile aggiornare i metadati: MediaBrowser non connesso.")
            return
        }

        lastDuration = duration

        // Se l'URL è lo stesso, usa l'ultimo bitmap per evitare che l'icona sparisca
        if (!artUrl.isNullOrBlank() && artUrl == lastArtUrl && lastBitmap != null) {
            sendMetadata(title, artist, lastBitmap, duration)
            return
        }

        // Se l'URL è nuovo, invia intanto il testo senza icona (o con l'icona vecchia se preferisci, 
        // ma di solito è meglio resettare se il brano è diverso)
        sendMetadata(title, artist, null, duration)

        // Se c'è una URL, scarica l'immagine
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
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist ?: "")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)

        icon?.let {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
        }

        val bundle = Bundle().apply {
            putParcelable("MediaMetadataCompat", metadataBuilder.build())
        }
        browser.sendCustomAction("MediaMetadataCompat", bundle, null)
    }

    companion object {
        private const val TAG = "MediaSessionManager"
    }
}