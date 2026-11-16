package dev.memscope.analysis.findings.rules

import dev.memscope.analysis.Dominators
import dev.memscope.analysis.findings.Finding
import dev.memscope.analysis.findings.LeakChildLauncher
import dev.memscope.analysis.findings.LeakPass
import dev.memscope.analysis.findings.LeakPassResult
import dev.memscope.analysis.findings.Rule
import dev.memscope.analysis.findings.RuleContext
import dev.memscope.analysis.findings.Severity
import dev.memscope.analysis.findings.formatBytes
import dev.memscope.analysis.findings.simpleName

/** Shark's Android leak inspectors (run in a child JVM), mapped back to tree refs by the leaking object's hprof id. */
internal class LeakedComponentRule(private val launcher: LeakChildLauncher = LeakPass.realLauncher) : Rule {
    override val id = "leaked-component"

    override fun evaluate(c: RuleContext): List<Finding> {
        val s = c.snapshot
        val idx = s.index
        return when (val r = LeakPass.run(c.hprof, c.leakTimeoutMs, launcher)) {
            LeakPassResult.TimedOut -> listOf(skipped("leak analysis skipped: took longer than ${c.leakTimeoutMs / 1000} s"))
            LeakPassResult.OutOfMemory -> listOf(skipped("leak analysis failed: out of memory — raise MEMSCOPE_LEAK_XMX (now ${LeakPass.xmx()})"))
            is LeakPassResult.Failed -> listOf(skipped("leak analysis failed: ${r.message}"))
            is LeakPassResult.Done -> r.traces.mapNotNull { t ->
                val d = idx.indexOfObjectId(t.objectId)
                if (d < 0 || idx.idom[d] == Dominators.UNREACHABLE) return@mapNotNull null
                val bytes = s.appRetained[d]
                Finding(
                    ruleId = id, severity = Severity.HIGH,
                    title = "Leaked ${simpleName(t.className)}: destroyed but still referenced (retains ${formatBytes(bytes)})",
                    evidence = t.lines.joinToString("\n"),
                    suggestion = "`${t.holder}` still holds the destroyed ${simpleName(t.className)}. Null the static or use a WeakReference to it, " +
                        "and unregister listeners, callbacks and receivers that reference it in onDestroy.",
                    bytes = bytes, refs = listOf("obj:$d"),
                )
            }.distinctBy { it.refs }
        }
    }

    /** A LOW finding that the leak pass did not run; the UI offers to run it again (ruleId + severity LOW identify it). */
    private fun skipped(message: String) = Finding(
        ruleId = id, severity = Severity.LOW, title = message, evidence = message,
        suggestion = "Run the leak analysis again (it is the slowest check), or look for destroyed Activities in the Classes tab (filter Activity) and follow their path from the root.",
        bytes = 0, refs = emptyList(),
    )
}
