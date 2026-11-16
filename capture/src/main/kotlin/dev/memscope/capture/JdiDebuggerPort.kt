package dev.memscope.capture

import com.sun.jdi.Bootstrap
import com.sun.jdi.ClassType
import com.sun.jdi.IncompatibleThreadStateException
import com.sun.jdi.Location
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.StringReference
import com.sun.jdi.ThreadReference
import com.sun.jdi.VMDisconnectedException
import com.sun.jdi.VirtualMachine
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.event.ClassPrepareEvent
import com.sun.jdi.event.EventSet
import com.sun.jdi.event.VMDeathEvent
import com.sun.jdi.event.VMDisconnectEvent
import com.sun.jdi.request.EventRequest
import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [DebuggerPort] over JDI (`jdk.jdi`), as verified in spike/FINDINGS.md A.2/A.3: breakpoints suspend the whole VM,
 * code-index locations, caller-return breakpoints instead of STEP_OUT, `dumpHprofData` invoked single-threaded.
 */
class JdiDebuggerPort : DebuggerPort {
    @Volatile private var vm: VirtualMachine? = null
    private var currentSet: EventSet? = null
    private val threads = ConcurrentHashMap<Long, ThreadReference>()
    /** Armed caller-return breakpoints: key -> (thread, the thread's frame count when the caller frame is on top). */
    private val pendingReturns = HashMap<String, Pair<ThreadReference, Int>>()
    private val disposed = AtomicBoolean(false)

    override fun attach(localPort: Int, timeoutMs: Long) {
        JdiNoise.suppress()
        val connector = Bootstrap.virtualMachineManager().attachingConnectors().first { it.name() == "com.sun.jdi.SocketAttach" }
        val args = connector.defaultArguments()
        args.getValue("hostname").setValue("127.0.0.1")
        args.getValue("port").setValue(localPort.toString())
        args["timeout"]?.setValue(timeoutMs.toString())
        val attached = connector.attach(args)
        vm = attached
        if (disposed.get()) { // dispose() raced with the attach
            runCatching { attached.dispose() }
            throw DebuggerDisconnected("debugger disposed while attaching")
        }
    }

    override fun suspendAll() = vm().suspend()

    override fun armWaitReturn(): String? {
        val vm = vm()
        val main = vm.allThreads().firstOrNull { it.name() == "main" } ?: return "the app has no thread named 'main'"
        val frames = main.frames()
        val i = frames.indexOfFirst { it.location().method().name() == "handleBindApplication" }
        if (i < 0) return "the main thread is not in ActivityThread.handleBindApplication (top frame: ${frames.firstOrNull()?.location()})"
        // the call in handleBindApplication must be the one waiting for us; otherwise the app already started (for example
        // the one-shot debug-app flag was used up) and "process start" would silently be a later point
        if (frames.take(i).none { it.location().method().name() == "waitForDebugger" }) {
            return "$APP_NOT_WAITING_REASON (it was already at " +
                "${frames[i].location().method().name()} @${frames[i].location().codeIndex()}); the process start was missed"
        }
        val call = frames[i].location()
        val ret = returnLocation(call) ?: return "no code after ${call.method().name()} @${call.codeIndex()}"
        vm.eventRequestManager().createBreakpointRequest(ret).apply {
            addThreadFilter(main)
            setSuspendPolicy(BREAKPOINT_SUSPEND)
            putProperty(KEY, Milestones.PROCESS_START)
            putProperty(KIND, Kind.WAIT)
            enable()
        }
        pendingReturns[Milestones.PROCESS_START] = main to frames.size - i
        return null
    }

    override fun setEntryBreakpoints(key: String, hook: Hook): HookStatus {
        val vm = vm()
        val types = vm.classesByName(hook.className).filter { it.isPrepared }
        if (types.isEmpty()) {
            vm.eventRequestManager().createClassPrepareRequest().apply {
                addClassFilter(hook.className)
                setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                putProperty(KEY, key)
                putProperty(HOOK, hook)
                enable()
            }
            return HookStatus.Deferred
        }
        val n = types.sumOf { breakpointsIn(it, key, hook) }
        return if (n > 0) HookStatus.Resolved else HookStatus.Failed(noMethod(hook))
    }

