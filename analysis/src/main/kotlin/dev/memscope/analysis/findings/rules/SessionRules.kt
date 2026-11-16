package dev.memscope.analysis.findings.rules

import dev.memscope.analysis.findings.Finding
import dev.memscope.analysis.findings.SessionRule
import dev.memscope.analysis.findings.Severity
import dev.memscope.analysis.findings.SummarizedSnapshot
import dev.memscope.analysis.findings.Thresholds
import dev.memscope.analysis.findings.formatBytes
import dev.memscope.analysis.findings.simpleName

/** A value per snapshot for one class or static holder, plus the ref of its row in each snapshot. */
private class Series(val name: String, val isStatic: Boolean, val values: LongArray, val refs: Array<String?>)

private fun seriesOf(snaps: List<SummarizedSnapshot>, appOnlyClasses: Boolean): List<Series> {
    val byKey = LinkedHashMap<String, Series>()
    fun series(name: String, isStatic: Boolean) = byKey.getOrPut((if (isStatic) "S:" else "C:") + name) {
        Series(name, isStatic, LongArray(snaps.size), arrayOfNulls(snaps.size))
    }
    snaps.forEachIndexed { i, sn ->
        for (c in sn.summary.classes) {
            if (appOnlyClasses && !c.isApp) continue
            series(c.name, false).also { it.values[i] = c.appRetained; it.refs[i] = c.topRef }
        }
        for (st in sn.summary.statics) {
            if (appOnlyClasses && !st.isApp) continue
            series(st.className, true).also { it.values[i] = st.appRetained; it.refs[i] = st.ref }
        }
    }
    return byKey.values.toList()
}

internal class MonotonicGrowthRule : SessionRule {
    override val id = "monotonic-growth"

    override fun evaluate(snapshots: List<SummarizedSnapshot>, startup: Boolean): List<Finding> {
        // Milestones of a startup recording grow by nature; the startup-interval rule describes them.
        if (startup || snapshots.size < Thresholds.GROWTH_MIN_SNAPSHOTS) return emptyList()
        val found = ArrayList<Triple<Finding, Long, Boolean>>()
        for (s in seriesOf(snapshots, appOnlyClasses = true)) {
            // Longest run of strictly increasing values.
            var start = 0
            var best: IntRange? = null
            for (i in 1..s.values.size) {
                if (i == s.values.size || s.values[i] <= s.values[i - 1]) {
                    if (i - start >= Thresholds.GROWTH_MIN_SNAPSHOTS && s.values[i - 1] - s.values[start] >= Thresholds.GROWTH_MIN_BYTES &&
                        (best == null || s.values[i - 1] - s.values[start] > s.values[best.last] - s.values[best.first])
                    ) best = start until i
                    start = i
                }
            }
            val run = best ?: continue
            val growth = s.values[run.last] - s.values[run.first]
            val last = snapshots[run.last]
            val name = simpleName(s.name)
            val what = if (s.isStatic) "static fields of ${s.name}" else "${s.name} instances"
            val perStep = growth / (run.last - run.first)
            found += Finding(
                ruleId = id, severity = Severity.MEDIUM,
                title = "$name grew by ${formatBytes(growth)} across ${run.count()} snapshots",
                evidence = "$what retain: " + run.joinToString(" -> ") { "${formatBytes(s.values[it])} (${snapshots[it].label})" } +
                    ". Strictly increasing over at least ${Thresholds.GROWTH_MIN_SNAPSHOTS} snapshots, growth at least ${formatBytes(Thresholds.GROWTH_MIN_BYTES)}. " +
                    "Assumes the snapshots come from one run of the app: a restart in between resets the numbers.",
                suggestion = if (s.isStatic) {
                    "`$name` keeps retaining more (${formatBytes(perStep)} per snapshot on average). Find what adds to it each time and cap it: evict old entries, use a bounded LruCache or ring buffer, or release it when its screen closes."
                } else {
                    "$name instances accumulate (${formatBytes(perStep)} per snapshot on average). Find which owner keeps adding them (open the largest instance in the tree) and release them when no longer needed."
                },
                bytes = growth, refs = listOfNotNull(s.refs[run.last]), snapshotId = last.id,
            ).let { Triple(it, growth, s.isStatic) }
        }
        // One finding per cause: a class series that a static holder explains (about the same bytes) is dropped.
        val statics = found.filter { it.third }
        return found.filter { f -> f.third || statics.none { it.second >= f.second * 0.8 } }
            .sortedByDescending { it.second }.take(Thresholds.GROWTH_TOP).map { it.first }
    }
}

internal class StartupIntervalRule : SessionRule {
    override val id = "startup-interval"

    override fun evaluate(snapshots: List<SummarizedSnapshot>, startup: Boolean): List<Finding> {
        if (!startup || snapshots.size < 2) return emptyList()
        val series = seriesOf(snapshots, appOnlyClasses = true)
        return (1 until snapshots.size).map { i ->
            val a = snapshots[i - 1]; val b = snapshots[i]
            val reachable = b.summary.appHeapBytes - a.summary.appHeapBytes
            val top = series.map { it to (it.values[i] - it.values[i - 1]) }.filter { it.second > 0 }
                .sortedByDescending { it.second }.take(Thresholds.STARTUP_TOP)
            val title = "${a.label} → ${b.label}: app heap ${if (reachable >= 0) "+" else ""}${formatBytes(reachable)}"
            Finding(
                ruleId = id, severity = Severity.LOW, title = title,
                evidence = "App heap went from ${formatBytes(a.summary.appHeapBytes)} to ${formatBytes(b.summary.appHeapBytes)}.\n" +
                    if (top.isEmpty()) "No app class or static holder retains more than before." else
                        "Largest growth by retained size:\n" + top.joinToString("\n") { (s, delta) ->
                            "${s.name}${if (s.isStatic) " (static fields)" else ""}  +${formatBytes(delta)}"
                        },
                suggestion = if (top.isEmpty()) "Nothing app-owned grew in this interval." else
                    "${simpleName(top[0].first.name)} adds ${formatBytes(top[0].second)} in this interval. If it is not needed to show the first screen, initialise it lazily off the startup path " +
                        "(on first use or on a background thread after the first frame).",
                bytes = reachable.coerceAtLeast(0), refs = listOfNotNull(top.firstOrNull()?.first?.refs?.get(i)), snapshotId = b.id,
            )
        }
    }
}
