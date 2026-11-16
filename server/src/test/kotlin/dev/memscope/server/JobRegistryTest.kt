package dev.memscope.server

import dev.memscope.capture.Job
import dev.memscope.capture.SessionStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class JobRegistryTest {
    private val reg = JobRegistry(workers = 1)
    private val release = CountDownLatch(1)

    @AfterEach fun close() { release.countDown(); reg.close() }

    private fun blocker(lane: JobRegistry.Lane): Job {
        val started = CountDownLatch(1)
        val job = reg.create("blocker", listOf("Work"))
        reg.submit(job, lane) { started.countDown(); release.await(); job.complete(null) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        return job
    }

    private fun waitUntil(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(cond(), "condition not met")
    }

    @Test fun deviceJobsDoNotQueueBehindAnalysis() {
        blocker(JobRegistry.Lane.ANALYSIS)
        val ran = CountDownLatch(1)
        val dump = reg.create("dump", listOf("Dump"))
        reg.submit(dump, JobRegistry.Lane.DEVICE) { ran.countDown(); dump.complete(null) }
        assertTrue(ran.await(5, TimeUnit.SECONDS), "the dump runs while the analysis worker is busy")
    }

    @Test fun aQueuedJobSaysSoOnItsFirstStepUntilItStarts() {
        blocker(JobRegistry.Lane.DEVICE)
        val seenAtStart = java.util.concurrent.atomic.AtomicReference<String?>("unset")
        val queued = reg.create("recording", listOf("Prepare", "Launch"))
        reg.submit(queued, JobRegistry.Lane.DEVICE) { seenAtStart.set(queued.snapshot().steps[0].detail); queued.complete(null) }
        val step = queued.snapshot().steps[0]
        assertEquals(Job.StepState.PENDING, step.state)
        assertEquals(JobRegistry.QUEUED_NOTE, step.detail)
        assertNull(queued.snapshot().steps[1].detail)
        release.countDown()
        waitUntil { queued.snapshot().state == Job.State.DONE }
        assertNull(seenAtStart.get(), "the note is cleared when the job starts")
    }

    @Test fun aJobThatStartsAtOnceHasNoQueuedNote() {
        val seen = java.util.concurrent.atomic.AtomicReference<String?>("unset")
        val job = reg.create("dump", listOf("Dump"))
        reg.submit(job, JobRegistry.Lane.DEVICE) { seen.set(job.snapshot().steps[0].detail); job.complete(null) }
        waitUntil { job.snapshot().state == Job.State.DONE }
        assertNull(seen.get())
    }

    @Test fun cancellingAQueuedJobEndsItAtOnceAndItIsSkipped() {
        blocker(JobRegistry.Lane.DEVICE)
        val ran = AtomicBoolean(false)
        val skipped = AtomicInteger()
        val queued = reg.create("recording", listOf("Prepare"))
        reg.submit(queued, JobRegistry.Lane.DEVICE, onSkipped = { skipped.incrementAndGet() }) { ran.set(true) }
        queued.cancel.cancel()
        assertEquals(Job.State.CANCELLED, queued.snapshot().state, "cancelled immediately, not when dequeued")
        assertEquals(1, skipped.get())
        val after = reg.create("after", listOf("Dump"))
        reg.submit(after, JobRegistry.Lane.DEVICE) { after.complete(null) }
        release.countDown()
        waitUntil { after.snapshot().state == Job.State.DONE }
        assertFalse(ran.get(), "a cancelled queued job never runs")
        assertEquals(1, skipped.get())
    }

    @Test fun cancellingARunningJobOnlyFlagsIt() {
        val job = blocker(JobRegistry.Lane.DEVICE)
        job.cancel.cancel()
        assertEquals(Job.State.RUNNING, job.snapshot().state, "the work itself notices the token and cleans up")
    }

    @Test fun aQueuedCancelledRecordingIsNotTheRunningStartup(@TempDir home: Path) {
        val deps = ServerDeps(SessionStore(home), { FakeAdbRunner().adb }, jobs = reg, memoryPolling = false)
        blocker(JobRegistry.Lane.DEVICE) // e.g. a dump in progress
        val recording = reg.create("Record startup", listOf("Prepare"))
        deps.setRunningStartup(ServerDeps.RunningStartup("s1", recording))
        reg.submit(recording, JobRegistry.Lane.DEVICE) { error("must not run") }
        assertNotNull(deps.runningStartup())
        recording.cancel.cancel()
        assertNull(deps.runningStartup())
    }
}
