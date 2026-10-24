package com.fcaronte.aabrowser.mediaservice

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.car.app.connection.CarConnection
import androidx.core.content.IntentCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.media.MediaBrowserServiceCompat
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.utils.AppLog
import com.fcaronte.aabrowser.utils.VoiceCommandRouter
import com.fcaronte.aabrowser.utils.VoiceCommands
import com.fcaronte.aabrowser.utils.fetchWeather
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.milliseconds

@Suppress("DEPRECATION")
class CarMediaService : MediaBrowserServiceCompat() {
    private var mCarmedianotificationmanager: CarMediaNotificationManager? = null
    private var mMediasessioncompat: MediaSessionCompat? = null
    private var mMediacontrollercompat: MediaControllerCompat? = null
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    var showingWeather = true
    var isUserPaused = false

    // Stato e tasti mostrati nella notifica. La posizione arriva ogni mezzo secondo e non va ripubblicata:
    // oltre 5 aggiornamenti al secondo il sistema li scarta, compresi quelli con titolo e copertina nuovi
    private var notifiedState = -1
    private var notifiedActions = -1L

    val serviceScope = CoroutineScope(Dispatchers.Main)
    private var weatherCheckJob: Job? = null
    private val settingsPreferences by lazy {
        getSharedPreferences("aa_browser_settings", MODE_PRIVATE)
    }
    // Meteo acceso o spento dalle impostazioni: spento toglie subito scheda e notifica
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "weather_widget_enabled") {
            serviceScope.launch {
                checkAndUpdateWeatherMetadata()
                updateNotification()
            }
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

    private var connectivityManager: android.net.ConnectivityManager? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    // Il servizio resta vivo tra un viaggio e l'altro, e con lui la pausa (di Android Auto anche allo spegnimento
    // dell'auto): al viaggio dopo il meteo restava nascosto finché "Esci" non chiudeva il processo.
    // Il meteo c'è solo in auto: il servizio parte anche sul telefono (la Main si collega a ogni scheda) e il meteo
    // restava lì, con la sua notifica, anche fuori dall'auto. null = collegamento non ancora noto (servizio appena creato)
    private var carConnectionType: LiveData<Int>? = null
    private var wasConnectedToCar: Boolean? = null
    private val carConnectionObserver = Observer<Int> { type ->
        val connected = type != CarConnection.CONNECTION_TYPE_NOT_CONNECTED
        val wasConnected = wasConnectedToCar
        wasConnectedToCar = connected
        if (connected && wasConnected != true) onNewCarConnection()
        else if (!connected && wasConnected == true) onCarDisconnected()
    }

    private fun isInCar(): Boolean = wasConnectedToCar == true

    /** Collegamento all'auto (anche col servizio appena creato in auto): se non sta suonando niente si torna al meteo. */
    private fun onNewCarConnection() {
        val state = mMediacontrollercompat?.playbackState?.state ?: PlaybackStateCompat.STATE_NONE
        AppLog.d(TAG, "New car connection (playback state=$state)")
        if (isPlayingState(state)) return
        isUserPaused = false
        showingWeather = true
        checkAndUpdateWeatherMetadata()
    }

    /**
     * Auto scollegata: via il meteo e la notifica, anche di una pagina in pausa. Resta solo una pagina che suona
     * ancora sul telefono.
     */
    private fun onCarDisconnected() {
        weatherCheckJob?.cancel()
        endVoiceOverlay()
        val state = mMediacontrollercompat?.playbackState?.state ?: PlaybackStateCompat.STATE_NONE
        AppLog.d(TAG, "Car disconnected (playback state=$state)")
        if (!isPlayingState(state)) {
            clearPlayer()
            showingWeather = true
            isUserPaused = false
        }
        updateNotification()
    }

    // Player vuoto: Android Auto non mostra schede vuote
    private fun clearPlayer() {
        setSessionMetadata(MediaMetadataCompat.Builder().build())
        publishPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_NONE, 0, 1.0f)
                .build()
        )
    }

    private fun isPlayingState(state: Int): Boolean =
        state == PlaybackStateCompat.STATE_PLAYING || state == PlaybackStateCompat.STATE_BUFFERING

    private fun registerNetworkCallback() {
        try {
            connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val builder = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    AppLog.d(TAG, "Network available, triggering weather check...")
                    serviceScope.launch {
                        checkAndUpdateWeatherMetadata()
                    }
                }
            }
            connectivityManager?.registerNetworkCallback(builder.build(), networkCallback!!)
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            if (connectivityManager != null && networkCallback != null) {
                connectivityManager?.unregisterNetworkCallback(networkCallback!!)
            }
        } catch (_: Exception) {}
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
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    VOICE_SEARCH_ACTIONS
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
        runningService = WeakReference(this)
        applySessionActivity()

        settingsPreferences.registerOnSharedPreferenceChangeListener(settingsListener)
        registerNetworkCallback()
        carConnectionType = CarConnection(this).type.also { it.observeForever(carConnectionObserver) }
        checkAndUpdateWeatherMetadata()
        updateNotification()
    }

    private fun isWeatherEnabled(): Boolean = settingsPreferences.getBoolean("weather_widget_enabled", false)

    // Player col meteo: in pausa (Android Auto mostra la scheda senza far partire audio), sempre col solo ▶ che lo
    // aggiorna. ⏮ ⏭ non servono: i tasti al volante non arrivano alla scheda mostrata ma all'ultima app che ha
    // riprodotto audio, e il meteo apposta non ne riproduce (toglierebbe l'audio alla radio). Quando AABrowser ha
    // suonato per ultimo arrivano qui anche col meteo, e il doppio tocco su ⏮ apre il microfono lo stesso
    private fun weatherPlaybackState(): PlaybackStateCompat = PlaybackStateCompat.Builder()
        .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1.0f)
        .setActions(WEATHER_ACTIONS)
        .build()

    fun checkAndUpdateWeatherMetadata() {
        val prefs = getSharedPreferences("aa_browser_settings", MODE_PRIVATE)
        val weatherEnabled = prefs.getBoolean("weather_widget_enabled", false)
        val currentState = mMediacontrollercompat?.playbackState?.state ?: PlaybackStateCompat.STATE_NONE
        val inCar = isInCar()
        val isWeatherMode = weatherEnabled && inCar && (showingWeather || currentState == PlaybackStateCompat.STATE_NONE) && !isUserPaused

        AppLog.d(TAG, "checkAndUpdateWeatherMetadata: weatherEnabled=$weatherEnabled, inCar=$inCar, showingWeather=$showingWeather, isUserPaused=$isUserPaused, isWeatherMode=$isWeatherMode")

        if (isWeatherMode) {
            // Il player ora mostra il meteo: il caricamento qui sotto lo mette in pausa (non più "nessuno"), e il meteo
            // arrivato si mostra solo se showingWeather è vero
            showingWeather = true
            // Mostra subito uno stato di caricamento per dare feedback all'utente
            val loadingTitle = getString(R.string.weather_loading)
            val loadingMetadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, loadingTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "AABrowser")
                .build()
            setSessionMetadata(loadingMetadata)
            // Già coi tasti mentre carica: se la posizione tarda il player restava senza ⏮ (niente doppio tocco)
            publishPlaybackState(weatherPlaybackState())

            weatherCheckJob?.cancel()
            weatherCheckJob = serviceScope.launch {
                var weather: com.fcaronte.aabrowser.utils.WeatherData? = null
                val delays = listOf(0L, 3000L, 6000L, 10000L, 15000L, 20000L)
                for (delayTime in delays) {
                    if (delayTime > 0) {
                        delay(delayTime.milliseconds)
                    }
                    AppLog.d(TAG, "Starting background weather fetch for CarMediaService (attempt delay ${delayTime}ms)...")
                    weather = fetchWeather(applicationContext)
                    if (weather != null) break
                }

                if (weather != null && mMediasessioncompat != null) {
                    val currentPlayState = mMediacontrollercompat?.playbackState?.state ?: PlaybackStateCompat.STATE_NONE
                    val stillWeatherNode = (showingWeather || currentPlayState == PlaybackStateCompat.STATE_NONE) &&
                            !isUserPaused && isInCar() && isWeatherEnabled()

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
                        setSessionMetadata(metadata)

                        publishPlaybackState(weatherPlaybackState())

                        updateNotification()
                        AppLog.d(TAG, "CarMediaService metadata & state updated with weather: $titleStr at $artistStr (STATE_PAUSED)")
                    }
                }
            }
        } else if ((showingWeather || currentState == PlaybackStateCompat.STATE_NONE) && !isPlayingState(currentState)) {
            // Meteo spento o fuori dall'auto: player vuoto e niente notifica. Prima la notifica restava (staccata
            // dal servizio ma visibile)
            weatherCheckJob?.cancel()
            clearPlayer()
            removeNotification()
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

    // Avviato con startForegroundService (Main in auto e servizio in primo piano): Android vuole startForeground anche
    // quando il player non ha niente da mostrare, altrimenti chiude l'app. In quel caso la notifica sparisce subito
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (playerNotification() == null) {
            mCarmedianotificationmanager?.getPlaceholderNotification()?.let { startForegroundWith(it) }
        }
        updateNotification()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val currentState = mMediacontrollercompat?.playbackState?.state ?: PlaybackStateCompat.STATE_NONE
        if (currentState != PlaybackStateCompat.STATE_PLAYING) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (runningService?.get() === this) runningService = null
        settingsPreferences.unregisterOnSharedPreferenceChangeListener(settingsListener)
        unregisterNetworkCallback()
        carConnectionType?.removeObserver(carConnectionObserver)
        weatherCheckJob?.cancel()
        abandonAudioFocus()
        mCarmedianotificationmanager?.cancel()
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
    ): BrowserRoot? {
        AppLog.d(TAG, "onGetRoot: client=$clientPackageName")

        // Riproduzione recente: il telefono la chiede per mostrare un player "da riprendere" anche fuori dall'auto.
        // Non c'è (una pagina web non riparte da lì) e la richiesta non deve toccare player e notifica
        if (rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT) == true && clientPackageName != ANDROID_AUTO_PACKAGE) {
            AppLog.d(TAG, "onGetRoot: recent media not supported")
            return null
        }

        val prefs = getSharedPreferences("aa_browser_settings", MODE_PRIVATE)
        val mediaPlayerEnabled = prefs.getBoolean("media_player_enabled", true)
        
        if (!mediaPlayerEnabled) {
            mMediasessioncompat?.isActive = false
            return BrowserRoot("root", null)
        }
        
        mMediasessioncompat?.isActive = true
        checkAndUpdateWeatherMetadata()
        updateNotification()
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
        
        mMediasessioncompat?.isActive = true
        // Una volta sola: ogni chiamata annulla il recupero del meteo già partito
        checkAndUpdateWeatherMetadata()
        updateNotification()

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
            extras.classLoader = MediaMetadataCompat::class.java.classLoader
            var update = false
            var cancel = false
            if (action == PLAYBACK_STATE_COMPAT) {
                val playbackStateCompat =
                    extras.getParcelable<PlaybackStateCompat?>("PlaybackStateCompat")
                val title = extras.getString("title")
                val artist = extras.getString("artist")
                AppLog.d(TAG, "onCustomAction PLAYBACK_STATE_COMPAT: state=${playbackStateCompat?.state}, title='$title', artist='$artist'")
                if (playbackStateCompat != null) {
                    if (playbackStateCompat.state != notifiedState || playbackStateCompat.actions != notifiedActions) {
                        notifiedState = playbackStateCompat.state
                        notifiedActions = playbackStateCompat.actions
                        update = true
                    }
                    cancel = (playbackStateCompat.state == PlaybackStateCompat.STATE_NONE)

                    publishPlaybackState(playbackStateCompat)

                    if (!title.isNullOrBlank()) {
                        showingWeather = false
                        val currentMeta = mMediasessioncompat!!.controller.metadata
                        val currentTitle = currentMeta?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
                        
                        AppLog.d(TAG, "Syncing metadata from playback state bundle: $title, current: $currentTitle")
                        
                        if (currentTitle != title) {
                            val metadata = MediaMetadataCompat.Builder()
                                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist ?: "")
                                .build()
                            setSessionMetadata(metadata)
                            update = true
                        }
                    } else if (playbackStateCompat.state == PlaybackStateCompat.STATE_NONE ||
                        playbackStateCompat.state == PlaybackStateCompat.STATE_STOPPED) {
                        AppLog.d(TAG, "Playback state is NONE/STOPPED, restoring weather")
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
                    AppLog.d(TAG, "onCustomAction MEDIA_METADATA_COMPAT: title='$title'")
                    if (title.isNullOrBlank()) {
                        showingWeather = true
                        isUserPaused = false
                        checkAndUpdateWeatherMetadata()
                    } else {
                        showingWeather = false
                        setSessionMetadata(mediaMetadataCompat)
                        // Niente requestAudioFocus(): il focus audio lo prende già la WebView quando suona
                        // e toglierglielo la mette subito in pausa (stesso fix di "fix spotify auto pause")
                        update = true
                    }
                }
            }
            // Senza più niente da mostrare updateNotification toglie la notifica: cancel() da solo non toglie
            // quella del servizio in primo piano
            if (cancel || update) updateNotification()
        }
        result.sendResult(null)
    }

    // Ogni stato pubblicato dichiara anche la ricerca vocale: senza, l'Assistente (e Gemini) non manda
    // "metti … su AABrowser" all'app
    private fun publishPlaybackState(state: PlaybackStateCompat) {
        val withVoiceSearch = PlaybackStateCompat.Builder(state)
            .setActions(state.actions or VOICE_SEARCH_ACTIONS)
            .build()
        mMediasessioncompat?.setPlaybackState(withVoiceSearch)
    }

    // Microfono dei comandi vocali in ascolto (anche col doppio tocco su ⏮ e l'app dietro): in auto il player mostra
    // "🎤 In ascolto…" e sotto quello che si sta capendo. I metadati che arrivano intanto (pagine, meteo) si tengono da
    // parte e tornano alla fine
    private var voiceListening = false
    private var metadataUnderVoice: MediaMetadataCompat? = null

    /** Metadati del player, tranne mentre si ascolta un comando vocale: allora restano da parte fino alla fine. */
    private fun setSessionMetadata(metadata: MediaMetadataCompat) {
        if (voiceListening) metadataUnderVoice = metadata else mMediasessioncompat?.setMetadata(metadata)
    }

    // Toglie "In ascolto…" e rimette i metadati tenuti da parte. false = non era in ascolto
    private fun endVoiceOverlay(): Boolean {
        if (!voiceListening) return false
        voiceListening = false
        mMediasessioncompat?.setMetadata(metadataUnderVoice ?: MediaMetadataCompat.Builder().build())
        metadataUnderVoice = null
        return true
    }

    private fun applyVoiceListening(listening: Boolean, text: String?) {
        if (!listening) {
            if (endVoiceOverlay()) updateNotification()
            return
        }
        // Solo in auto: sul telefono l'ascolto si vede già nell'app
        if (!voiceListening && !isInCar()) return
        if (!voiceListening) {
            metadataUnderVoice = mMediacontrollercompat?.metadata
            voiceListening = true
        }
        val title = "🎤 " + getString(R.string.voice_listening)
        val subtitle = text?.takeIf { it.isNotBlank() } ?: getString(R.string.voice_command_button)
        mMediasessioncompat?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
                .build()
        )
        updateNotification()
    }

    /** Comando vocale senza nessuna schermata aperta: Android Auto mostra l'avviso, lo stato resta com'è. */
    private fun showVoiceCommandNeedsApp() {
        val current = mMediacontrollercompat?.playbackState
        val builder = current?.let { PlaybackStateCompat.Builder(it) }
            ?: PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_NONE, 0, 1.0f)
        publishPlaybackState(
            builder.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, getString(R.string.voice_command_open_app))
                .build()
        )
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
            // Scheda del meteo, anche mentre carica o mentre il player mostra "In ascolto…": ▶ aggiorna il meteo.
            // Fuori dall'auto il meteo non c'è: ▶ (es. cuffie) va alla pagina
            val weatherCard = service.isWeatherEnabled() && service.isInCar() &&
                    (service.showingWeather || currentTitle.contains("°C") ||
                            currentTitle == service.getString(R.string.weather_updating))
            if (weatherCard) {
                AppLog.d(TAG, "Play pressed in weather mode -> showing updating message and refreshing weather")
                
                service.showingWeather = true
                service.isUserPaused = false

                val updatingTitle = service.getString(R.string.weather_updating)
                val artistStr = service.mMediacontrollercompat?.metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: service.getString(R.string.weather_generic)
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
                service.setSessionMetadata(updatingMetadata)
                service.updateNotification()

                // Nel lavoro del meteo: scollegando l'auto si annulla anche questo
                service.weatherCheckJob?.cancel()
                service.weatherCheckJob = service.serviceScope.launch {
                    val weather = fetchWeather(service.applicationContext)
                    if (weather != null && service.mMediasessioncompat != null && service.isInCar()) {
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
                        service.setSessionMetadata(metadata)

                        service.publishPlaybackState(service.weatherPlaybackState())
                        service.updateNotification()
                        AppLog.d(TAG, "Weather refreshed successfully via Play button: $titleStr")
                    }
                }
                return
            }

            service.showingWeather = false
            service.isUserPaused = false
            // Il focus audio lo chiede la WebView quando riparte (vedi onCustomAction)
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
            AppLog.d(TAG, "Transport: skip to previous")
            service.broadcastPlaybackAction(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
        }

        override fun onSkipToNext() {
            AppLog.d(TAG, "Transport: skip to next")
            service.broadcastPlaybackAction(PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
        }

        // Doppio tocco su ⏮ (anche al volante) = microfono dei comandi vocali. Il primo tocco aspetta il secondo per
        // DOUBLE_TAP_MS: prima andava subito al precedente (indietro di 10 s o il video prima) anche col doppio tocco
        private val previousHandler = Handler(Looper.getMainLooper())
        private var previousPending = false
        private val delayedPrevious = Runnable {
            previousPending = false
            onSkipToPrevious()
        }

        // Tasti multimediali (anche quelli al volante), col log di cosa manda l'auto
        override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
            val event = mediaButtonEvent?.let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) }
            if (event != null) {
                AppLog.d(
                    TAG,
                    "Media button: ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action} " +
                            "repeat=${event.repeatCount} held=${event.eventTime - event.downTime} ms long=${event.isLongPress}"
                )
                if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onPreviousKey()
                    return true
                }
            }
            return super.onMediaButtonEvent(mediaButtonEvent)
        }

        // Il microfono va alla schermata aperta (Wide o Main) come per l'Assistente, anche se non suona niente o il
        // player è di un'altra: prima arrivava solo a chi aveva il player, e la Main non lo gestiva
        private fun onPreviousKey() {
            if (!previousPending) {
                previousPending = true
                previousHandler.postDelayed(delayedPrevious, DOUBLE_TAP_MS)
                return
            }
            previousPending = false
            previousHandler.removeCallbacks(delayedPrevious)
            AppLog.d(TAG, "Media button: double tap on previous -> voice command")
            if (!VoiceCommandRouter.startListening()) service.showVoiceCommandNeedsApp()
        }

        override fun onFastForward() {
            AppLog.d(TAG, "Transport: fast forward")
        }

        override fun onRewind() {
            AppLog.d(TAG, "Transport: rewind")
        }

        // "Ok Google, metti … su AABrowser" (Assistente o Gemini): va alla Wide o alla Main aperta, che cerca
        // su YouTube e fa partire il primo risultato. Richiesta vuota ("metti la musica su AABrowser") = riprendi
        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            AppLog.d(TAG, "onPlayFromSearch (empty=${query.isNullOrBlank()})")
            if (!VoiceCommandRouter.fromAssistant(query)) service.showVoiceCommandNeedsApp()
        }

        override fun onSeekTo(pos: Long) {
            val bundle = Bundle().apply {
                putLong(PLAYBACK_ACTION, PlaybackStateCompat.ACTION_SEEK_TO)
                putLong("SeekPosition", pos)
            }
            service.mMediasessioncompat?.sendSessionEvent(PLAYBACK_ACTION, bundle)
        }
    }

    // Notifica del player; null se non c'è niente da mostrare (nessuna pagina col player e niente meteo)
    private fun playerNotification(): Notification? {
        val state = mMediacontrollercompat?.playbackState?.state ?: PlaybackStateCompat.STATE_NONE
        if (state == PlaybackStateCompat.STATE_NONE || state == PlaybackStateCompat.STATE_STOPPED) return null
        if (mCarmedianotificationmanager == null || mMediacontrollercompat == null) return null
        return mCarmedianotificationmanager!!.getNotification(
            mMediacontrollercompat!!.metadata,
            mMediacontrollercompat?.playbackState,
            sessionToken
        )
    }

    private fun updateNotification() {
        val notification = playerNotification()
        val currentTitle = mMediacontrollercompat?.metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
        val currentState = mMediacontrollercompat?.playbackState?.state
        AppLog.d(TAG, "updateNotification: state=$currentState, title='$currentTitle'")
        if (notification == null) {
            removeNotification()
            return
        }
        startForegroundWith(notification)
        mCarmedianotificationmanager?.notify(notification)
    }

    private fun startForegroundWith(notification: Notification) {
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: Exception) {
            AppLog.e(TAG, "Error in startForeground: ${e.message}", e)
        }
    }

    // La notifica del servizio in primo piano si toglie con stopForeground: cancel() da solo la lascia
    private fun removeNotification() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        mCarmedianotificationmanager?.cancel()
    }

    fun stateChanged(playbackStateCompat: PlaybackStateCompat): Boolean {
        if (mMediacontrollercompat?.playbackState == null) return false
        return mMediacontrollercompat!!.playbackState.state != playbackStateCompat.state
    }

    // Activity della sessione: con la Wide non in uso un'activity vuota, così il tocco sulla scheda del player
    // di Android Auto non apre la Wide (che è un navigatore e chiuderebbe la navigazione in corso). Con la Wide
    // in uso nessuna: Android Auto la riporta in primo piano come sempre
    private val noOpSessionActivity: PendingIntent by lazy {
        val intent = Intent(this, NoOpActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        PendingIntent.getActivity(this, REQUEST_NO_OP_ACTIVITY, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun applySessionActivity() {
        mMediasessioncompat?.setSessionActivity(if (wideActive) null else noOpSessionActivity)
        AppLog.d(TAG, "Session activity: ${if (wideActive) "none (Wide in use)" else "no-op"}")
    }

    companion object {
        private const val TAG = "CarMediaService"
        private const val PLAYBACK_STATE_COMPAT = "PlaybackStateCompat"
        private const val MEDIA_METADATA_COMPAT = "MediaMetadataCompat"
        private const val PLAYBACK_ACTION = "PlaybackAction"
        private const val REQUEST_NO_OP_ACTIVITY = 2001
        // Stesso id di CarMediaNotificationManager
        private const val NOTIFICATION_ID = 600
        private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
        private const val VOICE_SEARCH_ACTIONS = PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
        // Due tocchi su ⏮ entro questo tempo = comando vocale (il primo da solo va al precedente dopo l'attesa)
        private const val DOUBLE_TAP_MS = 400L
        private const val WEATHER_ACTIONS = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE

        @Volatile
        private var wideActive = false
        private var runningService: WeakReference<CarMediaService>? = null

        /** La Wide è in primo piano (true) o non in uso (false): vedi [noOpSessionActivity]. Dal thread principale. */
        fun setWideActive(active: Boolean) {
            if (wideActive == active) return
            wideActive = active
            runningService?.get()?.applySessionActivity()
        }

        /** Microfono dei comandi vocali (Main e Wide): in ascolto, con [text] capito finora, oppure finito. */
        fun showVoiceListening(listening: Boolean, text: String? = null) {
            runningService?.get()?.applyVoiceListening(listening, text)
        }

        /** Comandi vocali "pausa", "riprendi", "prossimo", "precedente": come i tasti del player di Android Auto. */
        fun sendPlayerCommand(action: VoiceCommands.PlayerAction) {
            val controls = runningService?.get()?.mMediacontrollercompat?.transportControls ?: return
            when (action) {
                VoiceCommands.PlayerAction.PLAY -> controls.play()
                VoiceCommands.PlayerAction.PAUSE -> controls.pause()
                VoiceCommands.PlayerAction.NEXT -> controls.skipToNext()
                VoiceCommands.PlayerAction.PREVIOUS -> controls.skipToPrevious()
            }
        }

        /** Prove (build di debug): la stessa chiamata che fa l'Assistente per "metti [query] su AABrowser". */
        fun simulateVoiceSearch(query: String): Boolean {
            val controls = runningService?.get()?.mMediacontrollercompat?.transportControls ?: return false
            controls.playFromSearch(query, Bundle())
            return true
        }

        fun startServiceIfEnabled(context: Context) {
            val prefs = context.getSharedPreferences("aa_browser_settings", Context.MODE_PRIVATE)
            val mediaPlayerEnabled = prefs.getBoolean("media_player_enabled", true)
            if (mediaPlayerEnabled) {
                try {
                    val intent = Intent(context, CarMediaService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    AppLog.d(TAG, "CarMediaService requested to start.")
                } catch (e: Exception) {
                    AppLog.e(TAG, "Failed to start CarMediaService: ${e.message}")
                }
            }
        }
    }
}
