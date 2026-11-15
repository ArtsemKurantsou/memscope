package dev.memscope.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.seconds

/** Needs emulator-5554 (API 37). Run with `./gradlew emulatorTest`. The emulator is shared: only touch what we create. */
@Tag("emulator")
class AdbEmulatorTest {
    private val serial = "emulator-5554"
    private val adb = Adb(AdbLocator.find())

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
        val pid = adb.jdwpPids(serial).firstOrNull()
        Assumptions.assumeTrue(pid != null, "no debuggable process is running on the emulator to forward to")
        pid!!
        val port = adb.forwardJdwp(serial, pid)
        try {
            assertTrue("tcp:$port jdwp:$pid" in forwardList(), "forward not listed")
        } finally {
            adb.removeForward(serial, port)
        }
        assertFalse("tcp:$port " in forwardList(), "tcp:$port still forwarded")
    }

    @Test
    fun `system packages and unknown packages are refused`() {
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
