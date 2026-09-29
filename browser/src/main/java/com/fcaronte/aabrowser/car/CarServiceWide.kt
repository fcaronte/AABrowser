package com.fcaronte.aabrowser.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class CarServiceWide : CarAppService() {
    init {
        instance = this
    }

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return WideSession()
    }

    companion object {
        var instance: CarServiceWide? = null
            private set
    }
}
