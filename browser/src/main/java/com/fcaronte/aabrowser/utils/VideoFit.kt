package com.fcaronte.aabrowser.utils

import android.content.Context
import com.fcaronte.aabrowser.R
import org.json.JSONObject

/**
 * Adatta / Riempi / Allunga per i video dei siti a schermo intero: lo script (assets/scripts/video-fit.js, all'inizio
 * di ogni pagina nella Main e nella Wide) mostra il modo in alto al centro toccando lo schermo, e nella Wide con il menu
 * fisso anche la scelta "Menu sempre" / "Menu al tocco". Le pagine di CarWeb Media hanno la loro barra con lo zoom
 * (assets/player/video-zoom.js). I testi nella lingua dell'app.
 */
object VideoFit {

    @Volatile
    private var source: String? = null

    fun script(context: Context): String {
        val app = AppLocale.wrap(context)
        val labels = JSONObject()
            .put("fit", app.getString(R.string.video_fit))
            .put("fill", app.getString(R.string.video_fill))
            .put("stretch", app.getString(R.string.video_stretch))
            .put("menuAlways", app.getString(R.string.video_menu_always))
            .put("menuTouch", app.getString(R.string.video_menu_touch))
        val text = source ?: context.assets.open("scripts/video-fit.js").bufferedReader().use { it.readText() }
            .also { source = it }
        return text.replace("/*AAB_LABELS*/null", labels.toString())
    }
}
