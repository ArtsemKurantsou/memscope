package dev.memscope.capture

import dev.memscope.device.DeviceException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DebugAppGuardTest {
    @TempDir lateinit var tmp: Path
    private val device = FakeStartupDevice().apply { nameRace = false }
    private val marker get() = tmp.resolve("pending-debug-app.json")

    @Test
    fun `arm writes the marker before set-debug-app, disarm clears and removes it`() {
        val guard = DebugAppGuard(device.adb, tmp)
        var markerAtSetDebugApp = false
        device.onShell["am set-debug-app"] = { markerAtSetDebugApp = Files.exists(marker) }
        guard.arm(device.serial, "dev.app", "dev.app:remote")
        assertTrue(markerAtSetDebugApp)
        assertEquals(listOf("am set-debug-app -w dev.app:remote"), device.shellCommands())
        assertEquals(listOf(PendingDebugApp(device.serial, "dev.app", "dev.app:remote")), guard.pending())

        guard.update(device.serial) { it.copy(pid = 7, forwardPort = 40001) }
        assertEquals(7, DebugAppGuard(device.adb, tmp).pending().single().pid, "the marker is on disk")

        guard.disarm(device.serial)
        assertEquals("am clear-debug-app", device.shellCommands().last())
        assertTrue(guard.pending().isEmpty())
        assertFalse(Files.exists(marker))
    }

    @Test
    fun `failed set-debug-app keeps the marker so the attempt is cleaned up`() {
        val guard = DebugAppGuard(device.adb, tmp)
        device.failShell = "am set-debug-app"
        assertThrows<DeviceException> { guard.arm(device.serial, "dev.app") }
        assertEquals(1, guard.pending().size)
    }

    @Test
    fun `disarm that cannot reach the device keeps the marker`() {
        val guard = DebugAppGuard(device.adb, tmp)
        guard.arm(device.serial, "dev.app")
        device.disconnected = true
        assertThrows<DeviceException> { guard.disarm(device.serial) }
        assertEquals(1, guard.pending().size)
    }

    @Test
    fun `recoverStale disarms a connected device, removes the forward and stops a frozen app`() {
        DebugAppGuard(device.adb, tmp).apply {
            arm(device.serial, "dev.app")
            update(device.serial) { it.copy(pid = 4242, forwardPort = 40001) }
        }
        device.calls.clear()
        val log = DebugAppGuard(device.adb, tmp).recoverStale()
        val cmds = device.allCommands()
        assertTrue("shell am clear-debug-app" in cmds, cmds.toString())
        assertTrue("forward --remove tcp:40001" in cmds, cmds.toString())
        assertTrue("shell am force-stop dev.app" in cmds, "pid alive and never released: $cmds")
        assertFalse(Files.exists(marker))
        assertTrue(log.any { "cleared" in it }, log.toString())
    }

    @Test
    fun `recoverStale leaves a released or dead app running`() {
        val guard = DebugAppGuard(device.adb, tmp)
        guard.arm(device.serial, "dev.app")
        guard.update(device.serial) { it.copy(pid = 4242, released = true) }
        device.calls.clear()
        guard.recoverStale()
        assertFalse(device.allCommands().any { it.startsWith("shell am force-stop") })

        guard.arm(device.serial, "dev.app")
        guard.update(device.serial) { it.copy(pid = 4242) }
        device.pidAlive = false
        device.calls.clear()
        guard.recoverStale()
        assertFalse(device.allCommands().any { it.startsWith("shell am force-stop") })
        assertTrue(guard.pending().isEmpty())
    }

    @Test
    fun `recoverStale keeps the marker of a device that is not connected`() {
        val guard = DebugAppGuard(device.adb, tmp)
        guard.arm(device.serial, "dev.app")
        device.listedInDevices = false
        device.calls.clear()
        val log = guard.recoverStale()
        assertEquals(1, guard.pending().size)
        assertFalse(device.allCommands().any { it.startsWith("shell am") })
        assertTrue(log.any { "not connected" in it }, log.toString())
    }

    @Test
    fun `no marker means nothing to do and a corrupt marker is reported, not fatal`() {
        val guard = DebugAppGuard(device.adb, tmp)
        assertTrue(guard.recoverStale().isEmpty())
        assertTrue(device.calls.isEmpty())
        Files.writeString(marker, "{not json")
        val log = guard.recoverStale()
        assertTrue(log.any { "unreadable" in it }, log.toString())
    }
}
