package com.fcaronte.aabrowser

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.fcaronte.aabrowser.utils.AppLocale
import com.fcaronte.aabrowser.utils.AppLog
import com.fcaronte.aabrowser.utils.SharedLink
import com.fcaronte.aabrowser.utils.UrlUtils
import com.fcaronte.aabrowser.utils.VoiceCommandRouter

/**
 * "CarWeb in auto" nel menu Condividi del telefono: il link condiviso si apre nell'app aperta in auto (CarWeb o
 * CarWebWide, come un comando vocale); senza app aperte aspetta un minuto quella che si apre. C'è solo con l'opzione
 * nelle Impostazioni (AppSettings.shareToCar accende il componente). Nessuna schermata: un messaggio e basta.
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
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        val url = SharedLink.find(text)
            ?: text?.let { UrlUtils.asUrlOrNull(it) }?.takeIf { UrlUtils.isWebUrl(it) }
        val message = when {
            url == null -> R.string.share_to_car_no_link
            VoiceCommandRouter.openInCar(url) -> R.string.share_to_car_sent
            else -> R.string.share_to_car_waiting
        }
        AppLog.d("ShareToCar", "Shared link: found=${url != null}")
        Toast.makeText(applicationContext, AppLocale.wrap(this).getString(message), Toast.LENGTH_LONG).show()
        finish()
    }
}
