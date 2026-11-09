package com.fcaronte.aabrowser

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.car.app.connection.CarConnection
import androidx.core.content.IntentCompat
import androidx.lifecycle.Observer
import com.fcaronte.aabrowser.utils.AppLocale
import com.fcaronte.aabrowser.utils.AppLog
import com.fcaronte.aabrowser.utils.CarShareTarget
import com.fcaronte.aabrowser.utils.SharedLink
import com.fcaronte.aabrowser.utils.SharedMedia
import com.fcaronte.aabrowser.utils.UrlUtils
import com.fcaronte.aabrowser.utils.VoiceCommandRouter

/**
 * "CarWeb in auto" nel menu Condividi del telefono, presente solo con il telefono collegato all'auto (CarShareTarget):
 * il link, la foto o il video condivisi si aprono nell'app aperta in auto (CarWeb o CarWebWide, come un comando vocale);
 * senza app aperte aspettano un minuto quella che si apre. Nessuna schermata: un messaggio e basta.
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
        val send = intent?.takeIf { it.action == Intent.ACTION_SEND }
        // Una foto o un video: il permesso sul file vale finché questa attività è aperta, quindi si prende adesso
        val media = send?.takeIf { inCar }
            ?.let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_STREAM, Uri::class.java) }
            ?.let { SharedMedia.accept(this, it, send.type) }
        val text = send?.getStringExtra(Intent.EXTRA_TEXT)
        val url = media?.pageUrl
            ?: SharedLink.find(text)
            ?: text?.let { UrlUtils.asUrlOrNull(it) }?.takeIf { UrlUtils.isWebUrl(it) }
        val message = when {
            !inCar -> R.string.share_to_car_not_connected
            url == null -> R.string.share_to_car_no_link
            VoiceCommandRouter.openInCar(url, media?.name) -> R.string.share_to_car_sent
            else -> R.string.share_to_car_waiting
        }
        if (!inCar) CarShareTarget.setVisible(this, false)
        AppLog.d("ShareToCar", "Shared: found=${url != null} media=${media != null} inCar=$inCar")
        Toast.makeText(applicationContext, AppLocale.wrap(this).getString(message), Toast.LENGTH_LONG).show()
        finish()
    }
}
