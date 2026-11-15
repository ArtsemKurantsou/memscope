package dev.memscope.device

import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class Device(val serial: String, val state: String, val model: String?, val apiLevel: Int?)
data class ProcessInfo(val pid: Int, val name: String)

/**
 * Typed adb operations. Arguments are always passed to the runner as a list, never joined on the host.
 * Note that `adb shell` itself joins its arguments with spaces for the device shell, so device-side
 * paths must not contain spaces.
 */
class Adb(val adbPath: Path, val runner: ProcessRunner = RealProcessRunner()) {

    fun devices(): List<Device> {
        val result = adbRun(null, listOf("devices", "-l"), 10.seconds)
        return result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("*") && !it.startsWith("List of devices") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                val (serial, state) = parts
                val model = parts.drop(2).firstOrNull { it.startsWith("model:") }?.removePrefix("model:")
                Device(serial, state, model, if (state == "device") apiLevelOf(serial) else null)
            }
            .toList()
    }

    private fun apiLevelOf(serial: String): Int? = try {
        shell(serial, listOf("getprop", "ro.build.version.sdk"), 5.seconds).stdout.trim().toIntOrNull()
    } catch (_: DeviceException) {
        null
    }

    /** Runs a device command. A non-zero remote exit code is returned, not thrown; adb-level failures throw. */
    fun shell(
        serial: String,
        command: List<String>,
        timeout: Duration = 15.seconds,
        cancel: CancelToken = CancelToken.NONE,
    ): ProcessResult {
        val result = adbRun(serial, listOf("shell") + command, timeout, cancel = cancel)
        adbFailure(result, serial)?.let { throw it }
        return result
    }

    /**
     * Streams the stdout of a device command (`adb exec-out`, binary safe) into [dest] and returns its size.
     * [dest] is deleted on any failure.
     */
    fun execOutToFile(
        serial: String,
        command: List<String>,
        dest: Path,
        timeout: Duration,
        cancel: CancelToken = CancelToken.NONE,
        onBytes: (Long) -> Unit = {},
    ): Long = withCleanup(dest) {
        val result = adbRun(serial, listOf("exec-out") + command, timeout, dest, cancel, onBytes)
        requireSuccess(result, serial, "exec-out ${command.firstOrNull()}")
        Files.size(dest)
    }

    fun pull(serial: String, remote: String, dest: Path, timeout: Duration, cancel: CancelToken = CancelToken.NONE) {
        withCleanup(dest) {
            val result = adbRun(serial, listOf("pull", remote, dest.toString()), timeout, cancel = cancel)
            requireSuccess(result, serial, "pull $remote")
        }
    }

    /** Forwards a free local TCP port to the JDWP endpoint of [pid] and returns that port. */
    fun forwardJdwp(serial: String, pid: Int): Int {
        val result = adbRun(serial, listOf("forward", "tcp:0", "jdwp:$pid"), 10.seconds)
        requireSuccess(result, serial, "forward jdwp:$pid")
        return result.stdout.trim().toIntOrNull()
            ?: throw DeviceException(
                DeviceException.Kind.COMMAND_FAILED, "adb forward returned an unexpected port: '${result.stdout.trim()}'",
                "Update platform-tools (adb >= 1.0.41) and retry.",
            )
    }

    fun removeForward(serial: String, localPort: Int) {
        val result = adbRun(serial, listOf("forward", "--remove", "tcp:$localPort"), 10.seconds)
        requireSuccess(result, serial, "forward --remove tcp:$localPort")
    }

    /**
     * pids of debuggable processes. `adb jdwp` keeps streaming and never exits, so it is read for a short
     * window into a temp file and then stopped.
     */
    fun jdwpPids(serial: String): Set<Int> {
        val tmp = Files.createTempFile("memscope-jdwp", ".txt")
        try {
            try {
                val result = adbRun(serial, listOf("jdwp"), JDWP_WINDOW, stdoutTo = tmp)
                adbFailure(result, serial)?.let { throw it }
            } catch (e: DeviceException) {
                if (e.kind != DeviceException.Kind.TIMEOUT) throw e // the window elapsing is the normal way out
            }
            return Files.readAllLines(tmp).mapNotNull { it.trim().toIntOrNull() }.toSet()
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Processes whose name is [packageName] or starts with "[packageName]:". */
    fun processes(serial: String, packageName: String): List<ProcessInfo> {
        val result = shell(serial, listOf("ps", "-A", "-o", "PID,NAME"))
        requireSuccess(result, serial, "ps")
        return result.stdout.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            val pid = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val name = parts.getOrNull(1)?.trim() ?: return@mapNotNull null
            if (name == packageName || name.startsWith("$packageName:")) ProcessInfo(pid, name) else null
        }.toList()
    }

    /** Third-party packages where `run-as <pkg> id` exits 0. */
    fun debuggablePackages(serial: String): List<String> {
        val listing = shell(serial, listOf("pm", "list", "packages", "-3"), 30.seconds)
        requireSuccess(listing, serial, "pm list packages")
        return listing.stdout.lineSequence()
            .map { it.trim().removePrefix("package:") }
            .filter { it.isNotEmpty() && !it.contains(' ') }
            .filter { shell(serial, listOf("run-as", it, "id"), 10.seconds).exitCode == 0 }
            .toList()
    }

    /** Refuses before anything on the device is changed: throws NOT_INSTALLED or NOT_DEBUGGABLE. */
    fun requireDebuggable(serial: String, packageName: String) {
        val result = shell(serial, listOf("run-as", packageName, "id"), 10.seconds)
        if (result.exitCode == 0) return
        val text = (result.stderr + "\n" + result.stdout).trim()
        when {
            "unknown package" in text -> throw DeviceException(
                DeviceException.Kind.NOT_INSTALLED, "$packageName is not installed on $serial.",
                "Install the app on the device, or pick a package from the list of debuggable apps.",
            )
            "not debuggable" in text -> throw DeviceException(
                DeviceException.Kind.NOT_DEBUGGABLE, "$packageName is not debuggable.",
                "Use a debug build (android:debuggable=\"true\"). Release builds on a 'user' device cannot be inspected.",
            )
            else -> throw DeviceException(
                DeviceException.Kind.COMMAND_FAILED, "run-as $packageName failed: $text",
                "Check the package name and that the device is unlocked; 'run-as' must be allowed for this app.",
            )
        }
    }

    /** `pkg/.Activity` of the launcher activity, or null if the package has none. */
    fun launcherComponent(serial: String, packageName: String): String? {
        val result = shell(
            serial,
            listOf("cmd", "package", "resolve-activity", "--brief", "-c", "android.intent.category.LAUNCHER", packageName),
        )
        if (result.exitCode != 0) return null
        return result.stdout.lineSequence().map { it.trim() }.lastOrNull { it.contains('/') && !it.contains(' ') }
    }

    // -- internals --

    private fun adbRun(
        serial: String?,
        args: List<String>,
        timeout: Duration,
        stdoutTo: Path? = null,
        cancel: CancelToken = CancelToken.NONE,
        onBytes: (Long) -> Unit = {},
    ): ProcessResult {
        val full = buildList {
            add(adbPath.toString())
            if (serial != null) { add("-s"); add(serial) }
            addAll(args)
        }
        val result = runner.run(full, timeout, stdoutTo, cancel, onBytes)
        if (serial == null) {
            adbFailure(result, null)?.let { throw it }
            if (result.exitCode != 0) throw commandFailed("adb ${args.first()}", result)
        }
        return result
    }

    private fun requireSuccess(result: ProcessResult, serial: String, what: String) {
        adbFailure(result, serial)?.let { throw it }
        if (result.exitCode != 0) throw commandFailed(what, result)
    }

    private fun commandFailed(what: String, result: ProcessResult): DeviceException {
        val detail = result.stderr.trim().ifEmpty { result.stdout.trim() }.take(500)
        return DeviceException(
            DeviceException.Kind.COMMAND_FAILED, "$what failed (exit ${result.exitCode}): $detail",
            "Check the device is connected and the app/path exists, then retry.",
        )
    }

    /** Recognises adb transport-level errors in stderr; remote command failures are not adb errors. */
    private fun adbFailure(result: ProcessResult, serial: String?): DeviceException? {
        val text = result.stderr.lowercase()
        if (text.isBlank()) return null
        val who = serial ?: "the device"
        return when {
            "device offline" in text || "unauthorized" in text -> DeviceException(
                DeviceException.Kind.DEVICE_OFFLINE, "$who is offline or unauthorized.",
                "Unlock the device and accept the USB debugging prompt, or run 'adb reconnect', then retry.",
            )
            "no devices/emulators found" in text || "no devices found" in text -> DeviceException(
                DeviceException.Kind.NO_DEVICE, "No device is connected.",
                "Connect a device or start an emulator, and check that 'adb devices' lists it.",
            )
            ("device '" in text && "not found" in text) || "error: closed" in text || "connection reset" in text ||
                "protocol fault" in text -> DeviceException(
                DeviceException.Kind.DISCONNECTED, "$who disconnected.",
                "Device disconnected: reconnect it (check the cable or emulator), then retry. Partial files were removed.",
            )
            "more than one device" in text -> DeviceException(
                DeviceException.Kind.COMMAND_FAILED, "More than one device is connected and no serial was given.",
                "Select a device explicitly.",
            )
            "cannot connect to daemon" in text || "failed to start daemon" in text -> DeviceException(
                DeviceException.Kind.COMMAND_FAILED, "adb server is not responding: ${result.stderr.trim().take(300)}",
                "Run 'adb kill-server' then 'adb start-server', then retry.",
            )
            else -> null
        }
    }

    private inline fun <T> withCleanup(dest: Path, block: () -> T): T {
        dest.parent?.let { Files.createDirectories(it) }
        try {
            return block()
        } catch (t: Throwable) {
            try { Files.deleteIfExists(dest) } catch (_: Exception) { /* best effort */ }
            throw t
        }
    }

    private companion object {
        val JDWP_WINDOW = 2.seconds
    }
}
