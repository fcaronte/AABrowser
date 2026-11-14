package com.fcaronte.aabrowser.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarActionTest {
    @Test
    fun neverChosenMeansTheUsualButtons() {
        assertEquals(SidebarAction.DEFAULT, SidebarAction.parse(null))
        // Home per prima, poi quelli che la sidebar della Wide aveva già, poi i nuovi; condividi, schede e esci restano spenti
        assertEquals(
            listOf(
                SidebarAction.HOME, SidebarAction.BACK, SidebarAction.FORWARD, SidebarAction.RELOAD, SidebarAction.SEARCH,
                SidebarAction.ADBLOCK, SidebarAction.DESKTOP, SidebarAction.VOICE, SidebarAction.FAVORITE, SidebarAction.SETTINGS
            ),
            SidebarAction.DEFAULT
        )
        assertFalse(SidebarAction.EXIT in SidebarAction.DEFAULT)
    }

    @Test
    fun everyButtonOfTheMainFabIsThere() {
        // Indietro, avanti, home, schede, ricarica, cerca, preferiti, AdBlock, desktop, voce, condividi, impostazioni, esci
        val ids = SidebarAction.ALL.map { it.id }.toSet()
        assertEquals(
            setOf("back", "forward", "home", "tabs", "reload", "search", "favorite", "adblock", "desktop", "voice", "share", "settings", "exit"),
            ids
        )
        // Gli id sono quelli salvati nelle preferenze: non devono ripetersi
        assertEquals(SidebarAction.ALL.size, ids.size)
    }

    @Test
    fun savedOrderIsKeptAndHiddenOnesAreMissing() {
        val saved = SidebarAction.serialize(listOf(SidebarAction.HOME, SidebarAction.RELOAD, SidebarAction.BACK))
        assertEquals("home,reload,back", saved)
        assertEquals(listOf(SidebarAction.HOME, SidebarAction.RELOAD, SidebarAction.BACK), SidebarAction.parse(saved))
    }

    @Test
    fun homeIsAlwaysThereEvenIfTheSavedListForgotIt() {
        assertEquals(listOf(SidebarAction.HOME), SidebarAction.parse(""))
        assertEquals(listOf(SidebarAction.HOME, SidebarAction.BACK), SidebarAction.parse("back"))
        // Se c'è già resta dove l'utente l'ha messo
        assertEquals(listOf(SidebarAction.BACK, SidebarAction.HOME), SidebarAction.parse("back,home"))
    }

    @Test
    fun nonsenseAndRepeatsAreSkipped() {
        assertEquals(
            listOf(SidebarAction.HOME, SidebarAction.SEARCH, SidebarAction.VOICE),
            SidebarAction.parse("home, sconosciuto,search,home,voice,search")
        )
    }

    @Test
    fun anEssentialButtonCannotBeHiddenButTheOthersCan() {
        val shown = SidebarAction.DEFAULT
        assertEquals(shown, SidebarAction.without(shown, SidebarAction.HOME))
        assertEquals(shown - SidebarAction.BACK, SidebarAction.without(shown, SidebarAction.BACK))
        assertTrue(SidebarAction.HOME.essential)
        assertEquals(listOf(SidebarAction.HOME), SidebarAction.ALL.filter { it.essential })
    }

    @Test
    fun addingAButtonPutsItLastAndNeverTwice() {
        val shown = listOf(SidebarAction.HOME, SidebarAction.BACK)
        assertEquals(listOf(SidebarAction.HOME, SidebarAction.BACK, SidebarAction.EXIT), SidebarAction.with(shown, SidebarAction.EXIT))
        assertEquals(shown, SidebarAction.with(shown, SidebarAction.BACK))
    }

    @Test
    fun eachAppShowsOnlyTheButtonsThatMakeSenseThere() {
        val all = SidebarAction.ALL
        // La Wide ha una pagina sola: niente schede
        assertFalse(SidebarAction.TABS in SidebarAction.forHost(all, SidebarHost.WIDE))
        assertTrue(SidebarAction.TABS in SidebarAction.forHost(all, SidebarHost.MAIN))
        // Il resto c'è in tutte e due
        assertEquals(all - SidebarAction.TABS, SidebarAction.forHost(all, SidebarHost.WIDE))
        assertEquals(all, SidebarAction.forHost(all, SidebarHost.MAIN))
    }

    @Test
    fun hiddenButtonsAreTheOnesYouCouldTurnOnInThatApp() {
        val shown = SidebarAction.DEFAULT
        assertEquals(
            listOf(SidebarAction.TABS, SidebarAction.SHARE, SidebarAction.EXIT),
            SidebarAction.hidden(shown, SidebarHost.MAIN)
        )
        // Nella Wide le schede non si propongono nemmeno
        assertEquals(
            listOf(SidebarAction.SHARE, SidebarAction.EXIT),
            SidebarAction.hidden(shown, SidebarHost.WIDE)
        )
    }

    @Test
    fun movingAButtonSwapsItWithItsNeighbourAndStopsAtTheEnds() {
        val order = listOf(SidebarAction.HOME, SidebarAction.BACK, SidebarAction.RELOAD)
        assertEquals(
            listOf(SidebarAction.BACK, SidebarAction.HOME, SidebarAction.RELOAD),
            SidebarAction.moved(order, 1, -1)
        )
        assertEquals(
            listOf(SidebarAction.HOME, SidebarAction.RELOAD, SidebarAction.BACK),
            SidebarAction.moved(order, 1, 1)
        )
        assertEquals(order, SidebarAction.moved(order, 0, -1))
        assertEquals(order, SidebarAction.moved(order, 2, 1))
    }
}
