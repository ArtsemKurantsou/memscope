package dev.memscope.capture

import dev.memscope.device.CancelToken
import dev.memscope.device.DeviceException
import kotlinx.serialization.Serializable

/** Immutable, serializable snapshot of a [Job] for the UI. */
@Serializable
data class JobView(
    val id: String,
    val title: String,
    val state: Job.State,
    val steps: List<Job.Step>,
    val startedAtMs: Long,
    val elapsedMs: Long,
    val errorMessage: String?,
    val errorHint: String?,
    val resultRef: String?,
)

/**
 * Progress model for a long operation. All methods are thread-safe. Step names are declared up front
 * (PENDING) so the UI can draw the whole stepper from the start.
 */
class Job(
    val id: String,
    val title: String,
    stepNames: List<String> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    data class Step(val name: String, val state: StepState, val detail: String? = null)

    enum class StepState { PENDING, RUNNING, DONE, FAILED }
    enum class State { RUNNING, DONE, FAILED, CANCELLED }

    val cancel: CancelToken = CancelToken()

    private val lock = Any()
    private val startedAtMs = clock()
    private var finishedAtMs: Long? = null
    private var state = State.RUNNING
    private val steps = stepNames.mapTo(ArrayList()) { Step(it, StepState.PENDING) }
    private var errorMessage: String? = null
    private var errorHint: String? = null
    private var resultRef: String? = null

    /**
     * Runs [block] as step [name]: RUNNING, then DONE, or FAILED if it throws (the exception propagates).
     * [block] receives a function that sets the step's detail text (e.g. "12.3 MB pulled").
     * A name that was not declared up front is appended.
     */
    fun <T> step(name: String, block: (detail: (String) -> Unit) -> T): T {
        setStep(name, StepState.RUNNING, null)
        val result = try {
            block { text -> synchronized(lock) { replace(name) { it.copy(detail = text) } } }
        } catch (t: Throwable) {
            synchronized(lock) { replace(name) { it.copy(state = StepState.FAILED) } }
            throw t
        }
        synchronized(lock) { replace(name) { it.copy(state = StepState.DONE) } }
        return result
    }

    /** Marks step [name] FAILED with [detail] without running it (e.g. a milestone that was never reached). */
    fun failStep(name: String, detail: String) = setStep(name, StepState.FAILED, detail)

    /** Sets the detail text of step [name] without changing its state (e.g. what a pending step is waiting for). */
    fun note(name: String, detail: String) = synchronized(lock) { replace(name) { it.copy(detail = detail) } }

    /** Marks the job as finished successfully. */
    fun complete(resultRef: String?) = synchronized(lock) {
        if (state != State.RUNNING) return
        this.resultRef = resultRef
        state = State.DONE
        finishedAtMs = clock()
    }

    /** Marks the job as failed (or CANCELLED for [DeviceException.Kind.CANCELLED]); a running step becomes FAILED. */
    fun fail(e: Throwable) = synchronized(lock) {
        if (state != State.RUNNING) return
        val device = e as? DeviceException
        state = if (device?.kind == DeviceException.Kind.CANCELLED) State.CANCELLED else State.FAILED
        errorMessage = e.message ?: e.javaClass.simpleName
        errorHint = device?.hint
        steps.indices.filter { steps[it].state == StepState.RUNNING }.forEach { steps[it] = steps[it].copy(state = StepState.FAILED) }
        finishedAtMs = clock()
    }

    fun snapshot(): JobView = synchronized(lock) {
        JobView(
            id, title, state, steps.toList(), startedAtMs, (finishedAtMs ?: clock()) - startedAtMs,
            errorMessage, errorHint, resultRef,
        )
    }

    private fun setStep(name: String, newState: StepState, detail: String?) = synchronized(lock) {
        if (steps.none { it.name == name }) steps += Step(name, StepState.PENDING)
        replace(name) { it.copy(state = newState, detail = detail) }
    }

    private fun replace(name: String, f: (Step) -> Step) {
        val i = steps.indexOfFirst { it.name == name }
        if (i >= 0) steps[i] = f(steps[i])
    }
}
