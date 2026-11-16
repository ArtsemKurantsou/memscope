package dev.memscope.capture

import dev.memscope.device.DeviceException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class StartupRetryTest {
    @TempDir lateinit var tmp: Path
    private val device = FakeStartupDevice()
    private lateinit var store: SessionStore
    private lateinit var guard: DebugAppGuard
    private lateinit var session: SessionMeta
    private val fast = StartupTimeouts(baselineMs = 2_000, pidMs = 2_000, attachMs = 2_000, dumpMs = 2_000, pullMs = 2_000, jdwpReplyMs = 500)
    private val vms = ArrayList<FakeDebugger>()
    private val noteSeen = ArrayList<String?>()
    private val abortFlags = ArrayList<Boolean>()
    /** Configures the n-th attempt's VM (0-based) before it runs. */
    private var setup: (Int, FakeDebugger) -> Unit = { _, vm -> vm.defaultScript() }
    private var beforeAttempt: (Int) -> Unit = {}

    @BeforeEach
    fun setUp() {
        store = SessionStore(tmp)
        guard = DebugAppGuard(device.adb, tmp)
        session = store.create(device.serial, device.pkg, SessionMeta.Mode.STARTUP)
    }

    private fun retry() = StartupRetry(store) { abort, note ->
        val n = vms.size
        beforeAttempt(n)
        abortFlags += abort
        noteSeen += note
        val vm = FakeDebugger().also { v -> v.onDisconnected = { device.pidAlive = false }; setup(n, v); vms += v }
        StartupSession(device.adb, store, guard, { vm }, fast, abortIfNotWaiting = abort, startNote = note)
    }

    // without the process start milestone, a wedge in the first Application.onCreate stop happens before any snapshot
    private val config = StartupConfig(milestones = Milestones.defaults.filter { it.id != "process_start" })
    private fun job() = Job("j", "Startup", StartupSession.stepNames(config))
    private fun hangEarly(vm: FakeDebugger) { vm.defaultScript(); vm.hangOn = "armCallerReturn:first_activity_resumed" }
    private fun wedgeBeforeAnySnapshot(vm: FakeDebugger) { vm.defaultScript(); vm.hangOn = "armCallerReturn:after_app_oncreate" }

    @Test
    fun `a wedge before any snapshot is retried once, in the same job, with the first failure noted`() {
        setup = { n, vm -> if (n == 0) wedgeBeforeAnySnapshot(vm) else vm.defaultScript() }
        val job = job()
        retry().run(session.id, config, job)
        val v = job.snapshot()
        assertEquals(Job.State.DONE, v.state)
        assertEquals(2, vms.size)
        assertEquals(listOf(true, false), abortFlags)
        assertTrue("first attempt failed" in noteSeen[1]!! && "retrying once" in noteSeen[1]!!, noteSeen.toString())
        assertTrue("retrying once" in v.steps.single { it.name == StartupSession.PREPARE }.detail!!)
        assertEquals(4, store.get(session.id)!!.snapshots.size)
    }

    @Test
    fun `an app that was past its debugger wait is retried once, then keeps the old partial behaviour`() {
        val notWaiting = "$APP_NOT_WAITING_REASON (it was already at handleBindApplication @870); the process start was missed"
        setup = { n, vm -> vm.defaultScript(); vm.script.removeIf { s -> s.any { it is DebugEvent.WaitReturned } }; if (n <= 1) vm.waitArmError = notWaiting }
        val job = Job("j", "Startup", StartupSession.stepNames(StartupConfig()))
        retry().run(session.id, StartupConfig(), job)
        assertEquals(2, vms.size, "never more than one retry")
        assertEquals(Job.State.DONE, job.snapshot().state)
        assertEquals(4, store.get(session.id)!!.snapshots.size, "second attempt: only the process start is missing")
    }

    @Test
    fun `a second wedge is surfaced, not retried again`() {
        setup = { _, vm -> wedgeBeforeAnySnapshot(vm) }
        val job = job()
        assertThrows<DeviceException> { retry().run(session.id, config, job) }
        assertEquals(2, vms.size)
        assertEquals(Job.State.FAILED, job.snapshot().state)
    }

    @Test
    fun `no retry once a snapshot exists`() {
        setup = { _, vm -> hangEarly(vm) } // wedges at the last milestone, after three snapshots
        val job = job()
        assertThrows<DeviceException> { retry().run(session.id, config, job) }
        assertEquals(1, vms.size)
        assertEquals(Job.State.FAILED, job.snapshot().state)
        assertEquals(3, store.get(session.id)!!.snapshots.size)
    }

    @Test
    fun `no retry for other failures`() {
        setup = { _, vm -> vm.defaultScript(); vm.attachError = RuntimeException("boom") }
        val job = job()
        assertThrows<DeviceException> { retry().run(session.id, config, job) }
        assertEquals(1, vms.size)
        assertEquals(Job.State.FAILED, job.snapshot().state)
    }

    @Test
    fun `a not-waiting app is not retried when a different failure came first`() {
        // process start disabled: not waiting only skips nothing, the attempt goes on and then fails for another reason
        val notWaiting = "$APP_NOT_WAITING_REASON (it was already at handleBindApplication @870)"
        setup = { _, vm -> vm.defaultScript(); vm.waitArmError = notWaiting; vm.nextEventsError = IllegalStateException("event queue broke") }
        val job = job()
        assertThrows<Throwable> { retry().run(session.id, config, job) }
        assertEquals(1, vms.size, "no retry: the not-waiting abort was not the failure")
        assertEquals(Job.State.FAILED, job.snapshot().state)
        assertTrue(store.get(session.id)!!.snapshots.isEmpty())
    }

    @Test
    fun `the retry note is shown from the start of the second attempt and survives its failing Prepare`() {
        setup = { n, vm -> if (n == 0) wedgeBeforeAnySnapshot(vm) else vm.defaultScript() }
        var prepareDetailAtSecondStart: String? = "unset"
        val job = job()
        beforeAttempt = { n ->
            if (n == 1) {
                prepareDetailAtSecondStart = job.snapshot().steps.single { it.name == StartupSession.PREPARE }.detail
                device.launcher = null // the second Prepare fails: no launcher activity
            }
        }
        assertThrows<DeviceException> { retry().run(session.id, config, job) }
        assertEquals(2, vms.size, "two attempts were constructed")
        assertTrue(prepareDetailAtSecondStart?.contains("retrying once") == true, "visible before the second attempt runs: $prepareDetailAtSecondStart")
        val prepare = job.snapshot().steps.single { it.name == StartupSession.PREPARE }
        assertEquals(Job.StepState.FAILED, prepare.state)
        assertTrue(prepare.detail?.contains("retrying once") == true, "kept after the second Prepare failed: ${prepare.detail}")
        assertEquals(Job.State.FAILED, job.snapshot().state)
    }

    @Test
    fun `a bookkeeping error between attempts never replaces the original failure`() {
        setup = { _, vm -> wedgeBeforeAnySnapshot(vm) }
        beforeAttempt = { n -> if (n == 1) throw IllegalStateException("could not build the second attempt") }
        val job = job()
        val e = assertThrows<DeviceException> { retry().run(session.id, config, job) }
        assertTrue("stopped answering the debugger" in e.message!!, e.message)
        val v = job.snapshot()
        assertEquals(Job.State.FAILED, v.state)
        assertTrue("stopped answering the debugger" in v.errorMessage!!, v.errorMessage)
    }

    @Test
    fun `cancel between attempts ends the job cancelled without a second attempt`() {
        setup = { n, vm -> if (n == 0) wedgeBeforeAnySnapshot(vm) else vm.defaultScript() }
        val job = job()
        // the first attempt's failure arrives; cancel lands before the retry starts
        val r = StartupRetry(store) { abort, note ->
            val vm = FakeDebugger().also { v -> v.onDisconnected = { device.pidAlive = false }; setup(vms.size, v); vms += v }
            val s = StartupSession(device.adb, store, guard, { vm }, fast, listener = object : StartupListener {
                override fun onRecordingDone() { if (vms.size == 1) job.cancel.cancel() }
            }, abortIfNotWaiting = abort, startNote = note)
            s
        }
        assertThrows<Throwable> { r.run(session.id, config, job) }
        assertEquals(1, vms.size)
        assertTrue(job.snapshot().state != Job.State.RUNNING)
        assertNull(null)
    }
}
