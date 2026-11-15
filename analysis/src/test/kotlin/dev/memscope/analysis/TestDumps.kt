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
    fun fixtureBasic(): File = capture(listOf("fixture-basic"), emptyList()).single()

    /**
     * Two dumps of one fixture process: `before` straight after a plain launch, `after` once `grow` x5
     * and `static_cache` have run (Growth.list +5 MB, StaticCache +20 MB). Captured if missing; skips the
     * calling test if that is impossible.
     */
    @Synchronized
    fun fixtureBeforeAfter(): Pair<File, File> = capture(listOf("fixture-before", "fixture-after"), listOf("diff")).let { it[0] to it[1] }

    /** A plain launch with no planted actions (the negative test of the findings rules). */
    @Synchronized
    fun fixturePlain(): File = capture(listOf("fixture-plain"), listOf("fixture-plain", "")).single()

    /** Three dumps of one process after 1, 3 and 5 `grow` broadcasts (Growth.list holds 1, 3, 5 MB). */
    @Synchronized
    fun fixtureGrow(): List<File> = capture(listOf("fixture-grow-1", "fixture-grow-2", "fixture-grow-3"), listOf("grow"))

    private fun capture(names: List<String>, scriptArgs: List<String>): List<File> {
        val files = names.map { File(dumpDir, "$it.hprof") }
        if (files.all { it.isFile && it.length() > 0 }) return files
        val script = File(moduleDir, "capture-test-dumps.sh")
        val log = File(dumpDir, "${names.first()}.capture.log").also { it.parentFile.mkdirs() }
        val process = ProcessBuilder(listOf(script.absolutePath) + scriptArgs)
            .redirectErrorStream(true).redirectOutput(log).start()
        val finished = process.waitFor(5, TimeUnit.MINUTES)
        if (!finished) process.destroyForcibly()
        val ok = finished && process.exitValue() == 0 && files.all { it.isFile }
        assumeTrue(ok) {
            "Skipped: ${names.joinToString()} not in ${dumpDir.path} and could not be captured " +
                "(needs emulator-5554 with dev.memscope.fixture installed). Capture output: ${log.readText().trim()}"
        }
        return files
    }
}
