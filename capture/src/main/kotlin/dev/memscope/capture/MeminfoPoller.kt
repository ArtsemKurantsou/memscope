package dev.memscope.capture

import dev.memscope.device.Adb
import dev.memscope.device.CancelToken
import dev.memscope.device.DeviceException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.time.Duration.Companion.seconds

/**
 * Samples a process's memory into `<session>/meminfo.jsonl` while a session is active. One loop thread polls
 * `dumpsys meminfo` (about 150 ms per call). A poll that fails (timeout, no such process, no data) records nothing and
 * doubles the interval up to [MAX_INTERVAL_MS]; the next good poll restores it. While the app is held at a
 * breakpoint ([setPaused]) `dumpsys` would time out, so the poller reads `run-as <pkg> cat /proc/<pid>/smaps_rollup`
 * (FINDINGS A.7) and records a `paused` sample with only the total PSS fresh. Held intervals are recorded explicitly
 * (`held.jsonl`) at the moment [setPaused] is called, never inferred from samples. [setPaused] abandons a poll in
 * flight, discards its late result and polls again at once. At most one session is polled at a time.
 *
 * Every start owns its own [Run]: a thread that outlives [stop] can no longer write anything.
 */
class MeminfoPoller(
    private val adb: Adb,
    private val store: SessionStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Run(val sessionId: String, val processName: String, val baseIntervalMs: Long, val createdAtMs: Long) {
        @Volatile var stopped = false
        @Volatile var finished = false
        @Volatile var paused = false
        /** Bumped by every [setPaused] change: a poll that started under another epoch is stale. */
        @Volatile var epoch = 0
        @Volatile var wake = false
        @Volatile var inFlight: CancelToken? = null
        @Volatile var intervalMs = baseIntervalMs
        var last: MemSample? = null // poll thread only
        var pid: Int? = null        // poll thread only
    }

    private val monitor = Object()
    @Volatile private var run: Run? = null
    @Volatile private var thread: Thread? = null

    /** The wait before the next poll: the base interval, or the backed-off one after failures. */
    val currentIntervalMs: Long get() = run?.intervalMs ?: 1000

    /** True once a poll found its session ended or gone: nothing more will be recorded. */
    val isFinished: Boolean get() = run?.finished ?: false

    /** Starts polling [processName] for [sessionId], replacing any earlier run (which is stopped, not waited for). */
    fun start(sessionId: String, processName: String, intervalMs: Long = 1000) {
        halt()
        if (!begin(sessionId, processName, intervalMs)) return
        val r = run ?: return
        val t = Thread({
            while (!r.stopped) {
                pollOnce(r)
                if (r.finished) break
                synchronized(monitor) {
                    if (!r.stopped && !r.wake) monitor.wait(r.intervalMs)
                    r.wake = false
                }
            }
        }, "memscope-meminfo-poller").apply { isDaemon = true }
        thread = t
        t.start()
    }

    /** Sets the target without starting the loop (tests drive [pollOnce] themselves). False if the session is unknown. */
    internal fun begin(sessionId: String, processName: String, intervalMs: Long): Boolean {
        val session = store.get(sessionId) ?: return false
        halt()
        val r = Run(sessionId, processName, intervalMs, session.createdAtMs)
        run = r
        try { MemTimeline.endHeldIfOpen(store, sessionId, clock() - r.createdAtMs) } catch (_: Exception) { /* best effort */ }
        return true
    }

    /**
     * Held at a milestone (true) or running (false). Recorded as a held event at once, cancels a poll in flight (its
     * late result is discarded), and the loop polls again immediately with the matching source.
     */
    fun setPaused(paused: Boolean) {
        val r = run ?: return
        val tMs: Long
        synchronized(monitor) {
            if (r.stopped || r.paused == paused) return
            r.paused = paused
            r.epoch++
            r.intervalMs = r.baseIntervalMs
            r.wake = true
            r.inFlight?.cancel()
            tMs = clock() - r.createdAtMs
            monitor.notifyAll()
        }
        try { MemTimeline.appendHeld(store, r.sessionId, tMs, paused) } catch (_: Exception) { /* the band is lost, polling goes on */ }
    }

    /** Stops the loop and abandons a poll in flight; waits briefly for the thread. Idempotent. */
    fun stop() {
        val t = thread
        halt()
        if (t != null && t !== Thread.currentThread()) t.join(2000)
    }

    private fun halt() {
        val r = run
        synchronized(monitor) {
            if (r != null) {
                r.stopped = true
                r.inFlight?.cancel()
            }
            monitor.notifyAll()
        }
        thread = null
    }

    /** One poll of the current run. True if a sample was recorded. */
    internal fun pollOnce(): Boolean = run?.let { pollOnce(it) } ?: false

    private fun pollOnce(r: Run): Boolean {
        if (r.stopped) return false
        val session = try { store.get(r.sessionId) } catch (_: Exception) { null }
        if (session == null || session.state != SessionMeta.State.ACTIVE) {
            r.finished = true
            return false
        }
        val token = CancelToken()
        val epoch: Int
        val paused: Boolean
        synchronized(monitor) {
            epoch = r.epoch
            paused = r.paused
            r.inFlight = token
        }
        val tMs = clock() - r.createdAtMs
        val sample = try {
            if (paused) pausedSample(r, session, tMs, token) else runningSample(r, session, tMs, token)
        } catch (_: DeviceException) {
            null // timeout, cancelled, device gone, adb died: the next poll tries again
        }
        synchronized(monitor) {
            r.inFlight = null
            if (r.stopped || r.epoch != epoch) return false // a hold began or ended meanwhile: this result is stale, no backoff
            if (sample == null) {
                r.pid = null
                r.intervalMs = minOf(r.intervalMs * 2, MAX_INTERVAL_MS)
                return false
            }
            r.intervalMs = r.baseIntervalMs
            r.last = sample
            append(r.sessionId, sample)
            return true
        }
    }

    private fun runningSample(r: Run, session: SessionMeta, tMs: Long, token: CancelToken): MemSample? {
        val res = adb.shell(session.serial, listOf("dumpsys", "meminfo", r.processName), POLL_TIMEOUT, token)
        return MeminfoParser.parse(res.stdout, tMs)
    }

    private fun pausedSample(r: Run, session: SessionMeta, tMs: Long, token: CancelToken): MemSample? {
        val thePid = r.pid ?: adb.processes(session.serial, session.packageName).firstOrNull { it.name == r.processName }?.pid ?: return null
        val res = adb.shell(session.serial, listOf("run-as", session.packageName, "cat", "/proc/$thePid/smaps_rollup"), POLL_TIMEOUT, token)
        val total = (if (res.exitCode == 0) MeminfoParser.parseSmapsPss(res.stdout) else null) ?: return null
        r.pid = thePid
        val prev = r.last
        return (prev?.copy(tMs = tMs) ?: MemSample(tMs, 0, 0, 0, 0, 0, 0, 0)).copy(totalPssKb = total, paused = true)
    }

    private fun append(sessionId: String, sample: MemSample) {
        val line = json.encodeToString(MemSample.serializer(), sample) + "\n"
        Files.writeString(
            store.dir(sessionId).resolve(MemTimeline.FILE), line,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
    }

    private companion object {
        val POLL_TIMEOUT = 5.seconds
        const val MAX_INTERVAL_MS = 8000L
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}

@Serializable
private data class HeldEvent(val tMs: Long, val held: Boolean)

/**
 * Reads a session's timeline: samples from `meminfo.jsonl`, held intervals from `held.jsonl`, markers derived from the
 * snapshots of session.json. Files are read incrementally into a small per-session cache, so a request costs
 * O(new lines), not O(file).
 */
object MemTimeline {
    const val FILE = "meminfo.jsonl"
    const val HELD_FILE = "held.jsonl"
    /** A request without `sinceMs` (or after a long gap) returns at most this many samples. */
    const val MAX_POINTS = 2000
    private const val CACHED_SESSIONS = 4
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private class Tail<T>(val file: Path, val decode: (String) -> T?) {
        val items = ArrayList<T>()
        var offset = 0L

        /** Appends the complete lines written since the last call. */
        fun refresh() {
            if (!Files.isRegularFile(file)) { items.clear(); offset = 0; return }
            FileChannel.open(file, StandardOpenOption.READ).use { ch ->
                val size = ch.size()
                if (size < offset) { items.clear(); offset = 0 }
                if (size == offset) return
                val buf = java.nio.ByteBuffer.allocate((size - offset).toInt())
                ch.position(offset)
                while (buf.hasRemaining() && ch.read(buf) > 0) { /* fill */ }
                val bytes = buf.array().copyOf(buf.position())
                val end = bytes.lastIndexOf('\n'.code.toByte())
                if (end < 0) return // only a line being written right now
                String(bytes, 0, end, Charsets.UTF_8).lineSequence().forEach { line ->
                    // a torn or foreign line is skipped
                    decode(line)?.let(items::add)
                }
                offset += end + 1
            }
        }
    }

    private class Cache(sessionDir: Path) {
        val samples = Tail(sessionDir.resolve(FILE)) { runCatching { json.decodeFromString(MemSample.serializer(), it) }.getOrNull() }
        val held = Tail(sessionDir.resolve(HELD_FILE)) { runCatching { json.decodeFromString(HeldEvent.serializer(), it) }.getOrNull() }
    }

    private val caches = object : LinkedHashMap<String, Cache>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cache>) = size > CACHED_SESSIONS
    }

    private fun cache(store: SessionStore, id: String): Cache = caches.getOrPut(store.dir(id).toString()) { Cache(store.dir(id)) }

    /**
     * Samples with `tMs > sinceMs` (all when null), at most [MAX_POINTS] of them (bucketed, keeping each bucket's peak);
     * markers and held intervals are always complete. Unknown session: [NoSuchElementException].
     */
    fun read(store: SessionStore, sessionId: String, sinceMs: Long?): Timeline = synchronized(caches) {
        val session = store.get(sessionId) ?: throw NoSuchElementException("no such session: $sessionId")
        val c = cache(store, sessionId)
        c.samples.refresh()
        c.held.refresh()
        val all = c.samples.items
        val from = if (sinceMs == null) 0 else firstAfter(all, sinceMs)
        val samples = downsample(all.subList(from, all.size), MAX_POINTS)
        val markers = session.snapshots.map {
            TimelineMarker(maxOf(0, it.takenAtMs - session.createdAtMs), if (it.milestoneId != null) "milestone" else "dump", it.label, it.id)
        }
        Timeline(samples, markers, heldIntervals(c.held.items, session, all))
    }

    /** At most [max] samples in order; when thinning, each bucket keeps its sample with the highest total PSS. */
    fun downsample(samples: List<MemSample>, max: Int): List<MemSample> {
        if (samples.size <= max) return samples.toList()
        val out = ArrayList<MemSample>(max)
        val size = samples.size.toDouble() / max
        for (b in 0 until max) {
            val from = (b * size).toInt()
            val to = minOf(samples.size, ((b + 1) * size).toInt())
            var best = samples[from]
            for (i in from + 1 until to) if (samples[i].totalPssKb > best.totalPssKb) best = samples[i]
            out += best
        }
        return out
    }

    private fun firstAfter(samples: List<MemSample>, sinceMs: Long): Int {
        var lo = 0
        var hi = samples.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (samples[mid].tMs <= sinceMs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun heldIntervals(events: List<HeldEvent>, session: SessionMeta, samples: List<MemSample>): List<HeldInterval> {
        val out = ArrayList<HeldInterval>()
        var open: Long? = null
        for (e in events) {
            if (e.held && open == null) open = e.tMs
            else if (!e.held && open != null) { out += HeldInterval(open, maxOf(open, e.tMs)); open = null }
        }
        if (open != null) {
            // an ended session cannot still be held: close the interval where the data stops
            out += if (session.state == SessionMeta.State.ENDED) HeldInterval(open, maxOf(open, samples.lastOrNull()?.tMs ?: open)) else HeldInterval(open)
        }
        return out
    }

    internal fun appendHeld(store: SessionStore, sessionId: String, tMs: Long, held: Boolean) {
        val line = json.encodeToString(HeldEvent.serializer(), HeldEvent(tMs, held)) + "\n"
        Files.writeString(store.dir(sessionId).resolve(HELD_FILE), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    /** Closes a hold left open by an earlier run of the server. */
    internal fun endHeldIfOpen(store: SessionStore, sessionId: String, tMs: Long) {
        val file = store.dir(sessionId).resolve(HELD_FILE)
        if (!Files.isRegularFile(file)) return
        val last = Files.readAllLines(file).mapNotNull { runCatching { json.decodeFromString(HeldEvent.serializer(), it) }.getOrNull() }.lastOrNull()
        if (last != null && last.held) appendHeld(store, sessionId, maxOf(tMs, last.tMs), false)
    }
}
