package dev.memscope.device

import java.nio.file.Files
import java.nio.file.Path

val userHome: Path get() = Path.of(System.getProperty("user.home"))

object AdbLocator {
    /**
     * Search order: `<home>/Library/Android/sdk`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, then `adb` on `PATH`.
     */
    fun find(env: Map<String, String> = System.getenv(), home: Path = userHome): Path {
        val candidates = buildList {
            add(home.resolve("Library/Android/sdk/platform-tools/adb"))
            listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").forEach { key ->
                env[key]?.takeIf { it.isNotBlank() }?.let { add(Path.of(it).resolve("platform-tools/adb")) }
            }
            env["PATH"].orEmpty().split(':').filter { it.isNotBlank() }.forEach { add(Path.of(it).resolve("adb")) }
        }
        return candidates.firstOrNull { Files.isRegularFile(it) }
            ?: throw DeviceException(
                DeviceException.Kind.ADB_NOT_FOUND,
                "adb was not found (looked in ${candidates.size} locations).",
                "Install the Android SDK platform-tools, then set ANDROID_HOME to the SDK folder " +
                    "or add platform-tools to your PATH. Default location: ~/Library/Android/sdk/platform-tools.",
            )
    }
}
