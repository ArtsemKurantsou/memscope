package dev.memscope.capture

import dev.memscope.device.DeviceException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class StartupSessionTest {
    @TempDir lateinit var tmp: Path
    private val device = FakeStartupDevice()
    private val vm = FakeDebugger()
    private lateinit var store: SessionStore
    private lateinit var guard: DebugAppGuard
    private lateinit var session: SessionMeta
    private val fast = StartupTimeouts(baselineMs = 2_000, pidMs = 2_000, attachMs = 2_000, dumpMs = 2_000, pullMs = 2_000)
    private val milestoneCallbacks = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        store = SessionStore(tmp)
        guard = DebugAppGuard(device.adb, tmp)
        session = store.create(device.serial, device.pkg, SessionMeta.Mode.STARTUP)
        vm.onDisconnected = { device.pidAlive = false } // by default a lost VM means the process died
    }

    private fun startup(timeouts: StartupTimeouts = fast, onDone: (Milestone) -> Unit = {}) =
        StartupSession(device.adb, store, guard, { vm }, timeouts) { m -> milestoneCallbacks += m.id; onDone(m) }

    private fun newJob(config: StartupConfig = StartupConfig()) = Job("j", "Startup", StartupSession.stepNames(config))

    private fun runOk(config: StartupConfig = StartupConfig(), job: Job = newJob(config)): Job {
        startup().run(session.id, config, job)
        return job
    }

    private fun runFailing(config: StartupConfig = StartupConfig(), job: Job = newJob(config), s: StartupSession = startup()): Throwable {
        val e = assertThrows<Throwable> { s.run(session.id, config, job) }
        assertTrue(job.snapshot().state != Job.State.RUNNING)
        return e
    }

    private fun stored() = store.get(session.id)!!
    private fun step(job: Job, name: String) = job.snapshot().steps.single { it.name == name }
    private fun localFiles() = Files.list(store.dir(session.id)).use { s -> s.map { it.fileName.toString() }.filter { it != "session.json" }.sorted().toList() }

    private fun assertCleanDevice() {
        assertTrue(device.count("shell am clear-debug-app") >= 1, "clear-debug-app must run: ${device.allCommands()}")
        if (device.count("forward tcp:0") > 0 && !device.failForward) assertEquals(1, device.count("forward --remove tcp:40001"), device.allCommands().toString())
        assertTrue(guard.pending().isEmpty(), "marker must be removed: ${guard.pending()}")
        assertTrue(vm.violations.isEmpty(), vm.violations.toString())
        if (vm.calls.any { it.startsWith("attach") }) assertTrue(vm.disposed, "debugger must be disposed")
        assertFalse(localFiles().any { it.endsWith(".part") || it.endsWith(".gz") }, localFiles().toString())
    }

    @Test
    fun `the listener hears the pid, then held while stopped and running while resumed, and ends running`() {
        vm.defaultScript()
        val heard = java.util.concurrent.CopyOnWriteArrayList<String>()
        val listener = object : StartupListener {
            override fun onPid(pid: Int) { heard += "pid $pid" }
            override fun onHeld(held: Boolean) { heard += if (held) "held" else "running" }
        }
        val config = StartupConfig()
        StartupSession(device.adb, store, guard, { vm }, fast, listener).run(session.id, config, newJob(config))
        assertEquals("pid ${device.targetPid}", heard.first())
        assertEquals("running", heard.last())
        assertTrue(heard.count { it == "held" } >= 5, heard.toString()) // attach, then every milestone stop
        assertTrue(heard.zipWithNext().none { (a, b) -> a == b && a != "held" }, heard.toString())
    }

    @Test
    fun `a failing listener never breaks the recording`() {
        vm.defaultScript()
        val boom = object : StartupListener {
            override fun onPid(pid: Int) = throw IllegalStateException("boom")
            override fun onHeld(held: Boolean) = throw IllegalStateException("boom")
        }
        val config = StartupConfig()
        val job = newJob(config)
        StartupSession(device.adb, store, guard, { vm }, fast, boom).run(session.id, config, job)
        assertEquals(Job.State.DONE, job.snapshot().state)
    }

    @Test
    fun `default run records five OK snapshots in milestone order and leaves the device clean`() {
        vm.defaultScript()
        val job = runOk()

        val v = job.snapshot()
        assertEquals(Job.State.DONE, v.state, v.toString())
        assertEquals(session.id, v.resultRef)
        assertEquals(StartupSession.stepNames(StartupConfig()), v.steps.map { it.name })
        assertTrue(v.steps.all { it.state == Job.StepState.DONE }, v.steps.toString())
        val snaps = stored().snapshots
        assertEquals(Milestones.defaults.map { it.id }, snaps.map { it.milestoneId })
        assertEquals(Milestones.defaults.map { it.label }, snaps.map { it.label })
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), snaps.map { it.id })
        assertTrue(snaps.all { it.status == SnapshotMeta.Status.OK && it.processName == "dev.app" && it.sizeBytes == 4096L })
        assertEquals(listOf("s1.hprof", "s2.hprof", "s3.hprof", "s4.hprof", "s5.hprof"), localFiles())
        assertEquals(SessionMeta.State.ACTIVE, stored().state)
        assertEquals(Milestones.defaults.map { it.id }, milestoneCallbacks)

        val remotes = (1..5).map { "/data/data/dev.app/cache/memscope-${session.id}-s$it.hprof" }
        assertEquals(remotes, vm.dumpedPaths)
        for (r in remotes) {
            assertEquals(1, device.count("exec-out run-as dev.app gzip -1 -c $r"))
            assertEquals(1, device.count("shell run-as dev.app rm -f $r"))
        }
        // FINDINGS "Recommended sequence"
        val order = listOf(
            "shell run-as dev.app id", "shell run-as dev.app mkdir -p cache", "shell am set-debug-app -w dev.app",
            "shell am force-stop dev.app", "track-jdwp", "shell am start -n dev.app/.Main", "forward tcp:0 jdwp:4242",
        ).map { device.index(it) }
        assertTrue(order.all { it >= 0 } && order == order.sorted(), "${device.allCommands()}")
        assertFalse(device.allCommands().any { it.startsWith("shell am start") && " -W" in it })
        assertTrue(device.index("forward --remove tcp:40001") < device.index("shell am clear-debug-app"))
        assertEquals(0, device.count("shell am force-stop dev.app") - 1, "no force-stop after the launch")
        assertEquals(listOf("return:after_app_oncreate", "return:first_activity_created", "return:first_activity_resumed"), vm.calls.filter { it.startsWith("return:") })
        assertEquals(listOf("armWaitReturn"), vm.calls.filter { it == "armWaitReturn" })
        assertCleanDevice()
    }

    @Test
    fun `refuses a non-debuggable app before changing anything on the device`() {
        device.debuggable = false
        val e = runFailing()
        assertEquals(DeviceException.Kind.NOT_DEBUGGABLE, (e as DeviceException).kind)
        assertEquals(listOf("shell run-as dev.app id"), device.allCommands())
        assertTrue(guard.pending().isEmpty())
    }

    @Test
    fun `invalid config is rejected before any device command`() {
        for (bad in listOf(
            StartupConfig(launch = "dev.app/.Main; reboot"),
            StartupConfig(launch = "-n dev.app/.Main\nreboot"),
            StartupConfig(launch = "dev.app/.Main `id`"),
            StartupConfig(processName = "com.other"),
            StartupConfig(processName = "dev.app:remote x"),
            StartupConfig(milestones = Milestones.defaults.map { it.copy(enabled = false) }),
            StartupConfig(milestoneTimeoutSec = 0),
            StartupConfig(customGraceSec = 0),
        )) {
            device.calls.clear()
            val job = newJob(bad)
            assertThrows<IllegalArgumentException>("$bad") { startup().run(session.id, bad, job) }
            assertEquals(Job.State.FAILED, job.snapshot().state)
            assertTrue(device.calls.isEmpty(), "$bad: ${device.allCommands()}")
        }
    }

    @Test
    fun `launch target may be a component or raw am start arguments`() {
        vm.defaultScript()
        runOk(StartupConfig(launch = "dev.app/.Other"))
        assertEquals(1, device.count("shell am start -n dev.app/.Other"))
        assertEquals(0, device.count("shell cmd package resolve-activity"))
        device.calls.clear(); vm.defaultScript(); device.launched = false
        session = store.create(device.serial, device.pkg, SessionMeta.Mode.STARTUP)
        runOk(StartupConfig(launch = "-n dev.app/.Main --es actions leak_activity"))
        assertEquals(1, device.count("shell am start -n dev.app/.Main --es actions leak_activity"))
    }

    @Test
    fun `app without launcher activity and no launch target fails with a hint`() {
        device.launcher = null
        val e = runFailing() as DeviceException
        assertTrue("launch" in e.hint.lowercase(), e.hint)
        assertEquals(0, device.count("shell am set-debug-app"))
    }

    /** Disarm (clear-debug-app, marker removed, forward removed, debugger disposed) after a failure at every step. */
    @ParameterizedTest
    @ValueSource(strings = ["mkdir", "set-debug-app", "am-start-error", "no-pid", "forward", "attach", "events", "wait-arm"])
    fun `failure at any step still disarms and cleans up`(where: String) {
        vm.defaultScript()
        when (where) {
            "mkdir" -> device.failShell = "run-as dev.app mkdir"
            "set-debug-app" -> device.failShell = "am set-debug-app"
            "am-start-error" -> device.amStartOutput = "Error: Activity class {dev.app/.Main} does not exist."
            "no-pid" -> device.targetName = "dev.app:never"
            "forward" -> device.failForward = true
            "attach" -> vm.attachError = java.io.IOException("handshake failed")
            "events" -> vm.nextEventsError = IllegalStateException("boom")
            "wait-arm" -> { vm.waitArmError = "no handleBindApplication frame"; vm.script.clear(); vm.nextEventsError = IllegalStateException("boom") }
        }
        val job = newJob()
        runFailing(job = job)
        val v = job.snapshot()
        assertEquals(Job.State.FAILED, v.state)
        assertNotNull(v.errorMessage)
        if (where != "mkdir") assertCleanDevice() else assertEquals(0, device.count("shell am set-debug-app"))
        assertEquals(Job.StepState.DONE, step(job, StartupSession.DETACH).state)
        assertTrue(stored().snapshots.isEmpty())
        if (where in setOf("no-pid", "am-start-error", "forward", "attach", "events", "wait-arm")) {
            // launched but never past waitForDebugger: the app would stay frozen, pid known or not
            assertEquals(2, device.count("shell am force-stop dev.app"), "$where: ${device.allCommands()}")
        }
    }

    @Test
    fun `cancel while waiting for the pid force-stops the launched app`() {
        device.targetName = "dev.app:never"
        val job = newJob()
        device.onShell["am start"] = { job.cancel.cancel() }
        runFailing(job = job)
        assertEquals(Job.State.CANCELLED, job.snapshot().state)
        assertEquals(2, device.count("shell am force-stop dev.app"), device.allCommands().toString())
        assertCleanDevice()
    }

    @Test
    fun `the marker records launch before am start and the dump path while a dump is on the device`() {
        vm.defaultScript()
        var launchedAtStart = false
        device.onShell["am start"] = { launchedAtStart = guard.pending().single().launched }
        val seen = mutableListOf<String?>()
        vm.dumpResult = { _ -> seen += guard.pending().single().pendingDump; null }
        runOk()
        assertTrue(launchedAtStart)
        assertEquals((1..5).map { "/data/data/dev.app/cache/memscope-${session.id}-s$it.hprof" }, seen)
    }

    @Test
    fun `pid discovery skips zygote and pre-initialized names and other processes`() {
        vm.defaultScript()
        runOk()
        val reads = device.shellCommands().filter { it.startsWith("cat /proc/4242/cmdline") }
        assertEquals(3, reads.size, device.shellCommands().toString())
        assertEquals(1, device.count("forward tcp:0 jdwp:4242"))
        assertEquals(0, device.count("forward tcp:0 jdwp:4300"))
    }

    @Test
    fun `remote process is profiled when chosen`() {
        device.targetName = "dev.app:remote"
        vm.defaultScript()
        runOk(StartupConfig(processName = "dev.app:remote"))
        assertEquals(1, device.count("shell am set-debug-app -w dev.app:remote"))
        assertTrue(stored().snapshots.all { it.processName == "dev.app:remote" })
    }

    @Test
    fun `cancel while waiting for a milestone gives CANCELLED and a clean device`() {
        vm.script += listOf(DebugEvent.WaitReturned(vm.main))
        val job = newJob()
        val t = thread { runCatching { startup(fast.copy()).run(session.id, StartupConfig(milestoneTimeoutSec = 60), job) } }
        waitUntil { stored().snapshots.size == 1 }
        val t0 = System.nanoTime()
        job.cancel.cancel()
        t.join(5_000)
        assertFalse(t.isAlive)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_500, "cancel must be noticed quickly")
        assertEquals(Job.State.CANCELLED, job.snapshot().state)
        assertCleanDevice()
        assertEquals(0, device.count("shell am force-stop dev.app") - 1, "the app was past the wait: do not stop it")
        assertEquals(1, stored().snapshots.size)
    }

    @Test
    fun `cancel before the app got past waitForDebugger force-stops the frozen app`() {
        val job = newJob()
        val t = thread { runCatching { startup().run(session.id, StartupConfig(milestoneTimeoutSec = 60), job) } }
        waitUntil { vm.calls.contains("resume") }
        job.cancel.cancel()
        t.join(5_000)
        assertEquals(Job.State.CANCELLED, job.snapshot().state)
        assertCleanDevice()
        assertEquals(2, device.count("shell am force-stop dev.app"), device.allCommands().toString())
    }

    @Test
    fun `cancel during a hung dump unblocks it and cleans up`() {
        vm.script += listOf(DebugEvent.WaitReturned(vm.main))
        vm.dumpGate = CountDownLatch(1)
        val job = newJob()
        val t = thread { runCatching { startup(fast.copy(dumpMs = 60_000)).run(session.id, StartupConfig(), job) } }
        waitUntil { vm.calls.any { it.startsWith("dump:") } }
        job.cancel.cancel()
        t.join(5_000)
        assertFalse(t.isAlive)
        assertEquals(Job.State.CANCELLED, job.snapshot().state)
        assertCleanDevice()
        assertTrue(device.count("shell run-as dev.app rm -f") >= 1, "the remote file is removed")
    }

    @Test
    fun `dump that never returns times out, fails the job and cleans up`() {
        vm.script += listOf(DebugEvent.WaitReturned(vm.main))
        vm.dumpGate = CountDownLatch(1)
        val job = newJob()
        val e = runFailing(job = job, s = startup(fast.copy(dumpMs = 500)))
        assertEquals(DeviceException.Kind.TIMEOUT, (e as DeviceException).kind)
        assertEquals(Job.StepState.FAILED, step(job, "Dump: Process start").state)
        assertCleanDevice()
    }

    @Test
    fun `app exit mid-session ends the session with a note and keeps pulled snapshots`() {
        vm.script += listOf(DebugEvent.WaitReturned(vm.main))
        vm.script += listOf(DebugEvent.Reached("before_app_oncreate", vm.main), DebugEvent.EntryHit("after_app_oncreate", vm.main))
        vm.script += listOf(DebugEvent.Disconnected)
        val job = newJob()
        runFailing(job = job)
        val s = stored()
        assertEquals(SessionMeta.State.ENDED, s.state)
        assertEquals("app exited at Before Application.onCreate", s.note)
        assertEquals(listOf("process_start", "before_app_oncreate"), s.snapshots.map { it.milestoneId })
        assertEquals(listOf("s1.hprof", "s2.hprof"), localFiles())
        val v = job.snapshot()
        assertEquals(Job.State.FAILED, v.state)
        assertTrue("exited" in v.errorMessage!!, v.errorMessage)
        assertEquals(Job.StepState.FAILED, step(job, "Dump: After Application.onCreate").state)
        assertCleanDevice()
        assertEquals(1, device.count("shell am force-stop dev.app"), "nothing to stop after an exit")
    }

    @Test
    fun `app exit before any milestone is noted as exited at launch`() {
        vm.script += listOf(DebugEvent.Disconnected)
        runFailing()
        assertEquals("app exited at launch", stored().note)
        assertEquals(SessionMeta.State.ENDED, stored().state)
    }

    @Test
    fun `app exit during a dump invoke is an app exit`() {
        vm.script += listOf(DebugEvent.WaitReturned(vm.main))
        vm.dumpDisconnects = true
        runFailing()
        assertEquals("app exited at Process start", stored().note)
        assertTrue(stored().snapshots.isEmpty())
        assertCleanDevice()
    }

    @Test
    fun `Java exception from dumpHprofData fails that milestone and the session continues`() {
        vm.defaultScript()
        vm.dumpResult = { path -> if (path.endsWith("-s2.hprof")) "java.io.IOException: write failed: ENOSPC (No space left on device)" else null }
        val job = runOk()
        val v = job.snapshot()
        assertEquals(Job.State.DONE, v.state)
        val failed = step(job, "Dump: Before Application.onCreate")
        assertEquals(Job.StepState.FAILED, failed.state)
        assertTrue("ENOSPC" in failed.detail!!, failed.detail)
        assertEquals(listOf("process_start", "after_app_oncreate", "first_activity_created", "first_activity_resumed"), stored().snapshots.map { it.milestoneId })
        assertEquals(1, device.count("shell run-as dev.app rm -f /data/data/dev.app/cache/memscope-${session.id}-s2.hprof"))
        assertEquals(0, device.count("exec-out run-as dev.app gzip -1 -c /data/data/dev.app/cache/memscope-${session.id}-s2.hprof"))
        assertCleanDevice()
    }

    @Test
    fun `device disconnect during a pull fails the job, keeps the marker and recoverStale clears it later`() {
        vm.defaultScript()
        device.pull = FakeStartupDevice.Pull.DISCONNECT
        val job = newJob()
        val e = runFailing(job = job) as DeviceException
        assertEquals(DeviceException.Kind.DISCONNECTED, e.kind)
        val v = job.snapshot()
        assertEquals(Job.State.FAILED, v.state)
        assertTrue("disconnected" in v.errorHint!!.lowercase(), v.errorHint)
        assertEquals(1, guard.pending().size, "marker stays until the device is back")
        assertTrue(localFiles().isEmpty(), localFiles().toString())
        assertTrue(vm.disposed)

        // device still gone: recoverStale keeps the marker
        device.listedInDevices = false
        guard.recoverStale()
        assertEquals(1, guard.pending().size)
        // device back
        device.disconnected = false
        device.listedInDevices = true
        device.calls.clear()
        guard.recoverStale()
        assertTrue(guard.pending().isEmpty())
        assertEquals(1, device.count("shell am clear-debug-app"))
        assertEquals(1, device.count("shell run-as dev.app rm -f /data/data/dev.app/cache/memscope-${session.id}-s1.hprof"), "the orphan dump is removed")
    }

    @Test
    fun `lost debugger connection while the app lives is not an app exit`() {
        vm.onDisconnected = {} // the process survives
        vm.script += listOf(DebugEvent.WaitReturned(vm.main))
        vm.script += listOf(DebugEvent.Disconnected)
        val job = newJob()
        val e = runFailing(job = job) as DeviceException
        assertTrue("connection" in e.message!!.lowercase(), e.message)
        assertTrue(e.hint.isNotBlank())
        assertEquals(SessionMeta.State.ACTIVE, stored().state)
        assertNull(stored().note)
        assertEquals(1, stored().snapshots.size)
        assertTrue("connection" in step(job, "Dump: Before Application.onCreate").detail!!)
        assertEquals(1, device.count("shell am force-stop dev.app"), "released: the app runs normally, leave it")
        assertCleanDevice()
    }

    @Test
    fun `lost debugger connection before the app was released force-stops the frozen app`() {
        vm.onDisconnected = {}
        vm.script += listOf(DebugEvent.Disconnected)
        runFailing()
        assertEquals(SessionMeta.State.ACTIVE, stored().state)
        assertEquals(2, device.count("shell am force-stop dev.app"), device.allCommands().toString())
        assertCleanDevice()
    }

    @Test
    fun `a custom hook whose class never loads gets a hint after a short grace and does not fail the session`() {
        vm.defaultScript()
        vm.hookStatus = { key, _ -> if (key.startsWith("custom:")) HookStatus.Deferred else HookStatus.Resolved }
        val typo = Milestones.parseCustom("dev.app.LeakyActivty#onCreate")
        val config = StartupConfig(Milestones.defaults + typo, milestoneTimeoutSec = 60, customGraceSec = 1)
        val t0 = System.nanoTime()
        val job = runOk(config)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 5_000, "the grace period, not the 60 s timeout")
        assertEquals(Job.State.DONE, job.snapshot().state)
        assertEquals(session.id, job.snapshot().resultRef)
        val s = step(job, "Dump: LeakyActivty#onCreate")
        assertEquals(Job.StepState.FAILED, s.state)
        assertTrue("dev.app.LeakyActivty was never loaded" in s.detail!! && "check class and method name" in s.detail!!, s.detail)
        assertEquals(5, stored().snapshots.size)
        assertCleanDevice()
    }

    @Test
    fun `a loaded custom hook that is not hit still gets the full milestone timeout and fails`() {
        vm.defaultScript()
        val custom = Milestones.parseCustom("dev.app.Foo#bar") // Resolved: its class is loaded
        val config = StartupConfig(Milestones.defaults + custom, milestoneTimeoutSec = 2, customGraceSec = 1)
        val job = newJob(config)
        val t0 = System.nanoTime()
        val e = runFailing(config, job) as DeviceException
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 2_000, "full timeout, not the grace")
        assertEquals(DeviceException.Kind.TIMEOUT, e.kind)
        assertEquals("not reached within 2 s", step(job, "Dump: Foo#bar").detail)
    }

    @Test
    fun `a package name with shell characters is refused before any device command`() {
        session = store.create(device.serial, "dev.app;reboot", SessionMeta.Mode.STARTUP)
        assertThrows<IllegalArgumentException> { startup().run(session.id, StartupConfig(), newJob()) }
        assertTrue(device.calls.isEmpty(), device.allCommands().toString())
        session = store.create("emulator-5554;reboot", "dev.app", SessionMeta.Mode.STARTUP)
        assertThrows<IllegalArgumentException> { startup().run(session.id, StartupConfig(), newJob()) }
        assertTrue(device.calls.isEmpty(), device.allCommands().toString())
    }

    @Test
    fun `milestone not reached in time fails the remaining steps and detaches cleanly`() {
        vm.script += listOf(DebugEvent.WaitReturned(vm.main))
        vm.script += listOf(DebugEvent.Reached("before_app_oncreate", vm.main), DebugEvent.EntryHit("after_app_oncreate", vm.main))
        val job = newJob()
        val t0 = System.nanoTime()
        val e = runFailing(StartupConfig(milestoneTimeoutSec = 1), job) as DeviceException
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 4_000)
        assertEquals(DeviceException.Kind.TIMEOUT, e.kind)
        for (label in listOf("After Application.onCreate", "First Activity created", "First Activity resumed")) {
            val s = step(job, "Dump: $label")
            assertEquals(Job.StepState.FAILED, s.state)
            assertTrue("not reached" in s.detail!!, s.detail)
        }
        assertEquals(2, stored().snapshots.size)
        assertEquals(SessionMeta.State.ACTIVE, stored().state, "the app keeps running: on-demand dumps still work")
        assertCleanDevice()
    }

    @Test
    fun `unresolvable custom hook is a failed step with a hint and the session continues`() {
        vm.defaultScript()
        vm.hookStatus = { key, _ -> if (key.startsWith("custom:")) HookStatus.Failed("no method nope in dev.app.Foo") else HookStatus.Resolved }
        val config = StartupConfig(Milestones.defaults + Milestones.parseCustom("dev.app.Foo#nope"))
        val job = runOk(config)
        val s = step(job, "Dump: Foo#nope")
        assertEquals(Job.StepState.FAILED, s.state)
        assertTrue("check class and method name" in s.detail!!, s.detail)
        assertEquals(Job.State.DONE, job.snapshot().state)
        assertEquals(5, stored().snapshots.size)
    }

    @Test
    fun `deferred custom hook resolved by class prepare produces a sixth snapshot`() {
        vm.defaultScript()
        vm.hookStatus = { key, _ -> if (key.startsWith("custom:")) HookStatus.Deferred else HookStatus.Resolved }
        val custom = Milestones.parseCustom("dev.app.LeakyActivity#onCreate")
        vm.script += listOf(DebugEvent.HookResolved(custom.id))
        vm.script += listOf(DebugEvent.Reached(custom.id, vm.main))
        val job = runOk(StartupConfig(Milestones.defaults + custom))
        assertEquals(Job.State.DONE, job.snapshot().state)
        assertEquals(6, stored().snapshots.size)
        assertEquals("LeakyActivity#onCreate", stored().snapshots.last().label)
        assertEquals(custom.id, stored().snapshots.last().milestoneId)
    }

    @Test
    fun `missed caller return fails that milestone only`() {
        vm.script += listOf(DebugEvent.WaitReturned(vm.main))
        vm.script += listOf(DebugEvent.Reached("before_app_oncreate", vm.main), DebugEvent.EntryHit("after_app_oncreate", vm.main))
        vm.script += listOf(DebugEvent.HookFailed("after_app_oncreate", "the caller returned without reaching the return breakpoint"))
        vm.script += listOf(DebugEvent.EntryHit("first_activity_created", vm.main))
        vm.script += listOf(DebugEvent.Reached("first_activity_created", vm.main))
        vm.script += listOf(DebugEvent.EntryHit("first_activity_resumed", vm.main))
        vm.script += listOf(DebugEvent.Reached("first_activity_resumed", vm.main))
        val job = runOk()
        assertEquals(Job.StepState.FAILED, step(job, "Dump: After Application.onCreate").state)
        assertEquals(4, stored().snapshots.size)
    }

    @Test
    fun `caller return that cannot be armed fails that milestone only`() {
        vm.defaultScript()
        vm.script.removeIf { set -> set.any { it is DebugEvent.Reached && it.key == "after_app_oncreate" } }
        vm.returnArmError = { key -> if (key == "after_app_oncreate") "no location at code index 897" else null }
        val job = runOk()
        val s = step(job, "Dump: After Application.onCreate")
        assertEquals(Job.StepState.FAILED, s.state)
        assertTrue("897" in s.detail!!)
        assertEquals(4, stored().snapshots.size)
    }

    @Test
    fun `truncated or empty pull fails that milestone without leaving files`() {
        vm.defaultScript()
        device.pull = FakeStartupDevice.Pull.TRUNCATED
        val job = runOk()
        assertTrue(stored().snapshots.isEmpty())
        val s = step(job, "Dump: Process start")
        assertEquals(Job.StepState.FAILED, s.state)
        assertTrue("retry" in s.detail!!.lowercase() || "incomplete" in s.detail!!.lowercase(), s.detail)
        assertEquals(5, device.count("shell run-as dev.app rm -f"))
        assertCleanDevice()
    }

    @Test
    fun `disabled milestones get no step, no hook and no dump`() {
        val config = StartupConfig(Milestones.defaults.map { if (it.id == "process_start" || it.id == "after_app_oncreate") it.copy(enabled = false) else it })
        vm.defaultScript()
        val job = runOk(config)
        assertFalse(job.snapshot().steps.any { it.name == "Dump: Process start" })
        assertEquals(listOf("before_app_oncreate", "first_activity_created", "first_activity_resumed"), stored().snapshots.map { it.milestoneId })
        assertFalse(vm.calls.contains("entry:after_app_oncreate"))
        assertTrue(vm.calls.contains("armWaitReturn"), "the wait-return breakpoint also tells when the app is past the wait")
    }

    @Test
    fun `milestone callback runs after each completed milestone and can kill the app`() {
        vm.defaultScript()
        val s = startup { m -> if (m.id == "before_app_oncreate") { vm.script.clear(); vm.script += listOf(DebugEvent.Disconnected) } }
        val job = newJob()
        assertThrows<Throwable> { s.run(session.id, StartupConfig(), job) }
        assertEquals(listOf("process_start", "before_app_oncreate"), milestoneCallbacks)
        assertEquals("app exited at Before Application.onCreate", stored().note)
        assertNull(job.snapshot().resultRef)
    }

    private fun waitUntil(cond: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!cond()) { check(System.nanoTime() < deadline) { "condition not met in 5 s" }; Thread.sleep(10) }
    }
}
