package dev.memscope.analysis.findings.rules

import dev.memscope.analysis.RootKind
import dev.memscope.analysis.findings.Finding
import dev.memscope.analysis.findings.Rule
import dev.memscope.analysis.findings.RuleContext
import dev.memscope.analysis.findings.Severity
import dev.memscope.analysis.findings.Thresholds
import dev.memscope.analysis.findings.formatBytes
import dev.memscope.analysis.findings.simpleName
import java.util.Locale

private fun pct(bytes: Long, total: Long) = if (total <= 0) 0.0 else bytes * 100.0 / total

internal class LargeStaticRule : Rule {
    override val id = "large-static"

    private class Cand(val classObj: Int, val target: Int, val bytes: Long)

    override fun evaluate(c: RuleContext): List<Finding> {
        val s = c.snapshot
        val idx = s.index
        val appTotal = s.summary().appHeapBytes
        val app = s.appRetained
        val cands = ArrayList<Cand>()
        val roots = idx.graph.roots
        for (k in roots.indices) {
            if (idx.rootKinds[k].toInt() != RootKind.CLASS.ordinal) continue
            val d = roots[k]
            for (e in idx.domChildOffsets[d] until idx.domChildOffsets[d + 1]) {
                val t = idx.domChildren[e]
                val b = app[t]
                if (pct(b, appTotal) >= Thresholds.LARGE_STATIC_MIN_PERCENT || b >= Thresholds.LARGE_STATIC_MIN_BYTES) cands += Cand(d, t, b)
            }
        }
        return cands.sortedByDescending { it.bytes }.take(Thresholds.LARGE_STATIC_TOP).map { cand ->
            val className = s.displayName(cand.classObj)
            val field = s.edgeLabels(cand.classObj, intArrayOf(cand.target))[0] ?: "<unknown field>"
            val target = s.displayName(cand.target)
            val p = pct(cand.bytes, appTotal)
            val holder = "${simpleName(className)}.$field"
            Finding(
                ruleId = id, severity = if (p >= Thresholds.LARGE_STATIC_HIGH_PERCENT) Severity.HIGH else Severity.MEDIUM,
                title = "$holder retains ${formatBytes(cand.bytes)} (${String.format(Locale.US, "%.1f", p)}% of the app heap)",
                evidence = "Static field $className.$field -> $target retains ${formatBytes(cand.bytes)} of app-heap objects " +
                    "= ${String.format(Locale.US, "%.1f", p)}% of the ${formatBytes(appTotal)} app heap. Limit: ${Thresholds.LARGE_STATIC_MIN_PERCENT.toInt()}% of it or ${formatBytes(Thresholds.LARGE_STATIC_MIN_BYTES)}; " +
                    "HIGH from ${Thresholds.LARGE_STATIC_HIGH_PERCENT.toInt()}%.",
                suggestion = "`$holder` keeps ${formatBytes(cand.bytes)} alive for the whole process. Bound it (an LruCache with a byte limit), clear it in `onTrimMemory`, " +
                    "or null it when its screen goes away; if it only holds data for one screen, move it into that screen's ViewModel.",
                bytes = cand.bytes, refs = listOf("obj:${cand.target}"),
            )
        }
    }
}

internal class DominantClassRule : Rule {
    override val id = "dominant-class"

    override fun evaluate(c: RuleContext): List<Finding> {
        val s = c.snapshot
        val appTotal = s.summary().appHeapBytes
        return s.classes().filter { it.className !in Thresholds.DOMINANT_CLASS_EXCLUDED && pct(it.appRetainedBytes, appTotal) >= Thresholds.DOMINANT_CLASS_PERCENT }
            .map { cls ->
                val p = pct(cls.appRetainedBytes, appTotal)
                val top = s.instances(cls.className, 0, 1).rows.firstOrNull()?.ref
                Finding(
                    ruleId = id, severity = Severity.MEDIUM,
                    title = "${simpleName(cls.className)} instances retain ${String.format(Locale.US, "%.0f", p)}% of the app heap (${formatBytes(cls.appRetainedBytes)})",
                    evidence = "${cls.className}: ${cls.appInstanceCount} instances retain ${formatBytes(cls.appRetainedBytes)} = ${String.format(Locale.US, "%.1f", p)}% of the ${formatBytes(appTotal)} app heap " +
                        "(limit ${Thresholds.DOMINANT_CLASS_PERCENT.toInt()}%; byte[], Object[], String and Class are excluded).",
                    suggestion = "Open the largest ${simpleName(cls.className)} in the tree and follow its path from the root to the owner; if that owner is a cache, bound it, " +
                        "otherwise make it release ${simpleName(cls.className)} instances it no longer needs.",
                    bytes = cls.appRetainedBytes, refs = listOfNotNull(top),
                )
            }
    }
}
