package com.fcaronte.aabrowser.utils

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import javax.xml.parsers.SAXParserFactory

/**
 * Lettura dei feed RSS dei podcast e dei file OPML, un pezzo alla volta: ci sono feed da 29 MB e 6000 episodi, quindi ci
 * si ferma dopo [MAX_EPISODES] (i feed mettono prima i più recenti) e il testo che non serve non si tiene.
 */
internal object PodcastFeed {
    const val MAX_EPISODES = 300
    private const val MAX_FEEDS_IN_FILE = 1000
    private const val MAX_TEXT = 8 * 1024
    private const val HEAD_BYTES = 512

    data class Episode(
        val id: String,
        val title: String,
        val url: String,
        /** Millisecondi; 0 = data sconosciuta. */
        val publishedAt: Long,
        /** Secondi; 0 = durata sconosciuta. */
        val duration: Int,
        val image: String?,
    )

    /** [serial]: puntate da ascoltare in ordine (itunes:type "serial"), dalla prima. */
    data class Channel(
        val title: String,
        val author: String,
        val image: String?,
        val episodes: List<Episode>,
        val serial: Boolean = false,
    )

    /** [headOnly]: solo i dati del podcast, senza scaricare gli episodi. */
    fun parse(input: InputStream, maxEpisodes: Int = MAX_EPISODES, headOnly: Boolean = false): Channel {
        val handler = FeedHandler(maxEpisodes, headOnly)
        run(input, handler)
        require(handler.isFeed) { "Not an RSS feed" }
        return Channel(handler.title.trim(), handler.author.trim(), handler.image, handler.episodes, handler.serial)
    }

    /** Nome e indirizzo dei feed di un file OPML (anche nelle cartelle), senza doppioni. */
    fun parseOpml(input: InputStream): List<Pair<String, String>> {
        val handler = OpmlHandler()
        run(input, handler)
        return handler.feeds.distinctBy { it.second }
    }

    private class Stop : SAXException()

    private abstract class StoppingHandler : DefaultHandler() {
        var stopped = false

        fun stop(): Nothing {
            stopped = true
            throw Stop()
        }

