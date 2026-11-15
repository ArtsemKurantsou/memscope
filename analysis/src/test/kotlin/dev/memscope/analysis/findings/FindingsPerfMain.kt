package dev.memscope.analysis.findings

import dev.memscope.analysis.Snapshot
import java.io.File
import java.lang.management.ManagementFactory

/** `./gradlew :analysis:perfFindings -Phprof=<dump>`: times the findings of a dump rule by rule and prints the peak heap. */
fun main(args: Array<String>) {
    val file = File(args[0])
    val prefixes = listOf(args.getOrElse(1) { "dev.memscope.fixture" })
    val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == java.lang.management.MemoryType.HEAP }
    fun peakMb() = pools.sumOf { it.peakUsage.used } / (1024 * 1024)
    var t = System.nanoTime()
    fun lap(what: String) { val n = System.nanoTime(); println("%-22s %7.1f s   peak heap so far %d MB".format(what, (n - t) / 1e9, peakMb())); t = n }
    val s = Snapshot.open(file, prefixes)
    lap("open (index)")
    val ctx = RuleContext(s)
    s.summary(); s.classes(); lap("summary + classes")
    val rules = Findings.snapshotRuleIds
    val out = Findings.forSnapshot(s, progress = { id -> lap("(before $id)") })
    lap("last rule")
    println("findings: ${out.size}")
    out.forEach { println("  [${it.severity}] ${it.ruleId}: ${it.title}") }
    println("rules: $rules; hprof ${file.length() / (1024 * 1024)} MB; peak heap ${peakMb()} MB")
    SnapshotSummaries.compute(s); lap("summary sidecar")
    s.close()
}
