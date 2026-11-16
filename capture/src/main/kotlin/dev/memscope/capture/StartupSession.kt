package dev.memscope.capture

import dev.memscope.device.Adb
import dev.memscope.device.DeviceException
import kotlinx.serialization.Serializable
import java.io.EOFException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.GZIPInputStream
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Serializable
data class StartupConfig(
    val milestones: List<Milestone> = Milestones.defaults,
    /** The process to profile; default the package name. `pkg:remote` profiles that process instead. */
    val processName: String? = null,
    /** A component (`pkg/.Activity`) or raw `am start` arguments; default the launcher activity. */
    val launch: String? = null,
    val milestoneTimeoutSec: Int = 120,
    /**
     * Once every enabled built-in milestone was reached, how long to wait for custom hooks whose class has not been
     * loaded yet (likely a typo) instead of the full [milestoneTimeoutSec].
     */
    val customGraceSec: Int = 10,
)

/** Why a failed recording is worth one more try: the app is stopped and nothing was dumped, so the device is clean. */
enum class RetryableFailure {
    /** The app's VM stopped answering the debugger. */
    VM_WEDGED,
    /** The app was already past its debugger wait when the debugger stopped it. */
    APP_NOT_WAITING,
}

/** Every blocking wait of a startup session, in milliseconds. */
data class StartupTimeouts(
    val baselineMs: Long = 5_000,
    val pidMs: Long = 20_000,
    val attachMs: Long = 20_000,
    val dumpMs: Long = 180_000,
    val pullMs: Long = 300_000,
    /** Longest wait for any other JDWP reply (JDI has none of its own): a VM that does not answer is wedged. */
    val jdwpReplyMs: Long = 20_000,
    /** Longest wait for the garbage collection after a dump; a hung one must not fail a dump that succeeded. */
    val gcMs: Long = 20_000,
    /** After this long without a debugger event (app past the wait), ping the VM to tell a slow app from a wedged one. */
    val pingAfterMs: Long = 10_000,
)

/**
 * What a [StartupSession] tells an observer (the memory timeline poller) without depending on it: [onPid] when the
 * profiled process exists, [onHeld] with true while the app is stopped by the debugger (a milestone, or before the
 * first resume) and false while it runs. Callbacks run on the session thread; failures in them are ignored.
 */
interface StartupListener {
    fun onPid(pid: Int) {}
    fun onHeld(held: Boolean) {}
    /** The debugger has detached (success or not): from now on the app may be inspected normally. */
    fun onRecordingDone() {}

    companion object { val NONE = object : StartupListener {} }
}

/**
 * Records a startup: launches the app under a debugger that stops it at each enabled milestone, dumps the heap there
 * with `Debug.dumpHprofData` and pulls the dump into the session. Follows the "Recommended sequence" of
 * spike/FINDINGS.md. One thread drives everything (including the JDI event queue); only an attach or dump that must be
 * abandoned runs on a helper thread. [onMilestoneDone] runs after each milestone's step finished, while the app is
 * still stopped there (a test hook).
 */
