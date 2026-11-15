package dev.memscope.capture

import dev.memscope.device.DeviceException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OnDemandDumperTest {
    @TempDir lateinit var tmp: Path
    private val device = FakeDevice()
    private lateinit var store: SessionStore
    private lateinit var dumper: OnDemandDumper
    private lateinit var session: SessionMeta

    private val steps = listOf("Check app", "Dump heap on device", "Pull dump", "Clean up")
    private fun newJob() = Job("j", "Heap dump", steps)

    @BeforeEach
    fun setUp() {
        store = SessionStore(tmp)
        dumper = OnDemandDumper(device.adb, store)
        session = store.create(device.serial, "dev.app", SessionMeta.Mode.ATTACH)
    }

    private fun remoteRm() = device.shellCommands().filter { it.startsWith("rm ") }
    private fun localHprofs() = Files.list(store.dir(session.id)).use { s -> s.map { it.fileName.toString() }.filter { it != "session.json" }.toList() }

    @Test
    fun `the snapshot is stamped when the dump starts, not when the pull ends`() {
        device.pullDelayMs = 300
        val before = System.currentTimeMillis()
        val meta = dumper.dump(session.id, "dev.app", "x", newJob())
        val after = System.currentTimeMillis()
        assertTrue(meta.takenAtMs >= before && after - meta.takenAtMs >= 250, "takenAtMs=${meta.takenAtMs} before=$before after=$after")
    }

    @Test
    fun `success records an OK snapshot, pulls a valid file and removes the remote file`() {
        val job = newJob()
        val meta = dumper.dump(session.id, "dev.app", "after login", job)
        assertEquals("s1", meta.id)
        assertEquals(SnapshotMeta.Status.OK, meta.status)
        assertEquals("after login", meta.label)
        assertEquals("dev.app", meta.processName)
        assertEquals(4096L, meta.sizeBytes)
        assertEquals(listOf("s1.hprof"), localHprofs())
        assertEquals(listOf("s1"), store.get(session.id)!!.snapshots.map { it.id })
        val remote = "/data/local/tmp/memscope-${session.id}-s1.hprof"
        assertTrue(device.shellCommands().contains("am dumpheap dev.app $remote"))
        assertEquals(listOf("rm -f $remote"), remoteRm())
        val v = job.snapshot()
        assertEquals(Job.State.DONE, v.state)
        assertEquals("s1", v.resultRef)
        assertEquals(steps, v.steps.map { it.name })
        assertTrue(v.steps.all { it.state == Job.StepState.DONE })
    }

    @Test
    fun `second dump gets the next snapshot id and remote process dump works`() {
        dumper.dump(session.id, "dev.app", "a", newJob())
        val m = dumper.dump(session.id, "dev.app:remote", "b", newJob())
        assertEquals("s2", m.id)
        assertEquals("dev.app:remote", m.processName)
    }

    @Test
    fun `non-debuggable package is refused before am dumpheap runs`() {
        device.debuggable = false
        val job = newJob()
        val e = assertThrows<DeviceException> { dumper.dump(session.id, "dev.app", "x", job) }
        assertEquals(DeviceException.Kind.NOT_DEBUGGABLE, e.kind)
        assertFalse(device.shellCommands().any { it.startsWith("am ") }, device.shellCommands().toString())
        assertEquals(Job.State.FAILED, job.snapshot().state)
        assertEquals(Job.StepState.FAILED, job.snapshot().steps[0].state)
        assertTrue(store.get(session.id)!!.snapshots.isEmpty())
        assertTrue(localHprofs().isEmpty())
    }

    @Test
    fun `process not running is COMMAND_FAILED with start-the-app hint`() {
        device.processes = listOf("101 dev.app:remote")
        val job = newJob()
        val e = assertThrows<DeviceException> { dumper.dump(session.id, "dev.app", "x", job) }
        assertEquals(DeviceException.Kind.COMMAND_FAILED, e.kind)
        assertTrue("start the app first" in e.hint, e.hint)
        assertFalse(device.shellCommands().any { it.startsWith("am ") })
        assertEquals("start the app first", job.snapshot().errorHint?.let { Regex("start the app first").find(it)?.value })
    }

    @Test
    fun `shell characters in the process or package name are refused before any device command`() {
        val job = newJob()
        assertThrows<IllegalArgumentException> { dumper.dump(session.id, "dev.app;reboot", "x", job) }
        assertEquals(Job.State.FAILED, job.snapshot().state)
        val bad = store.create(device.serial, "dev.app;reboot", SessionMeta.Mode.ATTACH)
        assertThrows<IllegalArgumentException> { dumper.dump(bad.id, "dev.app", "x", newJob()) }
        assertTrue(device.calls.isEmpty(), device.calls.toString())
    }

    @Test
    fun `process name outside the package is rejected`() {
        val e = assertThrows<DeviceException> { dumper.dump(session.id, "com.other.app", "x", newJob()) }
        assertEquals(DeviceException.Kind.COMMAND_FAILED, e.kind)
        assertFalse(device.shellCommands().any { it.startsWith("am ") })
    }

    @Test
    fun `disconnect during pull fails the job, deletes the partial file and still attempts remote cleanup`() {
        device.pull = FakeDevice.Pull.DISCONNECT
        val job = newJob()
        val e = assertThrows<DeviceException> { dumper.dump(session.id, "dev.app", "x", job) }
        assertEquals(DeviceException.Kind.DISCONNECTED, e.kind)
        val v = job.snapshot()
        assertEquals(Job.State.FAILED, v.state)
        assertTrue("disconnected" in v.errorHint!!, v.errorHint)
        assertEquals(Job.StepState.FAILED, v.steps[2].state)
        assertEquals(1, remoteRm().size, "remote cleanup must be attempted")
        assertTrue(localHprofs().isEmpty(), localHprofs().toString())
        assertTrue(store.get(session.id)!!.snapshots.isEmpty())
    }

    @Test
    fun `cancel during pull gives CANCELLED, no partial file, remote cleanup attempted`() {
        device.pull = FakeDevice.Pull.CANCEL
        val job = newJob()
        device.onCancelPull = { job.cancel.cancel() }
        val e = assertThrows<DeviceException> { dumper.dump(session.id, "dev.app", "x", job) }
        assertEquals(DeviceException.Kind.CANCELLED, e.kind)
        assertEquals(Job.State.CANCELLED, job.snapshot().state)
        assertEquals(1, remoteRm().size)
        assertTrue(localHprofs().isEmpty())
        assertTrue(store.get(session.id)!!.snapshots.isEmpty())
    }

    @Test
    fun `truncated pull with exit 0 is a failed pull with retry hint and nothing is recorded`() {
        device.pull = FakeDevice.Pull.TRUNCATED_EXIT0
        val job = newJob()
        val e = assertThrows<DeviceException> { dumper.dump(session.id, "dev.app", "x", job) }
        assertEquals(DeviceException.Kind.COMMAND_FAILED, e.kind)
        assertTrue("retry" in e.hint.lowercase(), e.hint)
        assertTrue(localHprofs().isEmpty())
        assertEquals(1, remoteRm().size)
        assertTrue(store.get(session.id)!!.snapshots.isEmpty())
    }

    @Test
    fun `pulled file that is not an hprof is rejected`() {
        device.pull = FakeDevice.Pull.NOT_HPROF
        assertThrows<DeviceException> { dumper.dump(session.id, "dev.app", "x", newJob()) }
        assertTrue(localHprofs().isEmpty())
    }

    @Test
    fun `local size differing from remote size is a failed pull`() {
        device.statOverride = 8192L // remote claims 8192 bytes, pull delivers 4096
        val e = assertThrows<DeviceException> { dumper.dump(session.id, "dev.app", "x", newJob()) }
        assertEquals(DeviceException.Kind.COMMAND_FAILED, e.kind)
        assertTrue(localHprofs().isEmpty())
    }
}
