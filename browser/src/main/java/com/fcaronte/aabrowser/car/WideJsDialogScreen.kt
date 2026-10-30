package com.fcaronte.aabrowser.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.utils.JsDialogRequest

/**
 * alert e confirm di una pagina nella Wide: OK (e Annulla per confirm). Indietro o schermata chiusa in altro modo =
 * Annulla. Il prompt non arriva qui: i messaggi di Android Auto non hanno un campo di testo.
 */
class WideJsDialogScreen(
    carContext: CarContext,
    private val request: JsDialogRequest,
    private val onDone: () -> Unit
) : Screen(carContext) {

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                request.cancel()
                onDone()
            }
        })
    }

    private fun action(titleRes: Int, primary: Boolean, onClick: () -> Unit): Action =
        Action.Builder()
            .setTitle(carContext.getString(titleRes))
            .apply { if (primary) setFlags(Action.FLAG_PRIMARY) }
            .setOnClickListener {
                onClick()
                finish()
            }
            .build()

    // setTitle/setHeaderAction sono deprecati, ma il sostituto (setHeader) richiede le API auto 7 e l'app parte dalla 5
    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val builder = MessageTemplate.Builder(request.message.ifBlank { " " })
            .setHeaderAction(Action.BACK)
            .addAction(action(R.string.ok_button, primary = true) { request.confirm() })
        request.site?.let { builder.setTitle(carContext.getString(R.string.js_dialog_title, it)) }
        if (request.kind == JsDialogRequest.Kind.CONFIRM) {
            builder.addAction(action(R.string.cancel_button, primary = false) { request.cancel() })
        }
        return builder.build()
    }
}
