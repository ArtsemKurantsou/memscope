package dev.memscope.capture

import dev.memscope.device.Adb
import dev.memscope.device.CancelToken
import dev.memscope.device.DeviceException
import dev.memscope.device.ProcessResult
import dev.memscope.device.ProcessRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

private val SUMMARY = """
 App Summary
                       Pss(KB)
           Java Heap:    1000
         Native Heap:    2000
                Code:     300
               Stack:      40
            Graphics:     500
       Private Other:      60
              System:      70

           TOTAL PSS:    3970            TOTAL RSS:   9999
""".trimIndent()

/** Answers `dumpsys meminfo`, `ps` and `smaps_rollup` as scripted; records every command and the timeout it was given. */
private class FakePhone : ProcessRunner {
    val commands = CopyOnWriteArrayList<List<String>>()
    val timeouts = CopyOnWriteArrayList<Duration>()
    @Volatile var meminfo: () -> ProcessResult = { ProcessResult(0, SUMMARY, "") }
    @Volatile var smapsPss = 4242
    @Volatile var pid = 777
    @Volatile var running = true

    val adb = Adb(Path.of("/fake/adb"), this)

    override fun run(args: List<String>, timeout: Duration, stdoutTo: Path?, cancel: CancelToken, onBytes: (Long) -> Unit): ProcessResult {
        val c = args.drop(3) // adb -s <serial>
        commands += c
        timeouts += timeout
        return when {
            c.getOrNull(1) == "dumpsys" -> meminfo()
            c.getOrNull(1) == "ps" -> ProcessResult(0, "PID NAME\n" + if (running) "$pid dev.app\n" else "", "")
            c.getOrNull(1) == "run-as" && c.getOrNull(3) == "cat" ->
                if (running && c[4] == "/proc/$pid/smaps_rollup") ProcessResult(0, "Rss: 1 kB\nPss:   $smapsPss kB\n", "")
                else ProcessResult(1, "", "cat: /proc/${c[4]}: No such file or directory")
            else -> ProcessResult(0, "", "")
        }
    }
}

class MeminfoPollerTest {
    @TempDir lateinit var tmp: Path
    private val phone = FakePhone()
    private lateinit var store: SessionStore
    private lateinit var poller: MeminfoPoller
    private lateinit var sid: String
    private var now = 0L

    @BeforeEach
    fun setUp() {
        store = SessionStore(tmp)
        sid = store.create("emulator-5554", "dev.app", SessionMeta.Mode.ATTACH).id
        now = store.get(sid)!!.createdAtMs
        poller = MeminfoPoller(phone.adb, store, clock = { now })
        poller.begin(sid, "dev.app", 1000)
    }

    private fun samples() = MemTimeline.read(store, sid, null).samples

    @Test
    fun `a good poll appends a sample stamped relative to the session start`() {
        now += 2500
        assertTrue(poller.pollOnce())
        val s = samples().single()
        assertEquals(2500, s.tMs)
        assertEquals(1000, s.javaHeapKb)
        assertEquals(3970, s.totalPssKb)
        assertFalse(s.paused)
        assertEquals(listOf("shell", "dumpsys", "meminfo", "dev.app"), phone.commands.single())
        assertEquals(5, phone.timeouts.single().inWholeSeconds)
    }

    @Test
    fun `the IoException text of a suspended app is a failed poll and the interval doubles up to 8 s then recovers`() {
        phone.meminfo = { ProcessResult(0, "Applications Memory Usage (in Kilobytes):\nGot IoException! java.io.IOException: Timeout\n", "") }
        val seen = ArrayList<Long>()
        repeat(5) { assertFalse(poller.pollOnce()); seen += poller.currentIntervalMs }
        assertEquals(listOf(2000L, 4000L, 8000L, 8000L, 8000L), seen)
        assertTrue(samples().isEmpty())
        phone.meminfo = { ProcessResult(0, SUMMARY, "") }
        assertTrue(poller.pollOnce())
        assertEquals(1000, poller.currentIntervalMs)
        assertEquals(1, samples().size)
    }

    @Test
    fun `a timed-out adb call is a failed poll with backoff`() {
        phone.meminfo = { throw DeviceException(DeviceException.Kind.TIMEOUT, "adb shell dumpsys timed out after 5s", "retry") }
        assertFalse(poller.pollOnce())
        assertEquals(2000, poller.currentIntervalMs)
        assertTrue(samples().isEmpty())
    }

