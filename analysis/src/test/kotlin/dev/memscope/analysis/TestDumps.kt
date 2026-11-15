package dev.memscope.analysis

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Heap dumps of the fixture app, generated on demand by `analysis/capture-test-dumps.sh` into
 * `analysis/build/test-dumps/` (too large to commit). Tests are skipped when the emulator is missing.
 */
object TestDumps {
    private val moduleDir = File(System.getProperty("user.dir")) // Gradle runs tests in the module dir
    private val dumpDir = File(moduleDir, "build/test-dumps")

    /** The standard fixture dump, captured if missing; skips the calling test if that is impossible. */
    @Synchronized
    fun fixtureBasic(): File {
        val name = "fixture-basic" // what the script captures when run without arguments
        val file = File(dumpDir, "$name.hprof")
        if (file.isFile && file.length() > 0) return file
        val script = File(moduleDir, "capture-test-dumps.sh")
        val log = File(dumpDir, "$name.capture.log").also { it.parentFile.mkdirs() }
        val process = ProcessBuilder(listOf(script.absolutePath))
            .redirectErrorStream(true).redirectOutput(log).start()
        val finished = process.waitFor(5, TimeUnit.MINUTES)
        if (!finished) process.destroyForcibly()
        val ok = finished && process.exitValue() == 0 && file.isFile
        assumeTrue(ok) {
            "Skipped: $name.hprof is not in ${dumpDir.path} and could not be captured " +
                "(needs emulator-5554 with dev.memscope.fixture installed). Capture output: ${log.readText().trim()}"
        }
        return file
    }
}
