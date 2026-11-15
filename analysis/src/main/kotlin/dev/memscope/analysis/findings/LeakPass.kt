package dev.memscope.analysis.findings

import dev.memscope.analysis.Snapshot
import shark.AndroidObjectInspectors
import shark.AndroidReferenceMatchers
import shark.FilteringLeakingObjectFinder
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.HeapAnalyzer
import shark.HeapGraph
import shark.LeakTrace
import shark.LeakingObjectFinder
import shark.MetadataExtractor
import shark.OnAnalysisProgressListener
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Result of Shark's own leak analysis of an hprof. */
internal sealed interface LeakPassResult {
    class Done(val traces: List<LeakTrace>, val leakingIds: Set<Long>) : LeakPassResult
    object TimedOut : LeakPassResult
    class Failed(val message: String) : LeakPassResult
}

/**
 * Shark's leak analysis (destroyed Activities, Fragments, Views, Services ... still strongly reachable) as a
 * separate pass over the hprof, bounded by [timeoutMs]. Shark has no cancel: the analysis runs on a daemon
 * thread, and after the deadline its progress listener and leaking-object filter throw to stop it at the next step.
 */
internal object LeakPass {
    private class Deadline(val atNanos: Long, val expired: AtomicBoolean = AtomicBoolean(false)) {
        fun check() { if (expired.get() || System.nanoTime() > atNanos) { expired.set(true); throw LeakTimeout() } }
    }
    private class LeakTimeout : RuntimeException("leak analysis deadline passed")

    fun run(hprof: File, timeoutMs: Long): LeakPassResult {
        val deadline = Deadline(System.nanoTime() + timeoutMs * 1_000_000)
        val ids = HashSet<Long>()
        val finder = LeakingObjectFinder { graph: HeapGraph ->
            deadline.check()
            FilteringLeakingObjectFinder(AndroidObjectInspectors.appLeakingObjectFilters).findLeakingObjectIds(graph).also { ids += it }
        }
        var outcome: LeakPassResult = LeakPassResult.Failed("leak analysis did not finish")
        val thread = Thread({
            outcome = try {
                val analysis = HeapAnalyzer(OnAnalysisProgressListener { deadline.check() }).analyze(
                    heapDumpFile = hprof, leakingObjectFinder = finder,
                    referenceMatchers = AndroidReferenceMatchers.appDefaults, computeRetainedHeapSize = false,
                    objectInspectors = AndroidObjectInspectors.appDefaults, metadataExtractor = MetadataExtractor.NO_OP,
                )
                when (analysis) {
                    is HeapAnalysisSuccess -> LeakPassResult.Done(analysis.applicationLeaks.flatMap { it.leakTraces }, ids.toSet())
                    is HeapAnalysisFailure -> LeakPassResult.Failed(analysis.exception.let { ex -> (ex.cause ?: ex).let { c -> (c.message ?: c.javaClass.simpleName) + (c.stackTrace.take(3).joinToString(prefix = " at ", separator = " < ")) } })
                }
            } catch (_: LeakTimeout) {
                LeakPassResult.TimedOut
            } catch (e: Throwable) {
                if (e.cause is LeakTimeout) LeakPassResult.TimedOut else LeakPassResult.Failed((e.message ?: e.javaClass.simpleName) + (e.stackTrace.firstOrNull()?.let { " at $it" } ?: ""))
            }
        }, "memscope-leak-pass").apply { isDaemon = true; start() }
        thread.join(TimeUnit.MILLISECONDS.toMillis(timeoutMs))
        if (thread.isAlive) {
            deadline.expired.set(true) // stops at the next Shark step
            return LeakPassResult.TimedOut
        }
        return outcome
    }
}
