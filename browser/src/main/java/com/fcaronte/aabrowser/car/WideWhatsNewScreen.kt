package com.fcaronte.aabrowser.car

import androidx.activity.OnBackPressedCallback
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.Template
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.utils.AppLocale
import com.fcaronte.aabrowser.utils.WhatsNew

/**
 * Le novità della versione nella Wide, la prima volta dopo un aggiornamento (se non le si sono già lette sul telefono o nella
 * Main): un messaggio lungo di Android Auto con il testo che scorre e OK. OK o Indietro le segnano come lette; se la
 * schermata sparisce in altro modo (l'auto si spegne, le ha chiuse la Main o il telefono) restano come sono.
 * Si apre solo da fermi: Android Auto non mostra i messaggi lunghi in movimento.
 */
class WideWhatsNewScreen(
    carContext: CarContext,
    private val notes: WhatsNew.Notes
) : Screen(carContext) {
    // Testi nella lingua dell'app, non in quella del telefono che arriva con la configurazione dell'auto
    private val strings = AppLocale.wrap(carContext)

    init {
        // Il tasto Indietro dell'auto vale come OK: se l'ha premuto, le ha viste
        carContext.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                close()
            }
        })
    }

    private fun close() {
        WhatsNew.dismiss(carContext)
        finish()
    }

    // setTitle/setHeaderAction sono deprecati, ma il sostituto (setHeader) richiede le API auto 7 e l'app parte dalla 5
    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template =
        LongMessageTemplate.Builder(notes.text)
            .setTitle(strings.getString(R.string.whats_new_title, notes.versionName))
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle(strings.getString(R.string.ok_button))
                    .setFlags(Action.FLAG_PRIMARY)
                    .setOnClickListener { close() }
                    .build()
            )
            .build()
}
