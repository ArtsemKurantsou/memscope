package dev.memscope.device

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

/** Cooperative cancellation flag shared between a job and the process runs it starts. */
class CancelToken {
    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }
    val isCancelled: Boolean get() = cancelled

    companion object {
        /** A token that is never cancelled. */
        val NONE = CancelToken()
    }
}

interface ProcessRunner {
    /**
     * Runs [args] (never joined into a shell string). When [stdoutTo] is set, stdout is streamed to that file
     * (truncating it), [ProcessResult.stdout] is empty and [onBytes] receives the cumulative byte count.
     * Throws [DeviceException] with kind TIMEOUT or CANCELLED after killing the process; the partial
     * [stdoutTo] file is left for the caller to delete.
     */
    fun run(
        args: List<String>,
        timeout: Duration,
        stdoutTo: Path? = null,
        cancel: CancelToken = CancelToken.NONE,
        onBytes: (Long) -> Unit = {},
    ): ProcessResult
}

class RealProcessRunner : ProcessRunner {
    override fun run(
        args: List<String>,
        timeout: Duration,
        stdoutTo: Path?,
        cancel: CancelToken,
        onBytes: (Long) -> Unit,
    ): ProcessResult {
        val label = args.firstOrNull()?.substringAfterLast('/') ?: "process"
        if (cancel.isCancelled) throw cancelled(label)

        val fileOut: OutputStream? = stdoutTo?.let { Files.newOutputStream(it) }
        val process = try {
            ProcessBuilder(args).redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null"))).start()
        } catch (e: IOException) {
            fileOut?.close()
            throw DeviceException(
                DeviceException.Kind.COMMAND_FAILED, "Could not start $label: ${e.message}",
                "Check that '${args.firstOrNull()}' exists and is executable.", e,
            )
        }

        val stdoutBuf = ByteArrayOutputStream()
        val stderrBuf = ByteArrayOutputStream()
        val pumps = listOf(
            pump(process.inputStream, fileOut ?: stdoutBuf, if (fileOut != null) onBytes else null),
            pump(process.errorStream, stderrBuf, null),
        )

        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        try {
            while (!process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
                if (cancel.isCancelled) throw cancelled(label)
                if (System.nanoTime() >= deadline) {
                    throw DeviceException(
                        DeviceException.Kind.TIMEOUT, "$label timed out after $timeout",
                        "The device or adb did not answer in time. Check the device is connected and unlocked, then retry.",
                    )
                }
            }
            if (cancel.isCancelled) throw cancelled(label)
            pumps.forEach { it.join(PUMP_JOIN_MS) }
            return ProcessResult(process.exitValue(), stdoutBuf.toString(Charsets.UTF_8), stderrBuf.toString(Charsets.UTF_8))
        } catch (e: Throwable) {
            kill(process)
            throw e
        } finally {
            pumps.forEach { it.join(PUMP_JOIN_MS) }
            fileOut?.close()
        }
    }

    private fun cancelled(label: String) =
        DeviceException(DeviceException.Kind.CANCELLED, "$label was cancelled", "The operation was cancelled.")

    private fun kill(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor(2, TimeUnit.SECONDS)
    }

    private fun pump(input: InputStream, out: OutputStream, onBytes: ((Long) -> Unit)?): Thread =
        Thread {
            val buf = ByteArray(64 * 1024)
            var total = 0L
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    onBytes?.invoke(total)
                }
            } catch (_: IOException) {
                // stream closed because the process was killed
            }
        }.apply { isDaemon = true; start() }

    private companion object {
        const val POLL_MS = 50L
        const val PUMP_JOIN_MS = 2_000L
    }
}
