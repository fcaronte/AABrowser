package com.fcaronte.aabrowser

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.fcaronte.aabrowser.model.WebStateRepository
import com.fcaronte.aabrowser.settings.AdBlockSettings
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.utils.AdBlockHost
import com.google.android.material.color.DynamicColors

class CarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        DynamicColors.applyToActivitiesIfAvailable(this)

        // Inizializza il gestore delle pubblicità (AdBlock leggero). Qui e non nella Main:
        // se Android Auto avvia solo la Wide, le impostazioni restavano ai valori predefiniti
        // (AdBlock di YouTube spento) finché non si apriva la Main
        AdBlockSettings.init(this)
        AdBlockHost.init(this)
        WebStateRepository.init(this)
    }

    companion object {
        private const val TAG = "CarApplication"
    }
}