    override fun armCallerReturn(key: String, thread: DebugThread): String? {
        val t = threads[thread.id] ?: return "unknown thread ${thread.name}"
        val count = t.frameCount()
        if (count < 2) return "${thread.name} has no caller frame"
        val caller = t.frame(1).location()
        val ret = returnLocation(caller) ?: return "no code after ${caller.method().declaringType().name()}.${caller.method().name()} @${caller.codeIndex()}"
        vm().eventRequestManager().createBreakpointRequest(ret).apply {
            addThreadFilter(t)
            setSuspendPolicy(BREAKPOINT_SUSPEND)
            putProperty(KEY, key)
            putProperty(KIND, Kind.RETURN)
            enable()
        }
        pendingReturns[key] = t to count - 1
        return null
    }

    override fun clearHook(key: String) {
        val erm = vm().eventRequestManager()
        erm.deleteEventRequests(erm.breakpointRequests().filter { it.getProperty(KEY) == key })
        erm.deleteEventRequests(erm.classPrepareRequests().filter { it.getProperty(KEY) == key })
        pendingReturns.remove(key)
    }

    override fun resume() {
        val set = currentSet
        currentSet = null
        try {
            if (set != null) set.resume() else vm().resume()
        } catch (_: VMDisconnectedException) {
            // reported by the next nextEvents()
        }
    }

    override fun nextEvents(timeoutMs: Long): List<DebugEvent>? {
        val set = try {
            vm().eventQueue().remove(timeoutMs) ?: return null
        } catch (_: VMDisconnectedException) {
            return listOf(DebugEvent.Disconnected)
        }
        currentSet = set
        val out = ArrayList<DebugEvent>()
        val hit = HashSet<Pair<ThreadReference, String>>()
        try {
            for (ev in set) {
                when (ev) {
                    is VMDeathEvent, is VMDisconnectEvent -> { currentSet = null; return listOf(DebugEvent.Disconnected) }
                    is ClassPrepareEvent -> {
                        val key = ev.request().getProperty(KEY) as? String ?: continue
                        val hook = ev.request().getProperty(HOOK) as Hook
                        out += if (breakpointsIn(ev.referenceType(), key, hook) > 0) DebugEvent.HookResolved(key)
                        else DebugEvent.HookFailed(key, noMethod(hook))
                    }
                    is BreakpointEvent -> {
                        val key = ev.request().getProperty(KEY) as? String ?: continue
                        val t = ev.thread()
                        threads[t.uniqueID()] = t
                        hit += t to key
                        val dt = DebugThread(t.uniqueID(), t.name())
                        out += when (ev.request().getProperty(KIND) as Kind) {
                            Kind.WAIT -> {
                                vm().eventRequestManager().deleteEventRequest(ev.request())
                                pendingReturns.remove(key)
                                DebugEvent.WaitReturned(dt)
                            }
                            Kind.RETURN -> { pendingReturns.remove(key); DebugEvent.Reached(key, dt) }
                            Kind.ENTRY_THEN_RETURN -> DebugEvent.EntryHit(key, dt)
                            Kind.ENTRY -> DebugEvent.Reached(key, dt)
                        }
                    }
                    else -> {}
                }
            }
            out += missedReturns(hit)
        } catch (_: VMDisconnectedException) {
            currentSet = null
            return listOf(DebugEvent.Disconnected)
        }
        return out
    }

    override fun invokeDump(thread: DebugThread, remotePath: String): String? {
        val t = threads[thread.id] ?: throw IllegalStateException("unknown thread ${thread.name}")
        try {
            val vm = vm()
            val debug = vm.classesByName("android.os.Debug").firstOrNull() as? ClassType ?: return "android.os.Debug is not loaded"
            val method = debug.methodsByName("dumpHprofData", "(Ljava/lang/String;)V").firstOrNull()
                ?: return "android.os.Debug has no dumpHprofData(String)"
            val path: StringReference = vm.mirrorOf(remotePath)
            path.disableCollection()
            try {
                debug.invokeMethod(t, method, listOf(path), ClassType.INVOKE_SINGLE_THREADED)
                collectGarbage(t)
                return null
            } catch (e: com.sun.jdi.InvocationException) {
                return describe(e.exception())
            } finally {
                runCatching { path.enableCollection() }
            }
        } catch (e: VMDisconnectedException) {
            throw DebuggerDisconnected("the app's VM disconnected during the dump", e)
        } catch (e: IncompatibleThreadStateException) {
            throw IllegalStateException("thread ${thread.name} is not stopped at an event", e)
        }
    }

