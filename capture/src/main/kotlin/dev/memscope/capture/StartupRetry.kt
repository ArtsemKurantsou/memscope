package dev.memscope.capture

import dev.memscope.device.DeviceException

/**
 * Runs a startup recording and, when it fails for a reason a fresh attempt probably avoids (the app's VM wedged under the
 * debugger, or the app was already past its debugger wait) before any snapshot was taken, runs it once more from the
 * Prepare step in the same job. The first attempt's failure is noted in the Prepare step. Never more than one retry; a
 * failure after snapshots exist, a cancel, or any other failure is surfaced as [StartupSession.run] would.
 * [newSession] builds each attempt's session: the retry is told to keep the old behaviour for a not-waiting app.
 */
class StartupRetry(
    private val store: SessionStore,
    private val newSession: (abortIfNotWaiting: Boolean, startNote: String?) -> StartupSession,
) {
    fun run(sessionId: String, config: StartupConfig, job: Job) {
        val first = newSession(true, null)
        try {
            first.run(sessionId, config, job, failJobOnError = false)
            return
        } catch (t: Throwable) {
            val reason = first.retryable
            if (reason == null || job.cancel.isCancelled || store.get(sessionId)?.snapshots?.isNotEmpty() == true) {
                job.fail(t)
                throw t
            }
            job.resetSteps()
            val note = "first attempt failed: ${t.message?.lineSequence()?.firstOrNull() ?: reason.name}; retrying once"
            newSession(false, note).run(sessionId, config, job)
        }
    }
}
