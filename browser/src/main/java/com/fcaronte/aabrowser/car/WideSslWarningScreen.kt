package com.fcaronte.aabrowser.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.utils.PendingSslError

/**
 * Avviso per un certificato non valido nella Wide (es. server di casa autofirmato), come quello di Chrome:
 * la pagina si carica solo con "Continua comunque". Indietro o schermata chiusa in altro modo = annulla.
 * La surface resta viva sotto questa schermata, quindi la WebView aspetta la risposta.
 */
class WideSslWarningScreen(
    carContext: CarContext,
    val prompt: PendingSslError,
    private val onDecision: (proceed: Boolean) -> Unit
) : Screen(carContext) {

    private var decided = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                decide(false)
            }
        })
    }

    private fun decide(proceed: Boolean) {
        if (decided) return
        decided = true
        onDecision(proceed)
    }

    // setTitle/setHeaderAction sono deprecati, ma il sostituto (setHeader) richiede le API auto 7 e l'app parte dalla 5
    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        // "Annulla" è il pulsante principale: proseguire deve essere una scelta consapevole
        val cancel = Action.Builder()
            .setTitle(carContext.getString(R.string.ssl_warning_cancel))
            .setFlags(Action.FLAG_PRIMARY)
            .setOnClickListener {
                decide(false)
                finish()
            }
            .build()
        val proceed = Action.Builder()
            .setTitle(carContext.getString(R.string.ssl_warning_proceed))
            .setOnClickListener {
                decide(true)
                finish()
            }
            .build()

        // Testo corto: Android Auto mostra solo un paio di righe e taglierebbe il consiglio finale
        return MessageTemplate.Builder(carContext.getString(R.string.ssl_warning_message_car, prompt.host))
            .setTitle(carContext.getString(R.string.ssl_warning_title))
            .setHeaderAction(Action.BACK)
            .setIcon(CarIcon.ALERT)
            .addAction(cancel)
            .addAction(proceed)
            .build()
    }
}
