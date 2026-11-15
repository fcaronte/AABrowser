package com.fcaronte.aabrowser.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Le radio passate dal tramite dell'app a un elemento audio. La WebView prende available() come lunghezza del file e il
 * lettore multimediale si ferma lì: prima la radio finiva dopo circa due secondi (32 KB di buffer).
 */
class IptvLiveStreamTest {

    @Test
    fun aRadioDeclaresTheLargestLengthWhateverIsBuffered() {
        val stream = IptvProxy.LiveStream(ByteArrayInputStream(ByteArray(10)))
        assertEquals(Int.MAX_VALUE, stream.available())
    }

    @Test
    fun skippingConsumesNothing() {
        val data = ByteArray(100) { (it + 1).toByte() }
        val stream = IptvProxy.LiveStream(ByteArrayInputStream(data))
        // Una richiesta che ripartisse molto più avanti non deve far leggere e buttare megabyte di una radio dal vivo
        assertEquals(5_000_000L, stream.skip(5_000_000L))
        assertEquals(1, stream.read())
        assertEquals(0L, stream.skip(0))
        assertEquals(0L, stream.skip(-3))
        assertEquals(2, stream.read())
    }

    @Test
    fun dataAndCloseGoThroughToTheRealStream() {
        var closed = false
        val upstream = object : InputStream() {
            private val source = ByteArrayInputStream(byteArrayOf(7, 8, 9))
            override fun read(): Int = source.read()
            override fun close() {
                closed = true
            }
        }
        val stream = IptvProxy.LiveStream(upstream)
        val buffer = ByteArray(8)
        assertEquals(3, stream.read(buffer, 0, buffer.size))
        assertEquals(listOf<Byte>(7, 8, 9), buffer.take(3))
        assertEquals(-1, stream.read())
        stream.close()
        assertTrue(closed)
    }
}
