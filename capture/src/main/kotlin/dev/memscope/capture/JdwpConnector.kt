package dev.memscope.capture

import dev.memscope.device.Adb
import dev.memscope.device.CancelToken
import dev.memscope.device.DeviceException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Finds the pid of a freshly launched debuggable process (spike/FINDINGS.md A.1): `adb track-jdwp` runs from before
 * the launch, and each new pid's `/proc/<pid>/cmdline` is polled until it is no longer `zygote*` / `usap*` /
 * `<pre-initialized>`.
 */
class JdwpConnector(private val adb: Adb, private val pollMs: Long = 50) {

    /** Starts `adb track-jdwp` and returns once its first pid list (the baseline) arrived. */
    fun track(serial: String, baselineTimeout: Duration, cancel: CancelToken): JdwpTracker {
        val tracker = JdwpTracker(adb, serial, pollMs)
        try {
            tracker.awaitBaseline(baselineTimeout, cancel)
        } catch (t: Throwable) {
            tracker.close()
            throw t
        }
        return tracker
    }
}

/** A running `adb track-jdwp`. Not thread-safe: one caller polls it. */
class JdwpTracker internal constructor(private val adb: Adb, private val serial: String, private val pollMs: Long) : AutoCloseable {
    private val file: Path = Files.createTempFile("memscope-track-jdwp", ".bin")
    private val stop = CancelToken()
    private val error = AtomicReference<Throwable>()
    private val thread = Thread({
        try {
            adb.trackJdwp(serial, file, MAX_RUN, stop)
        } catch (t: Throwable) {
            error.set(t)
        }
    }, "memscope-track-jdwp").apply { isDaemon = true; start() }
    private var baseline: Set<Int>? = null
    private val checked = HashSet<Int>()

    internal fun awaitBaseline(timeout: Duration, cancel: CancelToken) {
        poll(timeout, cancel, { "adb track-jdwp gave no process list within $timeout" }) {
            parseTrackJdwp(Files.readAllBytes(file)).firstOrNull()?.also { baseline = it }
        }
    }

    /** Waits until a new debuggable process named [processName] appears; returns its pid. Other new processes are ignored. */
    fun awaitProcess(processName: String, timeout: Duration, cancel: CancelToken): Int {
        val base = checkNotNull(baseline) { "baseline not read" }
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        return poll(timeout, cancel, { "Process $processName did not start within $timeout" }) {
            val seen = parseTrackJdwp(Files.readAllBytes(file)).drop(1).flatten().distinct()
            seen.filter { it !in base && it !in checked }.firstNotNullOfOrNull { pid ->
                val name = resolveName(pid, deadline, cancel)
                checked += pid
                pid.takeIf { name == processName }
            }
        }
    }

    /** The process name once it is past zygote specialisation, or null if the process is gone or the deadline passed. */
    private fun resolveName(pid: Int, deadline: Long, cancel: CancelToken): String? {
        while (System.nanoTime() < deadline) {
            if (cancel.isCancelled) throw cancelled()
            val r = adb.shell(serial, listOf("cat", "/proc/$pid/cmdline"), 5.seconds, cancel)
            if (r.exitCode != 0) return null
            val name = r.stdout.substringBefore('\u0000').trim()
            if (name.isNotEmpty() && name != "<pre-initialized>" && !name.startsWith("zygote") && !name.startsWith("usap")) return name
            Thread.sleep(pollMs / 2)
        }
        return null
    }

    private fun <T : Any> poll(timeout: Duration, cancel: CancelToken, timeoutMessage: () -> String, attempt: () -> T?): T {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (true) {
            if (cancel.isCancelled) throw cancelled()
            error.get()?.let { throw it }
            attempt()?.let { return it }
            if (System.nanoTime() >= deadline) {
                throw DeviceException(
                    DeviceException.Kind.TIMEOUT, timeoutMessage(),
                    "Check that the launch target starts the app and that this process name is right (it must be the exact name shown by 'adb shell ps'), then retry.",
                )
            }
            Thread.sleep(pollMs)
        }
    }

    private fun cancelled() = DeviceException(DeviceException.Kind.CANCELLED, "Startup recording was cancelled", "The operation was cancelled.")

    override fun close() {
        stop.cancel()
        thread.join(2_000)
        try { Files.deleteIfExists(file) } catch (_: java.io.IOException) { /* best effort */ }
    }

    private companion object {
        /** Upper bound only; the tracker is closed as soon as the pid is known. */
        val MAX_RUN = 10.minutes
    }
}

/** Decodes the `track-jdwp` stream: each message is 4 hex digits of length followed by newline-separated pids. */
internal fun parseTrackJdwp(bytes: ByteArray): List<Set<Int>> {
    val out = ArrayList<Set<Int>>()
    var pos = 0
    while (pos + 4 <= bytes.size) {
        val len = String(bytes, pos, 4, Charsets.US_ASCII).toIntOrNull(16) ?: break
        if (pos + 4 + len > bytes.size) break
        val body = String(bytes, pos + 4, len, Charsets.US_ASCII)
        out += body.lineSequence().mapNotNull { it.trim().toIntOrNull() }.toSet()
        pos += 4 + len
    }
    return out
}
