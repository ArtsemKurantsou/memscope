package dev.memscope.capture

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Scripted VM: after each `resume()` the next event set of [script] is delivered. Records calls and protocol
 * violations: JDWP traffic while the VM runs (anything but waiting between a resume and the next event set) and
 * waiting for events while an event set is still unresumed.
 */
class FakeDebugger : DebuggerPort {
    val main = DebugThread(1, "main")
    val calls = CopyOnWriteArrayList<String>()
    val violations = CopyOnWriteArrayList<String>()
    val script = ConcurrentLinkedDeque<List<DebugEvent>>()
    var attachError: Throwable? = null
    var waitArmError: String? = null
    var hookStatus: (String, Hook) -> HookStatus = { _, _ -> HookStatus.Resolved }
    var returnArmError: (String) -> String? = { null }
    /** Result of a dump per remote path: null = OK, else the Java exception text. */
    var dumpResult: (String) -> String? = { null }
    /** If set, dumps block until it is counted down (or the port is disposed). */
    @Volatile var dumpGate: CountDownLatch? = null
    var dumpDisconnects = false
    /** The JDWP reply that never comes (FINDINGS-style deadlock): a call named here blocks until the port is disposed. */
    @Volatile var hangOn: String? = null
    private fun maybeHang(op: String) { if (hangOn == op) while (!disposed) Thread.sleep(10) }
    var nextEventsError: Throwable? = null
    /** Runs when the VM goes away (a Disconnected set, or a dump that disconnects): tests let the process die here. */
    var onDisconnected: () -> Unit = {}
    val dumpedPaths = CopyOnWriteArrayList<String>()
    @Volatile var disposed = false
    @Volatile private var running = false
    @Volatile private var pendingSet = false

    fun defaultScript(): FakeDebugger {
        script += listOf(DebugEvent.WaitReturned(main))
        script += listOf(DebugEvent.Reached("before_app_oncreate", main), DebugEvent.EntryHit("after_app_oncreate", main))
        script += listOf(DebugEvent.Reached("after_app_oncreate", main))
        script += listOf(DebugEvent.EntryHit("first_activity_created", main))
        script += listOf(DebugEvent.Reached("first_activity_created", main))
        script += listOf(DebugEvent.EntryHit("first_activity_resumed", main))
        script += listOf(DebugEvent.Reached("first_activity_resumed", main))
        return this
    }

    private fun traffic(what: String) {
        calls += what
        if (running && !disposed) violations += "JDWP traffic while running: $what"
    }

    override fun attach(localPort: Int, timeoutMs: Long) { calls += "attach:$localPort"; attachError?.let { throw it } }
    override fun suspendAll() { calls += "suspendAll" }
    override fun armWaitReturn(): String? { traffic("armWaitReturn"); return waitArmError }
    /** A slow setEntryBreakpoints: this many sub-steps of [entryStepMs] each, reporting progress after each if [entryReportsProgress]. */
    @Volatile var entrySteps = 0
    @Volatile var entryStepMs = 0L
    @Volatile var entryReportsProgress = true
    @Volatile private var progress: () -> Unit = {}
    override fun setProgressListener(listener: () -> Unit) { progress = listener }

    override fun setEntryBreakpoints(key: String, hook: Hook): HookStatus {
        traffic("entry:$key")
        repeat(entrySteps) {
            if (disposed) return HookStatus.Failed("disposed")
            Thread.sleep(entryStepMs)
            if (entryReportsProgress) progress()
        }
        return hookStatus(key, hook)
    }
    override fun armCallerReturn(key: String, thread: DebugThread): String? { traffic("return:$key"); maybeHang("armCallerReturn:$key"); return returnArmError(key) }
    override fun clearHook(key: String) { traffic("clear:$key") }

    override fun resume() {
        calls += "resume"
        if (running) violations += "resume while running"
        running = true
        pendingSet = false
    }

    override fun nextEvents(timeoutMs: Long): List<DebugEvent>? {
        if (pendingSet) violations += "nextEvents before the previous event set was resumed"
        nextEventsError?.let { throw it }
        if (!running) violations += "nextEvents while suspended"
        val set = script.pollFirst()
        if (set == null) { Thread.sleep(minOf(timeoutMs, 20)); return null }
        // the real stall: an event set arrived, but translating it (ThreadReference.name()) never gets a reply
        if (hangOn == "events" && set.any { it is DebugEvent.EntryHit && it.key == "first_activity_resumed" }) { maybeHang("events"); return null }
        if (set.any { it is DebugEvent.Disconnected }) onDisconnected()
        calls += "events:" + set.joinToString(",") { it.toString() }
        if (set.any { it !is DebugEvent.Disconnected }) { running = false; pendingSet = true }
        return set
    }

    override fun invokeDump(thread: DebugThread, remotePath: String): String? {
        traffic("dump:$remotePath")
        dumpGate?.let { gate -> while (!gate.await(20, TimeUnit.MILLISECONDS)) if (disposed) throw DebuggerDisconnected("disposed") }
        if (dumpDisconnects) { onDisconnected(); throw DebuggerDisconnected("VM disconnected during invoke") }
        dumpedPaths += remotePath
        return dumpResult(remotePath)
    }

    override fun collectGarbage(thread: DebugThread) { traffic("gc"); maybeHang("gc") }

    override fun diagnose(): String { calls += "diagnose"; maybeHang("ping"); return "diagnosis of the fake VM" }

    override fun ping() { calls += "ping"; maybeHang("ping") }

    override fun dispose() { calls += "dispose"; disposed = true }
}
