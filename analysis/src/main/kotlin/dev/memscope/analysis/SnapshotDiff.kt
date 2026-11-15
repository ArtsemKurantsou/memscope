package dev.memscope.analysis

import kotlinx.serialization.Serializable

@Serializable
data class ClassDelta(
    val className: String, val isAppCode: Boolean, val countBefore: Int, val countAfter: Int,
    val shallowBefore: Long, val shallowAfter: Long, val retainedBefore: Long, val retainedAfter: Long,
    val appCountBefore: Int, val appCountAfter: Int, val appShallowBefore: Long, val appShallowAfter: Long,
    val appRetainedBefore: Long, val appRetainedAfter: Long,
)

@Serializable
data class RootDelta(val label: String, val retainedBefore: Long, val retainedAfter: Long, val appRetainedBefore: Long, val appRetainedAfter: Long)

@Serializable
data class DiffResult(
    val classes: List<ClassDelta>, val roots: List<RootDelta>,
    val reachableBefore: Long, val reachableAfter: Long, val appHeapBefore: Long, val appHeapAfter: Long,
)

/**
 * What grew between two heap dumps. Classes are matched by name; the tree's root categories and its
 * `Static fields` class rows are matched by label ([RootDelta.label]). Object identity is not tracked.
 *
 * "App" numbers count only app-heap objects: every dump holds the same ~35 MB of zygote and boot-image
 * objects, which would otherwise drown the change.
 */
object SnapshotDiff {
    const val STATICS_PREFIX = "Static fields / "

    /** Cost: O(classes) plus one pass over the roots' children (all `Static fields` rows). */
    fun diff(before: Snapshot, after: Snapshot): DiffResult {
        val b = before.summary()
        val a = after.summary()
        return DiffResult(
            classes = classDeltas(before.classes(), after.classes()),
            roots = rootDeltas(rootNumbers(before), rootNumbers(after)),
            reachableBefore = b.reachableBytes, reachableAfter = a.reachableBytes,
            appHeapBefore = b.appHeapBytes, appHeapAfter = a.appHeapBytes,
        )
    }

    private fun classDeltas(before: List<ClassStat>, after: List<ClassStat>): List<ClassDelta> {
        val old = before.associateBy { it.className }
        val new = after.associateBy { it.className }
        val empty = ClassStat("", 0, 0, 0, false)
        return (old.keys + new.keys).mapNotNull { name ->
            val o = old[name] ?: empty
            val n = new[name] ?: empty
            val d = ClassDelta(
                className = name, isAppCode = (old[name] ?: new.getValue(name)).isAppCode,
                countBefore = o.instanceCount, countAfter = n.instanceCount,
                shallowBefore = o.shallowBytes, shallowAfter = n.shallowBytes,
                retainedBefore = o.retainedBytes, retainedAfter = n.retainedBytes,
                appCountBefore = o.appInstanceCount, appCountAfter = n.appInstanceCount,
                appShallowBefore = o.appShallowBytes, appShallowAfter = n.appShallowBytes,
                appRetainedBefore = o.appRetainedBytes, appRetainedAfter = n.appRetainedBytes,
            )
            d.takeIf { o != n }
        }.sortedWith(compareByDescending<ClassDelta> { it.retainedAfter - it.retainedBefore }.thenBy { it.className })
    }

    /** Retained bytes (all, app) by row label: the root categories and the non-empty `Static fields` class rows. */
    private fun rootNumbers(s: Snapshot): Map<String, Pair<Long, Long>> {
        val out = LinkedHashMap<String, Pair<Long, Long>>()
        fun add(label: String, row: TreeRow) {
            val (all, app) = out[label] ?: (0L to 0L) // several class objects can share one name
            out[label] = (all + row.retainedBytes) to (app + row.appHeapBytes)
        }
        val categories = s.children("root", 0, Int.MAX_VALUE).rows
        for (c in categories) add(c.label, c)
        val statics = categories.firstOrNull { it.label == "Static fields" } ?: return out
        for (r in s.children(statics.ref, 0, Int.MAX_VALUE).rows) {
            if (r.retainedBytes != 0L || r.appHeapBytes != 0L) add(STATICS_PREFIX + r.label, r)
        }
        return out
    }

    private fun rootDeltas(before: Map<String, Pair<Long, Long>>, after: Map<String, Pair<Long, Long>>): List<RootDelta> =
        (before.keys + after.keys).map { label ->
            val (rb, ab) = before[label] ?: (0L to 0L)
            val (ra, aa) = after[label] ?: (0L to 0L)
            RootDelta(label, rb, ra, ab, aa)
        }
}
