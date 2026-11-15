package com.fcaronte.aabrowser.smartphone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo schermo diviso: 65/35 di serie, il pannello dell'app in verticale e leggibile, i pannelli che non si sovrappongono. */
class VirtualSplitTest {

    @Test
    fun theDefaultIsSixtyFiveThirtyFive() {
        assertEquals(35, VirtualSplit.DEFAULT_PERCENT)
        val panels = VirtualSplit.panels(1000, VirtualSplit.DEFAULT_PERCENT, VirtualSplit.Side.RIGHT)
        assertEquals(650, panels.browserWidth)
        assertEquals(350, panels.appWidth)
        assertEquals(0, panels.browserStart)
        assertEquals(650, panels.appStart)
    }

    @Test
    fun theAppPanelCanBeOnTheLeft() {
        val panels = VirtualSplit.panels(1000, 30, VirtualSplit.Side.LEFT)
        assertEquals(0, panels.appStart)
        assertEquals(300, panels.appWidth)
        assertEquals(300, panels.browserStart)
        assertEquals(700, panels.browserWidth)
    }

    @Test
    fun thePanelsNeverOverlapAndFillTheRegion() {
        for (total in listOf(0, 1, 7, 333, 800, 1075, 2217)) {
            for (percent in VirtualSplit.MIN_PERCENT..VirtualSplit.MAX_PERCENT) {
                for (side in VirtualSplit.Side.entries) {
                    val p = VirtualSplit.panels(total, percent, side)
                    assertEquals("somma $total/$percent/$side", total, p.browserWidth + p.appWidth)
                    val first = if (p.browserStart < p.appStart) p.browserStart + p.browserWidth else p.appStart + p.appWidth
                    val second = maxOf(p.browserStart, p.appStart)
                    assertEquals("i pannelli si toccano", first, second)
                    assertTrue(p.browserWidth >= 0 && p.appWidth >= 0)
                }
            }
        }
    }

    @Test
    fun thePercentStaysBetweenTwentyAndFifty() {
        assertEquals(VirtualSplit.MIN_PERCENT, VirtualSplit.clampPercent(0))
        assertEquals(VirtualSplit.MIN_PERCENT, VirtualSplit.clampPercent(-40))
        assertEquals(VirtualSplit.MAX_PERCENT, VirtualSplit.clampPercent(80))
        assertEquals(35, VirtualSplit.clampPercent(35))
        // Un valore salvato fuori misura (un ripristino da un'altra versione) non rompe il layout
        assertEquals(200, VirtualSplit.panels(1000, 5, VirtualSplit.Side.RIGHT).appWidth)
        assertEquals(500, VirtualSplit.panels(1000, 99, VirtualSplit.Side.RIGHT).appWidth)
    }

    @Test
    fun theSideIsReadFromTheSavedNameWithRightAsDefault() {
        assertEquals(VirtualSplit.Side.LEFT, VirtualSplit.Side.parse("LEFT"))
        assertEquals(VirtualSplit.Side.RIGHT, VirtualSplit.Side.parse("RIGHT"))
        assertEquals(VirtualSplit.Side.RIGHT, VirtualSplit.Side.parse(null))
        assertEquals(VirtualSplit.Side.RIGHT, VirtualSplit.Side.parse("centro"))
    }

    @Test
    fun aNarrowRegionGivesTheAppAReadablePanelWithinTheMaximum() {
        // La Wide con la colonna dell'host accanto: 503 px di regione a 119 dpi. Il 30% darebbe 150 px (112 di display): il minimo
        // è la barra (38 px) più 240 dp dell'auto (178 px) = 216 px
        val minPanel = 38 + (VirtualSplit.MIN_DISPLAY_DP * 119 / 160)
        assertEquals(150, VirtualSplit.appPanelWidth(503, 30, 0))
        assertEquals(minPanel, VirtualSplit.appPanelWidth(503, 30, minPanel))
        val panels = VirtualSplit.panels(503, 30, VirtualSplit.Side.RIGHT, minPanel)
        assertEquals(minPanel, panels.appWidth)
        assertEquals(503 - minPanel, panels.browserWidth)
        assertEquals(503 - minPanel, panels.appStart)
        // Una regione larga: il minimo non conta (1000 px, 30% = 300 > 216)
        assertEquals(300, VirtualSplit.appPanelWidth(1000, 30, minPanel))
        // Mai oltre il massimo del 50%, anche se il minimo è più grande (una regione stretta davvero)
        assertEquals(200, VirtualSplit.appPanelWidth(400, 30, minPanel))
        assertEquals(125, VirtualSplit.appPanelWidth(250, 20, 400))
    }

    @Test
    fun theMinimumNeverBreaksTheTilingOfThePanels() {
        for (total in listOf(0, 1, 100, 503, 800, 1075, 2217)) {
            for (min in listOf(0, 50, 216, 600)) {
                for (side in VirtualSplit.Side.entries) {
                    val p = VirtualSplit.panels(total, 30, side, min)
                    assertEquals("somma $total/$min/$side", total, p.browserWidth + p.appWidth)
                    assertTrue(p.appWidth <= total * VirtualSplit.MAX_PERCENT / 100)
                    assertTrue(p.browserWidth >= 0 && p.appWidth >= 0)
                }
            }
        }
    }

    @Test
    fun theDisplayIsAlwaysPortrait() {
        // Un pannello stretto e alto lo è già: il display è largo quanto il pannello
        assertEquals(318, VirtualSplit.displayWidth(318, 514))
        // Un pannello largo (50% di uno schermo molto largo): alto quanto il pannello, largo come un telefono in piedi
        val width = VirtualSplit.displayWidth(1100, 1052)
        assertTrue("larghezza $width", width < 1052)
        assertEquals(591, width)
        // Un pannello quadrato non è in verticale: si stringe
        assertTrue(VirtualSplit.displayWidth(500, 500) < 500)
        // Misure ancora sconosciute: non si inventa niente
        assertEquals(0, VirtualSplit.displayWidth(0, 500))
        assertEquals(300, VirtualSplit.displayWidth(300, 0))
    }

    @Test
    fun anAppInTheThirtyPercentPanelStillSeesAPhoneWidth() {
        // Una finestra della Main di 1280 px con il dock chiuso: il pannello del 30% è largo 384 px, meno la barra (66 px a 3x)
        val panel = VirtualSplit.panels(1280, 30, VirtualSplit.Side.RIGHT).appWidth
        val appWidth = panel - 66
        val dpi = VirtualSplit.density(baseDpi = 240, widthPx = appWidth)
        val logical = VirtualSplit.logicalWidthDp(appWidth, dpi)
        assertTrue("dp visti: $logical", logical >= VirtualSplit.MIN_LOGICAL_WIDTH_DP - 1)
        // La densità scende, non sale mai oltre quella di partenza
        assertTrue(dpi <= 240)
    }

    @Test
    fun theDensityIsLeftAloneWhenThePanelIsWideEnough() {
        // 720 px a 240 dpi sono 480 dp: più dei 320 minimi, niente da cambiare
        assertEquals(240, VirtualSplit.density(240, 720))
        // Misura ancora sconosciuta
        assertEquals(240, VirtualSplit.density(240, 0))
    }

    @Test
    fun theDensityNeverGoesBelowTheFloorOfTheDisplay() {
        assertEquals(60, VirtualSplit.density(400, 100))
    }
}
