package dev.memscope.capture

import dev.memscope.device.Adb
import dev.memscope.device.CancelToken
import dev.memscope.device.DeviceException
import dev.memscope.device.ProcessResult
import dev.memscope.device.ProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/** Scripted adb: answers the handful of commands the dumper issues. Records every invocation. */
class FakeDevice(val serial: String = "emulator-5554") : ProcessRunner {
    enum class Pull { GOOD, DISCONNECT, TRUNCATED_EXIT0, CANCEL, NOT_HPROF }

    val calls = CopyOnWriteArrayList<List<String>>()
    var debuggable = true
    var processes = listOf("100 dev.app", "101 dev.app:remote")
    var pull = Pull.GOOD
    var hprofSize = 4096
    var pullDelayMs = 0L
    var statOverride: Long? = null
    var onCancelPull: () -> Unit = {}
    @Volatile var disconnected = false

    /** The command after `adb -s <serial>`. */
    private fun cmd(args: List<String>) = args.drop(3)
    fun shellCommands(): List<String> = calls.map(::cmd).filter { it.firstOrNull() == "shell" }.map { it.drop(1).joinToString(" ") }

    val adb = Adb(Path.of("/fake/adb"), this)

    override fun run(args: List<String>, timeout: Duration, stdoutTo: Path?, cancel: CancelToken, onBytes: (Long) -> Unit): ProcessResult {
        calls += args
        val c = cmd(args)
        if (disconnected) return ProcessResult(1, "", "adb: error: closed")
        return when {
            c.getOrNull(0) == "shell" && c.getOrNull(1) == "run-as" ->
                if (debuggable) ProcessResult(0, "uid=1", "") else ProcessResult(1, "", "run-as: package not debuggable: ${c[2]}")
            c.getOrNull(0) == "shell" && c.getOrNull(1) == "ps" -> ProcessResult(0, "PID NAME\n" + processes.joinToString("\n"), "")
            c.getOrNull(0) == "shell" && c.getOrNull(1) == "stat" -> ProcessResult(0, "${statOverride ?: hprofSize}\n", "")
            c.getOrNull(0) == "shell" -> ProcessResult(0, "", "")
            c.getOrNull(0) == "pull" -> doPull(Path.of(c[2]), cancel)
            else -> ProcessResult(0, "", "")
        }
    }

    private fun doPull(dest: Path, cancel: CancelToken): ProcessResult {
        if (pullDelayMs > 0) Thread.sleep(pullDelayMs)
        val header = "JAVA PROFILE 1.0.3\u0000".toByteArray()
        fun write(n: Int, head: ByteArray = header) = Files.write(dest, head + ByteArray(maxOf(0, n - head.size)))
        return when (pull) {
            Pull.GOOD -> { write(hprofSize); ProcessResult(0, "", "") }
            Pull.NOT_HPROF -> { write(hprofSize, "garbage garbage ".toByteArray()); ProcessResult(0, "", "") }
            Pull.TRUNCATED_EXIT0 -> { write(10); ProcessResult(0, "", "") }
            Pull.DISCONNECT -> { write(100); disconnected = true; ProcessResult(1, "", "adb: error: closed") }
            Pull.CANCEL -> {
                write(100); onCancelPull()
                throw DeviceException(DeviceException.Kind.CANCELLED, "pull was cancelled", "The operation was cancelled.")
            }
        }
    }
}
