package com.fcaronte.aabrowser.smartphone

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import rikka.shizuku.Shizuku
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Lato app del servizio di Shizuku ([VirtualDisplayService]): un'unica connessione per tutta l'app (Main sul telefono,
 * Main in auto, Wide), che resta agganciata finché vive il processo (Shizuku ferma il servizio quando muore).
 *
 * Tutte le chiamate partono da un solo thread, in ordine: i tocchi arrivano al display nell'ordine in cui sono stati
 * fatti. Non si aspetta mai sul thread dell'interfaccia: i risultati tornano sul thread principale.
 */
object ShizukuAppBridge {
    private const val TAG = "ShizukuAppBridge"

    // Da alzare a ogni modifica di VirtualDisplayService: Shizuku altrimenti riusa il processo di prima con il codice vecchio
    private const val SERVICE_VERSION = 3
    private const val CONNECT_TIMEOUT_SECONDS = 20L

    private val thread = HandlerThread("carweb-virtual-apps").apply { start() }
    private val io = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile
    private var remote: IVirtualDisplayService? = null
    private var connection: ServiceConnection? = null
    private var connectionFuture: CompletableFuture<IVirtualDisplayService>? = null

    /** true quando il servizio è collegato: i tocchi servono solo allora. */
    val isConnected: Boolean get() = remote?.asBinder()?.isBinderAlive == true

    fun createDisplay(
        context: Context, surface: Surface?, width: Int, height: Int, dpi: Int, keyboardOnDisplay: Boolean, secure: Boolean,
        onResult: (displayId: Int, message: String) -> Unit
    ) {
        run(context.applicationContext, onFailure = { main.post { onResult(-1, it) } }) { service ->
            val reply = service.createDisplay(surface, width, height, dpi, keyboardOnDisplay, secure)
            // "OK:<id>;<dettagli>": i dettagli (flag, orientamento, tastiera) vanno nel registro, che si condivide dall'app
            val id = if (reply.startsWith("OK:")) reply.removePrefix("OK:").substringBefore(';').toIntOrNull() ?: -1 else -1
            Log.i(TAG, "createDisplay ${width}x$height@$dpi secure=$secure -> ${reply.take(200)}")
            main.post { onResult(id, if (id > 0) "" else reply.removePrefix("ERROR:")) }
        }
    }

    fun updateDisplay(
        context: Context, displayId: Int, surface: Surface?, width: Int, height: Int, dpi: Int,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        run(context.applicationContext, onFailure = { message -> onResult?.let { main.post { it(false, message) } } }) { service ->
            val reply = service.updateDisplay(displayId, surface, width, height, dpi)
            Log.i(TAG, "updateDisplay display=$displayId surface=${surface != null} ${width}x$height -> $reply")
            onResult?.let { callback -> main.post { callback(reply.startsWith("OK"), reply.removePrefix("ERROR:")) } }
        }
    }

    fun releaseDisplay(context: Context, displayId: Int) {
        if (displayId <= 0) return
        run(context.applicationContext, onFailure = {}) { service ->
            val reply = service.releaseDisplay(displayId)
            if (!reply.startsWith("OK")) Log.w(TAG, "releaseDisplay: $reply")
        }
    }

    /** Avvia l'app sul display (o ci porta il suo task). Con [displayId] 0 sullo schermo del telefono. */
    fun launch(context: Context, displayId: Int, component: ComponentName, onResult: ((Boolean, String) -> Unit)? = null) {
        run(context.applicationContext, onFailure = { message -> onResult?.let { main.post { it(false, message) } } }) { service ->
            val reply = service.launch(displayId, component)
            Log.i(TAG, "launch display=$displayId -> ${reply.take(160)}")
            onResult?.let { callback -> main.post { callback(reply.startsWith("OK"), reply.removePrefix("ERROR:")) } }
        }
    }

    /** Un tocco sul display. L'evento si copia: chi chiama può riusare o riciclare il suo. */
    fun touch(displayId: Int, event: MotionEvent) {
        if (displayId <= 0) return
        val service = remote ?: return
        val copy = MotionEvent.obtain(event)
        copy.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        io.post {
            try {
                service.touch(displayId, copy)
            } catch (error: Throwable) {
                Log.w(TAG, "touch failed: ${error.javaClass.simpleName}")
                lost(service)
            } finally {
                copy.recycle()
            }
        }
    }

