package com.fcaronte.aabrowser

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import com.fcaronte.aabrowser.mediaservice.CarMediaService
import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.apps.auto.sdk.CarActivityService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

open class CarService : CarActivityService() {
    private val mediaReady = CountDownLatch(1)

    @Volatile
    private var mediaBinder: IBinder? = null
    private var mediaBound = false

    private val mediaConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            mediaBinder = service
            mediaReady.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mediaBinder = null
        }
    }

    // Stesso processo: la chiamata resta nello stesso thread e il servizio vede ancora Android Auto come chiamante
    private val mediaForward = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            mediaReady.await(MEDIA_WAIT_MS, TimeUnit.MILLISECONDS)
            val target = mediaBinder
            if (target == null) {
                Log.w(TAG, "Media service not available")
                return false
            }
            return target.transact(code, data, reply, flags)
        }
    }

    override fun getCarActivity(): Class<out CarActivity?> {
        return MainCarActivity::class.java
    }

    // Con l'opzione "Il tocco sul player apre CarWeb" la sessione del player dichiara questo componente (vedi
    // CarMediaService) e Android Auto ci si collega anche come MediaBrowser: se non gli risponde nessuno la scheda del
    // player resta vuota (log del 07/11/2026). Queste richieste passano al servizio vero
    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != MediaBrowserServiceCompat.SERVICE_INTERFACE) return super.onBind(intent)
        if (!mediaBound) {
            Log.d(TAG, "Android Auto connects its player here: forwarding to the media service")
            val media = Intent(MediaBrowserServiceCompat.SERVICE_INTERFACE).setComponent(ComponentName(this, CarMediaService::class.java))
            mediaBound = bindService(media, mediaConnection, BIND_AUTO_CREATE)
        }
        return mediaForward
    }

    override fun onUnbind(intent: Intent?): Boolean =
        if (intent?.action == MediaBrowserServiceCompat.SERVICE_INTERFACE) false else super.onUnbind(intent)

    override fun onDestroy() {
        if (mediaBound) unbindService(mediaConnection)
        mediaBound = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CarService"
        private const val MEDIA_WAIT_MS = 5_000L
    }
}

/**
 * La stessa Main senza icona nel menu di Android Auto (solo CATEGORY_PROJECTION: l'icona viene da
 * CATEGORY_PROJECTION_OEM). Attiva con "CarWeb tra le app media di Android Auto", quando nel menu al suo posto c'è
 * l'app media che la apre (vedi CarMediaApp).
 */
class CarServiceUnlisted : CarService()
