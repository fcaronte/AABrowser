package com.fcaronte.aabrowser.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class WideSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return WideScreen(carContext)
    }
}
