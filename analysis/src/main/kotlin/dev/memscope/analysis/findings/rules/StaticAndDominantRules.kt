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

    companion object {
        private val PRIMITIVE_ARRAYS = setOf("boolean[]", "char[]", "float[]", "double[]", "byte[]", "short[]", "int[]", "long[]")

        /** Groups classes (largest first) whose app-retained bytes are within the tolerance of the group's largest: one cache seen through its HashMap, Node[] and Node. */
        fun cluster(sortedDesc: List<dev.memscope.analysis.ClassStat>): List<List<dev.memscope.analysis.ClassStat>> {
            val out = ArrayList<MutableList<dev.memscope.analysis.ClassStat>>()
            for (c in sortedDesc) {
                val head = out.lastOrNull()?.first()
                if (head != null && c.appRetainedBytes >= head.appRetainedBytes * (1 - Thresholds.DOMINANT_CLUSTER_TOLERANCE)) out.last() += c else out += mutableListOf(c)
            }
            return out
        }
    }

    override fun evaluate(c: RuleContext): List<Finding> {
        val s = c.snapshot
        val appTotal = s.summary().appHeapBytes
        val dominant = s.classes().filter {
            it.className !in Thresholds.DOMINANT_CLASS_EXCLUDED && it.className !in PRIMITIVE_ARRAYS && pct(it.appRetainedBytes, appTotal) >= Thresholds.DOMINANT_CLASS_PERCENT
        }.sortedByDescending { it.appRetainedBytes }
        // App classes, plus at most one framework class (the largest).
        val picked = (dominant.filter { it.isAppCode } + dominant.filter { !it.isAppCode }.take(1)).sortedByDescending { it.appRetainedBytes }
        return cluster(picked).mapNotNull { group ->
            val cls = group.firstOrNull { it.isAppCode } ?: group.first()
            if (c.largeStatics.any { it.bytes >= cls.appRetainedBytes * Thresholds.DOMINANT_COVERED_SHARE }) return@mapNotNull null
            val p = pct(cls.appRetainedBytes, appTotal)
            val top = s.instances(cls.className, 0, 1).rows.firstOrNull()?.ref
            val also = group.filter { it !== cls }.joinToString { it.className }
            Finding(
                ruleId = id, severity = Severity.MEDIUM,
                title = "${simpleName(cls.className)} instances retain ${String.format(Locale.US, "%.0f", p)}% of the app heap (${formatBytes(cls.appRetainedBytes)})",
                evidence = "${cls.className}: ${cls.appInstanceCount} instances retain ${formatBytes(cls.appRetainedBytes)} = ${String.format(Locale.US, "%.1f", p)}% of the ${formatBytes(appTotal)} app heap " +
                    "(limit ${Thresholds.DOMINANT_CLASS_PERCENT.toInt()}%; arrays, String and Class are excluded)." + if (also.isNotEmpty()) " Same bytes through: $also." else "",
                suggestion = "Open the largest ${simpleName(cls.className)} in the tree and follow its path from the root to the owner; if that owner is a cache, bound it, " +
                    "otherwise make it release ${simpleName(cls.className)} instances it no longer needs.",
                bytes = cls.appRetainedBytes, refs = listOfNotNull(top),
            )
        }
    }
}
