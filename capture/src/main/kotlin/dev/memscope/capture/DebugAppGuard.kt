package dev.memscope.capture

import dev.memscope.device.Adb
import dev.memscope.device.DeviceException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.time.Duration.Companion.seconds

/** One device on which MemScope set a debug app and has not cleared it yet. */
@Serializable
data class PendingDebugApp(
    val serial: String,
    val packageName: String,
    val processName: String,
    val pid: Int? = null,
    val forwardPort: Int? = null,
    /** True once the process got past `waitForDebugger`; a process that never did is frozen and must be stopped. */
    val released: Boolean = false,
)

/**
 * Crash-safe `am set-debug-app`: a marker in `<home>/pending-debug-app.json` is written before the device is changed
 * and removed only after `am clear-debug-app` succeeded, so a crash or a lost device is cleaned up by [recoverStale].
 * One entry per device serial. Thread-safe within a process.
 */
class DebugAppGuard(private val adb: Adb, private val home: Path) {
    private val lock = Any()
    private val file: Path get() = home.resolve(MARKER)

    /** Writes the marker, then `am set-debug-app -w <processName>` (one-shot: only that process waits). Throws if adb fails; the marker stays. */
    fun arm(serial: String, packageName: String, processName: String = packageName) {
        synchronized(lock) { write(read().filter { it.serial != serial } + PendingDebugApp(serial, packageName, processName)) }
        val result = adb.shell(serial, listOf("am", "set-debug-app", "-w", processName), 15.seconds)
        if (result.exitCode != 0) {
            throw DeviceException(
                DeviceException.Kind.COMMAND_FAILED,
                "am set-debug-app -w $processName failed (exit ${result.exitCode}): ${output(result.stderr, result.stdout)}",
                "Check that the device is unlocked and the app is installed, then retry.",
            )
        }
    }

    /** Updates the marker of [serial] (pid, forward port, released); no-op if there is none. */
    fun update(serial: String, f: (PendingDebugApp) -> PendingDebugApp) = synchronized(lock) {
        val all = read()
        if (all.any { it.serial == serial }) write(all.map { if (it.serial == serial) f(it) else it })
    }

    /** `am clear-debug-app`, then removes the marker. Throws (and keeps the marker) if the device cannot be reached. */
    fun disarm(serial: String) {
        val result = adb.shell(serial, listOf("am", "clear-debug-app"), 15.seconds)
        if (result.exitCode != 0) {
            throw DeviceException(
                DeviceException.Kind.COMMAND_FAILED, "am clear-debug-app failed (exit ${result.exitCode}): ${output(result.stderr, result.stdout)}",
                "The app may still be set to wait for a debugger. Run 'adb shell am clear-debug-app' or restart MemScope with the device connected.",
            )
        }
        synchronized(lock) { write(read().filter { it.serial != serial }) }
    }

    fun pending(): List<PendingDebugApp> = synchronized(lock) { read() }

    /**
     * For every marker left by a crashed or interrupted run: if its device is connected, clear the debug app, remove the
     * recorded forward and force-stop a process that is still frozen in `waitForDebugger`, then drop the marker; a device
     * that is not connected keeps its marker for the next start. Returns one log line per action. Never throws for a
     * single device; throws only if adb itself cannot be run.
     */
    fun recoverStale(): List<String> {
        val entries = try {
            pending()
        } catch (e: Exception) {
            synchronized(lock) { Files.deleteIfExists(file) }
            return listOf("Pending debug-app marker $file was unreadable (${e.message}) and was removed; run 'adb shell am clear-debug-app' if an app waits for a debugger.")
        }
        if (entries.isEmpty()) return emptyList()
        val connected = adb.devices().filter { it.state == "device" }.map { it.serial }.toSet()
        val log = ArrayList<String>()
        for (entry in entries) {
            if (entry.serial !in connected) {
                log += "Device ${entry.serial} is not connected: the debug app ${entry.processName} set by an earlier run will be cleared when it is connected at the next start."
                continue
            }
            try {
                recover(entry, log)
            } catch (e: DeviceException) {
                log += "Could not clean up ${entry.serial} (${e.message}); will retry at the next start."
            }
        }
        return log
    }

    private fun recover(entry: PendingDebugApp, log: MutableList<String>) {
        val serial = entry.serial
        entry.forwardPort?.let { port ->
            try { adb.removeForward(serial, port) } catch (_: DeviceException) { /* already gone */ }
        }
        val pid = entry.pid
        if (pid != null && !entry.released) {
            val cmdline = adb.shell(serial, listOf("cat", "/proc/$pid/cmdline"), 10.seconds)
            if (cmdline.exitCode == 0 && cmdline.stdout.substringBefore('\u0000').trim() == entry.processName) {
                adb.shell(serial, listOf("am", "force-stop", entry.packageName), 15.seconds)
                log += "Stopped ${entry.packageName} on $serial: it was still waiting for a debugger after an interrupted startup recording."
            }
        }
        disarm(serial)
        log += "Debug app ${entry.processName} on $serial cleared after an interrupted startup recording."
    }

    private fun output(stderr: String, stdout: String) = stderr.trim().ifEmpty { stdout.trim() }.take(300)

    private fun read(): List<PendingDebugApp> {
        if (!Files.isRegularFile(file)) return emptyList()
        return json.decodeFromString(ListSerializer(PendingDebugApp.serializer()), Files.readString(file))
    }

    private fun write(entries: List<PendingDebugApp>) {
        if (entries.isEmpty()) { Files.deleteIfExists(file); return }
        Files.createDirectories(home)
        val tmp = Files.createTempFile(home, "pending-debug-app", ".tmp")
        try {
            Files.writeString(tmp, json.encodeToString(ListSerializer(PendingDebugApp.serializer()), entries))
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private companion object {
        const val MARKER = "pending-debug-app.json"
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
