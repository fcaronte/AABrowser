package com.fcaronte.aabrowser.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.webkit.GeolocationPermissions
import com.fcaronte.aabrowser.settings.AppSettings

/**
 * Posizione per i siti web (Main e Wide). La WebView la chiede con onGeolocationPermissionsShowPrompt e senza
 * risposta il sito non la riceve mai. Si concede con la posizione precisa del telefono (l'app la chiede solo per
 * questo) e l'opzione "Posizione per i siti web" accesa (di serie).
 */
object WebLocation {
    private const val TAG = "WebLocation"

    const val REQUEST_CODE = 1006
    const val REQUEST_CODE_BACKGROUND = 1007

    // Android 12+ vuole i due permessi nella stessa richiesta (l'utente può scegliere "approssimativa")
    private val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasPreciseLocation(context: Context): Boolean = isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /**
     * In auto (app solo sullo schermo dell'auto, telefono bloccato) Android considera l'app in background
     * (CarAppService, "Accessing Location"): senza "Consenti sempre" la WebView non riceve posizioni.
     */
    fun hasBackgroundLocation(context: Context): Boolean =
        isGranted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun isAllowed(context: Context): Boolean = AppSettings.webLocation.value && hasPreciseLocation(context)

    /** Posizione ai siti pronta anche per l'auto. */
    fun isReadyForCar(context: Context): Boolean = isAllowed(context) && hasBackgroundLocation(context)

    /** Chiede il prossimo permesso che manca: prima la posizione precisa, poi "Consenti sempre". false se c'è tutto. */
    fun requestMissing(activity: Activity): Boolean {
        when {
            !hasPreciseLocation(activity) -> activity.requestPermissions(PERMISSIONS, REQUEST_CODE)
            !hasBackgroundLocation(activity) -> activity.requestPermissions(
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                REQUEST_CODE_BACKGROUND
            )
            else -> return false
        }
        return true
    }

    /** Da onRequestPermissionsResult: con la posizione precisa accende l'opzione e chiede subito "Consenti sempre". */
    fun onPermissionResult(activity: Activity, requestCode: Int) {
        if (requestCode != REQUEST_CODE || !hasPreciseLocation(activity)) return
        AppSettings.setWebLocation(activity, true)
        requestMissing(activity)
    }

    /** Risposta alla richiesta di un sito. Non viene ricordata: spegnere l'opzione vale dalla richiesta dopo. */
    fun answer(context: Context, origin: String?, callback: GeolocationPermissions.Callback?) {
        val allowed = isAllowed(context)
        AppLog.d(
            TAG,
            "Geolocation request from ${AppLog.safeUrlForLog(origin)}: allowed=$allowed " +
                    "(option=${AppSettings.webLocation.value}, precise=${hasPreciseLocation(context)}, " +
                    "always=${hasBackgroundLocation(context)})"
        )
        callback?.invoke(origin, allowed, false)
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
