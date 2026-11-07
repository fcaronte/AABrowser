package com.fcaronte.aabrowser.car

import androidx.car.app.Session

/**
 * Prova del 07/11/2026: la Wide come app di categoria POI ("CarWeb POI" nel menu di Android Auto). Android Auto non la
 * tratta da navigatore, quindi non dovrebbe prendere il posto di Waze o Maps. La pagina va sulla mappa come nella Wide,
 * nel template mappa con pannello (Car API 7): il pannello è obbligatorio e mostra i preferiti (WideScreen.poiTemplate).
 * Commit a parte, si toglie con un revert.
 */
class CarServicePoi : CarServiceWide() {
    override fun onCreateSession(): Session = WideSession(poi = true)
}
