package dev.memscope.server

import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Opened snapshots, least recently used evicted beyond [capacity] and closed. A query holds the entry's
 * read lock, so eviction waits for it, and a query that lost the race to eviction sees "not analysed"
 * instead of a closed snapshot.
 */
class SnapshotRegistry(private val capacity: Int = 2) : AutoCloseable {
    private class Entry(val view: SnapshotView) {
        val lock = ReentrantReadWriteLock()
        var evicted = false
    }

    private val entries = LinkedHashMap<String, Entry>(4, 0.75f, true)

    fun contains(key: String): Boolean = synchronized(entries) { entries.containsKey(key) }

    fun put(key: String, view: SnapshotView) {
        val dropped = synchronized(entries) {
            val old = entries.put(key, Entry(view))
            val out = ArrayList<Entry>()
            if (old != null) out += old
            while (entries.size > capacity) {
                val eldest = entries.entries.first()
                entries.remove(eldest.key)
                out += eldest.value
            }
            out
        }
        dropped.forEach(::retire)
    }

    /**
     * Closes least-recently-used snapshots until one more fits, so a build about to start never runs while the
     * registry still holds [capacity] indexes (three large indexes do not fit in the heap at once).
     */
    fun makeRoomForOne() {
        val dropped = synchronized(entries) {
            val out = ArrayList<Entry>()
            while (entries.isNotEmpty() && entries.size >= capacity) {
                val eldest = entries.entries.first()
                entries.remove(eldest.key)
                out += eldest.value
            }
            out
        }
        dropped.forEach(::retire)
    }

    /** Closes and forgets the snapshot under [key], if open (waits for queries running on it). */
    fun remove(key: String) {
        val entry = synchronized(entries) { entries.remove(key) } ?: return
        retire(entry)
    }

    /** Keys of the open snapshots, least recently used first. */
    fun keys(): List<String> = synchronized(entries) { entries.keys.toList() }

    /** Runs [block] on the snapshot under [key]; throws [notAnalysed] if it is not (or no longer) open. */
    fun <T> use(key: String, block: (SnapshotView) -> T): T {
        val entry = synchronized(entries) { entries[key] } ?: throw notAnalysed()
        entry.lock.readLock().lock()
        try {
            if (entry.evicted) throw notAnalysed()
            return try {
                block(entry.view)
            } catch (e: IllegalStateException) {
                if (e.message == "snapshot closed") throw notAnalysed() else throw e
            }
        } finally {
            entry.lock.readLock().unlock()
        }
    }

    private fun retire(entry: Entry) {
        entry.lock.writeLock().lock()
        try {
            entry.evicted = true
            entry.view.close()
        } finally {
            entry.lock.writeLock().unlock()
        }
    }

    override fun close() {
        val all = synchronized(entries) { entries.values.toList().also { entries.clear() } }
        all.forEach(::retire)
    }

    companion object {
        fun notAnalysed() = ApiException(
            io.ktor.http.HttpStatusCode.Conflict, "not analysed",
            "Open the snapshot first (POST /api/snapshots/{sid}/{snapId}/open); only the two most recent stay open.", "NOT_ANALYSED",
        )
    }
}
