package com.fcaronte.aabrowser.mediaservice

import android.app.Notification
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import com.fcaronte.aabrowser.R

// TODO: Valutare migrazione a Jetpack Media3 in futuro
@Suppress("DEPRECATION")
class CarMediaService : MediaBrowserServiceCompat() {
    private var m_CarMediaNotificationManager: CarMediaNotificationManager? = null
    private var m_MediaSessionCompat: MediaSessionCompat? = null
    private var m_MediaControllerCompat: MediaControllerCompat? = null
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Perdita definitiva: qui potremmo voler stoppare, ma per ora restiamo conservativi
                // per evitare stop indesiderati su alcuni sistemi che lo inviano erroneamente.
                // broadcastPlaybackAction(PlaybackStateCompat.ACTION_PAUSE)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Perdita temporanea (es. assistente vocale): NON stoppiamo il browser.
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // L'audio si abbassa ma non deve stopparsi (es. indicazioni stradali)
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                // Focus riottenuto: potremmo voler riprendere se avevamo pausato
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        m_CarMediaNotificationManager = CarMediaNotificationManager()
        m_CarMediaNotificationManager!!.setCarMediaService(this)
        m_CarMediaNotificationManager!!.onCreate()

        val builder = PlaybackStateCompat.Builder()
        builder.setActions(
            PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SEEK_TO
        )
        builder.setState(PlaybackStateCompat.STATE_NONE, 0, 1.0f)

        m_MediaSessionCompat = MediaSessionCompat(this, "CarMediaService").apply {
            setCallback(MediaSessionCallback(this@CarMediaService))
            setActive(true)
            setPlaybackState(builder.build())
            setMetadata(MediaMetadataCompat.Builder().build())
        }

