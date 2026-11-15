package dev.memscope.device

import java.nio.file.Path
import kotlin.time.Duration

/** Records every invocation and answers with whatever [handler] returns (or throws). */
class FakeProcessRunner(
    private val handler: (List<String>) -> ProcessResult = { ProcessResult(0, "", "") },
) : ProcessRunner {
    val calls = mutableListOf<List<String>>()

    override fun run(
        args: List<String>,
        timeout: Duration,
        stdoutTo: Path?,
        cancel: CancelToken,
        onBytes: (Long) -> Unit,
    ): ProcessResult {
        calls += args
        val result = handler(args)
        if (stdoutTo != null && result.stdout.isNotEmpty()) {
            java.nio.file.Files.writeString(stdoutTo, result.stdout)
            return result.copy(stdout = "")
        }
        return result
    }
}