        // Niente entità esterne: un feed non deve poter leggere file o indirizzi per conto dell'app
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))
    }

    private fun run(input: InputStream, handler: StoppingHandler) {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        try {
            factory.newSAXParser().parse(InputSource(reader(input)), handler)
        } catch (e: SAXException) {
            if (!handler.stopped) throw e
        }
    }

    // Il parser di Android conosce poche codifiche: si legge quella dichiarata nel file e gli si passa già il testo.
    // Fuori anche gli spazi prima di <?xml, che alcuni server aggiungono e che il parser rifiuta
    private fun reader(input: InputStream): InputStreamReader {
        val stream = BufferedInputStream(input, HEAD_BYTES * 2)
        stream.mark(HEAD_BYTES)
        val head = ByteArray(HEAD_BYTES)
        var length = 0
        while (length < HEAD_BYTES) {
            val read = stream.read(head, length, HEAD_BYTES - length)
            if (read < 0) break
            length += read
        }
        stream.reset()
        fun byteAt(index: Int) = if (index < length) head[index].toInt() and 0xff else -1
        val (skip, bom) = when {
            byteAt(0) == 0xEF && byteAt(1) == 0xBB && byteAt(2) == 0xBF -> 3 to Charsets.UTF_8
            byteAt(0) == 0xFE && byteAt(1) == 0xFF -> 2 to Charsets.UTF_16BE
            byteAt(0) == 0xFF && byteAt(1) == 0xFE -> 2 to Charsets.UTF_16LE
            else -> 0 to null
        }
        var blanks = 0
        if (bom == null || bom == Charsets.UTF_8) {
            while (skip + blanks < length && Char(byteAt(skip + blanks)).isWhitespace()) blanks++
        }
        stream.skip((skip + blanks).toLong())
        val declared = Regex("""encoding\s*=\s*["']([\w.:-]+)["']""")
            .find(String(head, 0, length, Charsets.ISO_8859_1))?.groupValues?.get(1)
        val charset = bom ?: declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        return InputStreamReader(stream, charset)
    }

    private fun Attributes.named(name: String): String? =
        (0 until length).firstOrNull { getLocalName(it).equals(name, ignoreCase = true) }?.let { getValue(it) }

    internal fun parseDuration(value: String): Int {
        val parts = value.trim().split(':')
        if (parts.size > 3) return 0
        var seconds = 0.0
        for (part in parts) {
            val number = part.trim().toDoubleOrNull() ?: return 0
            seconds = seconds * 60 + number
        }
        return seconds.toInt().coerceAtLeast(0)
    }

    private class FeedHandler(private val maxEpisodes: Int, private val headOnly: Boolean) : StoppingHandler() {
        var isFeed = false
        var title = ""
        var author = ""
        var image: String? = null
        var serial = false
        val episodes = ArrayList<Episode>()

        private val dates = DATE_PATTERNS.map { SimpleDateFormat(it, Locale.ENGLISH) }
        private var inChannel = false
        private var inItem = false
        private var inChannelImage = false
        private var capturing = false
        private val text = StringBuilder()

        private var itemTitle = ""
        private var itemGuid = ""
        private var itemDate = ""
        private var itemDuration = ""
        private var itemUrl: String? = null
        private var itemImage: String? = null

        private fun isItunes(uri: String) = uri.contains("itunes.com", ignoreCase = true)

        private fun capture() {
            capturing = true
            text.setLength(0)
        }

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            when {
                uri.isEmpty() && localName == "channel" -> {
                    inChannel = true
                    isFeed = true
                }
                !inChannel -> Unit
                uri.isEmpty() && localName == "item" -> {
                    if (headOnly || episodes.size >= maxEpisodes) stop()
                    inItem = true
                    itemTitle = ""
                    itemGuid = ""
                    itemDate = ""
                    itemDuration = ""
                    itemUrl = null
                    itemImage = null
                }
                isItunes(uri) && localName == "image" -> {
                    val href = attributes.named("href")?.takeIf(::isWebAddress)
                    if (inItem) itemImage = href else image = href ?: image
                }
                inItem && uri.isEmpty() && localName == "enclosure" -> {
                    val url = attributes.named("url")?.trim()?.takeIf(::isWebAddress)
                    val type = attributes.named("type").orEmpty()
                    if (itemUrl == null && url != null && (type.isBlank() || type.startsWith("audio/") || type.startsWith("video/"))) {
                        itemUrl = url
                    }
                }
                inItem && (uri.isEmpty() && localName in ITEM_TEXT || isItunes(uri) && localName == "duration") -> capture()
                !inItem && uri.isEmpty() && localName == "image" -> inChannelImage = true
                inChannelImage && uri.isEmpty() && localName == "url" -> capture()
                !inItem && !inChannelImage && (uri.isEmpty() && localName == "title" || isItunes(uri) && localName in CHANNEL_ITUNES) -> capture()
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (capturing && text.length < MAX_TEXT) text.append(ch, start, length)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            val value = if (capturing) text.toString().trim() else ""
            capturing = false
            when {
                !inChannel -> Unit
                uri.isEmpty() && localName == "item" -> {
                    inItem = false
                    finishItem()
                }
                inItem && uri.isEmpty() && localName == "title" -> itemTitle = value
                inItem && uri.isEmpty() && localName == "guid" -> itemGuid = value
                inItem && uri.isEmpty() && localName == "pubDate" -> itemDate = value
                inItem && isItunes(uri) && localName == "duration" -> itemDuration = value
                inItem -> Unit
                uri.isEmpty() && localName == "image" -> inChannelImage = false
                inChannelImage && uri.isEmpty() && localName == "url" -> {
                    if (image == null && isWebAddress(value)) image = value
                }
                inChannelImage -> Unit
                uri.isEmpty() && localName == "title" && title.isEmpty() -> title = value
                isItunes(uri) && localName == "author" && author.isEmpty() -> author = value
                isItunes(uri) && localName == "type" -> serial = value.equals("serial", ignoreCase = true)
            }
        }

        private fun finishItem() {
            val url = itemUrl ?: return
            episodes += Episode(
                id = UUID.nameUUIDFromBytes(itemGuid.ifEmpty { url }.toByteArray(Charsets.UTF_8)).toString(),
                title = itemTitle.ifEmpty { url.substringAfterLast('/') },
                url = url,
                publishedAt = parseDate(itemDate),
                duration = parseDuration(itemDuration),
                image = itemImage,
            )
        }

        private fun parseDate(value: String): Long {
            if (value.isEmpty()) return 0
            for (format in dates) {
                try {
                    return format.parse(value)?.time ?: continue
                } catch (_: java.text.ParseException) {
                    // Il formato dopo
                }
            }
            return 0
        }
    }

    private class OpmlHandler : StoppingHandler() {
        val feeds = ArrayList<Pair<String, String>>()

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            if (!localName.equals("outline", ignoreCase = true)) return
            val url = attributes.named("xmlUrl")?.trim()?.takeIf(::isWebAddress) ?: return
            val name = attributes.named("text")?.takeIf(String::isNotBlank) ?: attributes.named("title").orEmpty()
            feeds += name.trim() to url
            if (feeds.size >= MAX_FEEDS_IN_FILE) stop()
        }
    }

    private val ITEM_TEXT = setOf("title", "guid", "pubDate")
    private val CHANNEL_ITUNES = setOf("author", "type")

    private val DATE_PATTERNS = listOf(
        "EEE, d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm:ss z",
        "d MMM yyyy HH:mm:ss Z",
        "d MMM yyyy HH:mm:ss z",
        "EEE, d MMM yyyy HH:mm Z",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd",
    )

    private fun isWebAddress(value: String): Boolean =
        value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)
}
