package dev.memscope.analysis.findings.rules

import dev.memscope.analysis.findings.Finding
import dev.memscope.analysis.findings.Rule
import dev.memscope.analysis.findings.RuleContext
import dev.memscope.analysis.findings.Severity
import dev.memscope.analysis.findings.Thresholds
import dev.memscope.analysis.findings.formatBytes
import dev.memscope.analysis.findings.simpleName
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import java.util.BitSet
import java.util.Locale

/**
 * Collections whose backing array is >= 64 KB but < 10 % used: ArrayList, HashMap (and LinkedHashMap),
 * SparseArray / LongSparseArray (framework and androidx), ArrayDeque. Big arrays are found from the
 * index; their owners are the objects with an edge to them, read through the Shark graph (a handful).
 */
internal class SparseCollectionRule : Rule {
    override val id = "sparse-collection"

    private enum class Kind(val declaring: String, val sizeField: String) {
        LIST("java.util.ArrayList", "size"), MAP("java.util.HashMap", "size"), SPARSE("", "mSize"), DEQUE("java.util.ArrayDeque", "")
    }

    private fun kindOf(className: String): Kind? = when (className) {
        "java.util.ArrayList" -> Kind.LIST
        "java.util.HashMap", "java.util.LinkedHashMap" -> Kind.MAP
        "android.util.SparseArray", "android.util.LongSparseArray", "androidx.collection.SparseArrayCompat", "androidx.collection.LongSparseArray" -> Kind.SPARSE
        "java.util.ArrayDeque" -> Kind.DEQUE
        else -> null
    }

    override fun evaluate(c: RuleContext): List<Finding> {
        val s = c.snapshot
        val idx = s.index
        val n = idx.objectCount
        val big = BitSet(n)
        for (d in 0 until n) if (idx.shallow[d] >= Thresholds.SPARSE_MIN_BACKING && idx.isReachableApp(d)) big.set(d)
        if (big.isEmpty) return emptyList()
        val kindByClass = arrayOfNulls<Kind>(idx.classNames.size).also { for (t in it.indices) it[t] = kindOf(idx.classNames[t]) }
        val owners = LinkedHashMap<Int, Int>() // owner -> a big array it references
        val g = idx.graph
        for (u in 0 until n) {
            if (kindByClass[idx.classOf[u]] == null || !idx.isReachableApp(u)) continue
            for (e in g.edgeOffsets[u] until g.edgeOffsets[u + 1]) {
                val t = g.edgeTargets[e]
                if (big.get(t)) owners.putIfAbsent(u, t)
            }
        }
        val found = ArrayList<Pair<Finding, Long>>()
        for ((owner, array) in owners) {
            val kind = kindByClass[idx.classOf[owner]]!!
            val className = idx.classNames[idx.classOf[owner]]
            val read = s.withGraph { gr ->
                val inst = gr.findObjectByIdOrNull(idx.objectIds[owner]) as? HeapInstance ?: return@withGraph null
                val arr = gr.findObjectByIdOrNull(idx.objectIds[array])
                val capacity = when (arr) {
                    is HeapObjectArray -> arr.readRecord().elementIds.size
                    is HeapPrimitiveArray -> arr.readRecord().size
                    else -> return@withGraph null
                }
                val used = when (kind) {
                    Kind.LIST, Kind.MAP -> inst[kind.declaring, "size"]?.value?.asInt
                    Kind.SPARSE -> inst[className, "mSize"]?.value?.asInt
                    Kind.DEQUE -> {
                        val head = inst[kind.declaring, "head"]?.value?.asInt
                        val tail = inst[kind.declaring, "tail"]?.value?.asInt
                        if (head == null || tail == null || capacity == 0) null else (tail - head) and (capacity - 1)
                    }
                } ?: return@withGraph null
                capacity to used
            } ?: continue
            val (capacity, used) = read
            if (capacity <= 0 || used.toDouble() / capacity >= Thresholds.SPARSE_MAX_USED) continue
            val backing = idx.shallow[array].toLong()
            val wasted = (backing * (1.0 - used.toDouble() / capacity)).toLong()
            val pct = String.format(Locale.US, "%.2f%%", 100.0 * used / capacity)
            val name = simpleName(className)
            val fix = when (kind) {
                Kind.LIST -> "Right-size it: pass the expected size to the ArrayList constructor, or call trimToSize() once it is filled."
                Kind.MAP -> "A HashMap never shrinks its table: size it with the expected count up front, or copy the entries into a new HashMap after bulk removal."
                Kind.SPARSE -> "A sparse array never shrinks its arrays: after bulk removal copy it into a new one, and do not preallocate a huge capacity."
                Kind.DEQUE -> "Construct it with the expected size; an ArrayDeque keeps its peak capacity after it drains, so replace it with a new one after a burst."
            }
            found += Finding(
                ruleId = id, severity = Severity.LOW,
                title = "$name holds $used items in a ${formatBytes(backing)} backing array ($pct used)",
                evidence = "${className} instance: backing array has $capacity slots (${formatBytes(backing)}), $used in use ($pct); about ${formatBytes(wasted)} unused. " +
                    "Limit: backing array of ${formatBytes(Thresholds.SPARSE_MIN_BACKING)} or more, under ${(Thresholds.SPARSE_MAX_USED * 100).toInt()}% used.",
                suggestion = "$name has $capacity slots for $used items, about ${formatBytes(wasted)} unused. $fix",
                bytes = wasted, refs = listOf("obj:$owner"),
            ) to wasted
        }
        return found.sortedByDescending { it.second }.take(10).map { it.first }
    }
}
