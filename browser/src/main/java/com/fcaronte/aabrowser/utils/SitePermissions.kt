package com.fcaronte.aabrowser.utils

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import androidx.compose.runtime.mutableIntStateOf
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.settings.AppSettings

/** Permessi che i siti chiedono e che si concedono sito per sito (Main e Wide). */
enum class SitePermission(val key: String, val titleRes: Int, val messageRes: Int) {
    MICROPHONE("mic", R.string.site_permission_mic_title, R.string.site_permission_mic_message),
    LOCATION("location", R.string.site_permission_location_title, R.string.site_permission_location_message),

    // In auto il selettore non si apre: la pagina riceve gli ultimi file scelti sul telefono
    FILES("files", R.string.site_permission_files_title, R.string.site_permission_files_message),
}

enum class SitePermissionChoice { ALWAYS, ONCE, DENY, DISMISS }

/** Richiesta in attesa della scelta dell'utente: quelle uguali (stesso sito e permesso) aspettano la stessa risposta. */
class PendingSitePermission internal constructor(val origin: String, val permission: SitePermission) {
    val site: String = SitePermissions.displayName(origin)
    internal val callbacks = mutableListOf<(Boolean) -> Unit>()

    internal fun resolve(allowed: Boolean) {
        val pending = callbacks.toList()
        callbacks.clear()
        pending.forEach { it(allowed) }
    }
}

/**
 * Permessi chiesti dalle pagine di una WebView, mostrati uno alla volta come gli avvisi di certificato. Tutto gira
 * sul main thread (callback della WebView e UI). [onChanged] riceve la richiesta da mostrare ora (null = nessuna).
 * "Solo questa volta" vale finché la WebView resta sullo stesso sito. [inCar]: WebView sullo schermo dell'auto.
 */
