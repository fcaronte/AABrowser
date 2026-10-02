package com.fcaronte.aabrowser.mediaservice

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.fcaronte.aabrowser.utils.AppLog
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture

@UnstableApi
class CarMediaService3 : MediaLibraryService() {
    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()
        AppLog.d(TAG, "CarMediaService3 onCreate")

        player = ExoPlayer.Builder(this).build()

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, CarMediaLibrarySessionCallback())
            .build()

        AppLog.d(TAG, "MediaLibrarySession created")
    }

    override fun onDestroy() {
        AppLog.d(TAG, "CarMediaService3 onDestroy")
        mediaLibrarySession?.release()
        mediaLibrarySession = null
        player.release()
        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    private inner class CarMediaLibrarySessionCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            AppLog.d(TAG, "MediaLibrarySession onConnect from ${controller.packageName}")
            return MediaSession.ConnectionResult.accept(
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,
                androidx.media3.common.Player.Commands.Builder().build()
            )
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            AppLog.d(TAG, "onGetLibraryRoot")
            val result = SettableFuture.create<LibraryResult<MediaItem>>()
            
            val rootItem = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle("AABrowser Media")
                        .build()
                )
                .build()
            
            result.set(LibraryResult.ofItem(rootItem, params))
            return result
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            AppLog.d(TAG, "onGetChildren: parentId=$parentId")
            val result = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            
            val children = mutableListOf<MediaItem>()
            
            if (parentId == "root") {
                val currentItem = MediaItem.Builder()
                    .setMediaId("current_web_audio")
                    .setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder()
                            .setTitle("Web Audio")
                            .setArtist("Current Playback")
                            .build()
                    )
                    .build()
                children.add(currentItem)
            }
            
            result.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
            return result
        }
    }

    companion object {
        private const val TAG = "CarMediaService3"
    }
}
