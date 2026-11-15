package dev.memscope.server

import dev.memscope.analysis.Progress
import dev.memscope.capture.Job
import dev.memscope.device.DeviceException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Step names of an analysis ("open") job. */
val ANALYSIS_STEPS = listOf("Parse", "Index", "Dominators")

private sealed interface Event
private class Report(val step: String, val detail: String) : Event
private class Finished(val view: SnapshotView) : Event
private class Failed(val error: Throwable) : Event

/**
 * Runs [open] on its own thread and mirrors its [Progress] callbacks onto [job]'s steps.
 * The indexer reports "parse" (first half: pass 1, second half: pass 2), "dominators", "retained",
 * "tree", and "load" (cache hit) or "cache" (cache write). They map to: Parse = pass 1; Index = pass 2
 * or loading the cache; Dominators = dominators, retained sizes, tree; cache write stays in Dominators.
 * Steps never go backwards. Steps that never ran (cache hit skips Parse) are marked done at the end.
 * Cancelling the job abandons the work; a snapshot opened afterwards is closed. [exited] completes when the
 * analysis thread has really finished (also after an abandon), so callers can avoid running two at once.
 */
fun runAnalysis(job: Job, exited: java.util.concurrent.CompletableFuture<Unit>, open: (Progress) -> SnapshotView): SnapshotView {
    val queue = LinkedBlockingQueue<Event>()
    val lock = Any()
    var abandoned = false

    fun target(step: String, fraction: Double): Int = when (step) {
        "parse" -> if (fraction < 0.5) 0 else 1
        "load" -> 1
        "dominators", "retained", "tree" -> 2
        else -> 0 // e.g. "cache": detail only, the step does not go back
    }

    Thread({
        var last = 0 // steps never go backwards
        try {
            val view = open { step, fraction ->
                val r = maxOf(target(step, fraction), last).also { last = it }
                val local = if (step == "parse") (if (fraction < 0.5) fraction * 2 else (fraction - 0.5) * 2) else fraction
                queue.put(Report(ANALYSIS_STEPS[r], "%d%%".format((local * 100).toInt().coerceIn(0, 100))))
            }
            synchronized(lock) { if (abandoned) view.close() else queue.put(Finished(view)) }
        } catch (t: Throwable) {
            queue.put(Failed(t))
        } finally {
            exited.complete(Unit) // only now may another analysis of the same file start
        }
    }, "memscope-analysis").apply { isDaemon = true; start() }

    fun next(): Event {
        while (true) {
            if (job.cancel.isCancelled) {
                synchronized(lock) {
                    abandoned = true
                    (queue.toList().firstOrNull { it is Finished } as Finished?)?.view?.close()
                }
                throw DeviceException(DeviceException.Kind.CANCELLED, "Analysis was cancelled.", "Open the snapshot again to retry.")
            }
            queue.poll(100, TimeUnit.MILLISECONDS)?.let { return it }
        }
    }

    val ran = HashSet<String>()
    var event = next()
    while (true) {
        when (val e = event) {
            is Finished -> {
                ANALYSIS_STEPS.filter { it !in ran }.forEach { job.step(it) { } }
                return e.view
            }
            is Failed -> throw e.error
            is Report -> {
                var following: Event? = null
                ran += e.step
                job.step(e.step) { detail ->
                    var current: Report = e
                    while (true) {
                        detail(current.detail)
                        val n = next()
                        when {
                            n is Failed -> throw n.error // the running step fails with it
                            n is Report && n.step == e.step -> current = n
                            else -> { following = n; break }
                        }
                    }
                }
                event = following!!
            }
        }
    }
}
