package com.fcaronte.aabrowser.utils

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.car.app.connection.CarConnection
import com.fcaronte.aabrowser.ShareToCarActivity

/**
 * "CarWeb in auto" nel menu Condividi del telefono: c'è solo mentre il telefono è collegato all'auto, così nell'uso
 * normale non si vede. Android non permette di nascondere una voce a seconda del momento: si accende e si spegne il
 * componente (spento nel manifest). Android Auto annuncia il collegamento solo alle app già avviate (broadcast
 * implicito, visto nel suo codice), quindi la voce compare quando CarWeb è avviata in auto.
 */
object CarShareTarget {

    /** All'avvio dell'app: la voce segue il collegamento finché il processo è vivo. */
    fun watch(app: Application) {
        CarConnection(app).type.observeForever { type ->
            setVisible(app, type != CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
        }
    }

    fun setVisible(context: Context, visible: Boolean) {
        val component = ComponentName(context, ShareToCarActivity::class.java)
        val state = if (visible) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        try {
            if (context.packageManager.getComponentEnabledSetting(component) != state) {
                context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                AppLog.d("CarShareTarget", "Share entry visible=$visible")
            }
        } catch (e: Exception) {
            AppLog.w("CarShareTarget", "Share entry not updated: ${e.javaClass.simpleName}")
        }
    }
}
