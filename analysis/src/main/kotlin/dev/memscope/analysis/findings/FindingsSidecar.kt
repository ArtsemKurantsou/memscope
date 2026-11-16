package dev.memscope.analysis.findings

import kotlinx.serialization.Serializable
import java.io.File

@Serializable
private class PersistedFindings(val key: SidecarKey, val screenWidth: Int, val screenHeight: Int, val findings: List<Finding>)

/**
 * Computed findings kept next to the hprof (`<snapshot>.findings.json`) so a server restart does not redo them.
 * Same staleness key as the summary, plus the screen size the oversized-bitmap rule used. Only complete results
 * are stored: a result whose leak pass was skipped or failed is not final ([isComplete]).
 */
object FindingsSidecar {
    fun file(hprof: File): File = File(hprof.path + ".findings.json")

    /** False when the leak pass did not run (its LOW "skipped/failed" finding is present): worth running again. */
    fun isComplete(findings: List<Finding>): Boolean = findings.none { it.ruleId == "leaked-component" && it.severity == Severity.LOW }

    fun read(hprof: File, prefixes: List<String>, screen: ScreenSize): List<Finding>? =
        Sidecars.read(file(hprof), { Sidecars.json.decodeFromString(PersistedFindings.serializer(), it) }, { it.key }, SidecarKey.of(hprof, prefixes))
            ?.takeIf { it.screenWidth == screen.width && it.screenHeight == screen.height }?.findings

    fun write(hprof: File, prefixes: List<String>, screen: ScreenSize, findings: List<Finding>) {
        if (!isComplete(findings)) return
        Sidecars.write(file(hprof), Sidecars.json.encodeToString(PersistedFindings.serializer(), PersistedFindings(SidecarKey.of(hprof, prefixes), screen.width, screen.height, findings)))
    }

    fun delete(hprof: File) { file(hprof).delete() }
}
