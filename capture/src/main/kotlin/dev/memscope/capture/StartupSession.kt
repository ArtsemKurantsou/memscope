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
)

/** Every blocking wait of a startup session, in milliseconds. */
data class StartupTimeouts(
    val baselineMs: Long = 5_000,
    val pidMs: Long = 20_000,
    val attachMs: Long = 20_000,
    val dumpMs: Long = 180_000,
    val pullMs: Long = 300_000,
)

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
    private val onMilestoneDone: (Milestone) -> Unit = {},
) {
    /**
     * Steps: "Prepare", "Launch app", "Attach debugger", one "Dump: <label>" per enabled milestone, "Detach". Returns
     * when all enabled milestones were reached (job DONE, result = session id, session stays ACTIVE). Otherwise the job
     * is failed and the exception rethrown: app exited (session ENDED with note `app exited at <label>`), a milestone
     * timed out (remaining steps FAILED "not reached"), cancelled, or a device error. A failed dump or an unresolvable
     * hook fails only its own step. Always detaches, removes its forward and clears the debug app it set; snapshots
     * already pulled are kept.
     */
    fun run(sessionId: String, config: StartupConfig, job: Job) {
        try {
            val session = store.get(sessionId) ?: throw NoSuchElementException("no such session: $sessionId")
            validate(config, session.packageName)
            Run(session, config, job).execute()
        } catch (t: Throwable) {
            job.fail(t)
            throw t
        }
    }

    private enum class Outcome { ALL_REACHED, APP_EXITED, TIMED_OUT }

    /** Ends this milestone only; the session goes on. */
    private class MilestoneFailed(message: String) : Exception(message)

    /** The debugged VM is gone while the device is still there. */
    private class AppExited : Exception("the app exited")

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
        private var appExited = false
        private var lastReached = "launch"
        private var timedOutLabel: String? = null
        /** When the app last ran on after a stop; the time to the next milestone is the app running under the debugger. */
        private var resumedAtMs = 0L

        fun execute() {
            config.milestones.filter { it.enabled }.forEach { pending[it.id] = it }
            var failure: Throwable? = null
            var outcome: Outcome? = null
            try {
                val launchArgs = job.step(PREPARE) { d -> timed(d) { prepare() } }
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
                Outcome.TIMED_OUT -> if (failure == null) failure = DeviceException(
                    DeviceException.Kind.TIMEOUT, "$timedOutLabel was not reached within ${config.milestoneTimeoutSec} s.",
                    "The app may never get there (wrong hook, or a path not taken on this launch), or is slow under the debugger: " +
                        "raise the per-milestone timeout or deselect the milestone. Snapshots taken before are kept.",
                )
                else -> {}
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
            dbg.suspendAll()
            val waitError = dbg.armWaitReturn()
            for (m in pending.values.toList()) {
                if (m.id == Milestones.PROCESS_START) {
                    if (waitError != null) failMilestone(m, "Could not find where the app waits for the debugger: $waitError")
                    continue
                }
                val status = dbg.setEntryBreakpoints(m.id, m.hook)
                if (status is HookStatus.Failed) failMilestone(m, hookProblem(m, status.reason))
            }
            resumedAtMs = nowMs()
            dbg.resume() // from here on the VM must see no JDWP traffic until it reports an event (settle delay, FINDINGS A.2)
        }

        private fun awaitMilestones(): Outcome {
            val dbg = debugger!!
            val timeoutMs = config.milestoneTimeoutSec * 1000L
            var lastProgress = nowMs()
            while (pending.isNotEmpty()) {
                checkCancel()
                val events = dbg.nextEvents(POLL_MS)
                if (events == null) {
                    if (nowMs() - lastProgress >= timeoutMs) return timedOut()
                    continue
                }
                for (e in events.sortedBy(::order)) {
                    when (e) {
                        is DebugEvent.Disconnected -> return appExit()
                        is DebugEvent.WaitReturned -> {
                            markReleased()
                            pending[Milestones.PROCESS_START]?.let { if (reach(it, e.thread)) return appExit() }
                        }
                        is DebugEvent.HookResolved -> {}
                        is DebugEvent.HookFailed -> pending[e.key]?.let { failMilestone(it, hookProblem(it, e.reason)) }
                        is DebugEvent.EntryHit -> {
                            markReleased()
                            val m = pending[e.key] ?: continue
                            dbg.clearHook(m.id)
                            dbg.armCallerReturn(m.id, e.thread)?.let { failMilestone(m, "Could not stop where ${m.hook.methodName} returns: $it") }
                        }
                        is DebugEvent.Reached -> {
                            markReleased()
                            val m = pending[e.key] ?: continue
                            dbg.clearHook(m.id)
                            if (reach(m, e.thread)) return appExit()
                        }
                    }
                }
                if (events.any { it !is DebugEvent.HookResolved }) lastProgress = nowMs() // also excludes time spent dumping
                if (pending.isEmpty()) break // detach resumes the VM
                resumedAtMs = nowMs()
                dbg.resume()
            }
            return Outcome.ALL_REACHED
        }

        /** Dumps and pulls milestone [m]; true if the app exited meanwhile. */
        private fun reach(m: Milestone, thread: DebugThread): Boolean {
            pending.remove(m.id)
            lastReached = m.label
            try {
                job.step(dumpStep(m)) { d -> dumpAndPull(m, thread, d) }
            } catch (_: MilestoneFailed) {
                // only this milestone failed; its step carries the reason
            } catch (_: AppExited) {
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
                val error = try {
                    bounded("The heap dump", timeouts.dumpMs, onAbort = { debugger?.dispose() }) { debugger!!.invokeDump(thread, remote) }
                } catch (_: DebuggerDisconnected) {
                    appExited = true
                    d("the app exited during the dump")
                    throw AppExited()
                }
                if (error != null) throw MilestoneFailed("Debug.dumpHprofData failed in the app: $error")
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
                    SnapshotMeta(snapshotId, m.label, m.id, processName, System.currentTimeMillis(), local.fileName.toString(), size, SnapshotMeta.Status.OK),
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
                removeRemote(remote)
            }
        }

        /** Never throws: cleanup must not mask the error being reported. */
        private fun detach(d: (String) -> Unit) {
            val notes = ArrayList<String>()
            debugger?.dispose()
            debugger = null
            tracker?.close()
            tracker = null
            if (pid != null && !released && !appExited) {
                // still frozen in waitForDebugger: disposing does not release it (FINDINGS A.5)
                try {
                    adb.shell(serial, listOf("am", "force-stop", pkg), SHORT)
                    notes += "stopped the app, which was still waiting for the debugger"
                } catch (_: DeviceException) { /* the disarm below reports the device problem */ }
            }
            var forwardError: DeviceException? = null
            localPort?.let { port ->
                try {
                    adb.removeForward(serial, port)
                    localPort = null
                    guard.update(serial) { it.copy(forwardPort = null) }
                } catch (e: DeviceException) {
                    forwardError = e
                }
            }
            if (armed) {
                guard.disarm(serial)
                armed = false
                notes += "debug app cleared"
            }
            forwardError?.let { throw it }
            if (notes.isNotEmpty()) d(notes.joinToString(" · "))
        }

        // -- helpers --

        private fun appExit(): Outcome {
            appExited = true
            adb.shell(serial, listOf("true"), SHORT) // throws if the device itself is gone: that is a device failure, not an exit
            pending.values.toList().forEach { failMilestone(it, "not reached: the app exited") }
            return Outcome.APP_EXITED
        }

        private fun timedOut(): Outcome {
            val missing = pending.values.toList()
            timedOutLabel = missing.first().label
            missing.forEach { failMilestone(it, "not reached within ${config.milestoneTimeoutSec} s") }
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

        private fun removeRemote(remote: String) {
            try {
                adb.shell(serial, listOf("run-as", pkg, "rm", "-f", remote), SHORT)
            } catch (_: DeviceException) { /* best effort */ }
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

        private fun <T> timed(d: (String) -> Unit, block: () -> T): T {
            val t0 = nowMs()
            val r = block()
            d(listOfNotNull((r as? String), "%.1f s".format((nowMs() - t0) / 1000.0)).joinToString(" · "))
            return r
        }
    }

    companion object {
        const val PREPARE = "Prepare"
        const val LAUNCH = "Launch app"
        const val ATTACH = "Attach debugger"
        const val DETACH = "Detach"
        private const val POLL_MS = 200L
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
            Milestones.validate(config.milestones)
            require(config.milestoneTimeoutSec in 1..3600) { "The per-milestone timeout must be between 1 and 3600 seconds." }
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