class SitePermissionPrompts(
    context: Context,
    private val inCar: Boolean,
    private val onChanged: (PendingSitePermission?) -> Unit
) {
    private val appContext = context.applicationContext
    private val queue = mutableListOf<PendingSitePermission>()
    private val grantedOnce = mutableSetOf<String>()
    private var pageOrigin: String? = null
    private val pendingRequests = mutableMapOf<PermissionRequest, () -> Unit>()
    private val pendingGeolocation = mutableListOf<() -> Unit>()

    init {
        SitePermissions.init(appContext)
    }

    val current: PendingSitePermission? get() = queue.firstOrNull()

    /**
     * Il sito chiede un permesso che il telefono non ha dato all'app (o non del tutto, es. posizione approssimativa).
     * Una volta per permesso e pagina. true = Android lo sta chiedendo (telefono): la richiesta del sito aspetta
     * [onPhonePermissionsAnswered]. false = solo un avviso (in auto la finestra di Android non compare).
     */
    var onPhonePermissionMissing: ((SitePermission) -> Boolean)? = null
    private val phonePermissionHints = mutableSetOf<SitePermission>()

    // Richieste dei siti ferme mentre Android chiede il permesso all'app
    private val askingPhone = mutableSetOf<SitePermission>()
    private val waitingForPhone = mutableListOf<WaitingRequest>()

    private inner class WaitingRequest(
        val originUrl: String?,
        val permission: SitePermission,
        val onResult: (Boolean) -> Unit
    ) {
        // Dopo la risposta di Android la richiesta riparte: come rinunciare a quella nuova
        var next: (() -> Unit)? = null

        fun cancel() {
            if (!waitingForPhone.remove(this)) next?.invoke()
        }
    }

    /**
     * Android ha risposto a una richiesta di permesso: le richieste dei siti in attesa ripartono, così il sito chiede
     * subito (prima riceveva un no e andava toccato di nuovo).
     */
    fun onPhonePermissionsAnswered() {
        askingPhone.clear()
        val waiting = waitingForPhone.toList()
        waitingForPhone.clear()
        waiting.forEach { it.next = request(it.originUrl, it.permission, it.onResult) }
    }

    /** DRM sempre (Spotify, Netflix, ...), microfono come scelto per il sito, fotocamera mai (l'app non ha il permesso). */
    fun onPermissionRequest(request: PermissionRequest) {
        val drm = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE !in request.resources) {
            reply(request, drm)
            return
        }
        val cancel = request(request.origin?.toString(), SitePermission.MICROPHONE) { allowed ->
            pendingRequests.remove(request)
            reply(request, if (allowed) drm + PermissionRequest.RESOURCE_AUDIO_CAPTURE else drm)
        }
        if (cancel != null) pendingRequests[request] = cancel
    }

    /** La pagina ha rinunciato alla richiesta: se era in attesa sparisce. */
    fun onPermissionRequestCanceled(request: PermissionRequest) {
        pendingRequests.remove(request)?.invoke()
    }

    fun onGeolocationPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
        callback ?: return
        // Mai ricordata dalla WebView: la scelta la tiene l'app
        val cancel = request(origin, SitePermission.LOCATION) { allowed -> callback.invoke(origin, allowed, false) }
        if (cancel != null) pendingGeolocation.add(cancel)
    }

    fun onGeolocationHidePrompt() {
        val pending = pendingGeolocation.toList()
        pendingGeolocation.clear()
        pending.forEach { it() }
    }

    /**
     * File chiesti da una pagina in auto, dove il selettore non si apre: l'ultima scelta del telefono se il sito ha
     * il permesso. false (la WebView annulla da sola) se sul telefono non è ancora stato scelto nulla.
     */
    fun onCarFileRequest(pageUrl: String?, callback: ValueCallback<Array<Uri>>, mode: Int): Boolean {
        if (!LastFileSelection.hasSelection(appContext, mode)) return false
        // AAB Media è una pagina dell'app: i file del telefono sono il suo lavoro, nessuna domanda
        if (LocalPlayer.isPlayerUrl(pageUrl)) {
            if (!LastFileSelection.deliver(appContext, callback, mode)) callback.onReceiveValue(null)
            return true
        }
        request(pageUrl, SitePermission.FILES) { allowed ->
            if (!allowed || !LastFileSelection.deliver(appContext, callback, mode)) callback.onReceiveValue(null)
        }
        return true
    }

    /** Scelta dell'utente; ignorata se nel frattempo la richiesta è decaduta (nuova pagina). */
    fun answer(prompt: PendingSitePermission, choice: SitePermissionChoice) {
        if (current !== prompt) return
        queue.removeAt(0)
        when (choice) {
            SitePermissionChoice.ALWAYS -> SitePermissions.set(prompt.origin, prompt.permission, true)
            SitePermissionChoice.DENY -> SitePermissions.set(prompt.origin, prompt.permission, false)
            SitePermissionChoice.ONCE -> grantedOnce.add(onceKey(prompt.origin, prompt.permission))
            SitePermissionChoice.DISMISS -> Unit
        }
        prompt.resolve(choice == SitePermissionChoice.ALWAYS || choice == SitePermissionChoice.ONCE)
        onChanged(current)
    }

    /** Nuova pagina nella WebView: le richieste in attesa decadono, "solo questa volta" se cambia sito. */
    fun onPageStarted(url: String?) {
        val origin = SitePermissions.originOf(url)
        if (origin != pageOrigin) {
            grantedOnce.clear()
            pageOrigin = origin
        }
        phonePermissionHints.clear()
        cancelAll()
    }

    /** Pagina cambiata o WebView chiusa: le richieste in attesa ricevono un no. */
    fun cancelAll() {
        val pending = queue.toList()
        queue.clear()
        pending.forEach { it.resolve(false) }
        val waiting = waitingForPhone.toList()
        waitingForPhone.clear()
        askingPhone.clear()
        waiting.forEach { it.onResult(false) }
        pendingRequests.clear()
        pendingGeolocation.clear()
        if (pending.isNotEmpty()) onChanged(null)
    }

    /**
     * [onResult] subito se la scelta c'è già (salvata, "solo questa volta", permessi automatici), altrimenti alla
     * risposta dell'utente. Ritorna come rinunciare all'attesa, o null se la risposta è già arrivata.
     */
    private fun request(originUrl: String?, permission: SitePermission, onResult: (Boolean) -> Unit): (() -> Unit)? {
        val origin = SitePermissions.originOf(originUrl)
        if (origin != null && !SitePermissions.isComplete(appContext, permission, inCar) &&
            phonePermissionHints.add(permission) && onPhonePermissionMissing?.invoke(permission) == true
        ) {
            askingPhone.add(permission)
        }
        if (origin != null && permission in askingPhone) {
            if (permission == SitePermission.LOCATION) logLocation("waiting for the phone permission")
            val waiting = WaitingRequest(originUrl, permission, onResult)
            waitingForPhone.add(waiting)
            return waiting::cancel
        }
        val available = SitePermissions.isAvailable(appContext, permission, inCar)
        val allowed = when {
            origin == null || !available -> false
            else -> SitePermissions.decision(origin, permission)
                ?: if (onceKey(origin, permission) in grantedOnce || AppSettings.autoSitePermissions.value) true else null
        }
        if (permission == SitePermission.LOCATION) logLocation("allowed=$allowed (web=${origin != null})")
        if (allowed != null || origin == null) {
            onResult(allowed == true)
            return null
        }
        val prompt = queue.firstOrNull { it.origin == origin && it.permission == permission }
            ?: PendingSitePermission(origin, permission).also {
                queue.add(it)
                if (queue.size == 1) onChanged(it)
            }
        prompt.callbacks.add(onResult)
        return {
            prompt.callbacks.remove(onResult)
            if (prompt.callbacks.isEmpty()) drop(prompt)
        }
    }

    private fun drop(prompt: PendingSitePermission) {
        val wasCurrent = current === prompt
        if (queue.remove(prompt) && wasCurrent) onChanged(current)
    }

    private fun onceKey(origin: String, permission: SitePermission) = "${permission.key}|$origin"

    // Perché un sito riceve o no la posizione: in auto (telefono bloccato) senza "Consenti sempre" non ne arrivano
    private fun logLocation(state: String) {
        AppLog.d(
            TAG, "Location: $state, phone: precise=${WebLocation.hasPreciseLocation(appContext)}, " +
                    "any=${WebLocation.hasLocation(appContext)}, " +
                    "always=${WebLocation.hasBackgroundLocation(appContext)}, car=$inCar"
        )
    }

    // La richiesta può essere già chiusa dalla WebView (pagina cambiata nel frattempo)
    private fun reply(request: PermissionRequest, granted: List<String>) {
        try {
            if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
        } catch (e: IllegalStateException) {
            AppLog.w(TAG, "Permission request already closed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "SitePermissions"
    }
}

/**
 * Scelte salvate per sito ("Consenti sempre" / "Non consentire"), condivise da Main e Wide e ricordate anche dopo la
 * chiusura dell'app. Si tolgono dalle impostazioni, una alla volta o tutte (Cancella dati).
 */
object SitePermissions {
    private const val PREFS_NAME = "site_permissions"

    // "permesso|origine" -> consentito
    private val decisions = mutableMapOf<String, Boolean>()
    private var prefs: SharedPreferences? = null

    /** Cambia a ogni modifica, così le impostazioni si ridisegnano. */
    val version = mutableIntStateOf(0)

    /** Carica le scelte salvate, una volta per processo (la chiamano sia la Main sia la Wide). */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val stored = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = stored
        stored.all.forEach { (key, value) -> if (value is Boolean) decisions[key] = value }
    }

    /** "https://www.google.com/maps" -> "https://www.google.com"; null per ciò che non è una pagina web. */
    fun originOf(url: String?): String? {
        val uri = url?.let { Uri.parse(it.trim()) } ?: return null
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val defaultPort = if (scheme == "https") 443 else 80
        val port = uri.port.takeIf { it != -1 && it != defaultPort }
        return if (port != null) "$scheme://$host:$port" else "$scheme://$host"
    }

    /** Nome da mostrare: il sito senza "www." (con la porta, se c'è). */
    fun displayName(origin: String): String = origin.substringAfter("://").removePrefix("www.")

    /**
     * Serve anche il permesso dato all'app dal telefono. Posizione: basta l'approssimativa (il sito riceve quella); in
     * auto anche "Consenti sempre", perché per Android l'app è in background e la WebView non riceverebbe posizioni.
     */
    fun isAvailable(context: Context, permission: SitePermission, inCar: Boolean): Boolean = when (permission) {
        SitePermission.MICROPHONE ->
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        SitePermission.LOCATION ->
            WebLocation.hasLocation(context) && (!inCar || WebLocation.hasBackgroundLocation(context))
        SitePermission.FILES -> true
    }

    /** Il telefono ha dato tutto: per la posizione quella precisa (le mappe con l'approssimativa servono a poco). */
    fun isComplete(context: Context, permission: SitePermission, inCar: Boolean): Boolean = when (permission) {
        SitePermission.LOCATION ->
            WebLocation.hasPreciseLocation(context) && (!inCar || WebLocation.hasBackgroundLocation(context))
        else -> isAvailable(context, permission, inCar)
    }

    /** Cambia quando Android risponde a una richiesta di permesso dell'app: le richieste dei siti in attesa ripartono. */
    val phonePermissionAnswers = mutableIntStateOf(0)

    fun onPhonePermissionsAnswered() {
        phonePermissionAnswers.intValue++
    }

    @Synchronized
    fun decision(origin: String, permission: SitePermission): Boolean? = decisions[key(origin, permission)]

    @Synchronized
    fun set(origin: String, permission: SitePermission, allowed: Boolean) {
        val key = key(origin, permission)
        decisions[key] = allowed
        prefs?.edit()?.putBoolean(key, allowed)?.apply()
        version.intValue++
    }

    @Synchronized
    fun remove(origin: String, permission: SitePermission) {
        val key = key(origin, permission)
        if (decisions.remove(key) == null) return
        prefs?.edit()?.remove(key)?.apply()
        version.intValue++
    }

    /** Siti con almeno una scelta, in ordine di nome: per ognuno i permessi consentiti (true) o bloccati (false). */
    @Synchronized
    fun sites(context: Context): List<Pair<String, Map<SitePermission, Boolean>>> {
        init(context)
        return decisions.entries
            .mapNotNull { (key, allowed) ->
                val permission = SitePermission.entries.firstOrNull { it.key == key.substringBefore('|') }
                permission?.let { Triple(key.substringAfter('|'), it, allowed) }
            }
            .groupBy({ it.first }, { it.second to it.third })
            .map { (origin, choices) -> origin to choices.toMap() }
            .sortedBy { displayName(it.first) }
    }

    fun count(context: Context): Int = sites(context).size

    @Synchronized
    fun clear(context: Context) {
        init(context)
        decisions.clear()
        prefs?.edit()?.clear()?.apply()
        version.intValue++
    }

    private fun key(origin: String, permission: SitePermission) = "${permission.key}|$origin"
}
