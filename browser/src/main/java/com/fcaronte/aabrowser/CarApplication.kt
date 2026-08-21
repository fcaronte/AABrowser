package com.fcaronte.aabrowser

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.fcaronte.aabrowser.settings.AppSettings
import com.google.android.material.color.DynamicColors

class CarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        DynamicColors.applyToActivitiesIfAvailable(this)

        // Inizializza il gestore delle pubblicità (AdBlock leggero)
        AdBlockHost.init(this)
    }

    companion object {
        private const val TAG = "CarApplication"
    }
}
