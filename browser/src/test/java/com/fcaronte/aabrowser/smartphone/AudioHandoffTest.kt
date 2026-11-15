package com.fcaronte.aabrowser.smartphone

import com.fcaronte.aabrowser.smartphone.AudioHandoff.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il browser che suonava riprende quando l'app che gli ha preso l'audio smette, e solo allora: mai dopo una pausa voluta
 * dall'utente, mai per un audio qualunque.
 */
class AudioHandoffTest {

    /** Scorre il tempo da [from] a [to] a passi di mezzo secondo e dice il primo istante in cui il browser deve riprendere. */
    private fun AudioHandoff.runUntil(from: Long, to: Long): Long? {
        var t = from
        while (t <= to) {
            if (tick(t) == Action.RESUME_BROWSER) return t
            t += 500
        }
        return null
    }

    private fun playingBrowser(): AudioHandoff = AudioHandoff().apply { reset(playerCount = 1, browserPlaying = true) }

    @Test
    fun theBrowserResumesWhenTheAppThatTookItsAudioStops() {
        val handoff = playingBrowser()
        handoff.onPlayers(2, 1_000)      // l'app parte mentre il browser suona
        handoff.onPlayers(1, 1_250)      // Android toglie il focus al browser: resta l'app
        handoff.onBrowser(false, 1_300)  // la pagina è in pausa
        assertTrue(handoff.isPending)
        assertEquals("niente da riprendere finché l'app suona", null, handoff.runUntil(1_500, 9_500))
        handoff.onPlayers(0, 10_000)     // l'app smette
        val at = handoff.runUntil(10_000, 20_000)
        assertEquals("riprende dopo due secondi di silenzio", 12_000L, at)
        assertFalse(handoff.isPending)
        // Una volta sola
        assertEquals(null, handoff.runUntil(12_500, 30_000))
    }

    @Test
    fun aPauseChosenByTheUserIsNeverResumed() {
        val handoff = playingBrowser()
        handoff.onPlayers(0, 5_000)      // l'utente mette in pausa: il lettore sparisce
        handoff.onBrowser(false, 5_100)
        assertFalse(handoff.isPending)
        assertEquals(null, handoff.runUntil(5_500, 60_000))
    }

    @Test
    fun aSecondPlayerOfTheBrowserFollowedByAUserPauseIsNotAnInterruption() {
        // YouTube: il video e poi un altro elemento (anteprima, pubblicità); poi l'utente ferma tutto entro due secondi
        val handoff = playingBrowser()
        handoff.onPlayers(2, 1_000)
        handoff.onPlayers(1, 1_500)
        handoff.onPlayers(0, 2_000)
        handoff.onBrowser(false, 2_100)
        assertEquals(null, handoff.runUntil(2_500, 60_000))
        assertFalse("non resta in attesa", handoff.isPending)
        // Un audio qualunque che parte e smette dopo non fa riprendere il browser
        handoff.onPlayers(1, 70_000)
        handoff.onPlayers(0, 80_000)
        assertEquals(null, handoff.runUntil(80_000, 120_000))
    }

    @Test
    fun aBlipOfTheAppDoesNotResumeAnything() {
        val handoff = playingBrowser()
        handoff.onPlayers(2, 1_000)
        handoff.onBrowser(false, 1_100)
        handoff.onPlayers(1, 1_100)
        handoff.onPlayers(0, 1_800)      // l'app ha suonato meno di un secondo e mezzo
        assertEquals(null, handoff.runUntil(2_000, 60_000))
    }

    @Test
    fun theGapBetweenTwoTracksOfTheAppDoesNotResumeTheBrowser() {
        val handoff = playingBrowser()
        handoff.onPlayers(2, 1_000)
        handoff.onPlayers(1, 1_100)
        handoff.onBrowser(false, 1_200)
        assertEquals(null, handoff.runUntil(1_500, 5_000))
        handoff.onPlayers(0, 6_000)      // fine del brano
        assertEquals(null, handoff.runUntil(6_000, 7_500))
        handoff.onPlayers(1, 7_700)      // comincia il successivo dopo meno di due secondi
        assertEquals(null, handoff.runUntil(7_700, 15_000))
        handoff.onPlayers(0, 16_000)     // stavolta finisce davvero
        assertEquals(18_000L, handoff.runUntil(16_000, 30_000))
    }

    @Test
    fun ifTheBrowserPlaysAgainByItselfThereIsNothingToResume() {
        val handoff = playingBrowser()
        handoff.onPlayers(2, 1_000)
        handoff.onPlayers(1, 1_100)
        handoff.onBrowser(false, 1_200)
        handoff.runUntil(1_500, 4_000)
        handoff.onBrowser(true, 5_000)   // l'utente l'ha fatto ripartire
        assertFalse(handoff.isPending)
        handoff.onPlayers(0, 6_000)
        assertEquals(null, handoff.runUntil(6_000, 30_000))
    }

    @Test
    fun aPauseLongAfterAPlayerStartedIsNotAnInterruption() {
        val handoff = playingBrowser()
        handoff.onPlayers(2, 1_000)      // qualcosa parte
        handoff.onPlayers(1, 1_200)
        // ... e il browser si ferma solo tre secondi dopo: è passata la finestra
        handoff.onBrowser(false, 4_000)
        assertFalse(handoff.isPending)
    }

    @Test
    fun whatAlreadyPlaysWhenTheFirstAppOpensIsNotAnInterruption() {
        // All'apertura della prima app il browser suona già (1 lettore): non è un lettore partito in quel momento
        val handoff = AudioHandoff()
        handoff.reset(playerCount = 1, browserPlaying = true)
        handoff.onPlayers(1, 100)
        handoff.onBrowser(false, 1_000)
        assertFalse(handoff.isPending)
    }

    @Test
    fun theWaitEndsAfterHalfAnHour() {
        val handoff = playingBrowser()
        handoff.onPlayers(2, 1_000)
        handoff.onPlayers(1, 1_100)
        handoff.onBrowser(false, 1_200)
        // L'app suona per più di mezz'ora: poi non c'è più niente da riprendere
        val resumeAt = handoff.runUntil(1_500, AudioHandoff.MAX_PENDING_MS + 10_000)
        assertEquals(null, resumeAt)
        assertFalse(handoff.isPending)
        handoff.onPlayers(0, AudioHandoff.MAX_PENDING_MS + 20_000)
        assertEquals(null, handoff.runUntil(AudioHandoff.MAX_PENDING_MS + 20_000, AudioHandoff.MAX_PENDING_MS + 40_000))
    }

    @Test
    fun theBrowserThatWasNotPlayingIsNeverStarted() {
        val handoff = AudioHandoff().apply { reset(playerCount = 0, browserPlaying = false) }
        handoff.onPlayers(1, 1_000)      // l'app suona, il browser era fermo
        handoff.onPlayers(0, 10_000)
        assertEquals(null, handoff.runUntil(10_000, 60_000))
        assertFalse(handoff.isPending)
    }
}
