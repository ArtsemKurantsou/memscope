package dev.memscope.capture

/**
 * Runs a startup recording and, when it fails for a reason a fresh attempt probably avoids (the app's VM wedged under the
 * debugger, or the app was already past its debugger wait) before any snapshot was taken, runs it once more from the
 * Prepare step in the same job. The first attempt's failure is noted in the Prepare step from the moment the retry
 * starts. Never more than one retry; a failure after snapshots exist, a cancel, or any other failure is surfaced as
 * [StartupSession.run] would. An error in the bookkeeping between the attempts never replaces the first failure.
 * [newSession] builds each attempt's session: the retry is told to keep the old behaviour for a not-waiting app.
 */
class StartupRetry(
    private val store: SessionStore,
    private val newSession: (abortIfNotWaiting: Boolean, startNote: String?) -> StartupSession,
) {
    fun run(sessionId: String, config: StartupConfig, job: Job) {
        val first = newSession(true, null)
        val original = try {
            first.run(sessionId, config, job, failJobOnError = false)
            return
        } catch (t: Throwable) {
            t
        }
        val second = try {
            val reason = first.retryable
            if (reason == null || job.cancel.isCancelled || store.get(sessionId)?.snapshots?.isNotEmpty() == true) {
                null
            } else {
                val note = "first attempt failed: ${original.message?.lineSequence()?.firstOrNull() ?: reason.name}; retrying once"
                job.resetSteps()
                job.note(StartupSession.PREPARE, note) // visible at once, and kept if the second Prepare fails
                newSession(false, note)
            }
        } catch (t: Throwable) {
            original.addSuppressed(t)
            null
        }
        if (second == null) {
            job.fail(original)
            throw original
        }
        second.run(sessionId, config, job)
    }
}
