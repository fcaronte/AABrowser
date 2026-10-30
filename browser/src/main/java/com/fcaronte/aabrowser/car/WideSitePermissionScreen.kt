package com.fcaronte.aabrowser.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.utils.PendingSitePermission
import com.fcaronte.aabrowser.utils.SitePermissionChoice

/**
 * Richiesta di un permesso da parte di un sito nella Wide (microfono, posizione, file del telefono). Il messaggio di
 * Android Auto ha al massimo due pulsanti: "Consenti sempre" e "Solo questa volta"; "Non consentire" sta in alto a
 * destra. Indietro o schermata chiusa in altro modo = non ora. La WebView aspetta la risposta sotto questa schermata.
 */
class WideSitePermissionScreen(
    carContext: CarContext,
    val prompt: PendingSitePermission,
    private val onChoice: (SitePermissionChoice) -> Unit
) : Screen(carContext) {

    private var decided = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                decide(SitePermissionChoice.DISMISS)
            }
        })
    }

    private fun decide(choice: SitePermissionChoice) {
        if (decided) return
        decided = true
        onChoice(choice)
    }

    private fun action(titleRes: Int, choice: SitePermissionChoice, primary: Boolean = false): Action =
        Action.Builder()
            .setTitle(carContext.getString(titleRes))
            .apply { if (primary) setFlags(Action.FLAG_PRIMARY) }
            .setOnClickListener {
                decide(choice)
                finish()
            }
            .build()

    // setTitle/setHeaderAction sono deprecati, ma il sostituto (setHeader) richiede le API auto 7 e l'app parte dalla 5
    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(carContext.getString(prompt.permission.messageRes, prompt.site))
            .setTitle(carContext.getString(prompt.permission.titleRes))
            .setHeaderAction(Action.BACK)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(action(R.string.site_permission_deny, SitePermissionChoice.DENY))
                    .build()
            )
            .addAction(action(R.string.site_permission_allow_always, SitePermissionChoice.ALWAYS, primary = true))
            .addAction(action(R.string.site_permission_allow_once, SitePermissionChoice.ONCE))
            .build()
}
