package dev.memscope.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.seconds

/** Needs emulator-5554 (API 37). Run with `./gradlew emulatorTest`. The emulator is shared: only touch what we create. */
@Tag("emulator")
class AdbEmulatorTest {
    private val serial = "emulator-5554"
    private val adb = Adb(AdbLocator.find())

    private companion object {
        const val FIXTURE_PACKAGE = "dev.memscope.fixture"
        const val FIXTURE_ACTIVITY = ".MainActivity"
    }

    private fun forwardList(): String =
        adb.runner.run(listOf(adb.adbPath.toString(), "forward", "--list"), 10.seconds).stdout

    @Test
    fun `devices contains the emulator with API 37`() {
        val emulator = adb.devices().single { it.serial == serial }
        assertEquals("device", emulator.state)
        assertEquals(37, emulator.apiLevel)
    }

    @Test
    fun `shell echo`() {
        assertEquals("hi", adb.shell(serial, listOf("echo", "hi")).stdout.trim())
    }

    @Test
    fun `forwardJdwp then removeForward removes exactly that forward`() {
        val pkg = FIXTURE_PACKAGE
        if (pkg !in adb.shell(serial, listOf("pm", "list", "packages", pkg)).stdout.lineSequence().map { it.trim().removePrefix("package:") }.toList()) {
            throw AssertionError("fixture app not installed: run fixture-app/build-and-install.sh")
        }
        adb.shell(serial, listOf("am", "start", "-n", "$pkg/$FIXTURE_ACTIVITY"))
        val pid = waitForJdwpPid(pkg)
        val port = adb.forwardJdwp(serial, pid)
        try {
            assertTrue("tcp:$port jdwp:$pid" in forwardList(), "forward not listed")
        } finally {
            adb.removeForward(serial, port)
        }
        assertFalse("tcp:$port " in forwardList(), "tcp:$port still forwarded")
    }

    private fun waitForJdwpPid(pkg: String): Int {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            val pids = adb.processes(serial, pkg).map { it.pid }.toSet()
            adb.jdwpPids(serial).firstOrNull { it in pids }?.let { return it }
            Thread.sleep(300)
        }
        throw AssertionError("no JDWP process for $pkg appeared within 15 s after am start")
    }

    @Test
    fun `unknown package is NOT_INSTALLED and settings has a launcher component`() {
        val e = assertThrows<DeviceException> { adb.requireDebuggable(serial, "no.such.package.memscope") }
        assertEquals(DeviceException.Kind.NOT_INSTALLED, e.kind)
        assertNotNull(adb.launcherComponent(serial, "com.android.settings"))
    }

    @Test
    fun `execOutToFile streams binary output and pull of a missing file leaves nothing behind`() {
        val dir = java.nio.file.Files.createTempDirectory("memscope-emu")
        try {
            val out = dir.resolve("out dir/data.bin")
            assertEquals(4096L, adb.execOutToFile(serial, listOf("head", "-c", "4096", "/dev/zero"), out, 15.seconds))
            val missing = dir.resolve("missing.bin")
            val e = assertThrows<DeviceException> { adb.pull(serial, "/data/local/tmp/does-not-exist", missing, 15.seconds) }
            assertEquals(DeviceException.Kind.COMMAND_FAILED, e.kind)
            assertFalse(java.nio.file.Files.exists(missing))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
