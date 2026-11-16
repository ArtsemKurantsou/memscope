package dev.memscope.server

import dev.memscope.capture.Job
import dev.memscope.device.DeviceException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory jobs plus the worker pools that run them. At most [keepFinished] finished jobs are
 * retained (oldest dropped first); running jobs are never dropped.
 *
 * Two lanes: [Lane.DEVICE] (dumps, startup recordings) has one worker — one device session at a time — and never
 * waits behind [Lane.ANALYSIS] (analysis, findings, warm-up), which has [workers] workers.
 */
class JobRegistry(workers: Int = 3, private val keepFinished: Int = 50) : AutoCloseable {
    enum class Lane { DEVICE, ANALYSIS }

    /** What a job works on: a whole session ([snapshotId] null: a dump or a recording) or one snapshot of it. */
    data class Scope(val sessionId: String, val snapshotId: String?, val what: String)

    private val scopes = HashMap<String, Scope>()

    private val jobs = LinkedHashMap<String, Job>()
    private val counter = AtomicLong()
    private val analysisPool = pool("memscope-worker", workers)
    private val devicePool = pool("memscope-device", 1)

    /** Ids of submitted jobs that have not started; whoever removes an id first (start or cancel) decides. */
    private val notStarted = ConcurrentHashMap.newKeySet<String>()

    private fun pool(name: String, threads: Int): ExecutorService = ThreadPoolExecutor(
        threads, threads, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
        object : ThreadFactory {
            private val n = AtomicLong()
            override fun newThread(r: Runnable) = Thread(r, "$name-${n.incrementAndGet()}").apply { isDaemon = true }
        },
    )

    private fun poolOf(lane: Lane) = if (lane == Lane.DEVICE) devicePool else analysisPool

    fun create(title: String, steps: List<String>, scope: Scope? = null): Job = synchronized(jobs) {
        val job = Job("j${counter.incrementAndGet()}", title, steps)
        jobs[job.id] = job
        if (scope != null) scopes[job.id] = scope
        val finished = jobs.values.filter { it.snapshot().state != Job.State.RUNNING }
        finished.take((finished.size - keepFinished).coerceAtLeast(0)).forEach { jobs.remove(it.id); scopes.remove(it.id) }
        job
    }

    /**
     * Running (or queued) jobs that would be affected by removing session [sessionId] ([snapshotId] null) or its snapshot
     * [snapshotId]: the session's dumps and recordings, and the analysis / findings jobs of that snapshot (of any, for null).
     */
    fun runningFor(sessionId: String, snapshotId: String? = null): List<Pair<Job, Scope>> = synchronized(jobs) {
        scopes.entries.mapNotNull { (id, scope) ->
            val job = jobs[id] ?: return@mapNotNull null
            val hit = scope.sessionId == sessionId && (snapshotId == null || scope.snapshotId == null || scope.snapshotId == snapshotId)
            if (hit && job.snapshot().state == Job.State.RUNNING) job to scope else null
        }
    }

    fun get(id: String): Job? = synchronized(jobs) { jobs[id] }

    /**
     * Runs [work] on [lane]'s pool. Whatever it throws marks [job] as failed (a no-op if it already is).
     * Until it starts, the job's first step says it is queued; cancelling it meanwhile ends it as CANCELLED at once,
     * [work] never runs and [onSkipped] runs instead (on the cancelling thread).
     */
    fun submit(job: Job, lane: Lane = Lane.ANALYSIS, onSkipped: () -> Unit = {}, work: () -> Unit) {
        val first = job.snapshot().steps.firstOrNull()?.name
        notStarted += job.id
        first?.let { job.note(it, QUEUED_NOTE) }
        job.cancel.onCancel {
            if (notStarted.remove(job.id)) {
                job.fail(DeviceException(DeviceException.Kind.CANCELLED, "Cancelled before it started.", "Start it again when you need it."))
                try { onSkipped() } catch (t: Throwable) { ServerLog.error("clean-up of skipped job ${job.id} failed: ${t.message}", t) }
            }
        }
        poolOf(lane).execute {
            if (!notStarted.remove(job.id)) return@execute // cancelled while queued
            first?.let { job.clearNote(it, QUEUED_NOTE) }
            try {
                work()
            } catch (t: Throwable) {
                job.fail(t)
                val device = t as? DeviceException
                if (device != null) {
                    ServerLog.error("job ${job.id} (${job.title}) failed: kind=${device.kind} message=${device.message} hint=${device.hint}")
                } else {
                    ServerLog.error("job ${job.id} (${job.title}) failed: ${t.message}", t)
                }
            }
        }
    }

    /** Fire-and-forget analysis work with no job (cache warm-up); failures are ignored. */
    fun background(work: () -> Unit) {
        analysisPool.execute { try { work() } catch (_: Throwable) { /* best effort */ } }
    }

    override fun close() {
        devicePool.shutdownNow()
        analysisPool.shutdownNow()
    }

    companion object {
        const val QUEUED_NOTE = "queued — waiting for a previous job to finish"
    }
}
