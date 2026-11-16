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

    fun run(hprof: File, timeoutMs: Long, launcher: LeakChildLauncher = realLauncher): LeakPassResult {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        if (!gate.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) return LeakPassResult.TimedOut
        val out = File.createTempFile("memscope-leaks", ".json")
        val err = File.createTempFile("memscope-leaks", ".err")
        var process: Process? = null
        try {
            process = launcher.start(hprof, out, err)
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0 || !process.waitFor(left, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
                return LeakPassResult.TimedOut
            }
            val code = process.exitValue()
            if (code == CHILD_OOM_EXIT || err.readText().contains("OutOfMemoryError")) return LeakPassResult.OutOfMemory
            if (code != 0) return LeakPassResult.Failed("exit code $code: " + err.readText().lines().firstOrNull { it.isNotBlank() }.orEmpty().take(200))
            return LeakPassResult.Done(Json { ignoreUnknownKeys = true }.decodeFromString(LeakResultDto.serializer(), out.readText()).traces)
        } catch (e: Exception) {
            return LeakPassResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            process?.takeIf { it.isAlive }?.destroyForcibly()
            out.delete(); err.delete()
            gate.release()
        }
    }
}
