package dev.memscope.server

import dev.memscope.analysis.ClassStat
import dev.memscope.analysis.ClassDelta
import dev.memscope.analysis.DiffResult
import dev.memscope.analysis.RootDelta
import dev.memscope.analysis.FieldValue
import dev.memscope.analysis.HeapSummary
import dev.memscope.analysis.ObjectDetails
import dev.memscope.analysis.Progress
import dev.memscope.analysis.RowKind
import dev.memscope.analysis.TreePage
import dev.memscope.analysis.TreeRow
import dev.memscope.capture.SessionMeta
import dev.memscope.capture.SessionStore
import dev.memscope.capture.SnapshotMeta
import dev.memscope.device.Adb
import dev.memscope.device.CancelToken
import dev.memscope.device.ProcessResult
import dev.memscope.device.ProcessRunner
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/** Scripted adb: answers the commands the routes issue. Records every invocation. */
class FakeAdbRunner : ProcessRunner {
    val calls = CopyOnWriteArrayList<List<String>>()
    var devicesOutput = "List of devices attached\nemulator-5554          device product:sdk model:sdk_gphone64 device:emu64\n"
    var noDevices = false
    var packages = listOf("dev.app", "dev.other")
    var debuggable = setOf("dev.app")
    var processes = listOf("100 dev.app", "101 dev.app:remote")
    var hprofSize = 4096
    var meminfo = ""
    @Volatile var disconnected = false

    fun count(vararg prefix: String) = calls.count { c -> c.drop(1).let { a -> a.windowed(prefix.size).any { it == prefix.toList() } } }

    val adb = Adb(Path.of("/fake/adb"), this)

    override fun run(args: List<String>, timeout: Duration, stdoutTo: Path?, cancel: CancelToken, onBytes: (Long) -> Unit): ProcessResult {
        calls += args
        val c = if (args.getOrNull(1) == "-s") args.drop(3) else args.drop(1)
        if (noDevices) return ProcessResult(1, "", "error: no devices/emulators found")
        if (disconnected && c.firstOrNull() != "devices") return ProcessResult(1, "", "adb: error: closed")
        return when {
            c[0] == "devices" -> ProcessResult(0, devicesOutput, "")
            c[0] == "shell" && c[1] == "getprop" -> ProcessResult(0, "35\n", "")
            c[0] == "shell" && c[1] == "pm" -> ProcessResult(0, packages.joinToString("\n") { "package:$it" }, "")
            c[0] == "shell" && c[1] == "run-as" && c.getOrNull(3) == "cat" -> ProcessResult(0, "Rss: 9 kB\nPss: 4242 kB\n", "")
            c[0] == "shell" && c[1] == "run-as" ->
                if (c[2] in debuggable) ProcessResult(0, "uid=1", "")
                else if (c[2] in packages) ProcessResult(1, "", "run-as: package not debuggable: ${c[2]}")
                else ProcessResult(1, "", "run-as: unknown package: ${c[2]}")
            c[0] == "shell" && c[1] == "ps" -> {
                val name = "dev.app"
                ProcessResult(0, "PID NAME\n" + processes.filter { it.endsWith(name) || it.contains("$name:") }.joinToString("\n"), "")
            }
            c[0] == "shell" && c[1] == "dumpsys" -> ProcessResult(0, meminfo, "")
            c[0] == "shell" && c[1] == "stat" -> ProcessResult(0, "$hprofSize\n", "")
            c[0] == "shell" && c[1] == "cmd" && c.last() == "dev.app" -> ProcessResult(0, "priority=0\ndev.app/.Main\n", "")
            c[0] == "pull" -> {
                val head = "JAVA PROFILE 1.0.3\u0000".toByteArray()
                Files.write(Path.of(c[2]), head + ByteArray(hprofSize - head.size))
                ProcessResult(0, "", "")
            }
            else -> ProcessResult(0, "", "")
        }
    }
}

fun treeRow(ref: String, label: String = ref) = TreeRow(
    ref, RowKind.OBJECT, label, null, "java.lang.String", 1, 16, 32, 0, 1.5, 0, false, 0,
)

