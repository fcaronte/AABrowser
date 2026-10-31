package com.fcaronte.aabrowser.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager

/**
 * Permessi del telefono per la posizione dei siti web (Main e Wide): la posizione precisa, che l'app chiede solo per
 * questo (se l'utente sceglie l'approssimativa i siti ricevono quella), e in auto "Consenti sempre". Quali siti la
 * ricevono lo decide l'utente sito per sito (SitePermissions).
 */
object WebLocation {
    const val REQUEST_CODE = 1006
    const val REQUEST_CODE_BACKGROUND = 1007

    // Android 12+ vuole i due permessi nella stessa richiesta (l'utente può scegliere "approssimativa")
    private val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasPreciseLocation(context: Context): Boolean = isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /** Precisa o almeno approssimativa (quella chiesta all'avvio per il meteo). */
    fun hasLocation(context: Context): Boolean =
        hasPreciseLocation(context) || isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * In auto (app solo sullo schermo dell'auto, telefono bloccato) Android considera l'app in background
     * (CarAppService, "Accessing Location"): senza "Consenti sempre" la WebView non riceve posizioni.
     */
    fun hasBackgroundLocation(context: Context): Boolean =
        isGranted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    /** Posizione ai siti pronta anche per l'auto. */
    fun isReadyForCar(context: Context): Boolean = hasPreciseLocation(context) && hasBackgroundLocation(context)

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

    /** Da onRequestPermissionsResult: dopo la posizione precisa chiede subito "Consenti sempre". */
    fun onPermissionResult(activity: Activity, requestCode: Int) {
        if (requestCode != REQUEST_CODE || !hasPreciseLocation(activity)) return
        requestMissing(activity)
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
