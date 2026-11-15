package dev.memscope.capture

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class DeviceArgsTest {
    @Test
    fun `package names`() {
        for (ok in listOf("dev.app", "dev.memscope.fixture", "com.Example_1.a2")) assertDoesNotThrow { DeviceArgs.requirePackage(ok) }
        for (bad in listOf("", "app", "a.b;reboot", "a.b reboot", "a.b\$(id)", "1a.b", "a..b", "a.b.", ".a.b", "a.b`id`", "a.b\nc"))
            assertThrows<IllegalArgumentException>("'$bad'") { DeviceArgs.requirePackage(bad) }
    }

    @Test
    fun `serials`() {
        for (ok in listOf("emulator-5554", "192.168.1.5:5555", "R58M12ABCDE", "adb-XYZ._adb-tls-connect._tcp"))
            assertDoesNotThrow { DeviceArgs.requireSerial(ok) }
        for (bad in listOf("", "a b", "x;y", "x\$y", "x/y", "a".repeat(200))) assertThrows<IllegalArgumentException>("'$bad'") { DeviceArgs.requireSerial(bad) }
    }

    @Test
    fun `process names`() {
        for (ok in listOf("dev.app", "dev.app:remote", "com.other.app")) assertDoesNotThrow { DeviceArgs.requireProcessName(ok) }
        for (bad in listOf("", "dev.app:re mote", "dev.app;x", "dev.app:x;y", ":remote")) assertThrows<IllegalArgumentException>("'$bad'") { DeviceArgs.requireProcessName(bad) }
    }
}
