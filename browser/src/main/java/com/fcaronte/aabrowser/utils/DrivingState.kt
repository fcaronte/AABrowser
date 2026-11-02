package com.fcaronte.aabrowser.utils

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.support.car.Car
import android.support.car.CarConnectionCallback
import android.support.car.hardware.CarSensorEvent
import android.support.car.hardware.CarSensorManager
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.fcaronte.aabrowser.settings.AppSettings
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Auto ferma o in movimento, come la vede Android Auto: lo stato di guida (sensore DRIVING_STATUS dell'SDK di
 * Android Auto) è quello con cui blocca anche la sua tastiera mentre si guida. L'app non fa controlli suoi (GPS,
 * velocità). null = non comunicato (ancora): nessun blocco, come se l'auto fosse ferma.
 *
 * Una connessione per tutta l'app, aperta finché la Main o la Wide sono sullo schermo dell'auto ([acquire] /
 * [release]). La Main passa anche lo stato che riceve dal suo host ([onStatus]): se su quella versione di Android
 * Auto una delle due strade non funziona, basta l'altra. Log senza dati personali, anche nelle build di rilascio.
 *
 * La connessione dell'SDK risponde su un thread suo: un errore dentro l'SDK (che nei suoi callback non lo gestisce)
 * ferma solo quel thread e lascia lo stato sconosciuto, invece di chiudere l'app in auto.
 */
object DrivingState {
    private const val TAG = "DrivingState"

    private val _moving = mutableStateOf<Boolean?>(null)
    val moving: State<Boolean?> = _moving

    /** Sullo schermo dell'auto, in movimento e senza la Modalità passeggero: l'app resta coperta. */
    val isLocked: Boolean
        get() = _moving.value == true && !AppSettings.passengerMode.value

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var users = 0
    private var carThread: HandlerThread? = null
    private var carHandler: Handler? = null

    @Volatile
    private var car: Car? = null

    @Volatile
    private var sensors: CarSensorManager? = null

    // Lo stato arriva anche dalla connessione dell'app: se no, chiusa la Main, quello del suo host non si aggiorna più
    @Volatile
    private var carStatusSeen = false

    private val sensorListener = CarSensorManager.OnSensorChangedListener { _, event ->
        event?.let(::onEvent)
    }

    private val connectionCallback = object : CarConnectionCallback() {
        override fun onConnected(connectedCar: Car) {
            try {
                val manager = connectedCar.getCarManager(CarSensorManager::class.java) ?: return
                sensors = manager
                manager.getLatestSensorEvent(CarSensorManager.SENSOR_TYPE_DRIVING_STATUS)?.let(::onEvent)
                val listening = manager.addListener(
                    sensorListener,
                    CarSensorManager.SENSOR_TYPE_DRIVING_STATUS,
                    CarSensorManager.SENSOR_RATE_NORMAL
                )
                Log.d(TAG, "Car connected, driving status listener=$listening")
            } catch (e: Exception) {
                Log.w(TAG, "Driving status not available: ${e.javaClass.simpleName} ${e.message}")
            }
        }

        override fun onDisconnected(disconnectedCar: Car) {
            sensors = null
            carStatusSeen = false
            handler.post { update(null, "car disconnected") }
        }
    }

    /** Main o Wide sullo schermo dell'auto (thread principale): apre la connessione con Android Auto. */
    fun acquire(context: Context) {
        users++
        if (car != null) return
        try {
            car = Car.createCar(context.applicationContext, connectionCallback, carHandler())?.also { it.connect() }
            if (car == null) Log.w(TAG, "Driving status not available: no car connection")
        } catch (e: Exception) {
            car = null
            Log.w(TAG, "Driving status not available: ${e.javaClass.simpleName} ${e.message}")
        } catch (e: LinkageError) {
            // Android Auto senza le classi che l'SDK carica da lui
            car = null
            Log.w(TAG, "Driving status not available: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /** Main o Wide chiusa: con l'ultima si chiude la connessione e lo stato torna sconosciuto. */
    fun release() {
        if (users > 0) users--
        if (users > 0) return
        val oldCar = car
        val oldSensors = sensors
        car = null
        sensors = null
        carStatusSeen = false
        // Sul thread della connessione, come i suoi callback
        carHandler?.post {
            try {
                oldSensors?.removeListener(sensorListener)
            } catch (_: Exception) {
            }
            try {
                oldCar?.disconnect()
            } catch (_: Exception) {
            }
        }
        update(null, "released")
    }

    // Thread della connessione; se un errore dell'SDK l'ha fermato se ne apre un altro alla prossima Main o Wide
    private fun carHandler(): Handler {
        carThread?.takeIf { it.isAlive }?.let { return carHandler ?: Handler(it.looper) }
        val thread = HandlerThread("DrivingState").apply {
            setUncaughtExceptionHandler { _, e ->
                Log.w(TAG, "Driving status connection failed: ${e.javaClass.simpleName} ${e.message}")
                handler.post {
                    car = null
                    sensors = null
                    update(null, "connection failed")
                }
            }
            start()
        }
        carThread = thread
        return Handler(thread.looper).also { carHandler = it }
    }

    /** Stato di Android Auto: 0 = nessuna restrizione (ferma), altre = restrizioni di guida (in movimento). */
    fun onStatus(status: Int, source: String) {
        handler.post { update(status != CarSensorEvent.DRIVE_STATUS_UNRESTRICTED, "$source status=$status") }
    }

    /** La Main non manda più lo stato del suo host: senza quello della connessione dell'app diventa sconosciuto. */
    fun onHostStopped() {
        handler.post { if (!carStatusSeen) update(null, "host stopped") }
    }

    /** Modalità passeggero cambiata nelle impostazioni: la Wide ricontrolla la copertura. */
    fun notifyChanged() {
        handler.post { listeners.forEach { it() } }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun onEvent(event: CarSensorEvent) {
        if (event.sensorType != CarSensorManager.SENSOR_TYPE_DRIVING_STATUS) return
        carStatusSeen = true
        onStatus(event.drivingStatusData.status, "car")
    }

    private fun update(moving: Boolean?, reason: String) {
        if (_moving.value == moving) return
        _moving.value = moving
        val state = when (moving) {
            true -> "moving"
            false -> "parked"
            null -> "unknown"
        }
        Log.d(TAG, "Driving state: $state ($reason)")
        listeners.forEach { it() }
    }
}
