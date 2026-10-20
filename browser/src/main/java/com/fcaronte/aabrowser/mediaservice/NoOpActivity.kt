package com.fcaronte.aabrowser.mediaservice

import android.app.Activity
import android.os.Build
import android.os.Bundle

/**
 * Activity vuota e invisibile: è l'activity della sessione media quando la Wide non è in uso, così il tocco sulla
 * scheda del player di Android Auto non apre niente (vedi CarMediaService.setWideActive). Si chiude subito.
 */
class NoOpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        finish()
    }
}
