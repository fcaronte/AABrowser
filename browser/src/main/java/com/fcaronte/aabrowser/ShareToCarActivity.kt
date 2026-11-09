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
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.utils.AppLocale
import com.fcaronte.aabrowser.utils.AppLog
import com.fcaronte.aabrowser.utils.CarShareTarget
import com.fcaronte.aabrowser.utils.SharedLink
import com.fcaronte.aabrowser.utils.SharedMedia
import com.fcaronte.aabrowser.utils.UrlUtils
import com.fcaronte.aabrowser.utils.VoiceCommandRouter

/**
 * "Condividi su CarWeb", la voce nel menu Condividi del telefono (CarShareTarget). Il link, la foto, il video o il file
 * audio condivisi si aprono nell'app aperta in auto (CarWeb o CarWebWide, come un comando vocale); senza app aperte
 * aspettano un minuto quella che si apre. Lontano dall'auto, con l'opzione "Sempre", si apre CarWeb sul telefono.
 * Nessuna schermata: un messaggio e basta.
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
        val onPhone = !inCar && AppSettings.shareAlways.value
        // Un file: il permesso per leggerlo vale finché questa attività è aperta, quindi si prende adesso
        val media = send?.takeIf { inCar || onPhone }
            ?.let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_STREAM, Uri::class.java) }
            ?.let { SharedMedia.accept(this, it, send.type) }
        val text = send?.getStringExtra(Intent.EXTRA_TEXT)
        val url = media?.pageUrl
            ?: SharedLink.find(text)
            ?: text?.let { UrlUtils.asUrlOrNull(it) }?.takeIf { UrlUtils.isWebUrl(it) }
        val message = when {
            !inCar && !onPhone -> R.string.share_to_car_not_connected
            url == null -> R.string.share_to_car_no_link
            onPhone -> null
            VoiceCommandRouter.openInCar(url, media?.name) -> R.string.share_to_car_sent
            else -> R.string.share_to_car_waiting
        }
        if (!inCar && !onPhone) CarShareTarget.setVisible(this, false)
        if (onPhone && url != null) openOnPhone(url, media?.name)
        AppLog.d("ShareToCar", "Shared: found=${url != null} media=${media != null} inCar=$inCar onPhone=$onPhone")
        message?.let { Toast.makeText(applicationContext, AppLocale.wrap(this).getString(it), Toast.LENGTH_LONG).show() }
        finish()
    }

    // Se la Main non è ancora aperta il link la aspetta (VoiceCommandRouter)
    private fun openOnPhone(url: String, label: String?) {
        VoiceCommandRouter.openOnPhone(url, label)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
