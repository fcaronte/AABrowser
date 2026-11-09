package com.fcaronte.aabrowser.utils

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.fcaronte.aabrowser.settings.AdBlockSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * La lista dei siti pubblicitari dell'AdBlock, oltre ai pochi domini scritti in AdBlockHost: si sceglie nelle
 * Impostazioni tra quelle di [SOURCES] (HaGeZi Multi PRO di serie: sul sito di prova ha gli script dei pop-up che la
 * lista di AdGuard non aveva) o con l'indirizzo di una qualsiasi. Scaricata dall'app e rinnovata ogni tre giorni. Si
 * tengono solo le impronte dei domini (64 bit, ordinate): 2 MB per 250.000 domini, ricerca per dominio e domini che lo
 * contengono. Le eccezioni della lista ("@@") vincono.
 */
object AdBlockLists {

    class Source(val key: String, val name: String, val url: String)

    private const val HAGEZI = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/"
    const val DEFAULT_KEY = "hagezi_pro"
    const val CUSTOM_KEY = "custom"
    val SOURCES = listOf(
        Source(DEFAULT_KEY, "HaGeZi Multi PRO", "${HAGEZI}pro.txt"),
        Source("hagezi_pro_plus", "HaGeZi Multi PRO++", "${HAGEZI}pro.plus.txt"),
        Source("hagezi_normal", "HaGeZi Multi NORMAL", "${HAGEZI}multi.txt"),
        Source("adguard", "AdGuard DNS filter", "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"),
    )

    private const val REFRESH_MS = 3L * 24 * 60 * 60 * 1000
    private const val MAX_BYTES = 40L * 1024 * 1024
    private const val MAX_DOMAINS = 3_000_000
    private const val MAX_ADDRESS_LENGTH = 2000
    private const val TIMEOUT_MS = 30_000
    private const val FILE_NAME = "adblock_list.bin"
    private const val MAGIC = 0x41414232 // "AAB2"
    private const val TAG = "AdBlockLists"

    // Una lista scelta tra quelle note con meno domini di così è un errore del server, non una lista nuova
    private const val MIN_DOMAINS = 1000

    // FNV-1a a 64 bit
    private const val FNV_OFFSET = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L

    private val DOMAIN = Regex("[a-z0-9_-]+(\\.[a-z0-9_-]+)+")

    /** Per le Impostazioni: nome della lista, domini, quando è stata scaricata, aggiornamento in corso o non riuscito. */
    data class Info(
        val name: String = "",
        val count: Int = 0,
        val updatedAt: Long = 0L,
        val updating: Boolean = false,
        val failed: Boolean = false
    )

    private val _info = mutableStateOf(Info())
    val info: State<Info> = _info

    /** Impronte ordinate: domini bloccati ed eccezioni. */
    class Lists(val blocked: LongArray, val allowed: LongArray)

    @Volatile
    private var lists = Lists(LongArray(0), LongArray(0))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updating = AtomicBoolean(false)