/** Canned answers; records what it was asked. [onChildren] lets a test inject failures. */
class FakeView(val name: String = "fake") : SnapshotView {
    @Volatile var closed = false
    var revealPath: List<String> = listOf("root", "cat:static", "obj:3")
    var revealFails: Throwable? = null
    val detailsCalls = CopyOnWriteArrayList<String>()
    var onChildren: (String) -> Unit = {}
    val childrenCalls = CopyOnWriteArrayList<Triple<String, Int, Int>>()

    override fun summary() = HeapSummary(1000, 10, 5, 42, 7, 600)
    override fun children(ref: String, offset: Int, limit: Int): TreePage {
        childrenCalls += Triple(ref, offset, limit)
        onChildren(ref)
        return TreePage(listOf(treeRow("obj:1", "$name-row")), 1)
    }
    override fun details(ref: String): ObjectDetails {
        detailsCalls += ref
        require(ref.startsWith("obj:")) { "Ref \"$ref\" does not name a single object" }
        return ObjectDetails(ref, "java.lang.String", 16, 32, 0, emptyList(), listOf(FieldValue("value", "int", "7", null)), emptyList(), 0, emptyList())
    }
    override fun classes() = listOf(ClassStat("java.lang.String", 3, 48, 96, false))
    override fun instances(className: String, offset: Int, limit: Int) = TreePage(listOf(treeRow("obj:2")), 1)
    val diffCalls = CopyOnWriteArrayList<String>()
    /** Canned: one class and one root delta; [name] and the base view's name are in the class name so tests see what was compared. */
    override fun diff(base: SnapshotView): DiffResult {
        diffCalls += (base as FakeView).name
        return DiffResult(
            listOf(ClassDelta("$name vs ${base.name}", false, 1, 3, 10, 30, 10, 30, 1, 3, 10, 30, 10, 30)),
            listOf(RootDelta("Static fields", 100, 300, 50, 150)), 1000, 2000, 600, 1600,
        )
    }
    override fun revealPath(ref: String): List<String> { revealFails?.let { throw it }; return revealPath }
    override fun indexInParent(parentRef: String, childRef: String) = 4
    /** Replaces the (empty) findings computation: gets the rule callback and the cancel check. */
    @Volatile var findingsImpl: ((onRule: (String) -> Unit, isCancelled: () -> Boolean) -> List<dev.memscope.analysis.findings.Finding>)? = null
    override fun findings(screen: dev.memscope.analysis.findings.ScreenSize, onRule: (String) -> Unit, isCancelled: () -> Boolean) =
        findingsImpl?.invoke(onRule, isCancelled) ?: emptyList()
    override fun close() { closed = true }
}

/** An opener that emits scripted progress and returns a [FakeView] per call. */
class FakeOpener(
    val reports: List<Pair<String, Double>> = listOf("parse" to 0.0, "parse" to 0.3, "parse" to 0.8, "dominators" to 0.5, "retained" to 1.0, "cache" to 1.0),
) : SnapshotOpener {
    val opened = CopyOnWriteArrayList<FakeView>()
    val calls = AtomicInteger()
    val running = AtomicInteger()
    val maxRunning = AtomicInteger()
    @Volatile var failWith: Throwable? = null
    @Volatile var gate: java.util.concurrent.CountDownLatch? = null
    @Volatile var seenPrefixes: List<String> = emptyList()

    override fun open(file: File, appPackagePrefixes: List<String>, progress: Progress): SnapshotView {
        calls.incrementAndGet()
        maxRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
        try {
            seenPrefixes = appPackagePrefixes
            gate?.await()
            reports.forEach { (s, f) -> progress.report(s, f) }
            failWith?.let { throw it }
            return FakeView(file.name).also { opened += it }
        } finally {
            running.decrementAndGet()
        }
    }
}

/** Adds an OK snapshot (with a file of [bytes] bytes) to [sessionId] and returns its id. */
fun SessionStore.addFakeSnapshot(sessionId: String, bytes: Int = 2048): String {
    val id = nextSnapshotId(sessionId)
    Files.write(snapshotFile(sessionId, id), ByteArray(bytes))
    addSnapshot(sessionId, SnapshotMeta(id, "label", null, "dev.app", 1L, "$id.hprof", bytes.toLong(), SnapshotMeta.Status.OK))
    return id
}

fun SessionStore.newAttachSession(pkg: String = "dev.app") = create("emulator-5554", pkg, SessionMeta.Mode.ATTACH)
