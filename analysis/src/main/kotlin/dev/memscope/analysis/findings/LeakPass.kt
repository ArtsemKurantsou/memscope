package dev.memscope.analysis.findings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** One leak as the child JVM reports it: the leaking object's hprof id and a compact rendering of Shark's trace. */
@Serializable
class LeakTraceDto(
    val objectId: Long,
    val className: String,
    val reason: String,
    /** "GC root: ..." first, one line per reference (the suspect marked), the leaking object last. */
    val lines: List<String>,
    /** The suspect reference as `Owner.field`, or "its GC root". */
    val holder: String,
)

@Serializable
class LeakResultDto(val traces: List<LeakTraceDto>)

/** Result of Shark's own leak analysis of an hprof. */
internal sealed interface LeakPassResult {
    class Done(val traces: List<LeakTraceDto>) : LeakPassResult
    object TimedOut : LeakPassResult
    object OutOfMemory : LeakPassResult
    /** Still waiting for another pass (one at a time) when the time was up. */
    object Busy : LeakPassResult
    object Cancelled : LeakPassResult
    class Failed(val message: String) : LeakPassResult
}

/** Starts the child process that writes its [LeakResultDto] JSON to [out]; a seam so tests need no real dump. */
internal fun interface LeakChildLauncher {
    fun start(hprof: File, out: File, stderr: File): Process
}

/**
 * Shark's leak analysis in a CHILD JVM (`LeakPassMain`): its memory (a second Shark graph) never counts against the
 * server's heap, an out-of-memory only kills the child, and on timeout the process is destroyed (a real bound).
 * One pass at a time (global semaphore). `MEMSCOPE_LEAK_XMX` (default 4g) sizes the child heap.
 */
internal object LeakPass {
    const val CHILD_OOM_EXIT = 3
    private val gate = Semaphore(1)

    fun xmx(): String = System.getenv("MEMSCOPE_LEAK_XMX")?.takeIf { it.isNotBlank() } ?: "4g"

    val realLauncher = LeakChildLauncher { hprof, out, stderr ->
        val java = ProcessHandle.current().info().command().orElse("java")
        ProcessBuilder(
            java, "-Xmx${xmx()}", "-XX:+ExitOnOutOfMemoryError", "-cp", System.getProperty("java.class.path"),
            "dev.memscope.analysis.findings.LeakPassMainKt", hprof.absolutePath, out.absolutePath,
        ).redirectErrorStream(false).redirectError(stderr).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    }

    /**
     * Runs the child for [hprof] within [timeoutMs] (waiting for the gate included). [isCancelled] is polled while
     * waiting; when it turns true the child is destroyed and [LeakPassResult.Cancelled] returned. [tmpDir] holds
     * the child's output files (default: the system temp dir).
     */
    fun run(
        hprof: File, timeoutMs: Long, launcher: LeakChildLauncher = realLauncher,
        isCancelled: () -> Boolean = { false }, tmpDir: File? = null,
    ): LeakPassResult {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        fun leftMs() = (deadline - System.nanoTime()) / 1_000_000
        var acquired = false
        var out: File? = null
        var err: File? = null
        var process: Process? = null
        try {
            while (!acquired) {
                if (isCancelled()) return LeakPassResult.Cancelled
                val left = leftMs()
                if (left <= 0) return LeakPassResult.Busy
                acquired = gate.tryAcquire(minOf(left, POLL_MS), TimeUnit.MILLISECONDS)
            }
            out = File.createTempFile("memscope-leaks", ".json", tmpDir)
            err = File.createTempFile("memscope-leaks", ".err", tmpDir)
            process = launcher.start(hprof, out, err)
            while (true) {
                if (isCancelled()) {
                    process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
                    return LeakPassResult.Cancelled
                }
                val left = leftMs()
                if (left <= 0) {
                    process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
                    return LeakPassResult.TimedOut
                }
                if (process.waitFor(minOf(left, POLL_MS), TimeUnit.MILLISECONDS)) break
            }
            val code = process.exitValue()
            if (code == CHILD_OOM_EXIT || err.readText().contains("OutOfMemoryError")) return LeakPassResult.OutOfMemory
            if (code != 0) return LeakPassResult.Failed("exit code $code: " + err.readText().lines().firstOrNull { it.isNotBlank() }.orEmpty().take(200))
            return LeakPassResult.Done(Json { ignoreUnknownKeys = true }.decodeFromString(LeakResultDto.serializer(), out.readText()).traces)
        } catch (e: Exception) {
            return LeakPassResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            process?.takeIf { it.isAlive }?.destroyForcibly()
            out?.delete(); err?.delete()
            if (acquired) gate.release()
        }
    }

    private const val POLL_MS = 100L
}
