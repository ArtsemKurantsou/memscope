package dev.memscope.server

import dev.memscope.analysis.DiffResult

/**
 * Computed diffs by (snapshot, base). A snapshot file never changes, so an entry stays valid after the
 * snapshots it was computed from have been evicted from the registry. Computations are serialised: a
 * diff holds two snapshots open at once, and two of them racing would hold each other's locks.
 * Only two are kept: a result is a few MB of objects on a large app.
 */
class DiffCache(private val capacity: Int = 2) {
    private val entries = object : LinkedHashMap<String, DiffResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DiffResult>) = size > capacity
    }
    private val computeLock = Any()

    fun cached(key: String): DiffResult? = synchronized(entries) { entries[key] }

    /** Drops every cached diff whose key matches [predicate] (keys are `<sid>/<snap>|<base>`). */
    fun removeIf(predicate: (String) -> Boolean) = synchronized(entries) { entries.keys.removeIf(predicate) }

    /** The cached result, or the result of [compute] (run at most once at a time, and not again for a key it just stored). */
    fun getOrCompute(key: String, compute: () -> DiffResult): DiffResult = cached(key) ?: synchronized(computeLock) {
        cached(key) ?: compute().also { synchronized(entries) { entries[key] = it } }
    }
}