    @Test
    fun `a missing process records nothing and keeps the poller alive at a backed-off interval until it returns`() {
        phone.meminfo = { ProcessResult(0, "No process found for: dev.app\n", "") }
        assertFalse(poller.pollOnce())
        assertFalse(poller.pollOnce())
        assertEquals(4000, poller.currentIntervalMs)
        phone.meminfo = { ProcessResult(0, SUMMARY, "") }
        now += 9000
        assertTrue(poller.pollOnce())
        assertEquals(9000, samples().single().tMs)
    }

    @Test
    fun `paused polls read smaps_rollup, carry the other fields forward and are flagged`() {
        now += 1000
        assertTrue(poller.pollOnce())
        poller.setPaused(true)
        now += 1000
        assertTrue(poller.pollOnce())
        val (running, held) = samples()
        assertFalse(running.paused)
        assertTrue(held.paused)
        assertEquals(4242, held.totalPssKb)
        assertEquals(running.javaHeapKb, held.javaHeapKb)
        assertEquals(running.graphicsKb, held.graphicsKb)
        assertEquals(running.otherKb, held.otherKb)
        val last = phone.commands.last()
        assertEquals(listOf("shell", "run-as", "dev.app", "cat", "/proc/777/smaps_rollup"), last)
        poller.setPaused(false)
        now += 1000
        assertTrue(poller.pollOnce())
        assertFalse(samples().last().paused)
    }

    @Test
    fun `a paused poll before any running sample carries zeros`() {
        poller.setPaused(true)
        assertTrue(poller.pollOnce())
        val s = samples().single()
        assertTrue(s.paused)
        assertEquals(4242, s.totalPssKb)
        assertEquals(0, s.javaHeapKb)
    }

    @Test
    fun `paused polling of a process that is gone fails softly`() {
        poller.setPaused(true)
        phone.running = false
        assertFalse(poller.pollOnce())
        assertTrue(samples().isEmpty())
        phone.running = true
        phone.pid = 888 // came back with a new pid
        assertTrue(poller.pollOnce())
        assertEquals(listOf("shell", "run-as", "dev.app", "cat", "/proc/888/smaps_rollup"), phone.commands.last())
    }

    @Test
    fun `an ended session stops the poll and records nothing`() {
        store.update(sid) { it.copy(state = SessionMeta.State.ENDED) }
        assertFalse(poller.pollOnce())
        assertTrue(phone.commands.isEmpty())
        assertTrue(poller.isFinished)
    }

    @Test
    fun `timeline read honours sinceMs and derives markers from snapshots`() {
        repeat(3) { now += 1000; poller.pollOnce() }
        val created = store.get(sid)!!.createdAtMs
        store.addSnapshot(sid, SnapshotMeta("s1", "Application.onCreate", "application_oncreate", "dev.app", created + 1500, "s1.hprof", 10, SnapshotMeta.Status.OK))
        store.addSnapshot(sid, SnapshotMeta("s2", "Snapshot 2", null, "dev.app", created + 2500, "s2.hprof", 10, SnapshotMeta.Status.OK))
        val all = MemTimeline.read(store, sid, null)
        assertEquals(listOf(1000L, 2000L, 3000L), all.samples.map { it.tMs })
        assertEquals(
            listOf(
                TimelineMarker(1500, "milestone", "Application.onCreate", "s1"),
                TimelineMarker(2500, "dump", "Snapshot 2", "s2"),
            ),
            all.markers,
        )
        val newer = MemTimeline.read(store, sid, 2000)
        assertEquals(listOf(3000L), newer.samples.map { it.tMs })
        assertEquals(2, newer.markers.size)
    }

    @Test
    fun `a torn last line in meminfo jsonl is skipped`() {
        now += 1000; poller.pollOnce()
        java.nio.file.Files.writeString(store.dir(sid).resolve("meminfo.jsonl"), "{\"tMs\":5,\"javaH", java.nio.file.StandardOpenOption.APPEND)
        assertEquals(1, samples().size)
    }

    @Test
    fun `start runs a background loop that stops with the session and with stop()`() {
        val p = MeminfoPoller(phone.adb, store)
        p.start(sid, "dev.app", 20)
        val deadline = System.currentTimeMillis() + 5000
        while (MemTimeline.read(store, sid, null).samples.size < 3 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(MemTimeline.read(store, sid, null).samples.size >= 3)
        p.stop()
        val n = MemTimeline.read(store, sid, null).samples.size
        Thread.sleep(150)
        assertEquals(n, MemTimeline.read(store, sid, null).samples.size)
    }
}
