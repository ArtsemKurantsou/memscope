package dev.memscope.device

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
     * Throws [DeviceException] after killing the process: TIMEOUT, CANCELLED, or COMMAND_FAILED if writing [stdoutTo] (or [onBytes]) fails; the partial
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

        val fileOut: OutputStream? = stdoutTo?.let {
            try {
                Files.newOutputStream(it)
            } catch (e: IOException) {
                throw writeFailed(it, e)
            }
        }
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
        val writeError = AtomicReference<Throwable>()
        val pumps = listOf(
            pump(process.inputStream, fileOut ?: stdoutBuf, if (fileOut != null) onBytes else null, writeError),
            pump(process.errorStream, stderrBuf, null, writeError),
        )

        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        try {
            while (!process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
                writeError.get()?.let { throw writeFailed(stdoutTo, it) }
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
            writeError.get()?.let { throw writeFailed(stdoutTo, it) }
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

    private fun writeFailed(dest: Path?, cause: Throwable) = DeviceException(
        DeviceException.Kind.COMMAND_FAILED, "could not write ${dest ?: "process output"}: ${cause.message ?: cause}",
        "Free disk space or check that the destination folder exists and is writable, then retry.", cause,
    )

    /** Copies [input] to [out]. A read error means the process was killed; a write or callback error is recorded for the caller. */
    private fun pump(
        input: InputStream, out: OutputStream, onBytes: ((Long) -> Unit)?, writeError: AtomicReference<Throwable>,
    ): Thread =
        Thread {
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = try {
                    input.read(buf)
                } catch (_: IOException) {
                    break // stream closed because the process was killed
                }
                if (n < 0) break
                try {
                    out.write(buf, 0, n)
                    total += n
                    onBytes?.invoke(total)
                } catch (t: Throwable) {
                    writeError.compareAndSet(null, t)
                    break
                }
            }
        }.apply { isDaemon = true; start() }

    private companion object {
        const val POLL_MS = 50L
        const val PUMP_JOIN_MS = 2_000L
    }
}