    /** `Runtime.getRuntime().gc()` on the stopped thread, so no collection is pending when the app resumes. Best effort. */
    private fun collectGarbage(t: ThreadReference) {
        runCatching {
            val runtime = vm().classesByName("java.lang.Runtime").firstOrNull() as? ClassType ?: return
            val get = runtime.methodsByName("getRuntime", "()Ljava/lang/Runtime;").firstOrNull() ?: return
            val instance = runtime.invokeMethod(t, get, emptyList(), ClassType.INVOKE_SINGLE_THREADED) as? ObjectReference ?: return
            val gc = runtime.methodsByName("gc", "()V").firstOrNull() ?: return
            instance.invokeMethod(t, gc, emptyList(), ObjectReference.INVOKE_SINGLE_THREADED)
        }
    }

    override fun ping() { vm().allThreads() }

    override fun diagnose(): String = try {
        val vm = vm()
        val main = vm.allThreads().firstOrNull { it.name() == "main" }
        val suspendBefore = main?.suspendCount()
        vm.suspend()
        buildString {
            val erm = vm.eventRequestManager()
            append("armed breakpoints: ")
            append(erm.breakpointRequests().joinToString("; ") { r ->
                val l = r.location()
                "${r.getProperty(KEY)}/${r.getProperty(KIND)} ${l.declaringType().name()}.${l.method().name()}@${l.codeIndex()}${if (r.isEnabled) "" else " (disabled)"}"
            }.ifEmpty { "none" })
            append("\nclass-prepare requests: ")
            append(erm.classPrepareRequests().joinToString("; ") { "${it.getProperty(KEY)}" }.ifEmpty { "none" })
            append("\npending caller returns: ")
            append(pendingReturns.entries.joinToString("; ") { (k, p) -> "$k on ${p.first.name()} (caller depth ${p.second}, now ${runCatching { p.first.frameCount() }.getOrNull()})" }.ifEmpty { "none" })
            if (main == null) { append("\nno main thread"); return@buildString }
            append("\nmain thread: status ${threadStatus(main.status())}, suspend count before diagnosis $suspendBefore, ")
            append("at breakpoint ${main.isAtBreakpoint}")
            main.frames().take(14).forEach { f ->
                val l = f.location()
                append("\n  at ${l.declaringType().name()}.${l.method().name()} @${l.codeIndex()}")
            }
        }
    } catch (e: Exception) {
        "diagnosis failed: $e"
    }

    private fun threadStatus(s: Int) = when (s) {
        ThreadReference.THREAD_STATUS_RUNNING -> "RUNNING"
        ThreadReference.THREAD_STATUS_WAIT -> "WAIT"
        ThreadReference.THREAD_STATUS_MONITOR -> "MONITOR"
        ThreadReference.THREAD_STATUS_SLEEPING -> "SLEEPING"
        ThreadReference.THREAD_STATUS_ZOMBIE -> "ZOMBIE"
        ThreadReference.THREAD_STATUS_NOT_STARTED -> "NOT_STARTED"
        else -> "UNKNOWN($s)"
    }

