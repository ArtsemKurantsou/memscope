package dev.memscope.capture

import dev.memscope.device.Adb
import dev.memscope.device.AdbLocator
import dev.memscope.device.DeviceException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * Needs emulator-5554 with the fixture app. Run with `./gradlew :capture:emulatorTest`.
 * The emulator and fixture app are shared with other test runs: launch right before dumping, retry once if the
 * process vanished, and remove only our own remote files (names contain our session id).
 */
@Tag("emulator")
class OnDemandDumperEmulatorTest {
    @TempDir lateinit var tmp: Path
    private val serial = "emulator-5554"
    private val pkg = "dev.memscope.fixture"
    private val adb = Adb(AdbLocator.find())

    @Test
    fun `dumps main and remote process of the fixture app`() {
        val installed = adb.shell(serial, listOf("pm", "list", "packages", pkg)).stdout.lineSequence().map { it.trim().removePrefix("package:") }
        if (pkg !in installed) throw AssertionError("fixture app not installed: run fixture-app/build-and-install.sh")

        val store = SessionStore(tmp)
        val session = store.create(serial, pkg, SessionMeta.Mode.ATTACH)
        val dumper = OnDemandDumper(adb, store)

        val main = dumpWithRetry(dumper, session.id, pkg, "main")
        val remote = dumpWithRetry(dumper, session.id, "$pkg:remote", "remote")

        for (meta in listOf(main, remote)) {
            val file = store.snapshotFile(session.id, meta.id)
            assertTrue(Files.size(file) > 15L * 1024 * 1024, "dump too small: ${Files.size(file)}")
            assertEquals(Files.size(file), meta.sizeBytes)
            assertEquals("JAVA PROFILE", String(Files.newInputStream(file).use { it.readNBytes(12) }, Charsets.US_ASCII))
            val leftover = adb.shell(serial, listOf("ls", "/data/local/tmp/memscope-${session.id}-${meta.id}.hprof"))
            assertTrue(leftover.exitCode != 0, "remote file still present")
        }
        val onDisk = SessionStore(tmp).get(session.id)!!
        assertEquals(listOf("s1", "s2"), onDisk.snapshots.map { it.id })
        assertEquals(listOf(pkg, "$pkg:remote"), onDisk.snapshots.map { it.processName })
        assertFalse(Files.list(store.dir(session.id)).use { s -> s.anyMatch { it.fileName.toString().endsWith(".part") } })
    }

    private fun dumpWithRetry(dumper: OnDemandDumper, sessionId: String, process: String, label: String): SnapshotMeta {
        var last: DeviceException? = null
        repeat(2) {
            launchFixture()
            val job = Job("j", "Heap dump", OnDemandDumper.STEP_NAMES)
            try {
                return dumper.dump(sessionId, process, label, job)
            } catch (e: DeviceException) {
                last = e // process may have been force-stopped by a concurrent test run: relaunch and retry once
            }
        }
        throw last!!
    }

    /** Force-stops and starts the fixture, then waits for its `ready` log line from that very process. */
    private fun launchFixture() {
        adb.shell(serial, listOf("am", "force-stop", pkg))
        adb.shell(serial, listOf("am", "start", "-n", "$pkg/.MainActivity"))
        val deadline = System.nanoTime() + 60_000_000_000L
        while (System.nanoTime() < deadline) {
            val pids = adb.processes(serial, pkg)
            val main = pids.firstOrNull { it.name == pkg }
            if (main != null && pids.any { it.name == "$pkg:remote" }) {
                val log = adb.shell(serial, listOf("logcat", "-d", "--pid=${main.pid}", "-s", "MemScopeFixture:I"), 10.seconds).stdout
                if (log.lineSequence().any { it.trimEnd().endsWith("ready") }) return
            }
            Thread.sleep(500)
        }
        throw AssertionError("fixture did not log 'MemScopeFixture: ready' within 60 s")
    }
}
