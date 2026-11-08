package com.fcaronte.aabrowser

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import com.fcaronte.aabrowser.utils.AppLocale
import com.fcaronte.aabrowser.utils.AppLog
import com.fcaronte.aabrowser.utils.CarShareTarget
import com.fcaronte.aabrowser.utils.SharedLink
import com.fcaronte.aabrowser.utils.UrlUtils
import com.fcaronte.aabrowser.utils.VoiceCommandRouter

/**
 * "CarWeb in auto" nel menu Condividi del telefono, presente solo con il telefono collegato all'auto (CarShareTarget):
 * il link condiviso si apre nell'app aperta in auto (CarWeb o CarWebWide, come un comando vocale); senza app aperte
 * aspetta un minuto quella che si apre. Nessuna schermata: un messaggio e basta.
 */
class ShareToCarActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        // Il collegamento si legge adesso: la voce può essere rimasta accesa se l'app si è chiusa durante il viaggio
        val connection = CarConnection(applicationContext).type
        connection.observeForever(object : Observer<Int> {
            override fun onChanged(value: Int) {
                connection.removeObserver(this)
                share(inCar = value != CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
            }
        })
    }

    private fun share(inCar: Boolean) {
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        val url = SharedLink.find(text)
            ?: text?.let { UrlUtils.asUrlOrNull(it) }?.takeIf { UrlUtils.isWebUrl(it) }
        val message = when {
            !inCar -> R.string.share_to_car_not_connected
            url == null -> R.string.share_to_car_no_link
            VoiceCommandRouter.openInCar(url) -> R.string.share_to_car_sent
            else -> R.string.share_to_car_waiting
        }
        if (!inCar) CarShareTarget.setVisible(this, false)
        AppLog.d("ShareToCar", "Shared link: found=${url != null} inCar=$inCar")
        Toast.makeText(applicationContext, AppLocale.wrap(this).getString(message), Toast.LENGTH_LONG).show()
        finish()
    }
}
