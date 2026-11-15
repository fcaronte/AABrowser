package com.fcaronte.aabrowser.smartphone

/**
 * Schermo diviso tra il browser e un'app del telefono sul display virtuale: l'app sta in un pannello stretto in verticale, il
 * browser nel resto. Le misure sono pixel di una regione (la finestra della Main senza il dock, o la superficie della Wide senza
 * le fasce dell'host); qui non c'è niente di Android, così si provano con i test.
 *
 * Il rapporto non è 50/50: di serie l'app ha il 35% (era il 30%), da 20% a 50% dalle Impostazioni (niente trascinamento: nella Wide l'host non
 * dà la posizione del dito, quindi la dimensione si sceglie solo lì).
 */
object VirtualSplit {
    const val MIN_PERCENT = 20
    const val MAX_PERCENT = 50
    const val DEFAULT_PERCENT = 35
    const val STEP_PERCENT = 5

    /** Larghezza minima, in dp logici, che un'app vede nel pannello: sotto le app si impaginano male (telefoni stretti, 320 dp). */
    const val MIN_LOGICAL_WIDTH_DP = 320

    /**
     * Larghezza minima del display dell'app, in dp dello schermo che lo mostra (quello dell'auto, non quelli dell'app): più
     * stretto, anche con la densità abbassata per farci stare 320 dp, le cose sono troppo piccole per il dito. Con la colonna
     * di Android Auto accanto, la regione della Wide è stretta e il 30% dava un pannello di 24 mm.
     */
    const val MIN_DISPLAY_DP = 240

    // Un'app vuole un display in verticale: se il pannello non lo è già (schermi molto larghi, 50%), il display è alto quanto il
    // pannello e largo quanto un telefono in piedi, con le bande nere ai lati
    private const val PORTRAIT_RATIO = 9f / 16f
    private const val MIN_DPI = 60

    /** Il lato del pannello dell'app: assoluto sullo schermo (destra o sinistra), non segue la lingua. */
    enum class Side {
        LEFT, RIGHT;

        companion object {
            fun parse(name: String?): Side = entries.firstOrNull { it.name == name } ?: RIGHT
        }
    }

    fun clampPercent(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** Posizioni e larghezze dei due pannelli, dall'inizio della regione. */
    class Panels(val browserStart: Int, val browserWidth: Int, val appStart: Int, val appWidth: Int)

    /**
     * Larghezza del pannello dell'app in una regione larga [total]: la percentuale scelta, ma mai meno di [minPanel] (la barra
     * dell'app più [MIN_DISPLAY_DP] dp, nei pixel di chi la chiama), dentro il massimo [MAX_PERCENT]%. In una regione larga
     * il minimo non conta; in una stretta (la Wide con la colonna dell'host) il 30% sarebbe troppo poco per usare l'app.
     */
    fun appPanelWidth(total: Int, percent: Int, minPanel: Int = 0): Int {
        val width = total.coerceAtLeast(0)
        val wanted = (width.toLong() * clampPercent(percent) / 100).toInt()
        val cap = (width.toLong() * MAX_PERCENT / 100).toInt()
        return maxOf(wanted, minOf(minPanel.coerceAtLeast(0), cap))
    }

    /** Divide una regione larga [total] pixel: all'app [percent]% (tra [MIN_PERCENT] e [MAX_PERCENT], o il minimo [minPanel]), al browser il resto. */
    fun panels(total: Int, percent: Int, side: Side, minPanel: Int = 0): Panels {
        val width = total.coerceAtLeast(0)
        val app = appPanelWidth(width, percent, minPanel)
        val browser = width - app
        return if (side == Side.RIGHT) Panels(0, browser, browser, app) else Panels(app, browser, 0, app)
    }

    /**
     * Larghezza del display dell'app in un pannello largo [panelWidth] e alto [panelHeight]: tutto il pannello se è già in
     * verticale, altrimenti quanto un telefono in piedi alto quanto il pannello. Il display è sempre in verticale.
     */
    fun displayWidth(panelWidth: Int, panelHeight: Int): Int {
        if (panelWidth <= 0 || panelHeight <= 0) return panelWidth.coerceAtLeast(0)
        return if (panelWidth < panelHeight) panelWidth else minOf(panelWidth, (panelHeight * PORTRAIT_RATIO).toInt())
    }

    /**
     * Densità del display dell'app nello schermo diviso: quella di sempre, ma non tanto alta che l'app veda meno di
     * [MIN_LOGICAL_WIDTH_DP] dp di larghezza. Un pannello del 30% su un'auto è largo poche centinaia di pixel: alla densità
     * dell'auto l'app avrebbe uno schermo più stretto di un telefono e si impaginerebbe male. Con meno densità vede lo stesso
     * numero di dp di un telefono (le cose sono più piccole, ma si leggono).
     */
    fun density(baseDpi: Int, widthPx: Int): Int {
        if (widthPx <= 0) return baseDpi
        val limit = (widthPx.toLong() * 160 / MIN_LOGICAL_WIDTH_DP).toInt()
        return minOf(baseDpi, limit).coerceAtLeast(MIN_DPI)
    }

    /** Larghezza in dp logici che l'app vede con una vista larga [widthPx] a [dpi]. */
    fun logicalWidthDp(widthPx: Int, dpi: Int): Float = if (dpi <= 0) 0f else widthPx * 160f / dpi
}
