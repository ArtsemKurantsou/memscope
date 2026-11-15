package dev.memscope.capture

import dev.memscope.analysis.HeapIndex
import dev.memscope.analysis.HeapIndexer
import dev.memscope.device.Adb
import dev.memscope.device.AdbLocator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * Startup recordings of the fixture app on emulator-5554. Run with `./gradlew :capture:emulatorTest`.
 * Every test leaves the device clean (no debug app, no JDWP forward, no dump left in the app's cache), also on failure.
 */
@Tag("emulator")
class StartupSessionEmulatorTest {
    @TempDir lateinit var tmp: Path
    private val serial = "emulator-5554"
    private val pkg = "dev.memscope.fixture"
    private val adb = Adb(AdbLocator.find())
    private val mb = 1024 * 1024

    @BeforeEach
    fun requireFixture() {
        val installed = adb.shell(serial, listOf("pm", "list", "packages", pkg)).stdout.lineSequence().map { it.trim().removePrefix("package:") }
        if (pkg !in installed) throw AssertionError("fixture app not installed: run fixture-app/build-and-install.sh")
        cleanDevice()
    }

    @AfterEach
    fun cleanDevice() {
        adb.shell(serial, listOf("am", "clear-debug-app"))
        // only startup sessions create jdwp forwards on this device
        val forwards = forwardList()
        forwards.lineSequence().map { it.trim().split(Regex("\\s+")) }
            .filter { it.size == 3 && it[0] == serial && it[2].startsWith("jdwp:") }
            .forEach { adb.removeForward(serial, it[1].removePrefix("tcp:").toInt()) }
        leftoverDumps().forEach { adb.shell(serial, listOf("run-as", pkg, "rm", "-f", "cache/$it")) }
    }

    /** `adb forward --list` lines (`<serial> tcp:<port> <remote>`); not a device command, so not in [Adb]. */
    private fun forwardList(): String {
        val p = ProcessBuilder(adb.adbPath.toString(), "forward", "--list").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        check(p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) { "adb forward --list hung" }
        return text
    }

    private fun leftoverDumps(): List<String> =
        adb.shell(serial, listOf("run-as", pkg, "ls", "cache")).stdout.lineSequence().map { it.trim() }.filter { it.startsWith("memscope-") }.toList()

    private class Recorded(val session: SessionMeta, val job: JobView, val error: Throwable?, val store: SessionStore, val guard: DebugAppGuard)

    private fun record(config: StartupConfig, onMilestoneDone: (Milestone) -> Unit = {}): Recorded {
        val store = SessionStore(tmp)
        val guard = DebugAppGuard(adb, tmp)
        val session = store.create(serial, pkg, SessionMeta.Mode.STARTUP)
        val job = Job("j", "Startup", StartupSession.stepNames(config))
        val error = runCatching { StartupSession(adb, store, guard, onMilestoneDone = onMilestoneDone).run(session.id, config, job) }.exceptionOrNull()
        val view = job.snapshot()
        println("startup ${view.state} in ${view.elapsedMs} ms${error?.let { " ($it)" } ?: ""}")
        view.steps.forEach { println("  %-40s %-7s %s".format(it.name, it.state, it.detail ?: "")) }
        return Recorded(store.get(session.id)!!, view, error, store, guard)
    }

    private fun assertDeviceClean(r: Recorded) {
        assertTrue(r.guard.pending().isEmpty(), "marker left: ${r.guard.pending()}")
        val debugApp = adb.shell(serial, listOf("dumpsys", "activity"), 30.seconds).stdout.lineSequence().filter { "mDebugApp" in it }.toList()
        assertTrue(debugApp.isEmpty(), "debug app still set: $debugApp")
        val forwards = forwardList()
        assertFalse(forwards.lineSequence().any { serial in it && "jdwp:" in it }, "forward left: $forwards")
        assertEquals(emptyList<String>(), leftoverDumps())
    }

    private fun assertValidHprofs(r: Recorded) {
        for (s in r.session.snapshots) {
            val f = r.store.snapshotFile(r.session.id, s.id)
            assertEquals(SnapshotMeta.Status.OK, s.status)
            assertEquals(Files.size(f), s.sizeBytes)
            assertTrue(s.sizeBytes > 15L * mb, "${s.label}: ${s.sizeBytes} bytes")
            assertEquals("JAVA PROFILE", String(Files.newInputStream(f).use { it.readNBytes(12) }, Charsets.US_ASCII))
        }
        assertFalse(Files.list(r.store.dir(r.session.id)).use { st -> st.anyMatch { it.fileName.toString().endsWith(".part") } })
    }

    /** Size of the largest byte[] referenced by [holder]'s class object (its planted static blob), 0 if the class is not loaded. */
    private fun staticBlob(index: HeapIndex, holder: String): Int {
        val obj = index.classNames.indices.filter { index.classNames[it] == holder }.map { index.classObjectIndex[it] }.firstOrNull { it >= 0 } ?: return 0
        return (index.graph.edgeOffsets[obj] until index.graph.edgeOffsets[obj + 1]).map { index.graph.edgeTargets[it] }
            .filter { index.classNames[index.classOf[it]] == "byte[]" }.maxOfOrNull { index.shallow[it] } ?: 0
    }

    private fun waitForResumed(activity: String) {
        val deadline = System.nanoTime() + 15_000_000_000L
        var last = ""
        while (System.nanoTime() < deadline) {
            last = adb.shell(serial, listOf("dumpsys", "activity", "activities"), 30.seconds).stdout.lineSequence()
                .filter { "ResumedActivity" in it }.joinToString("\n")
            if ("$pkg/$activity" in last) return
            Thread.sleep(300)
        }
        throw AssertionError("$activity not resumed within 15 s: $last")
    }

    @Test
    fun `default milestones give five valid dumps holding the right blobs and a running app`() {
        val r = record(StartupConfig())
        assertEquals(Job.State.DONE, r.job.state, "${r.error}")
        assertEquals(Milestones.defaults.map { it.id }, r.session.snapshots.map { it.milestoneId })
        assertValidHprofs(r)
        assertEquals(SessionMeta.State.ACTIVE, r.session.state)

        val index = r.session.snapshots.take(4).map { HeapIndexer.build(r.store.snapshotFile(r.session.id, it.id).toFile()) }
        val provider = "dev.memscope.fixture.InitProvider"
        val app = "dev.memscope.fixture.FixtureApp"
        val activity = "dev.memscope.fixture.MainActivity"
        assertTrue(staticBlob(index[0], provider) < 5 * mb, "providerBlob absent at process_start")
        assertTrue(staticBlob(index[1], provider) >= 5 * mb, "providerBlob present before Application.onCreate")
        assertTrue(staticBlob(index[1], app) < 10 * mb, "appBlob absent before Application.onCreate")
        assertTrue(staticBlob(index[2], app) >= 10 * mb, "appBlob present after Application.onCreate")
        assertTrue(staticBlob(index[3], activity) >= 3 * mb, "activityBlob present at first_activity_created")

        waitForResumed(".MainActivity")
        assertTrue(adb.processes(serial, pkg).any { it.name == "$pkg:remote" }, ":remote is running")
        assertDeviceClean(r)
    }

    @Test
    fun `custom milestone on LeakyActivity#onCreate adds a sixth dump`() {
        val custom = Milestones.parseCustom("dev.memscope.fixture.LeakyActivity#onCreate")
        // MainActivity is loaded only after attach: its hook resolves on class prepare and finds no such method
        val noMethod = Milestones.parseCustom("dev.memscope.fixture.MainActivity#noSuchMethod")
        val r = record(StartupConfig(Milestones.defaults + noMethod + custom, launch = "-n $pkg/.MainActivity --es actions leak_activity"))
        assertEquals(Job.State.DONE, r.job.state, "${r.error}")
        assertEquals(Milestones.defaults.map { it.id } + custom.id, r.session.snapshots.map { it.milestoneId })
        assertEquals("LeakyActivity#onCreate", r.session.snapshots.last().label)
        val failed = r.job.steps.single { it.name == "Dump: MainActivity#noSuchMethod" }
        assertEquals(Job.StepState.FAILED, failed.state)
        assertTrue("check class and method name" in failed.detail!!, failed.detail)
        assertValidHprofs(r)
        assertDeviceClean(r)
    }

    @Test
    fun `remote process can be profiled while the main process starts normally`() {
        val remote = "$pkg:remote"
        val milestones = Milestones.defaults.filter { !it.id.startsWith("first_activity") } +
            Milestones.parseCustom("dev.memscope.fixture.RemoteService#onCreate")
        val r = record(StartupConfig(milestones, processName = remote, milestoneTimeoutSec = 60))
        assertEquals(Job.State.DONE, r.job.state, "${r.error}")
        assertEquals(milestones.map { it.id }, r.session.snapshots.map { it.milestoneId })
        assertTrue(r.session.snapshots.all { it.processName == remote })
        assertValidHprofs(r)
        waitForResumed(".MainActivity")
        assertDeviceClean(r)
    }

    /**
     * Fix round 2: `dumpsys meminfo` (an in-app heap walk) racing a SUSPEND_ALL breakpoint can wedge the app's VM so
     * that a JDWP reply never comes. Forces that timing by hammering dumpsys during the whole recording: whatever the
     * outcome, it must end within its bounds (never hang), and a wedged app must be stopped and the device left clean.
     */
    @Test
    fun `dumpsys meminfo hammering during a recording never hangs it`() {
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val hammer = Thread {
            while (!stop.get()) runCatching { adb.shell(serial, listOf("dumpsys", "meminfo", pkg), 10.seconds) }
        }.apply { isDaemon = true; start() }
        val t0 = System.nanoTime()
        val r = try {
            record(StartupConfig(milestoneTimeoutSec = 60))
        } finally {
            stop.set(true); hammer.join(15_000)
        }
        val secs = (System.nanoTime() - t0) / 1_000_000_000
        println("hammered recording: ${r.job.state} in $secs s: ${r.error?.message?.lineSequence()?.firstOrNull()}")
        assertTrue(secs < 90, "bounded: $secs s")
        if (r.job.state != Job.State.DONE) {
            val m = r.error?.message ?: ""
            assertTrue("stopped answering the debugger" in m || "not reached" in m, m)
        }
        assertValidHprofs(r)
        assertDeviceClean(r)
    }

    @Test
    fun `killing the app between milestones two and three ends the session and keeps two dumps`() {
        val r = record(StartupConfig()) { m ->
            if (m.id == "before_app_oncreate") adb.shell(serial, listOf("am", "force-stop", pkg))
        }
        assertEquals(Job.State.FAILED, r.job.state)
        assertEquals(SessionMeta.State.ENDED, r.session.state)
        assertEquals("app exited at Before Application.onCreate", r.session.note)
        assertEquals(listOf("process_start", "before_app_oncreate"), r.session.snapshots.map { it.milestoneId })
        assertValidHprofs(r)
        assertDeviceClean(r)
    }

    @Test
    fun `an unknown custom class gets a hint after the grace period and does not fail the recording`() {
        val bogus = Milestones.parseCustom("dev.memscope.fixture.NoSuchClass#onCreate")
        val r = record(StartupConfig(listOf(Milestones.defaults[0], bogus), milestoneTimeoutSec = 120, customGraceSec = 5))
        assertEquals(Job.State.DONE, r.job.state, "${r.error}")
        assertTrue(r.job.elapsedMs < 30_000, "the grace period, not the 120 s timeout: ${r.job.elapsedMs} ms")
        assertEquals(listOf("process_start"), r.session.snapshots.map { it.milestoneId })
        val detail = r.job.steps.single { it.name == "Dump: NoSuchClass#onCreate" }.detail!!
        assertTrue("was never loaded" in detail && "check class and method name" in detail, detail)
        assertDeviceClean(r)
        waitForResumed(".MainActivity")
    }
}
