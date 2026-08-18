package com.fcaronte.aabrowser

import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.apps.auto.sdk.CarActivityService

class CarService : CarActivityService() {
    override fun getCarActivity(): Class<out CarActivity?> {
        return MainCarActivity::class.java
    }

    companion object {
        private const val TAG = "CarService"
    }
}
