package com.fcaronte.aabrowser.mediaservice

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.fcaronte.aabrowser.R
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream

@OptIn(UnstableApi::class)
class CarMediaService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private lateinit var player: MyPlayer
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    // State variables to track what's happening in the WebView
    private var currentMediaMetadata = MediaMetadata.Builder()
        .setTitle("AABrowser")
        .setArtist("Initializing...")
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .build()
    private var currentPlaybackState = Player.STATE_IDLE
    private var currentPlayWhenReady = false
    private var currentPosition = 0L
    private var currentDuration = C.TIME_UNSET
    private var lastFocusRequestTime = 0L
    private var lastSeekRequestTime = 0L
    private var pendingSeekPosition = -1L
    private var hasAudioFocus = false
    private var lastTitle: String? = null

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.d(TAG, "@@@ onAudioFocusChange: $focusChange @@@")
        val now = System.currentTimeMillis()
        if (now - lastFocusRequestTime < 2000) {
            Log.d(TAG, "Ignoring focus change immediately after request")
            return@OnAudioFocusChangeListener
        }

        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Losing focus -> Pausing WebView")
                hasAudioFocus = false
                broadcastPlaybackAction(ACTION_PAUSE)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {}
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Gained focus")
                hasAudioFocus = true
            }
        }
    }

    private inner class MyPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var isComputingState = false

        override fun getState(): State {
            Log.e(TAG, "@@@ getState called: state=$currentPlaybackState, pwr=$currentPlayWhenReady @@@")
            if (isComputingState) {
                return State.Builder()
                    .setAvailableCommands(Player.Commands.EMPTY)
                    .build()
            }
            isComputingState = true
            try {
                val mediaItem = MediaItem.Builder()
                    .setMediaId("current_web_audio")
                    .setMediaMetadata(currentMediaMetadata)
                    .build()

                val mediaItemData = MediaItemData.Builder("current_web_audio")
                    .setMediaItem(mediaItem)
                    .setDurationUs(if (currentDuration > 0) currentDuration * 1000 else C.TIME_UNSET)
                    .build()

                return State.Builder()
                    .setAvailableCommands(
                        Player.Commands.Builder()
                            .add(Player.COMMAND_PLAY_PAUSE)
                            .add(Player.COMMAND_STOP)
                            .add(Player.COMMAND_PREPARE)
                            .add(Player.COMMAND_SEEK_TO_NEXT)
                            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                            .add(Player.COMMAND_GET_METADATA)
                            .add(Player.COMMAND_GET_TIMELINE)
                            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                            .add(Player.COMMAND_SET_MEDIA_ITEM)
                            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                            .build()
                    )
                    .setPlaybackState(currentPlaybackState)
                    .setPlayWhenReady(currentPlayWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                    .setPlaybackParameters(
                        if (currentPlayWhenReady && currentPlaybackState == Player.STATE_READY) 
                            androidx.media3.common.PlaybackParameters.DEFAULT 
                        else 
                            androidx.media3.common.PlaybackParameters(0.0001f)
                    )
                    .setPlaylist(ImmutableList.of(mediaItemData))
                    .setCurrentMediaItemIndex(0)
                    .setContentPositionMs(currentPosition)
                    .build()
            } finally {
                isComputingState = false
            }
        }

        fun triggerInvalidateState() {
            Log.e(TAG, "@@@ triggerInvalidateState @@@")
            invalidateState()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            Log.e(TAG, "@@@ handleSetPlayWhenReady: $playWhenReady (current=$currentPlayWhenReady) @@@")
            
            // System/Car Screen command: request/abandon focus explicitly
            if (playWhenReady) {
                requestAudioFocus()
                broadcastPlaybackAction(ACTION_PLAY)
            } else {
                abandonAudioFocus()
                broadcastPlaybackAction(ACTION_PAUSE)
            }
            
            // Only update locally if focus was granted or we are pausing
            currentPlayWhenReady = playWhenReady
            currentPlaybackState = Player.STATE_READY
            
            invalidateState()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun handleStop(): ListenableFuture<*> {
            Log.e(TAG, "@@@ handleStop @@@")
            currentPlaybackState = Player.STATE_IDLE
            currentPlayWhenReady = false
            abandonAudioFocus()
            broadcastPlaybackAction(ACTION_STOP)
            invalidateState()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // We will remove the custom functions for now to keep it clean, 
        // as the core issue is Play/Pause and Seek.
        
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            Log.e(TAG, "@@@ handleSeek: $positionMs @@@")
            lastSeekRequestTime = System.currentTimeMillis()
            pendingSeekPosition = positionMs
            this@CarMediaService.currentPosition = positionMs
            broadcastPlaybackAction(ACTION_SEEK_TO, positionMs)
            invalidateState()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
            Log.e(TAG, "@@@ handleSetMediaItems: ${mediaItems.size} items @@@")
            if (mediaItems.isNotEmpty()) {
                val item = mediaItems[0]
                this@CarMediaService.currentMediaMetadata = item.mediaMetadata
                this@CarMediaService.currentPosition = if (startPositionMs != C.TIME_UNSET) startPositionMs else 0L
                invalidateState()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onCreate() {
        Log.e(TAG, "@@@ onCreate called: this=${this.hashCode()} @@@")
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        player = MyPlayer()
        
        val intent = android.content.Intent(this, com.fcaronte.aabrowser.MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE)
        
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(pendingIntent)
            .setId("AABrowserSession")
            .build()
        player.triggerInvalidateState()
        Log.e(TAG, "@@@ session created and state invalidated: ${session?.hashCode()} @@@")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        Log.d(TAG, "onGetSession: from ${controllerInfo.packageName}")
        return session
    }

    fun requestAudioFocus(): Boolean {
        Log.d(TAG, "requestAudioFocus: current hasAudioFocus=$hasAudioFocus")
        if (hasAudioFocus) return true
        
        lastFocusRequestTime = System.currentTimeMillis()
        val playbackAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(playbackAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build()

        val res = audioManager.requestAudioFocus(focusRequest!!)
        if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            hasAudioFocus = true
        }
        return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        Log.d(TAG, "abandonAudioFocus")
        focusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
        hasAudioFocus = false
    }

    override fun onDestroy() {
        abandonAudioFocus()
        session?.run {
            player.release()
            release()
            session = null
        }
        super.onDestroy()
    }

    private fun broadcastPlaybackAction(action: Long, position: Long = 0L) {
        Log.e(TAG, "@@@ broadcastPlaybackAction: $action, position=$position @@@")
        val bundle = Bundle().apply {
            putLong(PLAYBACK_ACTION, action)
            if (action == ACTION_SEEK_TO) {
                putLong("SeekPosition", position)
            }
        }
        session?.broadcastCustomCommand(SessionCommand(PLAYBACK_ACTION, Bundle.EMPTY), bundle)
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Log.e(TAG, "@@@ onGetLibraryRoot from ${browser.packageName} @@@")
            val rootItem = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("AABrowser")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Log.e(TAG, "@@@ onGetItem: $mediaId from ${browser.packageName} @@@")
            val item = when (mediaId) {
                "root" -> MediaItem.Builder()
                    .setMediaId("root")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("AABrowser")
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                            .build()
                    )
                    .build()
                "current_web_audio" -> MediaItem.Builder()
                    .setMediaId("current_web_audio")
                    .setMediaMetadata(currentMediaMetadata)
                    .build()
                else -> return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            }
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            Log.e(TAG, "@@@ onGetChildren: parentId=$parentId from ${browser.packageName} @@@")
            if (parentId == "root") {
                val item = MediaItem.Builder()
                    .setMediaId("current_web_audio")
                    .setMediaMetadata(currentMediaMetadata)
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(item), params))
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            Log.e(TAG, "@@@ onAddMediaItems from ${controller.packageName} @@@")
            val resolvedItems = mediaItems.map { item ->
                if (item.mediaId == "current_web_audio") {
                    item.buildUpon()
                        .setMediaMetadata(currentMediaMetadata)
                        .build()
                } else {
                    item
                }
            }.toMutableList()
            return Futures.immediateFuture(resolvedItems)
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            Log.e(TAG, "@@@ onConnect from ${controller.packageName} @@@")
            
            // Start with the default session commands (which include browsing)
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(PLAYBACK_STATE_COMPAT, Bundle.EMPTY))
                .add(SessionCommand(MEDIA_METADATA_COMPAT, Bundle.EMPTY))
                .build()
            
            // Include all player commands to ensure full control
            val playerCommands = Player.Commands.Builder()
                .addAllCommands()
                .build()
                
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            Log.e(TAG, "@@@ onCustomCommand: ${customCommand.customAction} from ${controller.packageName} @@@")
            when (customCommand.customAction) {
                PLAYBACK_STATE_COMPAT -> {
                    val state = args.getInt("state")
                    val position = args.getLong("position")
                    updatePlayerState(state, position)
                }
                MEDIA_METADATA_COMPAT -> {
                    val title = args.getString("title") ?: ""
                    val artist = args.getString("artist") ?: ""
                    val duration = args.getLong("duration", C.TIME_UNSET)
                    val icon = args.getParcelable<Bitmap>("icon")
                    updateMetadata(title, artist, duration, icon)
                }
                RESET_COMMAND -> {
                    resetState()
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private fun resetState() {
        Log.e(TAG, "@@@ Resetting all state @@@")
        currentPosition = 0L
        currentDuration = C.TIME_UNSET
        currentPlaybackState = Player.STATE_IDLE
        currentPlayWhenReady = false
        lastTitle = null
        pendingSeekPosition = -1L
        currentMediaMetadata = MediaMetadata.Builder()
            .setTitle("AABrowser")
            .setArtist("Waiting for media...")
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
        player.triggerInvalidateState()
    }

    private fun updatePlayerState(state: Int, position: Long) {
        Log.e(TAG, "@@@ updatePlayerState: incoming state=$state, position=$position (current pos=$currentPosition) @@@")
        val newPlaybackState: Int
        val newPlayWhenReady: Boolean
        
        when (state) {
            1 -> { // STATE_STOPPED / STATE_NONE
                newPlaybackState = Player.STATE_IDLE
                newPlayWhenReady = false
            }
            2 -> { // STATE_PAUSED
                newPlaybackState = Player.STATE_READY
                newPlayWhenReady = false
            }
            3 -> { // STATE_PLAYING
                newPlaybackState = Player.STATE_READY
                newPlayWhenReady = true
                // Removed requestAudioFocus() here to prevent tug-of-war with WebView/Chromium
                if (!hasAudioFocus) {
                    hasAudioFocus = true // Assume we have it since WebView is playing
                }
            }
            6 -> { // STATE_BUFFERING
                newPlaybackState = Player.STATE_BUFFERING
                newPlayWhenReady = currentPlayWhenReady
            }
            else -> {
                newPlaybackState = Player.STATE_IDLE
                newPlayWhenReady = false
            }
        }

        val now = System.currentTimeMillis()
        
        // --- Seek Stability Logic ---
        // If we recently performed a seek, we strictly ignore WebView reports for 2 seconds
        // or until they are very close to our target.
        if (now - lastSeekRequestTime < 2500 && pendingSeekPosition != -1L) {
            val diff = Math.abs(pendingSeekPosition - position)
            if (diff > 4000) { 
                Log.d(TAG, "Ignoring stale/jitter report during seek lock: $position (target $pendingSeekPosition)")
                // IMPORTANT: We do NOT update currentPosition here, we keep it at the target
                return
            } else {
                Log.d(TAG, "Seek settled at $position")
                pendingSeekPosition = -1L // Settled
            }
        }

        val stateChanged = newPlaybackState != currentPlaybackState || newPlayWhenReady != currentPlayWhenReady
        val positionChanged = Math.abs(currentPosition - position) > 800

        currentPosition = position
        currentPlaybackState = newPlaybackState
        currentPlayWhenReady = newPlayWhenReady

        // Force invalidation to ensure UI reflects the exact position
        if (stateChanged || positionChanged || (newPlayWhenReady && now % 1000 < 300)) {
            Log.d(TAG, "@@@ State Invalidation: state=$currentPlaybackState, pwr=$currentPlayWhenReady, pos=$currentPosition @@@")
            player.triggerInvalidateState()
        }
    }

    private fun updateMetadata(title: String, artist: String, duration: Long, icon: Bitmap?) {
        Log.d(TAG, "@@@ updateMetadata: title=$title, artist=$artist, duration=$duration @@@")
        
        val durationMs = if (duration > 0) duration else C.TIME_UNSET
        
        if (title != lastTitle) {
            Log.e(TAG, "@@@ Title changed: $lastTitle -> $title. Resetting position to 0 @@@")
            currentPosition = 0L
            lastTitle = title
            // Reset duration to ensure it's updated correctly for the new track
            currentDuration = durationMs
            pendingSeekPosition = -1L
        } else if (durationMs != currentDuration && durationMs != C.TIME_UNSET) {
            currentDuration = durationMs
        }
        
        val builder = MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setArtist(artist)
            .setAlbumArtist(artist)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            
        if (currentDuration != C.TIME_UNSET) {
            builder.setDurationMs(currentDuration)
        }
            
        // Add duration to extras for legacy support
        if (currentDuration != C.TIME_UNSET) {
            val extras = Bundle()
            extras.putLong("android.media.metadata.DURATION", currentDuration)
            builder.setExtras(extras)
        }

        icon?.let {
            try {
                val stream = ByteArrayOutputStream()
                it.compress(Bitmap.CompressFormat.PNG, 100, stream)
                builder.setArtworkData(stream.toByteArray(), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            } catch (e: Exception) {
                Log.e(TAG, "Error compressing icon", e)
            }
        }

        currentMediaMetadata = builder.build()
        player.triggerInvalidateState()
    }

    companion object {
        private const val TAG = "CarMediaService"
        private const val PLAYBACK_STATE_COMPAT = "PlaybackStateCompat"
        private const val MEDIA_METADATA_COMPAT = "MediaMetadataCompat"
        private const val RESET_COMMAND = "ResetCommand"
        private const val PLAYBACK_ACTION = "PlaybackAction"

        // Legacy action constants to keep logic consistent
        private const val ACTION_PAUSE = 2L
        private const val ACTION_PLAY = 4L
        private const val ACTION_STOP = 1L
        private const val ACTION_SKIP_TO_NEXT = 32L
        private const val ACTION_SKIP_TO_PREVIOUS = 16L
        private const val ACTION_SEEK_TO = 256L
    }
}