        m_MediaControllerCompat = m_MediaSessionCompat!!.controller
        sessionToken = m_MediaSessionCompat!!.sessionToken
    }

    fun requestAudioFocus(): Boolean {
        val playbackAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        focusRequest =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN) // Prova anche AUDIOFOCUS_GAIN_TRANSIENT se continua a fallire
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()

        audioManager.requestAudioFocus(focusRequest!!)

        // FORZIAMO IL RITORNO A TRUE: anche se il sistema ci dà un warning sull'audio focus,
        // permettiamo comunque al comando Play di raggiungere la WebView.
        return true
    }

    private fun abandonAudioFocus() {
        focusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
    }

    override fun onDestroy() {
        abandonAudioFocus()
        m_CarMediaNotificationManager?.onDestroy()
        m_CarMediaNotificationManager = null

        m_MediaSessionCompat?.let {
            it.setCallback(null)
            it.isActive = false
            it.release()
            m_MediaSessionCompat = null
        }
        m_MediaControllerCompat = null
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot("root", null)
    }

    override fun onLoadChildren(
        parentMediaId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem?>?>
    ) {
        val mediaItems = mutableListOf<MediaBrowserCompat.MediaItem?>()

        if ("root" == parentMediaId) {
            val description = android.support.v4.media.MediaDescriptionCompat.Builder()
                .setMediaId("current_web_audio")
                .setTitle(getString(R.string.media_browser_title))
                .setSubtitle(getString(R.string.media_browser_subtitle))
                .build()

            mediaItems.add(
                MediaBrowserCompat.MediaItem(
                    description,
                    MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                )
            )
        }

        result.sendResult(mediaItems)
    }

    override fun onCustomAction(action: String, extras: Bundle?, result: Result<Bundle?>) {
        Log.d(TAG, "onCustomAction: $action")
        if (m_MediaSessionCompat != null && extras != null) {
            extras.classLoader = PlaybackStateCompat::class.java.classLoader
            var update = false
            var cancel = false
            if (action == PLAYBACK_STATE_COMPAT) {
                val playbackStateCompat =
                    extras.getParcelable<PlaybackStateCompat?>(PLAYBACK_STATE_COMPAT)
                if (playbackStateCompat != null) {
                    update = stateChanged(playbackStateCompat)
                    cancel = (playbackStateCompat.state == PlaybackStateCompat.STATE_NONE)

                    // Richiede il focus solo se stiamo effettivamente cambiando stato verso PLAYING
                    if (playbackStateCompat.state == PlaybackStateCompat.STATE_PLAYING &&
                        (m_MediaControllerCompat?.playbackState?.state != PlaybackStateCompat.STATE_PLAYING)
                    ) {
                        requestAudioFocus()
                    }

                    m_MediaSessionCompat!!.setPlaybackState(playbackStateCompat)
                }
            }
            if (action == MEDIA_METADATA_COMPAT) {
                val mediaMetadataCompat =
                    extras.getParcelable<MediaMetadataCompat?>(MEDIA_METADATA_COMPAT)
                if (mediaMetadataCompat != null) {
                    m_MediaSessionCompat!!.setMetadata(mediaMetadataCompat)
                    update = true
                }
            }
            if (cancel && m_CarMediaNotificationManager != null) {
                m_CarMediaNotificationManager!!.cancel()
            } else if (update) {
                updateNotification()
            }
        }
        result.sendResult(null)
    }

    private fun broadcastPlaybackAction(action: Long) {
        val bundle = Bundle().apply { putLong(PLAYBACK_ACTION, action) }
        if (m_MediaSessionCompat?.isActive == true) {
            m_MediaSessionCompat!!.sendSessionEvent(PLAYBACK_ACTION, bundle)
        }
    }

    private class MediaSessionCallback(private val service: CarMediaService) :
        MediaSessionCompat.Callback() {
        override fun onPlay() {
            // CORRETTO: Richiediamo l'audio focus immediatamente alla ricezione del comando Play
            service.requestAudioFocus()
            service.broadcastPlaybackAction(PlaybackStateCompat.ACTION_PLAY)
        }

        override fun onPause() {
            service.abandonAudioFocus()
            service.broadcastPlaybackAction(PlaybackStateCompat.ACTION_PAUSE)
        }

        override fun onStop() {
            service.abandonAudioFocus()
            service.broadcastPlaybackAction(PlaybackStateCompat.ACTION_STOP)
        }

        override fun onSkipToPrevious() {
            service.broadcastPlaybackAction(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
        }

        override fun onSkipToNext() {
            service.broadcastPlaybackAction(PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
        }

        override fun onSeekTo(pos: Long) {
            val bundle = Bundle().apply {
                putLong(PLAYBACK_ACTION, PlaybackStateCompat.ACTION_SEEK_TO)
                putLong("SeekPosition", pos)
            }
            service.m_MediaSessionCompat?.sendSessionEvent(PLAYBACK_ACTION, bundle)
        }
    }

    val notification: Notification?
        get() {
            if (m_CarMediaNotificationManager == null || m_MediaControllerCompat == null) return null
            return m_CarMediaNotificationManager!!.getNotification(
                m_MediaControllerCompat!!.metadata,
                m_MediaControllerCompat!!.playbackState,
                sessionToken
            )
        }

    private fun updateNotification() {
        val notification = this.notification
        val state = m_MediaControllerCompat?.playbackState?.state
        if (notification != null && m_CarMediaNotificationManager != null) {
            if (state == PlaybackStateCompat.STATE_PLAYING) {
                startForeground(
                    600,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                stopForeground(STOP_FOREGROUND_DETACH)
                m_CarMediaNotificationManager!!.notify(notification)
            }
        }
    }

    fun stateChanged(playbackStateCompat: PlaybackStateCompat): Boolean {
        if (m_MediaControllerCompat?.playbackState == null) return false
        return m_MediaControllerCompat!!.playbackState.state != playbackStateCompat.state
    }


    companion object {
        private const val TAG = "CarMediaService"
        private const val PLAYBACK_STATE_COMPAT = "PlaybackStateCompat"
        private const val MEDIA_METADATA_COMPAT = "MediaMetadataCompat"
        private const val PLAYBACK_ACTION = "PlaybackAction"
    }
}