package dev.memscope.analysis.findings.rules

import dev.memscope.analysis.Dominators
import dev.memscope.analysis.findings.Finding
import dev.memscope.analysis.findings.LeakPass
import dev.memscope.analysis.findings.LeakPassResult
import dev.memscope.analysis.findings.Rule
import dev.memscope.analysis.findings.RuleContext
import dev.memscope.analysis.findings.Severity
import dev.memscope.analysis.findings.formatBytes
import dev.memscope.analysis.findings.simpleName
import shark.LeakTrace
import shark.LeakTraceReference

/** Shark's Android leak inspectors, mapped back to tree refs by object id (class chain matched on the BFS path). */
internal class LeakedComponentRule : Rule {
    override val id = "leaked-component"

    override fun evaluate(c: RuleContext): List<Finding> {
        val s = c.snapshot
        val idx = s.index
        when (val r = LeakPass.run(c.hprof, c.leakTimeoutMs)) {
            LeakPassResult.TimedOut -> return listOf(skipped("leak analysis skipped: took too long (more than ${c.leakTimeoutMs / 1000} s)"))
            is LeakPassResult.Failed -> return listOf(skipped("leak analysis skipped: ${r.message}"))
            is LeakPassResult.Done -> {
                val candidates = r.leakingIds.map { idx.indexOfObjectId(it) }.filter { it >= 0 }
                    .filter { idx.idom[it] != Dominators.UNREACHABLE }.toMutableList()
                val out = ArrayList<Finding>()
                for (trace in r.traces) {
                    val className = trace.leakingObject.className
                    val chain = trace.referencePath.map { it.originObject.className } + className
                    val pick = candidates.filter { s.displayName(it) == className }
                        .maxByOrNull { chainMatch(c, it, chain) } ?: continue
                    candidates.remove(pick)
                    out += finding(c, pick, trace)
                }
                return out
            }
        }
    }

    private fun skipped(message: String) = Finding(
        ruleId = id, severity = Severity.LOW, title = message, evidence = message,
        suggestion = "Run the leak check on a smaller heap dump, or look for destroyed Activities in the Classes tab (filter Activity) and follow their path from the root.",
        bytes = 0, refs = emptyList(),
    )

    /** Number of trailing class names of [chain] that equal the class names on the BFS path to [d]. */
    private fun chainMatch(c: RuleContext, d: Int, chain: List<String>): Int {
        val idx = c.snapshot.index
        var x = d
        var matched = 0
        var k = chain.size - 1
        while (k >= 0 && x != idx.superRoot) {
            if (c.snapshot.displayName(x) != chain[k]) break
            matched++; k--
            x = idx.bfsParent[x]
            if (x == Dominators.UNREACHABLE) break
        }
        return matched
    }

    private fun finding(c: RuleContext, d: Int, trace: LeakTrace): Finding {
        val leaking = trace.leakingObject
        val suspect: LeakTraceReference? = trace.suspectReferenceSubpath.firstOrNull() ?: trace.referencePath.lastOrNull()
        val holder = suspect?.let { "${simpleName(it.owningClassName)}.${it.referenceDisplayName}" } ?: "its GC root"
        val lines = ArrayList<String>()
        lines += "GC root: ${trace.gcRootType.description}"
        trace.referencePath.forEachIndexed { i, ref ->
            val next = if (i + 1 < trace.referencePath.size) trace.referencePath[i + 1].originObject.className else leaking.className
            val mark = if (trace.referencePathElementIsSuspect(i)) "   <-- suspect reference" else ""
            lines += "${ref.originObject.className}.${ref.referenceDisplayName} -> $next$mark"
        }
        lines += "${leaking.className}: ${leaking.leakingStatusReason}"
        val bytes = c.snapshot.appRetained[d]
        return Finding(
            ruleId = id, severity = Severity.HIGH,
            title = "Leaked ${simpleName(leaking.className)}: destroyed but still referenced (retains ${formatBytes(bytes)})",
            evidence = lines.joinToString("\n"),
            suggestion = "`$holder` still holds the destroyed ${simpleName(leaking.className)}. Null the static or use a WeakReference to it, " +
                "and unregister listeners, callbacks and receivers that reference it in onDestroy.",
            bytes = bytes, refs = listOf("obj:$d"),
        )
    }
}
