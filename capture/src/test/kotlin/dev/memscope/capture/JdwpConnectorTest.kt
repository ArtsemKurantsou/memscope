package dev.memscope.capture

import dev.memscope.device.CancelToken
import dev.memscope.device.DeviceException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.seconds

class JdwpConnectorTest {
    private val device = FakeStartupDevice()
    private val connector = JdwpConnector(device.adb)

    @Test
    fun `parses complete length-prefixed messages and ignores a partial tail`() {
        val bytes = "0004101\n0009101\n4242\n0010123".toByteArray()
        assertEquals(listOf(setOf(101), setOf(101, 4242)), parseTrackJdwp(bytes))
        assertEquals(listOf(emptySet<Int>()), parseTrackJdwp("0000".toByteArray()))
        assertEquals(emptyList<Set<Int>>(), parseTrackJdwp(ByteArray(0)))
    }

    @Test
    fun `finds the launched process after the name race, ignoring other new pids`() {
        connector.track(device.serial, 2.seconds, CancelToken.NONE).use { tracker ->
            device.launched = true
            assertEquals(4242, tracker.awaitProcess("dev.app", 2.seconds, CancelToken.NONE))
        }
        assertEquals(3, device.shellCommands().count { it == "cat /proc/4242/cmdline" })
    }

    @Test
    fun `no matching process times out with a hint`() {
        connector.track(device.serial, 2.seconds, CancelToken.NONE).use { tracker ->
            device.launched = true
            val e = assertThrows<DeviceException> { tracker.awaitProcess("dev.app:nope", 1.seconds, CancelToken.NONE) }
            assertEquals(DeviceException.Kind.TIMEOUT, e.kind)
            assertTrue("dev.app:nope" in e.message!!)
        }
    }

    @Test
    fun `cancel stops the wait`() {
        val cancel = CancelToken()
        connector.track(device.serial, 2.seconds, cancel).use { tracker ->
            cancel.cancel()
            val e = assertThrows<DeviceException> { tracker.awaitProcess("dev.app", 10.seconds, cancel) }
            assertEquals(DeviceException.Kind.CANCELLED, e.kind)
        }
    }

    @Test
    fun `close stops the track-jdwp process`() {
        val tracker = connector.track(device.serial, 2.seconds, CancelToken.NONE)
        tracker.close()
        // the fake's track loop exits on cancel; a second close is harmless
        tracker.close()
        assertEquals(1, device.allCommands().count { it == "track-jdwp" })
    }
}
