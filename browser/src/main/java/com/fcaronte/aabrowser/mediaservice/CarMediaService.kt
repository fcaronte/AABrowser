package com.fcaronte.aabrowser.mediaservice

import android.app.Notification
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.utils.AppLog
import com.fcaronte.aabrowser.utils.fetchWeather
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// TODO: Valutare migrazione a Jetpack Media3 in futuro
@Suppress("DEPRECATION")
class CarMediaService : MediaBrowserServiceCompat() {
    private var mCarmedianotificationmanager: CarMediaNotificationManager? = null
    private var mMediasessioncompat: MediaSessionCompat? = null
    private var mMediacontrollercompat: MediaControllerCompat? = null
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    var showingWeather = true
    var isUserPaused = false

    val serviceScope = CoroutineScope(Dispatchers.Main)
    private var weatherCheckJob: Job? = null
    private val settingsPreferences by lazy {
        getSharedPreferences("aa_browser_settings", MODE_PRIVATE)
    }
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "weather_widget_enabled" && settingsPreferences.getBoolean(key, false)) {
            serviceScope.launch { checkAndUpdateWeatherMetadata() }
        }
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {}
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {}
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {}
            AudioManager.AUDIOFOCUS_GAIN -> {}
        }
    }

    override fun onCreate() {
        super.onCreate()

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        mCarmedianotificationmanager = CarMediaNotificationManager()
        mCarmedianotificationmanager!!.setCarMediaService(this)
        mCarmedianotificationmanager!!.onCreate()

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

        mMediasessioncompat = MediaSessionCompat(this, "CarMediaService").apply {
            setCallback(MediaSessionCallback(this@CarMediaService))
            setActive(true)
            setPlaybackState(builder.build())
            setMetadata(MediaMetadataCompat.Builder().build())
        }

        mMediacontrollercompat = mMediasessioncompat!!.controller
        sessionToken = mMediasessioncompat!!.sessionToken

        settingsPreferences.registerOnSharedPreferenceChangeListener(settingsListener)
        checkAndUpdateWeatherMetadata()
    }

    fun checkAndUpdateWeatherMetadata() {
        val prefs = getSharedPreferences("aa_browser_settings", MODE_PRIVATE)
        val weatherEnabled = prefs.getBoolean("weather_widget_enabled", false)
        val currentState = mMediacontrollercompat?.playbackState?.state ?: PlaybackStateCompat.STATE_NONE
        val isWeatherMode = weatherEnabled && (showingWeather || currentState == PlaybackStateCompat.STATE_NONE) && !isUserPaused

        AppLog.d(TAG, "checkAndUpdateWeatherMetadata: weatherEnabled=$weatherEnabled, showingWeather=$showingWeather, isUserPaused=$isUserPaused, isWeatherMode=$isWeatherMode")

        if (isWeatherMode) {
            // Mostra subito uno stato di caricamento per dare feedback all'utente
            val loadingTitle = "Meteo: recupero posizione..."
            val loadingMetadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, loadingTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "AABrowser")
                .build()
            mMediasessioncompat?.setMetadata(loadingMetadata)

            weatherCheckJob?.cancel()
            weatherCheckJob = serviceScope.launch {
                AppLog.d(TAG, "Starting background weather fetch for CarMediaService...")
                val weather = fetchWeather(applicationContext)
                if (weather != null && mMediasessioncompat != null) {
                    val currentPlayState = mMediacontrollercompat?.playbackState?.state ?: PlaybackStateCompat.STATE_NONE
                    val stillWeatherNode = (showingWeather || currentPlayState == PlaybackStateCompat.STATE_NONE) && !isUserPaused

                    if (stillWeatherNode) {
                        showingWeather = true
                        val titleStr = "${weather.temperature.toInt()}°C • ${weather.description}"
                        val artistStr = weather.locationName

                        val metadata = MediaMetadataCompat.Builder()
                            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, titleStr)
                            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artistStr)
                            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, titleStr)
                            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artistStr)
                            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, weather.bitmap)
                            .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, weather.bitmap)
                            .build()
                        mMediasessioncompat?.setMetadata(metadata)

                        // STATE_PAUSED garantisce che Android Auto mostri la barra del player in auto senza avviare audio
                        val state = PlaybackStateCompat.Builder()
                            .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1.0f)
                            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE)
                            .build()
                        mMediasessioncompat?.setPlaybackState(state)

                        updateNotification()
                        requestAudioFocus()
                        AppLog.d(TAG, "CarMediaService metadata & state updated with weather: $titleStr at $artistStr (STATE_PAUSED)")
                    }
                }
            }
        }
    }

    fun requestAudioFocus(): Boolean {
        val playbackAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        focusRequest =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()

        audioManager.requestAudioFocus(focusRequest!!)
        return true
    }

    private fun abandonAudioFocus() {
        focusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
    }

    override fun onDestroy() {
        settingsPreferences.unregisterOnSharedPreferenceChangeListener(settingsListener)
        weatherCheckJob?.cancel()
        abandonAudioFocus()
        mCarmedianotificationmanager?.onDestroy()
        mCarmedianotificationmanager = null

        mMediasessioncompat?.let {
            it.setCallback(null)
            it.isActive = false
            it.release()
            mMediasessioncompat = null
        }
        mMediacontrollercompat = null
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        AppLog.d(TAG, "onGetRoot: client=$clientPackageName")
        
        val prefs = getSharedPreferences("aa_browser_settings", MODE_PRIVATE)
        val mediaPlayerEnabled = prefs.getBoolean("media_player_enabled", true)
        
        if (!mediaPlayerEnabled) {
            mMediasessioncompat?.isActive = false
            return BrowserRoot("root", null)
        }
        
        mMediasessioncompat?.isActive = true
        checkAndUpdateWeatherMetadata()
        return BrowserRoot("root", null)
    }

    override fun onLoadChildren(
        parentMediaId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem?>?>
    ) {
        AppLog.d(TAG, "onLoadChildren: parentMediaId=$parentMediaId")
        
        val prefs = getSharedPreferences("aa_browser_settings", MODE_PRIVATE)
        val mediaPlayerEnabled = prefs.getBoolean("media_player_enabled", true)
        
        if (!mediaPlayerEnabled) {
            result.detach()
            return
        }
        
        checkAndUpdateWeatherMetadata()

        val mediaItems = mutableListOf<MediaBrowserCompat.MediaItem?>()

        if ("root" == parentMediaId) {
            val description = MediaDescriptionCompat.Builder()
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
        AppLog.d(TAG, "onCustomAction: $action")
        if (mMediasessioncompat != null && extras != null) {
            extras.classLoader = PlaybackStateCompat::class.java.classLoader
            var update = false
            var cancel = false
            if (action == PLAYBACK_STATE_COMPAT) {
                val playbackStateCompat =
                    extras.getParcelable<PlaybackStateCompat?>("PlaybackStateCompat")
                if (playbackStateCompat != null) {
                    update = stateChanged(playbackStateCompat)
                    cancel = (playbackStateCompat.state == PlaybackStateCompat.STATE_NONE)

                    mMediasessioncompat!!.setPlaybackState(playbackStateCompat)

                    if (playbackStateCompat.state == PlaybackStateCompat.STATE_NONE ||
                        playbackStateCompat.state == PlaybackStateCompat.STATE_STOPPED) {
                        showingWeather = true
                        isUserPaused = false
                        checkAndUpdateWeatherMetadata()
                    }
                }
            }
            if (action == MEDIA_METADATA_COMPAT) {
                val mediaMetadataCompat =
                    extras.getParcelable<MediaMetadataCompat?>(MEDIA_METADATA_COMPAT)
                if (mediaMetadataCompat != null) {
                    val title = mediaMetadataCompat.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
                    if (title.isNullOrBlank()) {
                        showingWeather = true
                        isUserPaused = false
                        checkAndUpdateWeatherMetadata()
                    } else {
                        showingWeather = false
                        mMediasessioncompat!!.setMetadata(mediaMetadataCompat)
                        requestAudioFocus()
                        update = true
                    }
                }
            }
            if (cancel && mCarmedianotificationmanager != null) {
                mCarmedianotificationmanager!!.cancel()
            } else if (update) {
                updateNotification()
            }
        }
        result.sendResult(null)
    }

    private fun broadcastPlaybackAction(action: Long) {
        val bundle = Bundle().apply { putLong(PLAYBACK_ACTION, action) }
        if (mMediasessioncompat?.isActive == true) {
            mMediasessioncompat!!.sendSessionEvent(PLAYBACK_ACTION, bundle)
        }
    }

    private class MediaSessionCallback(private val service: CarMediaService) :
        MediaSessionCompat.Callback() {
        override fun onPlay() {
            val currentTitle = service.mMediacontrollercompat?.metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: ""
            if (currentTitle.contains("°C") || currentTitle.contains("aggiornando")) {
                AppLog.d(TAG, "Play pressed in weather mode -> showing updating message and refreshing weather")
                
                service.showingWeather = true
                service.isUserPaused = false

                val updatingTitle = "Sto aggiornando..."
                val artistStr = service.mMediacontrollercompat?.metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: "Meteo"
                val existingBitmap = service.mMediacontrollercompat?.metadata?.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)

                val updatingMetadata = MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, updatingTitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artistStr)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, updatingTitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artistStr)
                    .apply {
                        if (existingBitmap != null) {
                            putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, existingBitmap)
                            putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, existingBitmap)
                        }
                    }
                    .build()
                service.mMediasessioncompat?.setMetadata(updatingMetadata)
                service.updateNotification()

                service.serviceScope.launch {
                    val weather = fetchWeather(service.applicationContext)
                    if (weather != null && service.mMediasessioncompat != null) {
                        service.showingWeather = true
                        val titleStr = "${weather.temperature.toInt()}°C • ${weather.description}"
                        val newArtistStr = weather.locationName

                        val metadata = MediaMetadataCompat.Builder()
                            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, titleStr)
                            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, newArtistStr)
                            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, titleStr)
                            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, newArtistStr)
                            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, weather.bitmap)
                            .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, weather.bitmap)
                            .build()
                        service.mMediasessioncompat?.setMetadata(metadata)

                        val state = PlaybackStateCompat.Builder()
                            .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1.0f)
                            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE)
                            .build()
                        service.mMediasessioncompat?.setPlaybackState(state)
                        service.updateNotification()
                        AppLog.d(TAG, "Weather refreshed successfully via Play button: $titleStr")
                    }
                }
                return
            }

            service.showingWeather = false
            service.isUserPaused = false
            service.requestAudioFocus()
            service.broadcastPlaybackAction(PlaybackStateCompat.ACTION_PLAY)
        }

        override fun onPause() {
            service.showingWeather = false
            service.isUserPaused = true
            service.abandonAudioFocus()
            service.broadcastPlaybackAction(PlaybackStateCompat.ACTION_PAUSE)
        }

        override fun onStop() {
            service.showingWeather = true
            service.isUserPaused = false
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
            service.mMediasessioncompat?.sendSessionEvent(PLAYBACK_ACTION, bundle)
        }
    }

    val notification: Notification?
        get() {
            if (mCarmedianotificationmanager == null || mMediacontrollercompat == null) return null
            return mCarmedianotificationmanager!!.getNotification(
                mMediacontrollercompat!!.metadata,
                mMediacontrollercompat?.playbackState,
                sessionToken
            )
        }

    private fun updateNotification() {
        val notification = this.notification
        val state = mMediacontrollercompat?.playbackState?.state
        if (notification != null && mCarmedianotificationmanager != null) {
            if (state == PlaybackStateCompat.STATE_PLAYING) {
                startForeground(
                    600,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                stopForeground(STOP_FOREGROUND_DETACH)
                mCarmedianotificationmanager!!.notify(notification)
            }
        }
    }

    fun stateChanged(playbackStateCompat: PlaybackStateCompat): Boolean {
        if (mMediacontrollercompat?.playbackState == null) return false
        return mMediacontrollercompat!!.playbackState.state != playbackStateCompat.state
    }

    companion object {
        private const val TAG = "CarMediaService"
        private const val PLAYBACK_STATE_COMPAT = "PlaybackStateCompat"
        private const val MEDIA_METADATA_COMPAT = "MediaMetadataCompat"
        private const val PLAYBACK_ACTION = "PlaybackAction"
    }
}
