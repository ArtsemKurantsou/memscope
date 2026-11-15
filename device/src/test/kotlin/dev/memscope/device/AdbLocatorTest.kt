package dev.memscope.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AdbLocatorTest {
    @TempDir lateinit var tmp: Path

    private fun fakeAdb(dir: Path): Path {
        val adb = dir.resolve("platform-tools/adb")
        Files.createDirectories(adb.parent)
        Files.writeString(adb, "#!/bin/sh\n")
        return adb
    }

    @Test
    fun `finds adb in the default macOS sdk location`() {
        val home = tmp.resolve("home with space")
        val adb = fakeAdb(home.resolve("Library/Android/sdk"))
        assertEquals(adb, AdbLocator.find(emptyMap(), home))
    }

    @Test
    fun `falls back to ANDROID_HOME`() {
        val sdk = tmp.resolve("sdk")
        val adb = fakeAdb(sdk)
        assertEquals(adb, AdbLocator.find(mapOf("ANDROID_HOME" to sdk.toString()), tmp.resolve("emptyhome")))
    }

    @Test
    fun `falls back to ANDROID_SDK_ROOT then PATH`() {
        val sdk = tmp.resolve("sdk2")
        val adb = fakeAdb(sdk)
        assertEquals(adb, AdbLocator.find(mapOf("ANDROID_SDK_ROOT" to sdk.toString()), tmp.resolve("h")))
        val onPath = AdbLocator.find(mapOf("PATH" to "/nonexistent:${adb.parent}"), tmp.resolve("h"))
        assertEquals(adb, onPath)
    }

    @Test
    fun `default location wins over ANDROID_HOME`() {
        val home = tmp.resolve("home")
        val preferred = fakeAdb(home.resolve("Library/Android/sdk"))
        val other = fakeAdb(tmp.resolve("other"))
        val found = AdbLocator.find(mapOf("ANDROID_HOME" to tmp.resolve("other").toString()), home)
        assertEquals(preferred, found)
        assertTrue(other.toFile().exists())
    }

    @Test
    fun `throws ADB_NOT_FOUND with a platform-tools hint`() {
        val e = assertThrows<DeviceException> { AdbLocator.find(emptyMap(), tmp.resolve("nothing")) }
        assertEquals(DeviceException.Kind.ADB_NOT_FOUND, e.kind)
        assertTrue("platform-tools" in e.hint, e.hint)
    }
}
