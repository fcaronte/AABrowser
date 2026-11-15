package com.fcaronte.aabrowser.utils

import android.content.Context
import com.fcaronte.aabrowser.model.FavoriteSite
import com.fcaronte.aabrowser.smartphone.FavoriteAppsManager
import com.fcaronte.aabrowser.smartphone.VirtualApps

/**
 * "Usa un preferito come pagina iniziale": si sceglie tra i siti preferiti e le app del telefono con la stella (Altre App,
 * sullo schermo virtuale). Il valore salvato è l'id del sito, oppure l'indirizzo `app://pacchetto` dell'app: un id di sito
 * (un UUID o un numero dei predefiniti) non assomiglia mai a un indirizzo di app.
 */
object AutoOpenFavorite {
    /**
     * Chiave e nome di ogni scelta: prima i siti, poi le app che si possono ancora aprire (installate, con un'icona nel
     * launcher). CarWeb stessa no: all'avvio aprirebbe CarWeb sul display virtuale, che all'avvio aprirebbe CarWeb… a catena.
     */
    fun options(context: Context, sites: List<FavoriteSite>): List<Pair<String, String>> {
        val apps = FavoriteAppsManager(context).loadFavoritePackageNames()
            .filter { it != context.packageName && VirtualApps.isAvailable(context, it) }
            .map { VirtualApps.urlFor(it) to VirtualApps.label(context, it) }
        return sites.map { it.id to it.name } + apps
    }

    /** Il pacchetto dell'app scelta; null se la scelta è un sito (o non è una scelta). */
    fun appPackage(id: String?): String? = VirtualApps.packageOf(id)

    /**
     * Il pacchetto da aprire all'avvio: [appPackage], ma mai CarWeb stessa (un valore salvato prima, o di un ripristino, non
     * deve far aprire CarWeb dentro CarWeb a ogni avvio).
     */
    fun appToOpenAtStart(context: Context, id: String?): String? = appPackage(id)?.takeIf { it != context.packageName }
}
