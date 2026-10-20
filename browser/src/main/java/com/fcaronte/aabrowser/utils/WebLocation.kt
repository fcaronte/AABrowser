package com.fcaronte.aabrowser.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.webkit.GeolocationPermissions
import com.fcaronte.aabrowser.settings.AppSettings

/**
 * Posizione per i siti web (Main e Wide). La WebView la chiede con onGeolocationPermissionsShowPrompt e senza
 * risposta il sito non la riceve mai. Si concede solo con l'opzione "Posizione per i siti web" attiva e col
 * permesso del telefono: preciso per seguire la strada (Google Maps), approssimativo se l'utente ha scelto quello.
 */
object WebLocation {
    private const val TAG = "WebLocation"

    // Android 12+ vuole i due permessi nella stessa richiesta (l'utente può scegliere "approssimativa")
    val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasPreciseLocation(context: Context): Boolean = isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun isAllowed(context: Context): Boolean =
        AppSettings.webLocation.value && PERMISSIONS.any { isGranted(context, it) }

    /** Risposta alla richiesta di un sito. Non viene ricordata: spegnere l'opzione vale dalla richiesta dopo. */
    fun answer(context: Context, origin: String?, callback: GeolocationPermissions.Callback?) {
        val allowed = isAllowed(context)
        AppLog.d(TAG, "Geolocation request from ${AppLog.safeUrlForLog(origin)}: allowed=$allowed")
        callback?.invoke(origin, allowed, false)
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
