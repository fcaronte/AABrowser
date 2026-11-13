package com.fcaronte.aabrowser.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class TabBarActionTest {
    @Test
    fun neverChosenMeansTheUsualButtonsInTheUsualOrder() {
        assertEquals(TabBarAction.DEFAULT, TabBarAction.parse(null))
        assertEquals(
            listOf(TabBarAction.VOICE, TabBarAction.ADBLOCK, TabBarAction.DESKTOP, TabBarAction.BACK, TabBarAction.FORWARD),
            TabBarAction.DEFAULT
        )
    }

    @Test
    fun savedOrderIsKeptAndHiddenOnesAreSimplyMissing() {
        val saved = TabBarAction.serialize(listOf(TabBarAction.BACK, TabBarAction.VOICE))
        assertEquals("back,voice", saved)
        assertEquals(listOf(TabBarAction.BACK, TabBarAction.VOICE), TabBarAction.parse(saved))
    }

    @Test
    fun emptyMeansNoButtonsAndNonsenseIsSkipped() {
        assertEquals(emptyList<TabBarAction>(), TabBarAction.parse(""))
        assertEquals(
            listOf(TabBarAction.DESKTOP, TabBarAction.FORWARD),
            TabBarAction.parse("desktop, sconosciuto,desktop,forward")
        )
    }

    @Test
    fun visibleCountStaysBetweenOneAndTheChosenButtons() {
        assertEquals(2, TabBarAction.visibleCount(2, 5))
        assertEquals(1, TabBarAction.visibleCount(0, 5))
        assertEquals(3, TabBarAction.visibleCount(9, 3))
        assertEquals(0, TabBarAction.visibleCount(2, 0))
    }

    @Test
    fun movingAButtonSwapsItWithItsNeighbourAndStopsAtTheEnds() {
        val order = listOf(TabBarAction.VOICE, TabBarAction.BACK, TabBarAction.FORWARD)
        assertEquals(listOf(TabBarAction.BACK, TabBarAction.VOICE, TabBarAction.FORWARD), TabBarAction.moved(order, 1, -1))
        assertEquals(listOf(TabBarAction.VOICE, TabBarAction.FORWARD, TabBarAction.BACK), TabBarAction.moved(order, 1, 1))
        assertEquals(order, TabBarAction.moved(order, 0, -1))
        assertEquals(order, TabBarAction.moved(order, 2, 1))
    }
}
