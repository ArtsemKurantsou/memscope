package dev.memscope.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RealProcessRunnerTest {
    @TempDir lateinit var tmp: Path
    private val runner = RealProcessRunner()

    /** Starts `sleep` through a shell that records its own pid; returns the args and the pid file. */
    private fun sleeper(): Pair<List<String>, Path> {
        val pidFile = tmp.resolve("pid file")
        return listOf("/bin/sh", "-c", "echo \$\$ > \"\$1\"; exec sleep 5", "sh", pidFile.toString()) to pidFile
    }

    private fun assertDead(pidFile: Path) {
        val pid = Files.readString(pidFile).trim().toLong()
        val deadline = System.nanoTime() + 2_000_000_000L
        while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false) && System.nanoTime() < deadline) Thread.sleep(20)
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "child $pid still alive")
    }

    @Test
    fun `timeout throws within 2s and kills the child`() {
        val (args, pidFile) = sleeper()
        val start = System.nanoTime()
        val e = assertThrows<DeviceException> { runner.run(args, 200.milliseconds) }
        assertEquals(DeviceException.Kind.TIMEOUT, e.kind)
        assertTrue((System.nanoTime() - start) < 2_000_000_000L)
        assertDead(pidFile)
    }

    @Test
    fun `cancel token kills a running child`() {
        val (args, pidFile) = sleeper()
        val token = CancelToken()
        Thread { Thread.sleep(300); token.cancel() }.start()
        val start = System.nanoTime()
        val e = assertThrows<DeviceException> { runner.run(args, 30.seconds, cancel = token) }
        assertEquals(DeviceException.Kind.CANCELLED, e.kind)
        assertTrue((System.nanoTime() - start) < 2_500_000_000L)
        assertDead(pidFile)
    }

    @Test
    fun `an already cancelled token does not start anything`() {
        val token = CancelToken().also { it.cancel() }
        val e = assertThrows<DeviceException> { runner.run(listOf("/bin/echo", "hi"), 5.seconds, cancel = token) }
        assertEquals(DeviceException.Kind.CANCELLED, e.kind)
    }

    @Test
    fun `stdoutTo writes bytes and reports progress`() {
        val dest = tmp.resolve("out dir/out.bin")
        Files.createDirectories(dest.parent)
        val seen = mutableListOf<Long>()
        val r = runner.run(
            listOf("/bin/sh", "-c", "head -c 300000 /dev/zero"), 10.seconds, stdoutTo = dest, onBytes = { seen += it },
        )
        assertEquals(0, r.exitCode)
        assertEquals("", r.stdout)
        assertEquals(300000L, Files.size(dest))
        assertEquals(300000L, seen.last())
        assertEquals(seen.sorted(), seen, "progress is cumulative")
    }

    @Test
    fun `captures stdout stderr and exit code`() {
        val r = runner.run(listOf("/bin/sh", "-c", "echo out; echo err >&2; exit 3"), 5.seconds)
        assertEquals(ProcessResult(3, "out\n", "err\n"), r)
    }

    @Test
    fun `missing executable is COMMAND_FAILED`() {
        val e = assertThrows<DeviceException> { runner.run(listOf("/no/such/binary"), 5.seconds) }
        assertEquals(DeviceException.Kind.COMMAND_FAILED, e.kind)
    }

    @Test
    fun `large stderr does not deadlock`() {
        val r = runner.run(listOf("/bin/sh", "-c", "head -c 500000 /dev/zero | tr '\\0' x >&2"), 10.seconds)
        assertTrue(r.stderr.isNotEmpty())
    }
}