    /** All'avvio dell'app: la lista salvata, poi una nuova se è vecchia, manca o non è quella scelta. */
    fun init(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val stored = load(app)
            val isCurrent = stored != null && stored.url == selected()?.url &&
                    System.currentTimeMillis() - stored.updatedAt <= REFRESH_MS
            if (!isCurrent) update(app)
        }
    }

    /** Il tasto "Aggiorna" delle Impostazioni. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch { update(app) }
    }

    /** Scelta nelle Impostazioni: quella di prima resta in uso finché la nuova non è arrivata. */
    fun choose(context: Context, key: String) {
        AdBlockSettings.setList(context, key)
        refresh(context)
    }

    fun isValidAddress(address: String): Boolean = address.length <= MAX_ADDRESS_LENGTH && UrlUtils.isWebUrl(address)

    private fun selected(): Source? {
        val key = AdBlockSettings.listKey.value
        if (key != CUSTOM_KEY) return SOURCES.firstOrNull { it.key == key } ?: SOURCES.first()
        val address = AdBlockSettings.customListUrl.value.trim()
        if (!isValidAddress(address)) return null
        return Source(CUSTOM_KEY, Uri.parse(address).host.orEmpty(), address)
    }

    /** L'host o un dominio che lo contiene è nella lista (e non nelle sue eccezioni). */
    fun contains(host: String): Boolean = matches(host.lowercase(), lists)

    fun matches(host: String, lists: Lists): Boolean {
        if (lists.blocked.isEmpty()) return false
        var start = 0
        var listed = false
        while (true) {
            val key = hash(host, start)
            if (lists.allowed.isNotEmpty() && lists.allowed.binarySearch(key) >= 0) return false
            if (!listed && lists.blocked.binarySearch(key) >= 0) listed = true
            // Fino al dominio di secondo livello: "com" da solo no
            val dot = host.indexOf('.', start)
            if (dot < 0 || host.indexOf('.', dot + 1) < 0) break
            start = dot + 1
        }
        return listed
    }

    /** Impronta del dominio [domain] a partire da [start] (i domini che contengono un host senza nuove stringhe). */
    fun hash(domain: String, start: Int = 0): Long {
        var h = FNV_OFFSET
        for (i in start until domain.length) {
            h = (h xor domain[i].code.toLong()) * FNV_PRIME
        }
        return h
    }

    /**
     * Le righe di una lista: "||dominio^" (anche con $important), "@@||dominio^" e "@@|host^|" (eccezioni),
     * "0.0.0.0 dominio" (file hosts), un dominio per riga. Saltate le regole con jolly, le espressioni regolari e le
     * altre opzioni; "$badfilter" toglie la regola uguale.
     */
    fun parse(lines: Sequence<String>): Lists {
        val blocked = LongBuffer()
        val allowed = LongBuffer()
        val removed = HashSet<Long>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '!' || line[0] == '#' || line[0] == '[') continue
            when {
                line.startsWith("@@") -> domainOf(line.substring(2))?.let { allowed.add(hash(it)) }
                line.endsWith("\$badfilter") -> domainOf(line.removeSuffix("\$badfilter"))?.let { removed.add(hash(it)) }
                else -> domainOf(line)?.let { blocked.add(hash(it)) }
            }
        }
        val blocks = blocked.sortedDistinct().let { all ->
            if (removed.isEmpty()) all else all.filter { it !in removed }.toLongArray()
        }
        return Lists(blocks, allowed.sortedDistinct())
    }

    private fun domainOf(rule: String): String? {
        val parts = rule.split(' ', '\t').filter { it.isNotEmpty() }
        var name = when {
            // File hosts: "0.0.0.0 dominio" (con un commento in fondo, a volte)
            parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::") -> parts[1]
            parts.size == 1 -> parts[0]
            else -> return null
        }
        name = name.removeSuffix("\$important").removePrefix("||").removePrefix("|").removeSuffix("|").removeSuffix("^")
        name = name.lowercase()
        if (!DOMAIN.matches(name) || name == "localhost" || name.endsWith(".localdomain")) return null
        return name
    }

    private class Stored(val url: String, val updatedAt: Long)

    // Lista salvata, null se non c'è
    private suspend fun load(context: Context): Stored? {
        val file = File(context.noBackupFilesDir, FILE_NAME)
        if (!file.exists()) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return null
                val url = input.readUTF()
                val name = input.readUTF()
                val updatedAt = input.readLong()
                val blocked = LongArray(input.readInt()) { input.readLong() }
                val allowed = LongArray(input.readInt()) { input.readLong() }
                lists = Lists(blocked, allowed)
                publish(Info(name, blocked.size, updatedAt))
                AppLog.d(TAG, "List loaded: $name, ${blocked.size} domains, ${allowed.size} exceptions")
                Stored(url, updatedAt)
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "List not loaded: ${e.javaClass.simpleName}")
            null
        }
    }

    private suspend fun update(context: Context) {
        if (!updating.compareAndSet(false, true)) return
        try {
            while (true) {
                val source = selected() ?: return
                download(context, source)
                // Se nel frattempo è stata scelta un'altra lista si scarica quella
                if (selected()?.url == source.url) return
            }
        } finally {
            updating.set(false)
        }
    }

    private suspend fun download(context: Context, source: Source) {
        publish(_info.value.copy(updating = true, failed = false))
        try {
            val connection = (URL(source.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "CarWeb")
            }
            val fresh = try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
                LimitedStream(connection.inputStream).bufferedReader().useLines { parse(it) }
            } finally {
                connection.disconnect()
            }
            // Quella scritta dall'utente può essere piccola, basta che dentro ci sia qualcosa
            val minimum = if (source.key == CUSTOM_KEY) 1 else MIN_DOMAINS
            if (fresh.blocked.size < minimum) throw IOException("Only ${fresh.blocked.size} domains")
            val updatedAt = System.currentTimeMillis()
            save(context, source, fresh, updatedAt)
            lists = fresh
            publish(Info(source.name, fresh.blocked.size, updatedAt))
            AppLog.d(TAG, "List updated: ${source.name}, ${fresh.blocked.size} domains, ${fresh.allowed.size} exceptions")
        } catch (e: Exception) {
            failed(e)
        } catch (e: OutOfMemoryError) {
            failed(e)
        }
    }

    private suspend fun failed(e: Throwable) {
        AppLog.w(TAG, "List not updated: ${e.javaClass.simpleName} ${e.message}")
        publish(_info.value.copy(updating = false, failed = true))
    }

    private fun save(context: Context, source: Source, fresh: Lists, updatedAt: Long) {
        val file = File(context.noBackupFilesDir, FILE_NAME)
        val temp = File(context.noBackupFilesDir, "$FILE_NAME.tmp")
        DataOutputStream(temp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeUTF(source.url)
            out.writeUTF(source.name)
            out.writeLong(updatedAt)
            out.writeInt(fresh.blocked.size)
            fresh.blocked.forEach { out.writeLong(it) }
            out.writeInt(fresh.allowed.size)
            fresh.allowed.forEach { out.writeLong(it) }
        }
        if (!temp.renameTo(file)) throw IllegalStateException("Rename failed")
    }

    private suspend fun publish(value: Info) = withContext(Dispatchers.Main) { _info.value = value }

    // Una lista scelta dall'utente può essere qualunque cosa: oltre questa dimensione si smette di leggere
    internal class LimitedStream(input: InputStream, private var left: Long = MAX_BYTES) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) count(1) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) count(it) }

        private fun count(bytes: Int) {
            left -= bytes
            if (left < 0) throw IOException("Too big")
        }
    }

    // LongArray che cresce, senza un Long per oggetto
    private class LongBuffer {
        private var data = LongArray(1024)
        private var size = 0

        fun add(value: Long) {
            if (size >= MAX_DOMAINS) throw IOException("Too many domains")
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun sortedDistinct(): LongArray {
            val sorted = data.copyOf(size).also { it.sort() }
            if (sorted.isEmpty()) return sorted
            var count = 1
            for (i in 1 until sorted.size) if (sorted[i] != sorted[count - 1]) sorted[count++] = sorted[i]
            return sorted.copyOf(count)
        }
    }
}
