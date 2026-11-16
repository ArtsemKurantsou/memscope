package dev.memscope.analysis.findings

import dev.memscope.analysis.Snapshot
import dev.memscope.analysis.findings.rules.LeakedComponentRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The leak pass runs in a child process: these tests use fake children (real processes), no dump needed for the process handling. */
class LeakPassTest {
    private val dir = java.nio.file.Files.createTempDirectory("leakpass-test").toFile()
    private val hprof = File(dir, "x.hprof")

    private fun launcher(vararg command: String, write: ((File) -> Unit)? = null, onStart: (Process) -> Unit = {}) =
        LeakChildLauncher { _, out, _ -> write?.invoke(out); ProcessBuilder(*command).start().also(onStart) }

    @Test fun aChildThatOutlivesTheDeadlineIsKilled() {
        var child: Process? = null
        val t0 = System.nanoTime()
        val r = LeakPass.run(hprof, 500, launcher("sleep", "30") { child = it })
        assertTrue(r is LeakPassResult.TimedOut)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 10_000, "returned long before the child's 30 s")
        assertFalse(child!!.isAlive, "the process was really destroyed")
    }

    @Test fun cancellingDuringTheChildDestroysIt() {
        var child: Process? = null
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val t0 = System.nanoTime()
        Thread { Thread.sleep(300); cancelled.set(true) }.start()
        val r = LeakPass.run(hprof, 60_000, launcher("sleep", "30") { child = it }, isCancelled = { cancelled.get() })
        assertTrue(r is LeakPassResult.Cancelled, r.toString())
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 10_000, "returned soon after the cancel")
        assertFalse(child!!.isAlive, "the child was destroyed")
    }

    @Test fun aPassQueuedBehindAnotherSaysSoAndCancellingTheFirstFreesTheGate() {
        var first: Process? = null
        val cancelFirst = java.util.concurrent.atomic.AtomicBoolean(false)
        val firstResult = java.util.concurrent.atomic.AtomicReference<LeakPassResult>()
        val t = Thread { firstResult.set(LeakPass.run(hprof, 60_000, launcher("sleep", "30") { first = it }, isCancelled = { cancelFirst.get() })) }
        t.start()
        val deadline = System.currentTimeMillis() + 5000
        while (first == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val second = LeakPass.run(hprof, 300, launcher("true"))
        assertTrue(second is LeakPassResult.Busy, second.toString())
        cancelFirst.set(true)
        t.join(10_000)
        assertTrue(firstResult.get() is LeakPassResult.Cancelled)
        assertTrue(LeakPass.run(hprof, 5000, launcher("sh", "-c", "exit 1")) is LeakPassResult.Failed, "the gate is free again")
    }

    @Test fun anUnwritableTempDirDoesNotLeakTheGate() {
        val missing = File(dir, "no/such/dir")
        val r = LeakPass.run(hprof, 5000, launcher("true"), tmpDir = missing)
        assertTrue(r is LeakPassResult.Failed, r.toString())
        val next = LeakPass.run(hprof, 300, launcher("sh", "-c", "exit 1"))
        assertTrue(next is LeakPassResult.Failed && "exit code 1" in next.message, "the permit was released: $next")
    }

    @Test fun theRuleSaysWhenAnotherPassWasStillRunningAndThrowsWhenCancelled() {
        val h = File(dir, "busy.hprof").also { FindingsHprof.write(it, withLeak = true) }
        Snapshot.open(h, listOf("test.")).use { s ->
            val cancelFirst = java.util.concurrent.atomic.AtomicBoolean(false)
            var first: Process? = null
            val t = Thread { LeakPass.run(h, 60_000, launcher("sleep", "30") { first = it }, isCancelled = { cancelFirst.get() }) }
            t.start()
            val deadline = System.currentTimeMillis() + 5000
            while (first == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
            val busy = LeakedComponentRule(launcher("true")).evaluate(RuleContext(s, h, ScreenSize.FALLBACK, leakTimeoutMs = 300)).single()
            assertTrue(busy.title == "leak analysis skipped: another leak analysis was still running", busy.title)
            cancelFirst.set(true)
            t.join(10_000)
            val ctx = RuleContext(s, h, ScreenSize.FALLBACK, leakTimeoutMs = 60_000, isCancelled = { true })
            org.junit.jupiter.api.assertThrows<FindingsCancelledException> { LeakedComponentRule(launcher("sleep", "30")).evaluate(ctx) }
            org.junit.jupiter.api.assertThrows<FindingsCancelledException> { Findings.forSnapshot(s, isCancelled = { true }) }
        }
    }

    @Test fun anOutOfMemoryExitAndAFailureExitAreTold() {
        assertTrue(LeakPass.run(hprof, 5000, launcher("sh", "-c", "exit 3")) is LeakPassResult.OutOfMemory)
        val failed = LeakPass.run(hprof, 5000, launcher("sh", "-c", "exit 1"))
        assertTrue(failed is LeakPassResult.Failed && "exit code 1" in failed.message, failed.toString())
    }

    @Test fun ruleMapsReportedObjectIdsToRefsAndTellsSkips() {
        val h = File(dir, "leak.hprof").also { FindingsHprof.write(it, withLeak = true) }
        Snapshot.open(h, listOf("test.")).use { s ->
            val ctx = RuleContext(s, h, ScreenSize.FALLBACK, leakTimeoutMs = 5000)
            val json = """{"traces":[{"objectId":${FindingsHprof.LEAKY},"className":"test.LeakyActivity","reason":"Activity#mDestroyed is true",""" +
                """"lines":["GC root: System class","test.Caches.leaked -> test.LeakyActivity   <-- suspect reference","test.LeakyActivity: Activity#mDestroyed is true"],"holder":"Caches.leaked"},""" +
                """{"objectId":999999,"className":"x.Gone","reason":"r","lines":[],"holder":"h"}]}"""
            val ok = LeakedComponentRule(launcher("true", write = { it.writeText(json) })).evaluate(ctx).single()
            assertEquals(Severity.HIGH, ok.severity)
            assertEquals(listOf("obj:${s.index.indexOfObjectId(FindingsHprof.LEAKY)}"), ok.refs)
            assertTrue("suspect reference" in ok.evidence && "`Caches.leaked`" in ok.suggestion)
            val slow = LeakedComponentRule(launcher("sleep", "30")).evaluate(RuleContext(s, h, ScreenSize.FALLBACK, leakTimeoutMs = 300)).single()
            assertEquals(Severity.LOW, slow.severity)
            assertTrue(slow.title.startsWith("leak analysis skipped: took longer than"), slow.title)
            val oom = LeakedComponentRule(launcher("sh", "-c", "exit 3")).evaluate(ctx).single()
            assertTrue("out of memory" in oom.title && "MEMSCOPE_LEAK_XMX" in oom.title, oom.title)
        }
    }

    @Test fun theRealChildJvmFindsTheSyntheticLeak() {
        val h = File(dir, "real.hprof").also { FindingsHprof.write(it, withLeak = true) }
        Snapshot.open(h, listOf("test.")).use { s ->
            val f = LeakedComponentRule().evaluate(RuleContext(s, h, ScreenSize.FALLBACK, leakTimeoutMs = 60_000)).single()
            assertEquals(Severity.HIGH, f.severity, f.title)
            assertTrue("test.Caches.leaked" in f.evidence && "suspect reference" in f.evidence, f.evidence)
            assertEquals(listOf("obj:${s.index.indexOfObjectId(FindingsHprof.LEAKY)}"), f.refs)
        }
    }
}
