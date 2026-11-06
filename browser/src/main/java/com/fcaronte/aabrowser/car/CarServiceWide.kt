package com.fcaronte.aabrowser.car

import android.content.Context
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.fcaronte.aabrowser.BuildConfig

class CarServiceWide : CarAppService() {
    override fun createHostValidator(): HostValidator = carHostValidator(applicationContext)

    override fun onCreateSession(): Session {
        return WideSession()
    }
}

internal fun carHostValidator(context: Context): HostValidator =
    if (BuildConfig.DEBUG) {
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    } else {
        // Solo Android Auto e i sistemi auto Google (elenco ufficiale della libreria). Con "tutti gli host"
        // un'app qualsiasi del telefono poteva fingersi l'auto, ricevere le pagine disegnate dalla Wide
        // (anche quelle con l'utente loggato) e mandarle tocchi
        HostValidator.Builder(context)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()
    }
