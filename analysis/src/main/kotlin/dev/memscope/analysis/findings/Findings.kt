package dev.memscope.analysis.findings

import dev.memscope.analysis.HprofUnreadableException
import dev.memscope.analysis.Snapshot
import dev.memscope.analysis.findings.rules.DominantClassRule
import dev.memscope.analysis.findings.rules.DuplicateArraysRule
import dev.memscope.analysis.findings.rules.DuplicateBitmapsRule
import dev.memscope.analysis.findings.rules.DuplicateStringsRule
import dev.memscope.analysis.findings.rules.LargeStaticRule
import dev.memscope.analysis.findings.rules.LeakedComponentRule
import dev.memscope.analysis.findings.rules.MonotonicGrowthRule
import dev.memscope.analysis.findings.rules.OversizedBitmapRule
import dev.memscope.analysis.findings.rules.SparseCollectionRule
import dev.memscope.analysis.findings.rules.StartupIntervalRule
import java.io.File

/** Runs the rules. Output is sorted by severity, then bytes (largest first). */
object Findings {
    private val snapshotRules: List<Rule> = listOf(
        LeakedComponentRule(), OversizedBitmapRule(), DuplicateBitmapsRule(), DuplicateStringsRule(),
        DuplicateArraysRule(), SparseCollectionRule(), LargeStaticRule(), DominantClassRule(),
    )
    private val sessionRules: List<SessionRule> = listOf(MonotonicGrowthRule(), StartupIntervalRule())

    val snapshotRuleIds: List<String> get() = snapshotRules.map { it.id }

    /**
     * Findings of one opened snapshot. [progress] is called with each rule id before it runs. A rule that
     * fails produces a LOW finding naming it, except an unreadable hprof, which is rethrown. [isCancelled] is checked
     * between rules (and by the leak pass); a cancel throws [FindingsCancelledException].
     */
    fun forSnapshot(
        s: Snapshot, hprof: File = s.hprofFile, screen: ScreenSize = ScreenSize.FALLBACK,
        leakTimeoutMs: Long = Thresholds.LEAK_ANALYSIS_TIMEOUT_MS, progress: (String) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): List<Finding> {
        val ctx = RuleContext(s, hprof, screen, leakTimeoutMs, isCancelled)
        return sorted(snapshotRules.flatMap { rule ->
            if (isCancelled()) throw FindingsCancelledException()
            progress(rule.id)
            try {
                rule.evaluate(ctx)
            } catch (e: HprofUnreadableException) {
                throw e
            } catch (e: FindingsCancelledException) {
                throw e
            } catch (e: OutOfMemoryError) {
                listOf(
                    Finding(
                        rule.id, Severity.LOW, "Rule ${rule.id} ran out of memory",
                        "The JVM ran out of heap while this rule worked on a large dump; the other findings are unaffected.",
                        "Restart MemScope with a larger heap (java -Xmx) and open the Findings tab again.", 0, emptyList(),
                    ),
                )
            } catch (e: Exception) {
                listOf(
                    Finding(
                        rule.id, Severity.LOW, "Rule ${rule.id} failed: ${e.message ?: e.javaClass.simpleName}",
                        "${e.javaClass.name}: ${e.message}", "This is a bug in MemScope's rule; the other findings are unaffected.", 0, emptyList(),
                    ),
                )
            }
        })
    }

    /** Findings across the OK snapshots of a session ([snapshots] in time order, from their summaries). */
    fun forSession(snapshots: List<SummarizedSnapshot>, startup: Boolean): List<Finding> =
        sorted(sessionRules.flatMap { it.evaluate(snapshots, startup) })

    private fun sorted(list: List<Finding>) = list.sortedWith(compareBy<Finding> { it.severity.ordinal }.thenByDescending { it.bytes })
}