    /**
     * Deletes requests, resumes and disposes. Runs on a helper thread and waits at most [DISPOSE_WAIT_MS], so a
     * wedged connection (e.g. during a hung invoke) cannot block the caller.
     */
    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        val v = vm ?: return
        val set = currentSet
        val worker = Thread({
            runCatching {
                val erm = v.eventRequestManager()
                erm.deleteAllBreakpoints()
                erm.deleteEventRequests(erm.classPrepareRequests())
            }
            runCatching { set?.resume() }
            runCatching { v.dispose() }
        }, "memscope-jdi-dispose").apply { isDaemon = true; start() }
        worker.join(DISPOSE_WAIT_MS)
        if (worker.isAlive) {
            // capture has no logger; the server prints stderr to its terminal
            System.err.println("memscope: the JDI connection did not close within ${DISPOSE_WAIT_MS / 1000} s; abandoning it (the app is released when the forward is removed)")
        }
    }

    // -- internals --

    private fun vm(): VirtualMachine = vm ?: throw IllegalStateException("not attached")

    /** Entry breakpoints on every method with code named like the hook and declared in [type] itself. */
    private fun breakpointsIn(type: ReferenceType, key: String, hook: Hook): Int {
        val methods = (if (hook.signature != null) type.methodsByName(hook.methodName, hook.signature) else type.methodsByName(hook.methodName))
            .filter { it.declaringType() == type && !it.isAbstract && !it.isNative }
        val erm = vm().eventRequestManager()
        var n = 0
        for (m in methods) {
            val loc = m.location() ?: continue
            erm.createBreakpointRequest(loc).apply {
                setSuspendPolicy(BREAKPOINT_SUSPEND)
                putProperty(KEY, key)
                putProperty(KIND, if (hook.afterReturn) Kind.ENTRY_THEN_RETURN else Kind.ENTRY)
                enable()
            }
            n++
        }
        return n
    }

    /**
     * The instruction after the invoke at [call]: its length is decoded from the method's bytecodes when the VM
     * provides them, else the 3 code units of every `invoke-*` / `invoke-*`/range (FINDINGS A.2).
     */
    private fun returnLocation(call: Location): Location? {
        val method = call.method()
        val units = if (vm().canGetBytecodes()) runCatching { dexInvokeUnits(method.bytecodes(), call.codeIndex()) }.getOrNull() else null
        return method.locationOfCodeIndex(call.codeIndex() + (units ?: 3))
    }

    /**
     * A pending caller-return breakpoint whose thread stopped elsewhere with the caller frame already popped can never
     * hit: report it instead of waiting for the milestone timeout.
     */
    private fun missedReturns(hit: Set<Pair<ThreadReference, String>>): List<DebugEvent> {
        val out = ArrayList<DebugEvent>()
        for ((key, p) in pendingReturns.toList()) {
            val (t, depth) = p
            if (hit.none { it.first == t } || (t to key) in hit) continue
            if (t.frameCount() < depth) {
                val erm = vm().eventRequestManager()
                erm.deleteEventRequests(erm.breakpointRequests().filter { it.getProperty(KEY) == key && it.getProperty(KIND) in RETURN_KINDS })
                pendingReturns.remove(key)
                out += DebugEvent.HookFailed(key, "the method returned without reaching the return breakpoint")
            }
        }
        return out
    }

    private fun describe(exception: ObjectReference): String {
        val type = exception.referenceType()
        val message = type.fieldByName("detailMessage")?.let { (exception.getValue(it) as? StringReference)?.value() }
        return if (message != null) "${type.name()}: $message" else type.name()
    }

    private fun noMethod(hook: Hook) =
        "no method ${hook.methodName}${hook.signature ?: ""} with code is declared in ${hook.className}"

    private enum class Kind { WAIT, ENTRY, ENTRY_THEN_RETURN, RETURN }

    private companion object {
        const val KEY = "memscope.key"
        const val KIND = "memscope.kind"
        const val HOOK = "memscope.hook"
        const val DISPOSE_WAIT_MS = 3_000L
        /**
         * Breakpoints stop the whole VM (FINDINGS A.2/A.3). SUSPEND_EVENT_THREAD was tried in fix round 2 against the
         * first-activity wedge and did not change its rate (5 of 30 either way), so the verified policy stays.
         */
        const val BREAKPOINT_SUSPEND = EventRequest.SUSPEND_ALL
        val RETURN_KINDS = setOf(Kind.WAIT, Kind.RETURN)
    }
}

/** Length in 16-bit code units of the dex invoke instruction at [codeIndex], or null if it is not a known invoke. */
internal fun dexInvokeUnits(bytecodes: ByteArray, codeIndex: Long): Int? {
    val at = codeIndex * 2
    if (codeIndex < 0 || at >= bytecodes.size) return null
    return when (bytecodes[at.toInt()].toInt() and 0xff) {
        in 0x6e..0x72, in 0x74..0x78 -> 3 // invoke-kind (35c), invoke-kind/range (3rc)
        0xfa, 0xfb -> 4 // invoke-polymorphic (45cc), invoke-polymorphic/range (4rcc)
        0xfc, 0xfd -> 3 // invoke-custom (35c), invoke-custom/range (3rc)
        else -> null
    }
}

/**
 * JDI's `TargetVM` prints `Ignoring cmd <id>/199/1 from the VM` to stderr for every ART DDM packet (FINDINGS A.1).
 * On the first attach this replaces `System.err` process-wide with a PrintStream that drops exactly those
 * `println(String)` lines (starting with "Ignoring cmd " and ending with " from the VM") and passes everything else
 * through to the previous stream. It is installed once and never restored.
 */
private object JdiNoise {
    private val installed = AtomicBoolean(false)

    fun suppress() {
        if (!installed.compareAndSet(false, true)) return
        val target = System.err
        System.setErr(object : PrintStream(target, true) {
            override fun println(x: String?) {
                if (x != null && x.startsWith("Ignoring cmd ") && x.endsWith(" from the VM")) return
                target.println(x)
            }
        })
    }
}
