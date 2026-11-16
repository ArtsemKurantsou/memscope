package dev.memscope.capture

/** A thread of the debugged VM, by its JDWP id. */
data class DebugThread(val id: Long, val name: String)

/** What the debugged VM reported, translated from one JDI event set. */
sealed interface DebugEvent {
    /** The main thread returned from `ActivityThread.waitForDebugger`: the app is running and past the wait. */
    data class WaitReturned(val thread: DebugThread) : DebugEvent

    /** The entry of an `afterReturn` hook was hit: arm the caller-return breakpoint on [thread]. */
    data class EntryHit(val key: String, val thread: DebugThread) : DebugEvent

    /** The milestone location of [key] was reached (an entry hook, or the caller-return of an `afterReturn` hook). */
    data class Reached(val key: String, val thread: DebugThread) : DebugEvent

    /** A deferred hook's class was loaded and its breakpoints are set. */
    data class HookResolved(val key: String) : DebugEvent

    /** The hook of [key] can no longer stop: its class has no such method, or the caller returned without hitting. */
    data class HookFailed(val key: String, val reason: String) : DebugEvent

    /** The VM is gone (app exited, killed, or the connection dropped). */
    data object Disconnected : DebugEvent
}

sealed interface HookStatus {
    data object Resolved : HookStatus
    /** The class is not loaded yet; a class-prepare request will resolve it (reported as [DebugEvent.HookResolved]/[DebugEvent.HookFailed]). */
    data object Deferred : HookStatus
    data class Failed(val reason: String) : HookStatus
}

/** The debugged VM went away during a call. */
class DebuggerDisconnected(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The thin JDI layer a [StartupSession] drives. All calls come from one thread, except [dispose], which may be called
 * from another thread to unblock a hung [attach] or [invokeDump]. Breakpoints suspend the whole VM.
 */
interface DebuggerPort {
    /** Attaches over the forwarded JDWP port; throws on failure. */
    fun attach(localPort: Int, timeoutMs: Long)

    fun suspendAll()

    /**
     * Arms a main-thread-filtered breakpoint at the return of `ActivityThread.waitForDebugger` into `handleBindApplication`.
     * Returns null on success, else why it could not be armed. Its hit is reported as [DebugEvent.WaitReturned].
     */
    fun armWaitReturn(): String?

    /** Entry breakpoints on every matching method declared in the hook's class, tagged with [key]. */
    fun setEntryBreakpoints(key: String, hook: Hook): HookStatus

    /** Thread-filtered breakpoint where the current call on [thread] returns to its caller, tagged with [key]. Null on success, else the reason. */
    fun armCallerReturn(key: String, thread: DebugThread): String?

    /** Deletes every request tagged with [key]. */
    fun clearHook(key: String)

    /** Resumes the last reported event set, or the whole VM if there is none. */
    fun resume()

    /** Waits up to [timeoutMs] for the next event set. Null on timeout. Waiting sends no JDWP traffic. */
    fun nextEvents(timeoutMs: Long): List<DebugEvent>?

    /**
     * Calls `android.os.Debug.dumpHprofData(remotePath)` on [thread] (which must be stopped at an event), single-threaded.
     * Returns null on success, else the Java exception the app threw (class and message). Throws [DebuggerDisconnected].
     */
    fun invokeDump(thread: DebugThread, remotePath: String): String?

    /**
     * For a failure message when a milestone never came: suspends the VM (the session is failing anyway) and describes
     * the armed requests and the main thread (suspend count before suspending, status, top frames). Never throws.
     */
    fun diagnose(): String = "no diagnosis available"

    /** A cheap round trip (JDWP AllThreads) to tell a quiet VM from a wedged one; blocks if the VM does not answer. */
    fun ping() {}

    /** Deletes all requests, resumes and closes the connection. Idempotent; never throws. */
    fun dispose()
}

/** Start of the reason [DebuggerPort.armWaitReturn] returns when the app had already passed its debugger wait. */
const val APP_NOT_WAITING_REASON = "the app was not waiting for the debugger when it attached"