    fun key(displayId: Int, keyCode: Int) {
        if (displayId <= 0) return
        val service = remote ?: return
        io.post {
            try {
                service.key(displayId, keyCode)
            } catch (error: Throwable) {
                Log.w(TAG, "key failed: ${error.javaClass.simpleName}")
                lost(service)
            }
        }
    }

    fun closeApp(context: Context, displayId: Int, onResult: ((String) -> Unit)? = null) {
        run(context.applicationContext, onFailure = { message -> onResult?.let { main.post { it("ERROR:$message") } } }) { service ->
            val reply = service.closeApp(displayId)
            onResult?.let { callback -> main.post { callback(reply) } }
        }
    }

    /** "shown", "hidden", "no field", "old android" o "error: ..." (vedi il servizio). */
    fun toggleKeyboard(context: Context, displayId: Int, onResult: (String) -> Unit) {
        run(context.applicationContext, onFailure = { message -> main.post { onResult("error: $message") } }) { service ->
            val reply = service.toggleKeyboard(displayId)
            main.post { onResult(reply) }
        }
    }

    /** 1 = il display divide il gruppo del telefono, 0 = ne ha uno suo, -1 = non si sa. */
    fun phoneGroup(context: Context, displayId: Int, onResult: (Int) -> Unit) {
        run(context.applicationContext, onFailure = { main.post { onResult(-1) } }) { service ->
            val group = service.phoneGroup(displayId)
            main.post { onResult(group) }
        }
    }

    /** Tiene sveglio il telefono (e lo accende) con un blocco della shell; false lo lascia. */
    fun keepPhoneAwake(context: Context, on: Boolean) {
        run(context.applicationContext, onFailure = {}) { service -> service.keepPhoneAwake(on) }
    }

    /**
     * Avvia un'attività di CarWeb sullo schermo del telefono dalla shell: dallo sfondo l'app non può (limiti degli avvii),
     * ed è così che la schermata di attesa compare sul telefono bloccato.
     */
    fun startOnPhone(context: Context, component: ComponentName) = launch(context, 0, component)

    // ------------------------------------------------------------------ connessione

    private fun run(
        context: Context, onFailure: (String) -> Unit, operation: (IVirtualDisplayService) -> Unit
    ) {
        io.post {
            val service = try {
                obtain(context)
            } catch (error: Throwable) {
                Log.w(TAG, "Shizuku service unavailable: ${error.javaClass.simpleName}: ${error.message}")
                onFailure(error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
                return@post
            }
            try {
                operation(service)
            } catch (error: Throwable) {
                Log.w(TAG, "Shizuku call failed", error)
                lost(service)
                onFailure("${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            }
        }
    }

    private fun lost(service: IVirtualDisplayService) {
        synchronized(lock) {
            if (remote === service) {
                remote = null
                connectionFuture = null
            }
        }
    }

    private fun obtain(context: Context): IVirtualDisplayService {
        if (!ShizukuManager.isShizukuRunning()) throw IllegalStateException("Shizuku non è in esecuzione")
        if (!ShizukuManager.hasPermission()) throw SecurityException("Permesso Shizuku non concesso")

        val future: CompletableFuture<IVirtualDisplayService> = synchronized(lock) {
            remote?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
            remote = null
            connectionFuture ?: CompletableFuture<IVirtualDisplayService>().also { pending ->
                connectionFuture = pending
                pending.whenComplete { _, error ->
                    if (error != null) synchronized(lock) {
                        if (connectionFuture === pending) connectionFuture = null
                        remote = null
                    }
                }
                val args = Shizuku.UserServiceArgs(ComponentName(context, VirtualDisplayService::class.java))
                    .processNameSuffix("virtual_display")
                    .daemon(false)
                    .debuggable(false)
                    .version(SERVICE_VERSION)
                val serviceConnection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        val service = binder?.takeIf { it.pingBinder() }?.let(IVirtualDisplayService.Stub::asInterface)
                        if (service == null) {
                            pending.completeExceptionally(IllegalStateException("Shizuku ha dato un servizio non valido"))
                        } else {
                            synchronized(lock) { remote = service }
                            Log.i(TAG, "display service connected")
                            pending.complete(service)
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        Log.i(TAG, "display service disconnected")
                        synchronized(lock) {
                            remote = null
                            connectionFuture = null
                        }
                    }
                }
                connection = serviceConnection
                try {
                    Shizuku.bindUserService(args, serviceConnection)
                } catch (error: Throwable) {
                    pending.completeExceptionally(error)
                }
            }
        }
        return future.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }
}
