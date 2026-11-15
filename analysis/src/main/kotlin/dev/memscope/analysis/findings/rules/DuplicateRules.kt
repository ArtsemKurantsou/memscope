package dev.memscope.analysis.findings.rules

import dev.memscope.analysis.Snapshot
import dev.memscope.analysis.findings.Finding
import dev.memscope.analysis.findings.Rule
import dev.memscope.analysis.findings.RuleContext
import dev.memscope.analysis.findings.Severity
import dev.memscope.analysis.findings.Thresholds
import dev.memscope.analysis.findings.formatBytes
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapPrimitiveArray
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord as P

private fun contentOf(r: P): Any = when (r) {
    is P.BooleanArrayDump -> r.array; is P.CharArrayDump -> r.array; is P.FloatArrayDump -> r.array
    is P.DoubleArrayDump -> r.array; is P.ByteArrayDump -> r.array; is P.ShortArrayDump -> r.array
    is P.IntArrayDump -> r.array; is P.LongArrayDump -> r.array
}

private fun sameContent(a: Any, b: Any): Boolean = when {
    a is ByteArray && b is ByteArray -> a.contentEquals(b)
    a is CharArray && b is CharArray -> a.contentEquals(b)
    a is IntArray && b is IntArray -> a.contentEquals(b)
    a is LongArray && b is LongArray -> a.contentEquals(b)
    a is ShortArray && b is ShortArray -> a.contentEquals(b)
    a is FloatArray && b is FloatArray -> a.contentEquals(b)
    a is DoubleArray && b is DoubleArray -> a.contentEquals(b)
    a is BooleanArray && b is BooleanArray -> a.contentEquals(b)
    else -> false
}

/**
 * Splits [g] into the groups of members whose real content is equal (a hash match alone is not enough:
 * big arrays are hashed on a prefix). Reads at most [MAX_CONFIRM] members, one content at a time per
 * cluster representative; groups of one are dropped.
 */
private const val MAX_CONFIRM = 32

private fun confirm(s: Snapshot, g: ContentGroup): List<ContentGroup> {
    val ids = s.index.objectIds
    fun read(d: Int): Any? = s.withGraph { gr -> (gr.findObjectByIdOrNull(ids[d]) as? HeapPrimitiveArray)?.readRecord()?.let(::contentOf) }
    val reps = ArrayList<Pair<Any, ArrayList<Int>>>()
    for (d in g.members.take(MAX_CONFIRM)) {
        val content = read(d) ?: continue
        val hit = reps.firstOrNull { sameContent(it.first, content) }
        if (hit != null) hit.second += d else reps += content to arrayListOf(d)
    }
    return reps.filter { it.second.size >= 2 }.map { ContentGroup(it.second.toIntArray()) }
}

internal class DuplicateArraysRule : Rule {
    override val id = "duplicate-arrays"

    override fun evaluate(c: RuleContext): List<Finding> {
        val idx = c.snapshot.index
        return c.content.arrays.filter { (it.members.size - 1) * idx.shallow[it.members[0]].toLong() >= Thresholds.DUPLICATE_ARRAY_MIN_WASTED }
            .flatMap { confirm(c.snapshot, it) }.map { g ->
            val each = idx.shallow[g.members[0]].toLong()
            Triple(g, each, (g.members.size - 1) * each)
        }.filter { (g, _, wasted) -> g.members.size >= Thresholds.DUPLICATE_ARRAY_MIN_COPIES && wasted >= Thresholds.DUPLICATE_ARRAY_MIN_WASTED }
            .sortedByDescending { it.third }.take(10)
            .map { (g, each, wasted) ->
                val type = idx.classNames[idx.classOf[g.members[0]]]
                Finding(
                    ruleId = id, severity = Severity.MEDIUM,
                    title = "${g.members.size} identical $type arrays of ${formatBytes(each)} waste ${formatBytes(wasted)}",
                    evidence = "${g.members.size} copies of a ${formatBytes(each)} $type with the same content (checked byte by byte); " +
                        "keeping one would save ${formatBytes(wasted)}. Limit: at least ${Thresholds.DUPLICATE_ARRAY_MIN_COPIES} copies of ${formatBytes(Thresholds.DUPLICATE_ARRAY_MIN_BYTES)} or more, " +
                        "${formatBytes(Thresholds.DUPLICATE_ARRAY_MIN_WASTED)} wasted.",
                    suggestion = "Load or build this ${formatBytes(each)} $type once and share the reference (a singleton or an LruCache keyed by its source) instead of keeping ${g.members.size} copies.",
                    bytes = wasted, refs = g.members.take(3).map { "obj:$it" },
                )
            }
    }
}

internal class DuplicateStringsRule : Rule {
    override val id = "duplicate-strings"

    private class Dup(val owner: Int, val copies: Int, val wasted: Long, val text: String, val length: Int)

    override fun evaluate(c: RuleContext): List<Finding> {
        val s = c.snapshot
        val idx = s.index
        val dups = ArrayList<Dup>()
        for (g in c.content.strings) {
            val owners = g.members.map { c.content.stringOf(it) }.filter { it >= 0 }
            if (owners.size < 2) continue
            val each = idx.shallow[owners[0]].toLong() + idx.shallow[g.members[0]].toLong()
            // Hash match -> read the actual text of a few members; differing texts are a collision.
            val texts = owners.take(3).map { readString(s, it) }
            if (texts.any { it == null || it != texts[0] }) continue
            dups += Dup(owners[0], owners.size, (owners.size - 1) * each, texts[0]!!, texts[0]!!.length)
        }
        val top = dups.sortedByDescending { it.wasted }.take(Thresholds.DUPLICATE_STRING_TOP)
        val total = top.sumOf { it.wasted }
        if (top.isEmpty() || total < Thresholds.DUPLICATE_STRING_MIN_WASTED) return emptyList()
        val best = top[0]
        return listOf(
            Finding(
                ruleId = id, severity = Severity.LOW,
                title = "Duplicate strings waste ${formatBytes(total)} (${best.copies} copies of ${preview(best.text, 24)})",
                evidence = top.joinToString("\n") { "${it.copies} x ${preview(it.text, 60)} (${it.length} chars) wastes ${formatBytes(it.wasted)}" },
                suggestion = "Intern or share the repeated value: parse it once and keep one String (String.intern() for small closed sets, or a map from key to canonical instance), " +
                    "and avoid building a new String per item when the text is the same.",
                bytes = total, refs = top.take(3).map { "obj:${it.owner}" },
            ),
        )
    }

    private fun readString(s: Snapshot, d: Int): String? = try {
        s.withGraph { g -> (g.findObjectByIdOrNull(s.index.objectIds[d]) as? HeapInstance)?.readAsJavaString() }
    } catch (_: RuntimeException) {
        null
    }

    private fun preview(text: String, max: Int): String {
        val t = text.replace("\n", "\\n").replace("\r", "\\r")
        return "\"" + (if (t.length > max) t.take(max) + "…" else t) + "\""
    }
}
