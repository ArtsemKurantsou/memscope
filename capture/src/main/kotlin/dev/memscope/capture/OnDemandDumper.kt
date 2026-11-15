package dev.memscope.capture

import dev.memscope.device.Adb
import dev.memscope.device.DeviceException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.time.Duration.Companion.seconds

/** One-click heap dump of a running debuggable app: `am dumpheap` on the device, pull, validate, clean up. */
class OnDemandDumper(private val adb: Adb, private val store: SessionStore) {

    /**
     * Steps: "Check app", "Dump heap on device", "Pull dump", "Clean up". Timeouts: dump 180 s, pull 300 s.
     * Runs on the caller's thread. On success records an OK [SnapshotMeta] and completes [job] with the snapshot id.
     * On failure the job is marked (FAILED or CANCELLED), the exception is rethrown and nothing is recorded; the
     * remote file is always removed (best effort) and no partial local file is left.
     */
    fun dump(sessionId: String, processName: String, label: String, job: Job): SnapshotMeta {
        val session = store.get(sessionId) ?: throw NoSuchElementException("no such session: $sessionId")
        try {
            DeviceArgs.requireSerial(session.serial)
            DeviceArgs.requirePackage(session.packageName)
            DeviceArgs.requireProcessName(processName)
        } catch (e: IllegalArgumentException) {
            job.fail(e)
            throw e
        }
        val serial = session.serial
        val snapshotId = store.nextSnapshotId(sessionId)
        val remote = "/data/local/tmp/memscope-$sessionId-$snapshotId.hprof"
        val localFinal = store.snapshotFile(sessionId, snapshotId)
        val localPart = localFinal.resolveSibling("$snapshotId.hprof.part")
        var failure: Throwable? = null
        var meta: SnapshotMeta? = null
        var remoteTouched = false
        try {
            job.step(CHECK) { checkApp(serial, session.packageName, processName) }
            remoteTouched = true
            val takenAtMs = System.currentTimeMillis() // the dump is taken now, not when the pull ends (timeline marker position)
            job.step(DUMP) { dumpOnDevice(serial, processName, remote, job) }
            val size = job.step(PULL) { detail -> pullAndValidate(serial, remote, localPart, localFinal, job, detail) }
            meta = SnapshotMeta(
                snapshotId, label, null, processName, takenAtMs, localFinal.fileName.toString(),
                size, SnapshotMeta.Status.OK,
            )
        } catch (t: Throwable) {
            failure = t
        } finally {
            deleteQuietly(localPart)
            if (failure != null) deleteQuietly(localFinal)
            if (remoteTouched) cleanUp(serial, remote, job)
        }
        if (failure != null) {
            job.fail(failure)
            throw failure
        }
        val recorded = meta!!
        try {
            store.addSnapshot(sessionId, recorded)
        } catch (t: Throwable) {
            deleteQuietly(localFinal)
            job.fail(t)
            throw t
        }
        job.complete(snapshotId)
        return recorded
    }

    private fun checkApp(serial: String, packageName: String, processName: String) {
        adb.requireDebuggable(serial, packageName) // refuses before anything on the device is touched
        if (adb.processes(serial, packageName).none { it.name == processName }) {
            throw DeviceException(
                DeviceException.Kind.COMMAND_FAILED, "Process $processName is not running on $serial.",
                "Process $processName is not running: start the app first (and open the screen that starts that process), then retry.",
            )
        }
    }

    private fun dumpOnDevice(serial: String, processName: String, remote: String, job: Job) {
        val result = adb.shell(serial, listOf("am", "dumpheap", processName, remote), DUMP_TIMEOUT, job.cancel)
        if (result.exitCode != 0) {
            val text = result.stderr.trim().ifEmpty { result.stdout.trim() }.take(300)
            throw DeviceException(
                DeviceException.Kind.COMMAND_FAILED, "am dumpheap $processName failed (exit ${result.exitCode}): $text",
                "Check that the app is still running and the device has free storage in /data/local/tmp, then retry.",
            )
        }
    }

    private fun pullAndValidate(
        serial: String, remote: String, part: Path, final: Path, job: Job, detail: (String) -> Unit,
    ): Long {
        val poller = startProgressPoller(part, detail)
        try {
            adb.pull(serial, remote, part, PULL_TIMEOUT, job.cancel)
        } finally {
            poller.interrupt()
        }
        val size = Files.size(part)
        val expected = remoteSize(serial, remote)
        val problem = hprofProblem(part) ?: when {
            expected != null && expected != size -> "pulled $size bytes but the device file has $expected bytes"
            else -> null
        }
        if (problem != null) {
            deleteQuietly(part)
            throw DeviceException(
                DeviceException.Kind.COMMAND_FAILED, "Heap dump pull was incomplete: $problem.",
                "The transfer was cut short (the device may have disconnected or run low on space). Retry the dump.",
            )
        }
        Files.move(part, final, StandardCopyOption.REPLACE_EXISTING)
        return size
    }

    private fun remoteSize(serial: String, remote: String): Long? = try {
        adb.shell(serial, listOf("stat", "-c", "%s", remote), 10.seconds).stdout.trim().toLongOrNull()
    } catch (_: DeviceException) {
        null // size check is an extra safeguard only; the magic/size checks still apply
    }

    /** Never throws: a failed rm must not mask the real error or fail a good dump. */
    private fun cleanUp(serial: String, remote: String, job: Job) {
        try {
            job.step(CLEANUP) { detail ->
                try {
                    val r = adb.shell(serial, listOf("rm", "-f", remote), 15.seconds)
                    if (r.exitCode != 0) detail("could not remove $remote from the device")
                } catch (e: DeviceException) {
                    detail("could not remove $remote: ${e.message}")
                }
            }
        } catch (_: Throwable) { /* best effort */ }
    }

    private fun startProgressPoller(file: Path, detail: (String) -> Unit): Thread =
        Thread {
            try {
                while (true) {
                    Thread.sleep(500)
                    val size = try { Files.size(file) } catch (_: java.io.IOException) { continue }
                    detail("%.1f MB pulled".format(size / 1_048_576.0))
                }
            } catch (_: InterruptedException) { /* pull finished */ }
        }.apply { isDaemon = true; start() }

    private fun deleteQuietly(path: Path) {
        try { Files.deleteIfExists(path) } catch (_: java.io.IOException) { /* best effort */ }
    }

    companion object {
        const val CHECK = "Check app"
        const val DUMP = "Dump heap on device"
        const val PULL = "Pull dump"
        const val CLEANUP = "Clean up"
        val STEP_NAMES = listOf(CHECK, DUMP, PULL, CLEANUP)
        private val DUMP_TIMEOUT = 180.seconds
        private val PULL_TIMEOUT = 300.seconds
        private const val MIN_HPROF_BYTES = 1024L
        private val MAGIC = "JAVA PROFILE".toByteArray(Charsets.US_ASCII)

        /** Why a pulled [file] is not a usable heap dump (too small, no `JAVA PROFILE` header), or null if it looks fine. */
        internal fun hprofProblem(file: Path): String? {
            val size = Files.size(file)
            return when {
                size < MIN_HPROF_BYTES -> "the pulled file is only $size bytes"
                !Files.newInputStream(file).use { it.readNBytes(MAGIC.size) }.contentEquals(MAGIC) ->
                    "the pulled file is not a heap dump (missing JAVA PROFILE header)"
                else -> null
            }
        }
    }
}