class StartupSession(
    private val adb: Adb,
    private val store: SessionStore,
    private val guard: DebugAppGuard,
    private val debuggerFactory: () -> DebuggerPort = { JdiDebuggerPort() },
    private val timeouts: StartupTimeouts = StartupTimeouts(),
    private val listener: StartupListener = StartupListener.NONE,
    /** Fail the attempt (retryable, nothing dumped yet) when the app is past its debugger wait, instead of skipping only the process-start milestone. */
    private val abortIfNotWaiting: Boolean = false,
    /** Shown in the Prepare step: why this is a second attempt. */
    private val startNote: String? = null,
    private val onMilestoneDone: (Milestone) -> Unit = {},
) {
    /** Set by [run] when it failed in a way that a fresh attempt may avoid, before any snapshot was taken. */
    @Volatile var retryable: RetryableFailure? = null
        private set

    /**
     * Steps: "Prepare", "Launch app", "Attach debugger", one "Dump: <label>" per enabled milestone, "Detach". Returns
     * when all enabled milestones were reached (job DONE, result = session id, session stays ACTIVE). Otherwise the job
     * is failed and the exception rethrown: app exited (session ENDED with note `app exited at <label>`), debugger
     * connection lost while the app lives (session stays ACTIVE), a milestone timed out (remaining steps FAILED "not
     * reached"), cancelled, or a device error. A failed dump, an unresolvable hook, or a custom hook whose class never
     * loaded (waited for only [StartupConfig.customGraceSec] once the built-in milestones are done) fails only its own
     * step. Always detaches, removes its forward, clears the debug app it set, and stops an app it launched that never
     * got past waitForDebugger; snapshots already pulled are kept.
     */
    fun run(sessionId: String, config: StartupConfig, job: Job, failJobOnError: Boolean = true) {
        try {
            val session = store.get(sessionId) ?: throw NoSuchElementException("no such session: $sessionId")
            DeviceArgs.requireSerial(session.serial)
            validate(config, session.packageName)
            Run(session, config, job).execute()
        } catch (t: Throwable) {
            if (failJobOnError) job.fail(t)
            throw t
        }
    }

    private enum class Outcome { ALL_REACHED, APP_EXITED, CONNECTION_LOST, TIMED_OUT }

    /** Ends this milestone only; the session goes on. */
    private class MilestoneFailed(message: String) : Exception(message)

    /** The JDWP connection closed during a dump: the app exited, or only the connection dropped (decided by [Run.vmGone]). */
    private class VmGone : Exception("the debugger connection closed")

    private inner class Run(private val session: SessionMeta, private val config: StartupConfig, private val job: Job) {
        private val serial = session.serial
        private val pkg = session.packageName
        private val processName = config.processName ?: pkg
        private val pending = LinkedHashMap<String, Milestone>()
        private var armed = false
        private var tracker: JdwpTracker? = null
        private var pid: Int? = null
        private var localPort: Int? = null
        private var debugger: DebuggerPort? = null
        private var released = false
        /** Set just before `am start`: from then on a process may sit frozen in waitForDebugger, pid known or not. */
        private var launched = false
        private var appExited = false
        /** Custom hooks whose class was not loaded when they were set and has not been prepared since. */
        private val deferred = HashSet<String>()
        private val hadBuiltins = config.milestones.any { it.enabled && !it.custom }
        /** What each pending milestone waits for, shown in its step while waiting and in a timeout failure. */
        private val hookState = HashMap<String, String>()
        /** The last debugger events with times since the first resume, for a timeout failure. */
        private val trace = ArrayDeque<String>()
        private var loopStartMs = 0L
        private var timeoutDiagnosis: String? = null
        /** Set when a possibly frozen app could not be force-stopped; the marker is kept and the failure hint says so. */
        private var stopFailedHint: String? = null
        /** The debugger call that got no reply: the app's VM is wedged and must be stopped. */
        private var wedgedOp: String? = null
        /** The app was past its debugger wait at attach time (see [APP_NOT_WAITING_REASON]). */
        private var notWaiting = false
        /** This attempt was aborted because the app was past its debugger wait (the only APP_NOT_WAITING failure). */
        private var abortedNotWaiting = false
        /**
         * The one thread that talks JDWP (and owns the JDI event queue): JDI waits for replies without a timeout, so
         * every call runs here and the session thread waits for it with [jdwp]'s bound.
         */
        private val jdwpThread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "memscope-jdwp").apply { isDaemon = true }
        }
        private var lastReached = "launch"
        private var timedOutLabel: String? = null
        /** When the app last ran on after a stop; the time to the next milestone is the app running under the debugger. */
        private var resumedAtMs = 0L

        fun execute() {
            config.milestones.filter { it.enabled }.forEach { pending[it.id] = it }
            var failure: Throwable? = null
            var outcome: Outcome? = null
            try {
                val launchArgs = job.step(PREPARE) { d -> timed(d, startNote) { prepare() } }
                job.step(LAUNCH) { d -> timed(d) { launch(launchArgs); "pid $pid" } }
                job.step(ATTACH) { d -> timed(d) { attach(); "port $localPort" } }
                outcome = awaitMilestones()
            } catch (t: Throwable) {
                failure = t
            }
            try {
                job.step(DETACH) { d -> detach(d) }
            } catch (t: Throwable) {
                if (failure == null) failure = t
            }
            when (outcome) {
                Outcome.APP_EXITED -> {
                    store.update(session.id) { it.copy(state = SessionMeta.State.ENDED, note = "app exited at $lastReached") }
                    if (failure == null) failure = DeviceException(
                        DeviceException.Kind.COMMAND_FAILED, "The app exited at $lastReached.",
                        "The app crashed or was stopped during the recording (check logcat). Snapshots taken before are kept.",
                    )
                }
                Outcome.CONNECTION_LOST -> if (failure == null) failure = DeviceException(
                    DeviceException.Kind.COMMAND_FAILED, "The debugger connection to $processName was lost (at $lastReached).",
                    "The app is still running; adb or the emulator dropped the connection. Retry the recording; snapshots taken before are kept.",
                )
                Outcome.TIMED_OUT -> if (failure == null) failure = DeviceException(
                    DeviceException.Kind.TIMEOUT,
                    "$timedOutLabel was not reached within ${config.milestoneTimeoutSec} s." + (timeoutDiagnosis?.let { "\n$it" } ?: ""),
                    "The app may never get there (wrong hook, or a path not taken on this launch), or is slow under the debugger: " +
                        "raise the per-milestone timeout or deselect the milestone. Snapshots taken before are kept.",
                )
                else -> {}
            }
            stopFailedHint?.let { extra ->
                val f = failure
                val d = f as? DeviceException
                failure = DeviceException(
                    d?.kind ?: DeviceException.Kind.COMMAND_FAILED, f?.message ?: "The recording could not clean up.",
                    listOfNotNull(d?.hint, extra).joinToString(" "), f,
                )
            }
            retryable = if (failure == null || appExited || stopFailedHint != null || (failure as? DeviceException)?.kind == DeviceException.Kind.CANCELLED || store.get(session.id)?.snapshots?.isNotEmpty() == true) null else when {
                abortedNotWaiting -> RetryableFailure.APP_NOT_WAITING
                wedgedOp != null -> RetryableFailure.VM_WEDGED
                else -> null
            }
            failure?.let { throw it }
            job.complete(session.id)
        }

        // -- steps --

        /** Refuses before changing the device; then cache dir, debug app (marker first) and force-stop. */
        private fun prepare(): List<String> {
            adb.requireDebuggable(serial, pkg)
            val launchArgs = config.launch?.let(::launchArgs) ?: listOf(
                "-n",
                adb.launcherComponent(serial, pkg) ?: throw DeviceException(
                    DeviceException.Kind.COMMAND_FAILED, "$pkg has no launcher activity.",
                    "Enter a launch target, e.g. $pkg/.MainActivity, or am start arguments such as -n $pkg/.MainActivity --es key value.",
                ),
            )
            val mkdir = adb.shell(serial, listOf("run-as", pkg, "mkdir", "-p", "cache"), SHORT, job.cancel)
            if (mkdir.exitCode != 0) throw DeviceException(
                DeviceException.Kind.COMMAND_FAILED, "Could not create the app's cache directory: ${(mkdir.stderr + mkdir.stdout).trim().take(300)}",
                "Check that the device has free storage and 'adb shell run-as $pkg' works, then retry.",
            )
            armed = true
            guard.arm(serial, pkg, processName)
            adb.shell(serial, listOf("am", "force-stop", pkg), SHORT, job.cancel)
            return launchArgs
        }

        private fun launch(launchArgs: List<String>) {
            val t = JdwpConnector(adb).track(serial, timeouts.baselineMs.milliseconds, job.cancel)
            tracker = t
            launched = true // before the marker update: if that throws, detach still stops the app
            guard.update(serial) { it.copy(launched = true) }
            val r = adb.shell(serial, listOf("am", "start") + launchArgs, 30.seconds, job.cancel)
            val text = (r.stdout + "\n" + r.stderr).trim()
            if (r.exitCode != 0 || text.lineSequence().any { it.trimStart().startsWith("Error") }) {
                throw DeviceException(
                    DeviceException.Kind.COMMAND_FAILED, "am start failed: ${text.take(300)}",
                    "Check the launch target (a component such as $pkg/.MainActivity, or am start arguments), then retry.",
                )
            }
            val found = t.awaitProcess(processName, timeouts.pidMs.milliseconds, job.cancel)
            pid = found
            guard.update(serial) { it.copy(pid = found) }
            observe { listener.onPid(found) }
            t.close()
            tracker = null
        }

        private fun attach() {
            val port = adb.forwardJdwp(serial, pid!!)
            localPort = port
            guard.update(serial) { it.copy(forwardPort = port) }
            val dbg = debuggerFactory()
            debugger = dbg
            try {
                bounded("Attaching the debugger", timeouts.attachMs, onAbort = dbg::dispose) { dbg.attach(port, timeouts.attachMs) }
            } catch (e: DeviceException) {
                throw e
            } catch (e: Exception) {
                throw DeviceException(
                    DeviceException.Kind.COMMAND_FAILED, "Could not attach a debugger to $processName (pid $pid): ${e.message ?: e}",
                    "Another debugger (for example Android Studio) may be attached to the app: detach it, then retry.", e,
                )
            }
            jdwp("suspending the VM") { dbg.suspendAll() }
            observe { listener.onHeld(true) }
            val waitError = jdwp("arming the waitForDebugger return") { dbg.armWaitReturn() }
            if (waitError != null && waitError.startsWith(APP_NOT_WAITING_REASON)) {
                notWaiting = true
                if (abortIfNotWaiting && Milestones.PROCESS_START in pending) {
                    abortedNotWaiting = true
                    throw DeviceException(
                        DeviceException.Kind.COMMAND_FAILED, "The app was already past its debugger wait when MemScope attached, so the process start was missed.",
                        "MemScope stopped the app; press Record again.",
                    )
                }
            }
            for (m in pending.values.toList()) {
                if (m.id == Milestones.PROCESS_START) {
                    if (waitError != null) failMilestone(m, "Could not find where the app waits for the debugger: $waitError")
                    else hookState[m.id] = "breakpoint where waitForDebugger returns armed"
                    continue
                }
                val where = "${m.hook.className.substringAfterLast('.')}.${m.hook.methodName}"
                when (val status = jdwp("setting the breakpoints of ${m.label}") { dbg.setEntryBreakpoints(m.id, m.hook) }) {
                    is HookStatus.Failed -> failMilestone(m, hookProblem(m, status.reason))
                    HookStatus.Deferred -> {
                        if (m.custom) deferred += m.id
                        hookState[m.id] = "class ${m.hook.className} not loaded yet; entry of $where armed once it loads"
                    }
                    HookStatus.Resolved -> hookState[m.id] = "entry breakpoint on $where armed"
                }
            }
            loopStartMs = nowMs()
            resumedAtMs = nowMs()
            observe { listener.onHeld(false) }
            jdwp("resuming the app") { dbg.resume() } // from here on the VM must see no JDWP traffic until it reports an event (settle delay, FINDINGS A.2)
        }

        private fun awaitMilestones(): Outcome {
            val dbg = debugger!!
            var lastProgress = nowMs()
            var lastNoteMs = 0L
            var lastPingMs = 0L
            while (pending.isNotEmpty()) {
                checkCancel()
                val events = jdwp("reading debugger events") { dbg.nextEvents(POLL_MS) }
                if (events == null) {
                    val waited = nowMs() - lastProgress
                    val limitSec = if (onlyUnloadedCustomsLeft()) config.customGraceSec else config.milestoneTimeoutSec
                    if (waited >= limitSec * 1000L) return timedOut(limitSec)
                    // the settle rule forbids JDWP traffic only until the app is past waitForDebugger
                    if (released && waited >= timeouts.pingAfterMs && nowMs() - lastPingMs >= timeouts.pingAfterMs) {
                        lastPingMs = nowMs()
                        jdwp("checking the app still answers") { dbg.ping() }
                    }
                    if (waited >= NOTE_AFTER_MS && nowMs() - lastNoteMs >= NOTE_EVERY_MS) {
                        lastNoteMs = nowMs()
                        pending.values.forEach { job.note(dumpStep(it), "waiting ${waited / 1000} s of $limitSec s · ${hookState[it.id] ?: "?"}") }
                    }
                    continue
                }
                record(events.joinToString(", ", transform = ::describe))
                observe { listener.onHeld(true) }
                for (e in events.sortedBy(::order)) {
                    when (e) {
                        is DebugEvent.Disconnected -> return vmGone()
                        is DebugEvent.WaitReturned -> {
                            markReleased()
                            pending[Milestones.PROCESS_START]?.let { if (reach(it, e.thread)) return vmGone() }
                        }
                        is DebugEvent.HookResolved -> {
                            deferred -= e.key
                            pending[e.key]?.let { hookState[it.id] = "class loaded; entry breakpoint on ${it.hook.methodName} armed" }
                        }
                        is DebugEvent.HookFailed -> {
                            deferred -= e.key
                            pending[e.key]?.let { failMilestone(it, hookProblem(it, e.reason)) }
                        }
                        is DebugEvent.EntryHit -> {
                            markReleased()
                            val m = pending[e.key] ?: continue
                            jdwp("clearing the hook of ${m.label}") { dbg.clearHook(m.id) }
                            val err = jdwp("arming the return of ${m.label}") { dbg.armCallerReturn(m.id, e.thread) }
                            if (err != null) failMilestone(m, "Could not stop where ${m.hook.methodName} returns: $err")
                            else hookState[m.id] = "${m.hook.methodName} entered on ${e.thread.name} at +%.1f s; breakpoint where it returns to its caller armed".format((nowMs() - loopStartMs) / 1000.0)
                        }
                        is DebugEvent.Reached -> {
                            markReleased()
                            val m = pending[e.key] ?: continue
                            jdwp("clearing the hook of ${m.label}") { dbg.clearHook(m.id) }
                            if (reach(m, e.thread)) return vmGone()
                        }
                    }
                }
                if (events.any { it !is DebugEvent.HookResolved }) lastProgress = nowMs() // also excludes time spent dumping
                if (pending.isEmpty()) break // detach resumes the VM
                resumedAtMs = nowMs()
                observe { listener.onHeld(false) }
                jdwp("resuming the app") { dbg.resume() }
                record("resumed")
            }
            return Outcome.ALL_REACHED
        }

        /**
         * Runs one debugger call on [jdwpThread] and waits at most [StartupTimeouts.jdwpReplyMs], checking cancel every
         * [POLL_MS]. No reply in time means the app's VM is wedged (seen on API 37 when a heap walk inside the app, e.g.
         * from `dumpsys meminfo`, meets a SUSPEND_ALL breakpoint): the debugger is disposed, detach stops the app, and
         * the failure says what was pending.
         */
        private fun <T> jdwp(op: String, block: () -> T): T {
            val f = jdwpThread.submit(java.util.concurrent.Callable(block))
            val deadline = nowMs() + timeouts.jdwpReplyMs
            while (true) {
                try {
                    return f.get(POLL_MS, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // keep waiting
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
                if (job.cancel.isCancelled) { debugger?.dispose(); throw cancelled() }
                if (nowMs() >= deadline) {
                    wedgedOp = op
                    record("no reply while $op")
                    debugger?.dispose()
                    throw DeviceException(
                        DeviceException.Kind.TIMEOUT,
                        "The app stopped answering the debugger: $op got no reply within ${timeouts.jdwpReplyMs / 1000} s.\n" +
                            "waiting for: " + pending.values.joinToString("; ") { "${it.label}: ${hookState[it.id] ?: "?"}" }.ifEmpty { "nothing" } +
                            "\nrecent debugger events:\n" + trace.joinToString("\n").ifEmpty { "none" },
                        "The app's VM deadlocked while held by the debugger (this can happen when another tool inspects its heap at the " +
                            "same moment, e.g. 'dumpsys meminfo'). MemScope stopped the app; retry the recording. Snapshots taken before are kept.",
                    )
                }
            }
        }

        /** [DebuggerPort.diagnose], bounded; a VM that does not answer is reported as such. */
        private fun diagnoseQuietly(): String {
            val f = jdwpThread.submit(java.util.concurrent.Callable { debugger?.diagnose() ?: "debugger already closed" })
            return try {
                f.get(DIAGNOSE_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                wedgedOp = "the diagnosis" // detach must stop the app
                "the VM did not answer the diagnosis within ${DIAGNOSE_WAIT_MS / 1000} s (wedged)"
            } catch (e: Exception) {
                "diagnosis failed: ${e.cause ?: e}"
            }
        }

        private fun record(what: String) {
            if (trace.size >= TRACE_MAX) trace.removeFirst()
            trace.addLast("+%.1f s %s".format((nowMs() - loopStartMs) / 1000.0, what))
        }

        private fun describe(e: DebugEvent) = when (e) {
            is DebugEvent.WaitReturned -> "waitForDebugger returned"
            is DebugEvent.EntryHit -> "entered ${e.key}"
            is DebugEvent.Reached -> "reached ${e.key}"
            is DebugEvent.HookResolved -> "class loaded for ${e.key}"
            is DebugEvent.HookFailed -> "hook failed ${e.key}: ${e.reason}"
            DebugEvent.Disconnected -> "disconnected"
        }

        /** Keeps a collection from being pending when the app resumes. A hang ends the debugger connection but not the dump. */
        private fun collectGarbageAfterDump(thread: DebugThread) {
            try {
                bounded("The garbage collection after the dump", timeouts.gcMs, onAbort = { debugger?.dispose() }) { debugger!!.collectGarbage(thread) }
            } catch (e: DeviceException) {
                if (e.kind == DeviceException.Kind.CANCELLED) throw e
                wedgedOp = "the garbage collection after the dump" // detach must stop the app
                record("no reply while collecting garbage after the dump")
            }
        }

        /** Dumps and pulls milestone [m]; true if the debugger connection closed meanwhile. */
        private fun reach(m: Milestone, thread: DebugThread): Boolean {
            pending.remove(m.id)
            lastReached = m.label
            try {
                job.step(dumpStep(m)) { d -> dumpAndPull(m, thread, d) }
            } catch (_: MilestoneFailed) {
                // only this milestone failed; its step carries the reason
            } catch (_: VmGone) {
                return true
            }
            onMilestoneDone(m)
            return false
        }

        private fun dumpAndPull(m: Milestone, thread: DebugThread, d: (String) -> Unit) {
            val snapshotId = store.nextSnapshotId(session.id)
            val remote = "/data/data/$pkg/cache/memscope-${session.id}-$snapshotId.hprof"
            val local = store.snapshotFile(session.id, snapshotId)
            val gz = local.resolveSibling("$snapshotId.hprof.gz.part")
            val part = local.resolveSibling("$snapshotId.hprof.part")
            val facts = arrayListOf("reached after %.1f s".format((nowMs() - resumedAtMs) / 1000.0))
            readPss()?.let { facts += it }
            d((facts + "dumping").joinToString(" · "))
            var ok = false
            try {
                val t0 = nowMs()
                val takenAtMs = System.currentTimeMillis() // the dump is taken now, not when the pull ends (timeline marker position)
                guard.update(serial) { it.copy(pendingDump = remote) } // recoverStale removes it if this run dies
                val error = try {
                    bounded("The heap dump", timeouts.dumpMs, onAbort = { debugger?.dispose() }) { debugger!!.invokeDump(thread, remote) }
                } catch (_: DebuggerDisconnected) {
                    d("the debugger connection closed during the dump")
                    throw VmGone()
                }
                if (error != null) throw MilestoneFailed("Debug.dumpHprofData failed in the app: $error")
                collectGarbageAfterDump(thread)
                facts += "dump %.1f s".format((nowMs() - t0) / 1000.0)
                d((facts + "pulling").joinToString(" · "))
                val t1 = nowMs()
                try {
                    adb.execOutToFile(serial, listOf("run-as", pkg, "gzip", "-1", "-c", remote), gz, timeouts.pullMs.milliseconds, job.cancel) { n ->
                        d((facts + "%.1f MB pulled (compressed)".format(n / MB)).joinToString(" · "))
                    }
                } catch (e: DeviceException) {
                    if (e.kind == DeviceException.Kind.COMMAND_FAILED) throw MilestoneFailed("Pulling the heap dump failed: ${e.message}")
                    throw e
                }
                gunzip(gz, part)
                OnDemandDumper.hprofProblem(part)?.let { incomplete(it) }
                Files.move(part, local, StandardCopyOption.REPLACE_EXISTING)
                val size = Files.size(local)
                facts += "pull %.1f s".format((nowMs() - t1) / 1000.0)
                facts += "%.1f MB".format(size / MB)
                store.addSnapshot(
                    session.id,
                    SnapshotMeta(snapshotId, m.label, m.id, processName, takenAtMs, local.fileName.toString(), size, SnapshotMeta.Status.OK),
                )
                ok = true
                d(facts.joinToString(" · "))
            } catch (e: MilestoneFailed) {
                d(e.message!!)
                throw e
            } finally {
                deleteQuietly(gz)
                deleteQuietly(part)
                if (!ok) deleteQuietly(local)
                if (removeRemote(remote)) runCatching { guard.update(serial) { it.copy(pendingDump = null) } }
            }
        }

        /**
         * Tries every cleanup action (dispose, stop a frozen app, remove the forward, clear the debug app) even when one
         * fails, then throws the first device error, so the Detach step shows FAILED and a kept marker lets
         * `recoverStale` finish the job later.
         */
        private fun detach(d: (String) -> Unit) {
            val notes = ArrayList<String>()
            observe { listener.onHeld(false) }
            debugger?.dispose()
            debugger = null
            jdwpThread.shutdownNow() // a call stuck on a wedged VM is abandoned (daemon thread)
            observe { listener.onRecordingDone() }
            tracker?.close()
            tracker = null
            val wedged = wedgedOp != null && !appExited
            if ((launched && !released && !appExited) || wedged) {
                // frozen in waitForDebugger (pid known or not; disposing does not release it, FINDINGS A.5), or deadlocked
                val stopped = try {
                    adb.shell(serial, listOf("am", "force-stop", pkg), SHORT).exitCode == 0
                } catch (_: DeviceException) {
                    false // the disarm below reports a lost device
                }
                if (stopped) {
                    notes += when {
                        wedged -> "stopped the app: it no longer answered the debugger"
                        notWaiting -> "stopped the app, which had already passed its debugger wait"
                        else -> "stopped the app, which was still waiting for the debugger"
                    }
                } else {
                    stopFailedHint = "$pkg may still be frozen and could not be stopped: run " +
                        "'adb shell am force-stop $pkg', or MemScope stops it at its next start."
                }
            }
            var firstError: DeviceException? = null
            localPort?.let { port ->
                try {
                    adb.removeForward(serial, port)
                    localPort = null
                    guard.update(serial) { it.copy(forwardPort = null) }
                } catch (e: DeviceException) {
                    firstError = e
                }
            }
            if (armed) {
                try {
                    guard.disarm(serial, keepMarker = stopFailedHint != null) // a kept marker lets recoverStale stop it later
                    armed = false
                    notes += "debug app cleared"
                } catch (e: DeviceException) {
                    if (firstError == null) firstError = e
                }
            }
            firstError?.let { throw it }
            if (notes.isNotEmpty()) d(notes.joinToString(" · "))
        }

        // -- helpers --

        /**
         * The JDWP connection closed. A lost device throws (device failure); a process that still lives means only the
         * connection dropped (the session stays ACTIVE; detach stops the app if it never got past the wait); otherwise
         * the app exited.
         */
        private fun vmGone(): Outcome {
            adb.shell(serial, listOf("true"), SHORT) // throws if the device itself is gone: that is a device failure, not an exit
            if (processAlive()) {
                pending.values.toList().forEach { failMilestone(it, "not reached: the debugger connection was lost") }
                return Outcome.CONNECTION_LOST
            }
            appExited = true
            pending.values.toList().forEach { failMilestone(it, "not reached: the app exited") }
            return Outcome.APP_EXITED
        }

        /** The profiled pid still runs under its name; checked twice so a dying process is not mistaken for a live one. */
        private fun processAlive(): Boolean {
            val p = pid ?: return false
            for (attempt in 0..1) {
                if (attempt > 0) Thread.sleep(ALIVE_RECHECK_MS)
                val r = adb.shell(serial, listOf("cat", "/proc/$p/cmdline"), SHORT)
                if (r.exitCode != 0 || r.stdout.substringBefore('\u0000').trim() != processName) return false
            }
            return true
        }

        /** Every built-in milestone is done and the custom hooks left wait for a class that was never loaded. */
        private fun onlyUnloadedCustomsLeft() = hadBuiltins && pending.values.all { it.custom && it.id in deferred }

        /** Fails what is left; a custom hook whose class never loaded gets the hint and does not fail the session. */
        private fun timedOut(limitSec: Int): Outcome {
            val missing = pending.values.toList()
            for (m in missing) {
                if (m.custom && m.id in deferred) {
                    failMilestone(m, "class ${m.hook.className} was never loaded within $limitSec s — check class and method name")
                } else {
                    failMilestone(m, "not reached within $limitSec s · ${hookState[m.id] ?: "?"}")
                }
            }
            val real = missing.firstOrNull { !(it.custom && it.id in deferred) } ?: return Outcome.ALL_REACHED
            timedOutLabel = real.label
            timeoutDiagnosis = "waiting for: " + missing.joinToString("; ") { "${it.label}: ${hookState[it.id] ?: "?"}" } +
                "\n" + diagnoseQuietly() + "\nrecent debugger events:\n" + trace.joinToString("\n").ifEmpty { "none" }
            return Outcome.TIMED_OUT
        }

        private fun failMilestone(m: Milestone, detail: String) {
            pending.remove(m.id)
            job.failStep(dumpStep(m), detail)
        }

        private fun hookProblem(m: Milestone, reason: String) =
            if (m.custom) "$reason — check class and method name" else "$reason — this hook is not available on this Android version"

        private fun markReleased() {
            if (released) return
            released = true
            guard.update(serial) { it.copy(released = true) }
        }

        private fun readPss(): String? = try {
            val r = adb.shell(serial, listOf("run-as", pkg, "cat", "/proc/$pid/smaps_rollup"), SHORT, job.cancel)
            r.stdout.lineSequence().firstOrNull { it.startsWith("Pss:") }
                ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.let { "PSS %.1f MB".format(it / 1024.0) }
        } catch (e: DeviceException) {
            if (e.kind == DeviceException.Kind.CANCELLED) throw e
            null // informational only; a lost device shows up in the next step
        }

        /** Best effort; true if the file is gone from the device. */
        private fun removeRemote(remote: String): Boolean = try {
            adb.shell(serial, listOf("run-as", pkg, "rm", "-f", remote), SHORT).exitCode == 0
        } catch (_: DeviceException) {
            false
        }

        private fun incomplete(problem: String): Nothing = throw MilestoneFailed(
            "Heap dump pull was incomplete: $problem. The transfer was cut short; retry the recording.",
        )

        private fun gunzip(gz: Path, out: Path) {
            try {
                GZIPInputStream(Files.newInputStream(gz), 1 shl 16).use { input ->
                    Files.newOutputStream(out).use { input.transferTo(it) }
                }
            } catch (_: EOFException) {
                incomplete("the compressed stream ended early")
            } catch (e: java.util.zip.ZipException) {
                incomplete("the compressed stream is corrupt (${e.message})")
            }
        }

        private inline fun observe(f: () -> Unit) {
            try { f() } catch (e: Throwable) {
                if (e is VirtualMachineError || e is LinkageError) throw e
                /* an observer must never break a recording */
            }
        }

        private fun checkCancel() {
            if (job.cancel.isCancelled) throw cancelled()
        }

        /**
         * Runs [block] on a helper thread and waits for it, checking cancel every [POLL_MS]. On cancel or after
         * [timeoutMs], [onAbort] unblocks the helper (disposing the debugger) and CANCELLED / TIMEOUT is thrown.
         */
        private fun <T> bounded(what: String, timeoutMs: Long, onAbort: () -> Unit, block: () -> T): T {
            val result = CompletableFuture<T>()
            Thread({
                try { result.complete(block()) } catch (t: Throwable) { result.completeExceptionally(t) }
            }, "memscope-startup-helper").apply { isDaemon = true; start() }
            val deadline = nowMs() + timeoutMs
            while (true) {
                try {
                    return result.get(POLL_MS, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // keep waiting
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
                if (job.cancel.isCancelled) { onAbort(); throw cancelled() }
                if (nowMs() >= deadline) {
                    onAbort()
                    throw DeviceException(
                        DeviceException.Kind.TIMEOUT, "$what did not finish within ${timeoutMs / 1000} s.",
                        "The app or device stopped responding. Check the device, then retry; snapshots taken before are kept.",
                    )
                }
            }
        }

        private fun <T> timed(d: (String) -> Unit, lead: String? = null, block: () -> T): T {
            lead?.let(d) // shown while the step runs, and kept if it fails
            val t0 = nowMs()
            val r = block()
            d(listOfNotNull(lead, (r as? String), "%.1f s".format((nowMs() - t0) / 1000.0)).joinToString(" · "))
            return r
        }
    }

    companion object {
        const val PREPARE = "Prepare"
        const val LAUNCH = "Launch app"
        const val ATTACH = "Attach debugger"
        const val DETACH = "Detach"
        private const val POLL_MS = 200L
        private const val ALIVE_RECHECK_MS = 500L
        /** A pending step shows what it waits for once the wait exceeds this, refreshed this often. */
        private const val NOTE_AFTER_MS = 2_000L
        private const val NOTE_EVERY_MS = 1_000L
        private const val TRACE_MAX = 40
        private const val DIAGNOSE_WAIT_MS = 5_000L
        private const val MB = 1_048_576.0
        private val SHORT = 15.seconds
        private val COMPONENT = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")
        private val PROCESS_SUFFIX = Regex("[A-Za-z0-9_.]+")
        private const val FORBIDDEN = ";|&$<>`'\"\\\n\r"

        fun dumpStep(m: Milestone) = "Dump: ${m.label}"

        fun stepNames(config: StartupConfig): List<String> =
            listOf(PREPARE, LAUNCH, ATTACH) + config.milestones.filter { it.enabled }.map(::dumpStep) + DETACH

        /** Throws [IllegalArgumentException] naming the problem; nothing on the device is touched. */
        fun validate(config: StartupConfig, packageName: String) {
            DeviceArgs.requirePackage(packageName)
            Milestones.validate(config.milestones)
            require(config.milestoneTimeoutSec in 1..3600) { "The per-milestone timeout must be between 1 and 3600 seconds." }
            require(config.customGraceSec in 1..3600) { "The grace period for custom milestones must be between 1 and 3600 seconds." }
            val process = config.processName ?: packageName
            require(process == packageName || (process.startsWith("$packageName:") && PROCESS_SUFFIX.matches(process.substringAfter(':')))) {
                "Process '$process' is not a process of $packageName: use $packageName or $packageName:<name>."
            }
            config.launch?.let { launchArgs(it) }
        }

        /** `am start` arguments for a launch target: `-n <component>` for a component, else the whitespace-split arguments. */
        fun launchArgs(launch: String): List<String> {
            val text = launch.trim()
            require(text.isNotEmpty()) { "The launch target is empty." }
            require(text.none { it in FORBIDDEN }) {
                "The launch target must not contain shell characters (; | & \$ < > ` quotes, backslash or line breaks)."
            }
            return if (COMPONENT.matches(text)) listOf("-n", text) else text.split(Regex("\\s+"))
        }

        private fun order(e: DebugEvent) = when (e) {
            is DebugEvent.Disconnected -> 0
            is DebugEvent.WaitReturned -> 1
            is DebugEvent.HookResolved, is DebugEvent.HookFailed -> 2
            is DebugEvent.EntryHit -> 3
            is DebugEvent.Reached -> 4
        }

        private fun nowMs() = System.nanoTime() / 1_000_000

        private fun deleteQuietly(path: Path) {
            try { Files.deleteIfExists(path) } catch (_: IOException) { /* best effort */ }
        }

        private fun cancelled() = DeviceException(DeviceException.Kind.CANCELLED, "Startup recording was cancelled", "The operation was cancelled.")
    }
}
