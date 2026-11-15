package dev.memscope.capture

import kotlinx.serialization.Serializable

/**
 * One reading of the process's memory. [tMs] is milliseconds since the session was created. All sizes are PSS in kB.
 * [otherKb] is "Private Other" plus "System" of the App Summary; [paused] marks a sample taken while the app was held
 * at a breakpoint (only [totalPssKb] is fresh then, the rest is carried forward).
 */
@Serializable
data class MemSample(
    val tMs: Long,
    val javaHeapKb: Int,
    val nativeHeapKb: Int,
    val graphicsKb: Int,
    val codeKb: Int,
    val stackKb: Int,
    val otherKb: Int,
    val totalPssKb: Int,
    val paused: Boolean = false,
)

/** A vertical marker on the timeline: [kind] is "milestone" or "dump"; [tMs] is relative to the session start. */
@Serializable
data class TimelineMarker(val tMs: Long, val kind: String, val label: String, val snapshotId: String?)

/** An interval during which the app was held by the debugger; [endMs] is null while it still is. */
@Serializable
data class HeldInterval(val startMs: Long, val endMs: Long? = null)

@Serializable
data class Timeline(val samples: List<MemSample>, val markers: List<TimelineMarker>, val held: List<HeldInterval> = emptyList())

object MeminfoParser {
    private val NUMBER = Regex("-?\\d+")

    /** True for the output `dumpsys meminfo` gives while the app is suspended (exit code 0, no data). */
    fun isTimeoutText(text: String): Boolean =
        text.contains("Got IoException") || (text.contains("Timeout") && !text.contains("App Summary"))

    /**
     * Reads the "App Summary" block of `dumpsys meminfo <process>`; null if there is none (process absent, timeout text,
     * unknown layout). Each line is `Label:` followed by columns; the first number is the PSS in kB. Lines that are
     * missing count as 0, extra columns and lines are ignored, so older and newer Android layouts both work.
     */
    fun parse(text: String, tMs: Long): MemSample? {
        if (isTimeoutText(text)) return null
        val lines = text.lineSequence().toList()
        val start = lines.indexOfFirst { it.trim().startsWith("App Summary") }
        if (start < 0) return null
        val values = HashMap<String, Int>()
        for (line in lines.drop(start + 1)) {
            val t = line.trim()
            if (t.isEmpty() || t.all { it == '-' || it.isWhitespace() } || t.contains("(KB)", ignoreCase = true)) continue
            val colon = t.indexOf(':')
            if (colon < 0) break // the next section ("Objects", "SQL", ...)
            val label = t.substring(0, colon).trim().lowercase()
            val value = NUMBER.find(t, colon + 1)?.value?.toIntOrNull() ?: continue
            values.putIfAbsent(label, value)
        }
        if (values.isEmpty()) return null
        fun v(vararg labels: String) = labels.firstNotNullOfOrNull { values[it] } ?: 0
        val java = v("java heap")
        val native = v("native heap")
        val graphics = v("graphics")
        val code = v("code")
        val stack = v("stack")
        val other = v("private other") + v("system")
        val total = v("total pss", "total").takeIf { values.containsKey("total pss") || values.containsKey("total") }
            ?: (java + native + graphics + code + stack + other)
        return MemSample(tMs, java, native, graphics, code, stack, other, total)
    }

    /** The `Pss:` line (kB) of `/proc/<pid>/smaps_rollup`, or null. */
    fun parseSmapsPss(text: String): Int? =
        text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("Pss:") }
            ?.let { NUMBER.find(it)?.value?.toIntOrNull() }
}
