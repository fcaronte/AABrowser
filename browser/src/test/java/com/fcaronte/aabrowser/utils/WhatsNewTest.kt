package com.fcaronte.aabrowser.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {
    @Test
    fun versionsAreComparedNumberByNumber() {
        // "2.10" viene dopo "2.9": un confronto di stringhe direbbe il contrario
        assertTrue(WhatsNew.compareVersions("2.10", "2.9") > 0)
        assertTrue(WhatsNew.compareVersions("2.8", "2.7") > 0)
        assertTrue(WhatsNew.compareVersions("2.7", "2.8") < 0)
        assertEquals(0, WhatsNew.compareVersions("2.8", "2.8"))
        // Un numero in meno vale zero: 2.8 e 2.8.0 sono la stessa versione, 2.8.1 viene dopo
        assertEquals(0, WhatsNew.compareVersions("2.8", "2.8.0"))
        assertTrue(WhatsNew.compareVersions("2.8.1", "2.8") > 0)
        // Un pezzo che non è un numero (una sigla) conta zero
        assertEquals(0, WhatsNew.compareVersions("2.8-beta", "2.0"))
    }

    @Test
    fun sortingPutsTheNewestVersionFirst() {
        val versions = listOf("2.7", "2.10", "2.9", "3.0", "2.8")

        assertEquals(
            listOf("3.0", "2.10", "2.9", "2.8", "2.7"),
            versions.sortedWith { a, b -> WhatsNew.compareVersions(b, a) }
        )
    }
}
