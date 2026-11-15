package dev.memscope.capture

import dev.memscope.device.DeviceException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.Executors

class JobTest {
    @Test
    fun `declared steps start pending and step marks running then done`() {
        val job = Job("j1", "t", listOf("A", "B"))
        assertEquals(listOf(Job.StepState.PENDING, Job.StepState.PENDING), job.snapshot().steps.map { it.state })
        job.step("A") { detail ->
            detail("working")
            val v = job.snapshot()
            assertEquals(Job.StepState.RUNNING, v.steps[0].state)
            assertEquals("working", v.steps[0].detail)
        }
        assertEquals(Job.StepState.DONE, job.snapshot().steps[0].state)
        assertEquals(Job.State.RUNNING, job.snapshot().state)
        job.complete("s1")
        val v = job.snapshot()
        assertEquals(Job.State.DONE, v.state)
        assertEquals("s1", v.resultRef)
    }

    @Test
    fun `failing step is marked FAILED and rethrows, fail maps DeviceException`() {
        val job = Job("j", "t", listOf("A"))
        val e = DeviceException(DeviceException.Kind.TIMEOUT, "too slow", "retry")
        assertThrows<DeviceException> { job.step("A") { throw e } }
        job.fail(e)
        val v = job.snapshot()
        assertEquals(Job.StepState.FAILED, v.steps[0].state)
        assertEquals(Job.State.FAILED, v.state)
        assertEquals("too slow", v.errorMessage)
        assertEquals("retry", v.errorHint)
    }

    @Test
    fun `CANCELLED kind maps to CANCELLED state and plain exceptions get a message`() {
        val a = Job("a", "t")
        a.fail(DeviceException(DeviceException.Kind.CANCELLED, "x", "y"))
        assertEquals(Job.State.CANCELLED, a.snapshot().state)
        val b = Job("b", "t")
        b.fail(IllegalStateException("boom"))
        assertEquals(Job.State.FAILED, b.snapshot().state)
        assertEquals("boom", b.snapshot().errorMessage)
        assertNull(b.snapshot().errorHint)
    }

    @Test
    fun `elapsed is frozen once finished and cancel token is shared`() {
        var now = 1000L
        val job = Job("j", "t", clock = { now })
        now = 1500; assertEquals(500, job.snapshot().elapsedMs)
        job.complete(null)
        now = 9000; assertEquals(500, job.snapshot().elapsedMs)
        assertTrue(!job.cancel.isCancelled)
    }

    @Test
    fun `concurrent step updates and snapshots stay consistent`() {
        val names = (1..50).map { "S$it" }
        val job = Job("j", "t", names)
        val pool = Executors.newFixedThreadPool(8)
        val futures = names.map { n -> pool.submit { job.step(n) { d -> d("x") } } } + (1..50).map { pool.submit { job.snapshot() } }
        futures.forEach { it.get() }
        pool.shutdown()
        assertTrue(job.snapshot().steps.all { it.state == Job.StepState.DONE })
    }

    @Test
    fun `failStep marks a step FAILED with a detail without running it, appending unknown names`() {
        val job = Job("j", "t", listOf("A", "B"))
        job.failStep("B", "not reached within 120 s")
        job.failStep("C", "extra")
        val v = job.snapshot()
        assertEquals(listOf("A", "B", "C"), v.steps.map { it.name })
        assertEquals(Job.Step("B", Job.StepState.FAILED, "not reached within 120 s"), v.steps[1])
        assertEquals(Job.StepState.PENDING, v.steps[0].state)
        assertEquals(Job.State.RUNNING, v.state, "a failed step alone does not fail the job")
    }

    @Test
    fun `JobView is serializable`() {
        val json = kotlinx.serialization.json.Json.encodeToString(JobView.serializer(), Job("j", "t", listOf("A")).snapshot())
        assertTrue("\"PENDING\"" in json, json)
    }
}
