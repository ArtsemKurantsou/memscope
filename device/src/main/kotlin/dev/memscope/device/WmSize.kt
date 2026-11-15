package dev.memscope.device

/** Screen size in pixels. */
data class ScreenPx(val width: Int, val height: Int)

/**
 * Parses `adb shell wm size`: "Physical size: 1080x2400", optionally followed by "Override size: 720x1600"
 * (the size the user set, which is what the app renders at, so it wins). Null if neither line parses.
 */
fun parseWmSize(output: String): ScreenPx? {
    fun find(prefix: String): ScreenPx? = output.lineSequence().map { it.trim() }.firstOrNull { it.startsWith(prefix, ignoreCase = true) }
        ?.substringAfter(':')?.trim()?.let { Regex("""^(\d+)x(\d+)""").find(it) }
        ?.let { m -> ScreenPx(m.groupValues[1].toInt(), m.groupValues[2].toInt()).takeIf { it.width > 0 && it.height > 0 } }
    return find("Override size") ?: find("Physical size")
}
