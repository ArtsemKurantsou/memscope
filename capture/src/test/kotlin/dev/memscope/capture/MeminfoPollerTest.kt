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
    val tokens = CopyOnWriteArrayList<CancelToken>()
    @Volatile var meminfo: () -> ProcessResult = { ProcessResult(0, SUMMARY, "") }
    @Volatile var smapsPss = 4242
    @Volatile var pid = 777
    @Volatile var running = true

    val adb = Adb(Path.of("/fake/adb"), this)

    override fun run(args: List<String>, timeout: Duration, stdoutTo: Path?, cancel: CancelToken, onBytes: (Long) -> Unit): ProcessResult {
        val c = args.drop(3) // adb -s <serial>
        commands += c
        timeouts += timeout
        tokens += cancel
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
    fun `setPaused during a poll in flight cancels it, discards its late result and does not back off`() {
        val inCall = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        phone.meminfo = { inCall.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS); ProcessResult(0, SUMMARY, "") }
        val result = java.util.concurrent.atomic.AtomicReference<Boolean>()
        val t = Thread { result.set(poller.pollOnce()) }.also { it.start() }
        assertTrue(inCall.await(5, java.util.concurrent.TimeUnit.SECONDS))
        now += 300
        poller.setPaused(true)
        assertTrue(phone.tokens.single().isCancelled, "the in-flight call must be cancelled")
        release.countDown()
        t.join(5000)
        assertFalse(result.get())
        assertTrue(samples().isEmpty(), "a result that arrives after the hold began is discarded")
        assertEquals(1000, poller.currentIntervalMs)
        assertTrue(poller.pollOnce())
        assertTrue(samples().single().paused)
    }

    @Test
    fun `a 300 ms hold still yields a held interval and an open hold has no end`() {
        now += 1000
        poller.setPaused(true)
        assertEquals(listOf(HeldInterval(1000, null)), MemTimeline.read(store, sid, null).held)
        now += 300
        poller.setPaused(false)
        assertEquals(listOf(HeldInterval(1000, 1300)), MemTimeline.read(store, sid, null).held)
        poller.setPaused(false) // no change, no event
        assertEquals(1, MemTimeline.read(store, sid, null).held.size)
    }

    @Test
    fun `a hold still open when the session ended is closed where the data stops`() {
        now += 1000; poller.pollOnce()
        now += 1000; poller.setPaused(true)
        now += 1000; poller.pollOnce()
        store.update(sid) { it.copy(state = SessionMeta.State.ENDED) }
        assertEquals(listOf(HeldInterval(2000, 3000)), MemTimeline.read(store, sid, null).held)
    }

    @Test
    fun `a stopped run cannot write even if its poll finishes later`() {
        val inCall = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        phone.meminfo = { inCall.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS); ProcessResult(0, SUMMARY, "") }
        val t = Thread { poller.pollOnce() }.also { it.start() }
        assertTrue(inCall.await(5, java.util.concurrent.TimeUnit.SECONDS))
        poller.stop()
        assertTrue(phone.tokens.single().isCancelled)
        release.countDown()
        t.join(5000)
        assertTrue(samples().isEmpty())
    }

    @Test
    fun `timeline reads are incremental and a full read is capped and keeps the peak`() {
        val file = store.dir(sid).resolve("meminfo.jsonl")
        fun line(t: Long, total: Int) = "{\"tMs\":$t,\"javaHeapKb\":1,\"nativeHeapKb\":1,\"graphicsKb\":0,\"codeKb\":0,\"stackKb\":0,\"otherKb\":0,\"totalPssKb\":$total,\"paused\":false}\n"
        java.nio.file.Files.writeString(file, (0 until 5000).joinToString("") { line(it * 1000L, if (it == 1234) 99999 else it) })
        val full = MemTimeline.read(store, sid, null)
        assertEquals(MemTimeline.MAX_POINTS, full.samples.size)
        assertTrue(full.samples.any { it.totalPssKb == 99999 })
        assertTrue(full.samples.zipWithNext().all { (a, b) -> a.tMs < b.tMs })
        java.nio.file.Files.writeString(file, line(5_000_000, 5) + line(5_001_000, 6), java.nio.file.StandardOpenOption.APPEND)
        val inc = MemTimeline.read(store, sid, 4_999_000)
        assertEquals(listOf(5_000_000L, 5_001_000L), inc.samples.map { it.tMs })
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

    /** Task 9 fix round 2: `dumpsys meminfo` makes the app walk its heap, which deadlocked a SUSPEND_ALL breakpoint. */
    @Test
    fun `during a startup recording no dumpsys runs, only smaps_rollup, until the recording is done`() {
        poller.setRecording(true)
        now += 1000
        assertTrue(poller.pollOnce())
        val s = samples().single()
        assertFalse(s.paused, "running, just measured without dumpsys")
        assertEquals(4242, s.totalPssKb)
        assertTrue(phone.commands.none { "dumpsys" in it }, phone.commands.toString())
        poller.setRecording(false)
        now += 1000
        assertTrue(poller.pollOnce())
        assertEquals(listOf("shell", "dumpsys", "meminfo", "dev.app"), phone.commands.last())
    }

    @Test
    fun `no dumpsys right after a resume, only once the app ran for 500 ms`() {
        poller.setPaused(true)
        assertTrue(poller.pollOnce())
        poller.setPaused(false)
        now += 100
        assertFalse(poller.pollOnce(), "too soon after the resume")
        assertTrue(phone.commands.none { "dumpsys" in it }, phone.commands.toString())
        assertEquals(1000, poller.currentIntervalMs, "skipping is not a failure: no backoff")
        now += 400
        assertTrue(poller.pollOnce())
        assertEquals(listOf("shell", "dumpsys", "meminfo", "dev.app"), phone.commands.last())
    }

    @Test
    fun `overlapping starts leave a single polling thread`() {
        fun pollerThreads() = Thread.getAllStackTraces().keys.count { it.name == "memscope-meminfo-poller" && it.isAlive }
        val before = pollerThreads()
        val p = MeminfoPoller(phone.adb, store)
        val go = java.util.concurrent.CountDownLatch(1)
        val starters = (1..8).map { Thread { go.await(); p.start(sid, "dev.app", 50) }.apply { start() } }
        go.countDown()
        starters.forEach { it.join(5000) }
        Thread.sleep(300) // replaced runs notice they were stopped
        assertEquals(before + 1, pollerThreads())
        p.stop()
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
