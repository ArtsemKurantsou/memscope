package dev.memscope.capture

import dev.memscope.device.Adb
import dev.memscope.device.CancelToken
import dev.memscope.device.DeviceException
import dev.memscope.device.ProcessResult
import dev.memscope.device.ProcessRunner
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream
import kotlin.time.Duration

/**
 * Scripted adb for startup sessions: launch makes a new JDWP pid appear in `track-jdwp`, whose name resolves through
 * the zygote / `<pre-initialized>` race; `exec-out run-as … gzip` delivers a gzipped fake hprof. Records every call.
 */
class FakeStartupDevice(val serial: String = "emulator-5554", val pkg: String = "dev.app") : ProcessRunner {
    enum class Pull { GOOD, TRUNCATED, DISCONNECT, EMPTY }

    val calls = CopyOnWriteArrayList<List<String>>()
    var debuggable = true
    var launcher: String? = "$pkg/.Main"
    var targetPid = 4242
    var targetName = pkg
    /** Other new pids that appear with the launch (e.g. another process of the package). */
    var otherNewPids = mapOf(4300 to "$pkg:other")
    var pull = Pull.GOOD
    var hprofSize = 4096
    var amStartOutput = "Starting: Intent { cmp=$pkg/.Main }"
    /** Command (joined `shell` args prefix) that fails with exit 1, to inject errors. */
    var failShell: String? = null
    var failForward = false
    /** Called when a matching shell command runs (joined args start with the key). */
    val onShell = HashMap<String, () -> Unit>()
    var pidAlive = true
    /** A new process first shows as `zygote64`, then `<pre-initialized>`, then its name. */
    var nameRace = true
    @Volatile var launched = false
    @Volatile var disconnected = false
    /** Set to make `devices` list the device again after a disconnect. */
    @Volatile var listedInDevices = true
    private val cmdlineReads = HashMap<Int, Int>()

    val adb = Adb(Path.of("/fake/adb"), this)

    private fun cmd(args: List<String>) = if (args.getOrNull(1) == "-s") args.drop(3) else args.drop(1)
    fun shellCommands(): List<String> = calls.map(::cmd).filter { it.firstOrNull() == "shell" }.map { it.drop(1).joinToString(" ") }
    fun allCommands(): List<String> = calls.map { cmd(it).joinToString(" ") }
    fun index(prefix: String) = allCommands().indexOfFirst { it.startsWith(prefix) }
    fun count(prefix: String) = allCommands().count { it.startsWith(prefix) }

    override fun run(args: List<String>, timeout: Duration, stdoutTo: Path?, cancel: CancelToken, onBytes: (Long) -> Unit): ProcessResult {
        calls += args
        val c = cmd(args)
        if (c.firstOrNull() == "devices") {
            val line = if (listedInDevices && !disconnected) "$serial\tdevice product:x model:fake\n" else ""
            return ProcessResult(0, "List of devices attached\n$line", "")
        }
        if (disconnected) return ProcessResult(1, "", "adb: error: closed")
        val joined = c.drop(1).joinToString(" ")
        if (c[0] == "shell") {
            onShell.entries.firstOrNull { joined.startsWith(it.key) }?.value?.invoke()
            if (disconnected) return ProcessResult(1, "", "adb: error: closed")
            failShell?.let { if (joined.startsWith(it)) return ProcessResult(1, "", "injected failure: $joined") }
        }
        return when {
            c[0] == "track-jdwp" -> track(stdoutTo!!, timeout, cancel)
            c[0] == "forward" && c[1] == "tcp:0" ->
                if (failForward) ProcessResult(1, "", "error: cannot forward") else ProcessResult(0, "40001\n", "")
            c[0] == "forward" -> ProcessResult(0, "", "")
            c[0] == "exec-out" -> doPull(stdoutTo!!)
            c[0] == "shell" && c[1] == "run-as" && c.getOrNull(3) == "id" ->
                if (debuggable) ProcessResult(0, "uid=1", "") else ProcessResult(1, "", "run-as: package not debuggable: ${c[2]}")
            c[0] == "shell" && c[1] == "run-as" && c.getOrNull(3) == "cat" -> ProcessResult(0, "Rss: 90000 kB\nPss: 41000 kB\n", "")
            c[0] == "shell" && c[1] == "cmd" && c[2] == "package" ->
                launcher?.let { ProcessResult(0, "priority=0\n$it\n", "") } ?: ProcessResult(0, "No activity found\n", "")
            c[0] == "shell" && c[1] == "am" && c[2] == "start" -> { launched = true; ProcessResult(0, amStartOutput, "") }
            c[0] == "shell" && c[1] == "cat" && c[2].startsWith("/proc/") -> cmdline(c[2].removePrefix("/proc/").substringBefore('/').toInt())
            else -> ProcessResult(0, "", "")
        }
    }

    private fun cmdline(pid: Int): ProcessResult {
        val name = when (pid) {
            targetPid -> if (pidAlive) targetName else return ProcessResult(1, "", "No such file or directory")
            in otherNewPids -> otherNewPids.getValue(pid)
            else -> return ProcessResult(1, "", "No such file or directory")
        }
        val n = cmdlineReads.merge(pid, 1, Int::plus)!!
        val shown = if (!nameRace) name else when (n) { 1 -> "zygote64"; 2 -> "<pre-initialized>"; else -> name }
        return ProcessResult(0, "$shown\u0000", "")
    }

    private fun track(dest: Path, timeout: Duration, cancel: CancelToken): ProcessResult {
        fun msg(pids: Collection<Int>): String { val body = pids.joinToString("") { "$it\n" }; return "%04x".format(body.length) + body }
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        var sent = 0
        val out = StringBuilder()
        while (true) {
            if (disconnected) return ProcessResult(1, "", "adb: error: closed")
            val want = if (launched) 2 else 1
            if (sent < want) {
                out.append(msg(listOf(1, 2)))
                if (want == 2) out.append(msg(listOf(1, 2) + otherNewPids.keys + targetPid))
                sent = want
                Files.writeString(dest, out)
            }
            if (cancel.isCancelled) throw DeviceException(DeviceException.Kind.CANCELLED, "adb was cancelled", "cancelled")
            if (System.nanoTime() > deadline) throw DeviceException(DeviceException.Kind.TIMEOUT, "adb timed out", "timeout")
            Thread.sleep(10)
        }
    }

    private fun doPull(dest: Path): ProcessResult {
        val raw = "JAVA PROFILE 1.0.3\u0000".toByteArray().let { it + ByteArray(hprofSize - it.size) }
        val gz = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(raw) } }.toByteArray()
        return when (pull) {
            Pull.GOOD -> { Files.write(dest, gz); ProcessResult(0, "", "") }
            Pull.TRUNCATED -> { Files.write(dest, gz.copyOf(gz.size / 2)); ProcessResult(0, "", "") }
            Pull.EMPTY -> { Files.write(dest, ByteArray(0)); ProcessResult(0, "", "") }
            Pull.DISCONNECT -> { Files.write(dest, gz.copyOf(10)); disconnected = true; ProcessResult(1, "", "adb: error: closed") }
        }
    }
}
