package com.fcaronte.aabrowser.utils

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * La lista dei siti pubblicitari dell'AdBlock, oltre ai pochi domini scritti in AdBlockHost: l'AdGuard DNS filter
 * (circa 190.000 domini di pubblicità, tracciamento e pop-up, anche quelli che cambiano spesso nome), scaricata
 * dall'app e rinnovata ogni tre giorni. Si tengono solo le impronte dei domini (64 bit, ordinate): circa 1,5 MB in
 * memoria, ricerca per dominio e domini che lo contengono. Le eccezioni della lista ("@@") vincono.
 */
object AdBlockLists {

    const val SOURCE_NAME = "AdGuard DNS filter"
    private const val SOURCE_URL = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"
    private const val REFRESH_MS = 3L * 24 * 60 * 60 * 1000
    private const val MAX_BYTES = 40L * 1024 * 1024
    private const val TIMEOUT_MS = 30_000
    private const val FILE_NAME = "adblock_list.bin"
    private const val MAGIC = 0x41414231 // "AAB1"
    private const val TAG = "AdBlockLists"

    // FNV-1a a 64 bit
    private const val FNV_OFFSET = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L

    private val DOMAIN = Regex("[a-z0-9_-]+(\\.[a-z0-9_-]+)+")

    /** Per le Impostazioni: domini nella lista, quando è stata scaricata, aggiornamento in corso o non riuscito. */
    data class Info(val count: Int = 0, val updatedAt: Long = 0L, val updating: Boolean = false, val failed: Boolean = false)

    private val _info = mutableStateOf(Info())
    val info: State<Info> = _info

    /** Impronte ordinate: domini bloccati ed eccezioni. */
    class Lists(val blocked: LongArray, val allowed: LongArray)

    @Volatile
    private var lists = Lists(LongArray(0), LongArray(0))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updating = AtomicBoolean(false)

    /** All'avvio dell'app: la lista salvata, poi una nuova se è vecchia o manca. */
    fun init(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val updatedAt = load(app)
            if (System.currentTimeMillis() - updatedAt > REFRESH_MS) update(app)
        }
    }

    /** Il tasto "Aggiorna" delle Impostazioni. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch { update(app) }
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

    // Lista salvata: data di quando è stata scaricata, 0 se non c'è
    private suspend fun load(context: Context): Long {
        val file = File(context.noBackupFilesDir, FILE_NAME)
        if (!file.exists()) return 0L
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return 0L
                val updatedAt = input.readLong()
                val blocked = LongArray(input.readInt()) { input.readLong() }
                val allowed = LongArray(input.readInt()) { input.readLong() }
                lists = Lists(blocked, allowed)
                publish(Info(blocked.size, updatedAt))
                AppLog.d(TAG, "List loaded: ${blocked.size} domains, ${allowed.size} exceptions")
                updatedAt
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "List not loaded: ${e.javaClass.simpleName}")
            0L
        }
    }

    private suspend fun update(context: Context) {
        if (!updating.compareAndSet(false, true)) return
        publish(_info.value.copy(updating = true, failed = false))
        try {
            val connection = (URL(SOURCE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "CarWeb")
            }
            val fresh = try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP ${connection.responseCode}")
                val length = connection.contentLengthLong
                if (length > MAX_BYTES) throw IllegalStateException("Too big")
                connection.inputStream.bufferedReader().useLines { parse(it) }
            } finally {
                connection.disconnect()
            }
            // Una lista vuota o quasi è un errore del server, non una lista nuova
            if (fresh.blocked.size < 1000) throw IllegalStateException("Only ${fresh.blocked.size} domains")
            val updatedAt = System.currentTimeMillis()
            save(context, fresh, updatedAt)
            lists = fresh
            publish(Info(fresh.blocked.size, updatedAt))
            AppLog.d(TAG, "List updated: ${fresh.blocked.size} domains, ${fresh.allowed.size} exceptions")
        } catch (e: Exception) {
            AppLog.w(TAG, "List not updated: ${e.javaClass.simpleName} ${e.message}")
            publish(_info.value.copy(updating = false, failed = true))
        } finally {
            updating.set(false)
        }
    }

    private fun save(context: Context, fresh: Lists, updatedAt: Long) {
        val file = File(context.noBackupFilesDir, FILE_NAME)
        val temp = File(context.noBackupFilesDir, "$FILE_NAME.tmp")
        DataOutputStream(temp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeLong(updatedAt)
            out.writeInt(fresh.blocked.size)
            fresh.blocked.forEach { out.writeLong(it) }
            out.writeInt(fresh.allowed.size)
            fresh.allowed.forEach { out.writeLong(it) }
        }
        if (!temp.renameTo(file)) throw IllegalStateException("Rename failed")
    }

    private suspend fun publish(value: Info) = withContext(Dispatchers.Main) { _info.value = value }

    // LongArray che cresce, senza un Long per oggetto
    private class LongBuffer {
        private var data = LongArray(1024)
        private var size = 0

        fun add(value: Long) {
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
