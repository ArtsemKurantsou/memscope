package dev.memscope.capture

import dev.memscope.device.Adb
import dev.memscope.device.CancelToken
import dev.memscope.device.DeviceException
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.time.Duration.Companion.seconds

/**
 * Samples a process's memory into `<session>/meminfo.jsonl` while a session is active. One loop thread polls
 * `dumpsys meminfo` (about 150 ms per call). A poll that fails (timeout, no such process, no data) records nothing and
 * doubles the interval up to [MAX_INTERVAL_MS]; the next good poll restores it. While the app is held at a
 * breakpoint ([setPaused]) `dumpsys` would time out, so the poller reads `run-as <pkg> cat /proc/<pid>/smaps_rollup`
 * (FINDINGS A.7) and records a `paused` sample with only the total PSS fresh. At most one session is polled at a time.
 */
class MeminfoPoller(
    private val adb: Adb,
    private val store: SessionStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val monitor = Object()
    @Volatile private var thread: Thread? = null
    @Volatile private var cancel = CancelToken()
    @Volatile private var stopped = true
    @Volatile private var paused = false

    private var sessionId: String? = null
    private var processName = ""
    private var baseIntervalMs = 1000L
    private var last: MemSample? = null
    private var pid: Int? = null

    /** The wait before the next poll: the base interval, or the backed-off one after failures. */
    @Volatile var currentIntervalMs: Long = 1000
        private set

    /** True once a poll found its session ended or gone: nothing more will be recorded. */
    @Volatile var isFinished: Boolean = false
        private set

    /** Starts polling [processName] for [sessionId], replacing any earlier poller thread. */
    fun start(sessionId: String, processName: String, intervalMs: Long = 1000) {
        stop()
        begin(sessionId, processName, intervalMs)
        val token = cancel
        val t = Thread({
            while (!stopped && !token.isCancelled) {
                pollOnce()
                if (isFinished) break
                synchronized(monitor) {
                    if (!stopped && !token.isCancelled) monitor.wait(currentIntervalMs)
                }
            }
        }, "memscope-meminfo-poller").apply { isDaemon = true }
        thread = t
        t.start()
    }

    /** Sets the target without starting the loop (tests drive [pollOnce] themselves). */
    internal fun begin(sessionId: String, processName: String, intervalMs: Long) {
        synchronized(monitor) {
            this.sessionId = sessionId
            this.processName = processName
            baseIntervalMs = intervalMs
            currentIntervalMs = intervalMs
            last = null
            pid = null
            paused = false
            isFinished = false
            stopped = false
            cancel = CancelToken()
        }
    }

    /** Held at a milestone (true) or running (false); the next poll uses the matching source and happens at once. */
    fun setPaused(paused: Boolean) {
        synchronized(monitor) {
            if (this.paused == paused) return
            this.paused = paused
            monitor.notifyAll()
        }
    }

    /** Stops the loop and abandons a poll in flight. Idempotent. */
    fun stop() {
        val t = thread
        synchronized(monitor) {
            stopped = true
            cancel.cancel()
            monitor.notifyAll()
        }
        thread = null
        if (t != null && t !== Thread.currentThread()) t.join(2000)
    }

    /** One poll. True if a sample was recorded. */
    internal fun pollOnce(): Boolean {
        val id = sessionId ?: return false
        val token = cancel
        val session = try { store.get(id) } catch (_: Exception) { null }
        if (session == null || session.state != SessionMeta.State.ACTIVE) {
            isFinished = true
            return false
        }
        val tMs = clock() - session.createdAtMs
        val sample = try {
            if (paused) pausedSample(session, tMs, token) else runningSample(session, tMs, token)
        } catch (e: DeviceException) {
            null // timeout, device gone, adb died: the next poll tries again
        }
        if (sample == null) {
            pid = null
            currentIntervalMs = minOf(currentIntervalMs * 2, MAX_INTERVAL_MS)
            return false
        }
        currentIntervalMs = baseIntervalMs
        last = sample
        if (!token.isCancelled) append(id, sample)
        return !token.isCancelled
    }

    private fun runningSample(session: SessionMeta, tMs: Long, token: CancelToken): MemSample? {
        val r = adb.shell(session.serial, listOf("dumpsys", "meminfo", processName), POLL_TIMEOUT, token)
        return MeminfoParser.parse(r.stdout, tMs)
    }

    private fun pausedSample(session: SessionMeta, tMs: Long, token: CancelToken): MemSample? {
        val thePid = pid ?: adb.processes(session.serial, session.packageName).firstOrNull { it.name == processName }?.pid ?: return null
        val r = adb.shell(session.serial, listOf("run-as", session.packageName, "cat", "/proc/$thePid/smaps_rollup"), POLL_TIMEOUT, token)
        val total = (if (r.exitCode == 0) MeminfoParser.parseSmapsPss(r.stdout) else null) ?: return null
        pid = thePid
        val prev = last
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

/** Reads a session's timeline: samples from `meminfo.jsonl`, markers derived from its snapshots. */
object MemTimeline {
    const val FILE = "meminfo.jsonl"
    private val json = Json { ignoreUnknownKeys = true }

    /** Samples with `tMs > sinceMs` (all when null); markers are always complete. Unknown session: [NoSuchElementException]. */
    fun read(store: SessionStore, sessionId: String, sinceMs: Long?): Timeline {
        val session = store.get(sessionId) ?: throw NoSuchElementException("no such session: $sessionId")
        val file = store.dir(sessionId).resolve(FILE)
        val samples = if (!Files.isRegularFile(file)) emptyList() else Files.readAllLines(file).mapNotNull { line ->
            // a line being appended right now can be torn: skip what does not decode
            runCatching { json.decodeFromString(MemSample.serializer(), line) }.getOrNull()
        }.filter { sinceMs == null || it.tMs > sinceMs }
        val markers = session.snapshots.map {
            TimelineMarker(maxOf(0, it.takenAtMs - session.createdAtMs), if (it.milestoneId != null) "milestone" else "dump", it.label, it.id)
        }
        return Timeline(samples, markers)
    }
}
