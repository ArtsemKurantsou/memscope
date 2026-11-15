package dev.memscope.server

import dev.memscope.capture.Job
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory jobs plus the bounded worker pool that runs them. At most [keepFinished] finished jobs are
 * retained (oldest dropped first); running jobs are never dropped.
 */
class JobRegistry(workers: Int = 4, private val keepFinished: Int = 50) : AutoCloseable {
    private val jobs = LinkedHashMap<String, Job>()
    private val counter = AtomicLong()
    private val pool = ThreadPoolExecutor(
        workers, workers, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
        object : ThreadFactory {
            private val n = AtomicLong()
            override fun newThread(r: Runnable) = Thread(r, "memscope-worker-${n.incrementAndGet()}").apply { isDaemon = true }
        },
    )

    fun create(title: String, steps: List<String>): Job = synchronized(jobs) {
        val job = Job("j${counter.incrementAndGet()}", title, steps)
        jobs[job.id] = job
        val finished = jobs.values.filter { it.snapshot().state != Job.State.RUNNING }
        finished.take((finished.size - keepFinished).coerceAtLeast(0)).forEach { jobs.remove(it.id) }
        job
    }

    fun get(id: String): Job? = synchronized(jobs) { jobs[id] }

    /** Runs [work] on the pool. Whatever it throws marks [job] as failed (a no-op if it already is). */
    fun submit(job: Job, work: () -> Unit) {
        pool.execute {
            try {
                work()
            } catch (t: Throwable) {
                job.fail(t)
            }
        }
    }

    /** Fire-and-forget work with no job (cache warm-up); failures are ignored. */
    fun background(work: () -> Unit) {
        pool.execute { try { work() } catch (_: Throwable) { /* best effort */ } }
    }

    override fun close() {
        pool.shutdownNow()
    }
}
