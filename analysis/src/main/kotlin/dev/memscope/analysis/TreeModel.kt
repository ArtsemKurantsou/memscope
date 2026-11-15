package dev.memscope.analysis

import kotlinx.serialization.Serializable

@Serializable
enum class RowKind { CATEGORY, CLASS_STATICS, THREAD, OBJECT, GROUP, SHARED }

@Serializable
data class TreeRow(
    val ref: String,
    val kind: RowKind,
    val label: String,
    val edgeLabel: String?,
    val className: String?,
    val count: Int,
    val shallowBytes: Long,
    val retainedBytes: Long,
    val nativeEstBytes: Long,
    val percentOfHeap: Double,
    val childCount: Int,
    val isAppCode: Boolean,
    val appHeapBytes: Long,
)

@Serializable
data class TreePage(val rows: List<TreeRow>, val total: Int)

@Serializable
data class HeapSummary(
    val reachableBytes: Long,
    val unreachableBytes: Long,
    val nativeEstBytes: Long,
    val objectCount: Int,
    val classCount: Int,
    val appHeapBytes: Long,
)

/**
 * One step of a path. [edgeLabel] names the edge leaving this step (toward the next step or the target).
 * An elision step stands for [elided] omitted steps; it has `ref == null` and a descriptive [className].
 */
@Serializable
data class PathStep(val ref: String?, val className: String, val edgeLabel: String?, val elided: Int = 0)

@Serializable
data class FieldValue(val name: String, val type: String, val value: String, val ref: String?)

@Serializable
data class ObjectDetails(
    val ref: String,
    val className: String,
    val shallowBytes: Long,
    val retainedBytes: Long,
    val nativeEstBytes: Long,
    val pathFromRoot: List<PathStep>,
    val fields: List<FieldValue>,
    val referrers: List<PathStep>,
    val referrerCount: Int,
    val rootsReaching: List<PathStep>,
)

@Serializable
data class ClassStat(
    val className: String,
    val instanceCount: Int,
    val shallowBytes: Long,
    val retainedBytes: Long,
    val isAppCode: Boolean,
    /** The same three numbers counting only app-heap objects (heap "app", or unknown when the dump has no heap info). */
    val appInstanceCount: Int = 0,
    val appShallowBytes: Long = 0,
    val appRetainedBytes: Long = 0,
)

/** Top-level rows of the tree, in display order. Ref: `cat:<key>`. */
internal enum class Category(val key: String, val label: String) {
    STATIC("static", "Static fields"),
    THREADS("threads", "Thread stacks"),
    JNI("jni", "JNI globals"),
    OTHER("other", "Other roots"),
    SHARED("shared", "Shared (held by several roots)");

    val ref: String get() = "cat:$key"
}

/**
 * A parsed row reference. Text forms: `root`, `cat:<key>`, `thr:<dense index of the Thread object>`,
 * `obj:<dense index>` (OBJECT and CLASS_STATICS rows) and `grp:<parent ref>|<class table index>`.
 */
internal sealed class Ref {
    abstract val text: String

    object Root : Ref() { override val text = "root" }
    class Cat(val category: Category) : Ref() { override val text get() = category.ref }
    class Thread(val obj: Int) : Ref() { override val text get() = "thr:$obj" }
    class Obj(val obj: Int) : Ref() { override val text get() = "obj:$obj" }
    class Group(val parent: Ref, val classIndex: Int) : Ref() { override val text get() = "grp:${parent.text}|$classIndex" }
}

/** Aggregates of one GROUP row. */
internal class GroupStats(val classIndex: Int) {
    var count = 0
    var shallow = 0L
    var retained = 0L
    var native = 0L
    var app = 0L
}

/**
 * The rows under one parent. [entries] in display order: `>= 0` is a dense object index (for
 * `cat:threads` a Thread object, i.e. a THREAD row), `< 0` is the GROUP of class table index `-(e + 1)`.
 * [members] are all objects under the parent (group instances included) in retained-desc order.
 */
internal class ChildList(val entries: IntArray, val members: IntArray, val groups: Map<Int, GroupStats>)

/** What the tree needs from the hprof itself (edge labels, thread names); read lazily by [Snapshot]. */
internal interface HeapLabels {
    /** For each of [targets] (all directly referenced by [parent]): the field name / `[i]`, or null if not found. */
    fun directLabels(parent: Int, targets: IntArray): Array<String?>
    fun threadName(thread: Int): String?
}

/**
 * The collapsed tree over a [HeapIndex]: root categories, thread rows, dominator children with
 * same-class grouping, row construction and reveal paths. Not thread-safe ([Snapshot] serialises calls).
 */
internal class TreeModel(val index: HeapIndex, private val appPackagePrefixes: List<String>) {
    companion object {
        /** Siblings of one class under one parent numbering at least this many collapse into a GROUP row. */
        const val GROUP_MIN = 10
        private const val CACHE_SIZE = 64
    }

    private val n = index.objectCount
    private val superRoot = index.superRoot

    /** Shallow bytes of reachable objects. */
    val reachableBytes: Long
    val unreachableBytes: Long

    /** Retained bytes counting only app-heap objects (unknown heap counts as app). */
    val appRetained: LongArray

    // Class objects (dense index, ascending) -> class table index of the class they describe.
    private val classObjDense: IntArray
    private val classObjTable: IntArray

    init {
        var reachable = 0L
        var unreachable = 0L
        val appShallow = IntArray(n)
        for (i in 0 until n) {
            if (index.idom[i] == Dominators.UNREACHABLE) {
                unreachable += index.shallow[i]
            } else {
                reachable += index.shallow[i]
            }
            val kind = index.heapKind[i]
            if (kind == HeapKind.APP || kind == HeapKind.UNKNOWN) appShallow[i] = index.shallow[i]
        }
        reachableBytes = reachable
        unreachableBytes = unreachable
        appRetained = Dominators.retainedSizes(index.idom, appShallow, n)

        val packed = LongBuf()
        for (t in index.classObjectIndex.indices) {
            val d = index.classObjectIndex[t]
            if (d >= 0) packed.add((d.toLong() shl 32) or t.toLong())
        }
        val sorted = packed.trimmed().also { java.util.Arrays.sort(it) }
        classObjDense = IntArray(sorted.size) { (sorted[it] ushr 32).toInt() }
        classObjTable = IntArray(sorted.size) { (sorted[it] and 0xFFFFFFFFL).toInt() }
    }

    // ---------------------------------------------------------------- naming

    /** Class name shown for an object; a class object shows the class it describes. */
    fun displayName(d: Int): String {
        val k = java.util.Arrays.binarySearch(classObjDense, d)
        return if (k >= 0) index.classNames[classObjTable[k]] else index.classNames[index.classOf[d]]
    }

    fun isAppCode(className: String): Boolean {
        var base = className
        while (base.endsWith("[]")) base = base.substring(0, base.length - 2)
        return appPackagePrefixes.any { it.isNotEmpty() && base.startsWith(it) }
    }

    fun percent(bytes: Long): Double = if (reachableBytes == 0L) 0.0 else bytes * 100.0 / reachableBytes

    /** Position of [d] in `graph.roots` (sorted ascending, one entry per object), or < 0. */
    fun rootPos(d: Int): Int = java.util.Arrays.binarySearch(index.graph.roots, d)

    // ---------------------------------------------------------------- categories and threads

    private class Roots(
        val members: Array<IntArray>, // per Category ordinal, retained desc (THREADS: all thread members)
        val retained: LongArray, // summed retained per Category ordinal
        val threads: IntArray, // Thread objects, by summed retained desc
        val threadMembers: Map<Int, IntArray>,
    )

    private val roots: Roots by lazy { classifyRoots() }

    private fun classifyRoots(): Roots {
        val buffers = Array(Category.entries.size) { IntBuf() }
        val byThread = LinkedHashMap<Int, IntBuf>()
        val off = index.domChildOffsets
        for (k in off[superRoot] until off[superRoot + 1]) {
            val c = index.domChildren[k] // retained desc order is kept in every list below
            val category = when (val parent = parentOfRootChild(c)) {
                is Ref.Thread -> { byThread.getOrPut(parent.obj) { IntBuf() }.add(c); Category.THREADS }
                is Ref.Cat -> parent.category
                else -> error("unexpected parent ${parent.text}")
            }
            buffers[category.ordinal].add(c)
        }
        val threadMembers = byThread.mapValues { it.value.trimmed() }
        val threadSums = threadMembers.mapValues { (_, m) -> m.sumOf { index.retained[it] } }
        val threads = threadMembers.keys.sortedWith(compareByDescending<Int> { threadSums[it] }.thenBy { it }).toIntArray()
        val members = Array(buffers.size) { buffers[it].trimmed() }
        val retained = LongArray(members.size) { c -> members[c].sumOf { index.retained[it] } }
        return Roots(members, retained, threads, threadMembers)
    }

    /**
     * The row a dominator-tree child of the super-root is listed under: a category, or for thread
     * roots the THREAD row of its thread. The single source of truth for [children] and [revealPath].
     */
    private fun parentOfRootChild(c: Int): Ref {
        val pos = rootPos(c)
        if (pos < 0) return Ref.Cat(Category.SHARED)
        return when (index.rootKinds[pos].toInt()) {
            RootKind.CLASS.ordinal -> Ref.Cat(Category.STATIC)
            RootKind.THREAD.ordinal -> Ref.Thread(c)
            RootKind.JAVA_FRAME.ordinal, RootKind.JNI_LOCAL.ordinal ->
                index.rootThreadObj[pos].let { t -> if (t >= 0) Ref.Thread(t) else Ref.Cat(Category.OTHER) }
            RootKind.JNI_GLOBAL.ordinal -> Ref.Cat(Category.JNI)
            else -> Ref.Cat(Category.OTHER)
        }
    }

    fun isThread(d: Int): Boolean = roots.threadMembers.containsKey(d)

    // ---------------------------------------------------------------- refs

    fun parse(text: String): Ref {
        fun bad(why: String): Nothing = throw IllegalArgumentException("Invalid ref \"$text\": $why")
        fun number(s: String, limit: Int, what: String): Int {
            if (s.isEmpty() || s.length > 10 || !s.all { it in '0'..'9' }) bad("$what is not a number")
            val v = s.toLong()
            if (v >= limit) bad("$what $v out of range (0 until $limit)")
            return v.toInt()
        }
        return when {
            text == "root" -> Ref.Root
            text.startsWith("cat:") -> Category.entries.firstOrNull { it.key == text.substring(4) }?.let { Ref.Cat(it) }
                ?: bad("unknown category")
            text.startsWith("obj:") -> Ref.Obj(number(text.substring(4), n, "object index"))
            text.startsWith("thr:") -> {
                val t = number(text.substring(4), n, "object index")
                if (!isThread(t)) bad("object $t is not a thread with roots")
                Ref.Thread(t)
            }
            text.startsWith("grp:") -> {
                val rest = text.substring(4)
                val bar = rest.lastIndexOf('|')
                if (bar <= 0) bad("expected grp:<parent>|<class>")
                val parent = parse(rest.substring(0, bar))
                val groupable = parent is Ref.Obj || parent is Ref.Thread ||
                    (parent is Ref.Cat && parent.category != Category.STATIC && parent.category != Category.THREADS)
                if (!groupable) bad("a group cannot be under ${parent.text}")
                Ref.Group(parent, number(rest.substring(bar + 1), index.classNames.size, "class index"))
            }
            else -> bad("unknown form")
        }
    }

    // ---------------------------------------------------------------- child lists

    private val cache = object : LinkedHashMap<String, ChildList>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChildList>) = size > CACHE_SIZE
    }

    fun childList(ref: Ref): ChildList = cache.getOrPut(ref.text) { buildChildList(ref) }

    private fun buildChildList(ref: Ref): ChildList = when (ref) {
        Ref.Root -> {
            // Non-empty categories by summed retained desc; ties keep the enum order (sortedWith is stable).
            val present = Category.entries.filter { roots.members[it.ordinal].isNotEmpty() }
                .sortedWith(compareByDescending { roots.retained[it.ordinal] })
            ChildList(IntArray(present.size) { present[it].ordinal }, IntArray(0), emptyMap())
        }
        is Ref.Cat -> when (ref.category) {
            Category.STATIC -> roots.members[Category.STATIC.ordinal].let { ChildList(it, it, emptyMap()) }
            Category.THREADS -> ChildList(roots.threads, roots.members[Category.THREADS.ordinal], emptyMap())
            else -> grouped(roots.members[ref.category.ordinal])
        }
        is Ref.Thread -> grouped(roots.threadMembers.getValue(ref.obj))
        is Ref.Obj -> grouped(index.domChildren.copyOfRange(index.domChildOffsets[ref.obj], index.domChildOffsets[ref.obj + 1]))
        is Ref.Group -> {
            val members = childList(ref.parent).members
            val out = IntBuf()
            for (m in members) if (index.classOf[m] == ref.classIndex) out.add(m)
            out.trimmed().let { ChildList(it, it, emptyMap()) }
        }
    }

    /** Collapses classes with at least [GROUP_MIN] members into groups; [members] are retained desc. */
    private fun grouped(members: IntArray): ChildList {
        if (members.size < GROUP_MIN) return ChildList(members, members, emptyMap())
        val groupList = ArrayList<GroupStats>()
        forEachClassRun(members, 0, members.size) { cls, count -> if (count >= GROUP_MIN) groupList += GroupStats(cls) }
        if (groupList.isEmpty()) return ChildList(members, members, emptyMap())
        val slot = IntArray(index.classNames.size) { -1 } // class table index -> position in groupList
        groupList.forEachIndexed { k, g -> slot[g.classIndex] = k }
        val singles = IntBuf()
        for (m in members) {
            val s = slot[index.classOf[m]]
            if (s < 0) { singles.add(m); continue }
            val g = groupList[s]
            g.count++
            g.shallow += index.shallow[m]
            g.retained += index.retained[m]
            g.native += index.retainedWithNative[m] - index.retained[m]
            g.app += appRetained[m]
        }
        val groups = groupList.associateBy { it.classIndex }
        val sortedGroups = groupList.sortedWith(compareByDescending<GroupStats> { it.retained }.thenBy { it.classIndex })
        // Merge the (already sorted) single objects with the groups by retained size.
        val entries = IntArray(singles.size + sortedGroups.size)
        var i = 0
        var j = 0
        for (k in entries.indices) {
            val takeGroup = j < sortedGroups.size && (i >= singles.size || sortedGroups[j].retained >= index.retained[singles[i]])
            entries[k] = if (takeGroup) -(sortedGroups[j++].classIndex + 1) else singles[i++]
        }
        return ChildList(entries, members, groups)
    }

    /** Number of rows [children] returns for the dominator children of [d], without building the list. */
    fun objectChildCount(d: Int): Int {
        val from = index.domChildOffsets[d]
        val to = index.domChildOffsets[d + 1]
        if (to - from < GROUP_MIN) return to - from
        cache["obj:$d"]?.let { return it.entries.size }
        // O(k) with a reused per-class counter table (reset afterwards): no sort, no allocation.
        val counts = classCounts
        val children = index.domChildren
        for (k in from until to) counts[index.classOf[children[k]]]++
        var rows = 0
        for (k in from until to) {
            val c = index.classOf[children[k]]
            val count = counts[c]
            if (count in 1 until GROUP_MIN) rows++
            else if (count >= GROUP_MIN) { rows++; counts[c] = -1 } // one GROUP row; count it once
        }
        for (k in from until to) counts[index.classOf[children[k]]] = 0
        return rows
    }

    private val classCounts = IntArray(index.classNames.size)

    /** Calls [action] once per distinct class among `objects[from until to]` with its number of objects. */
    private inline fun forEachClassRun(objects: IntArray, from: Int, to: Int, action: (classIndex: Int, count: Int) -> Unit) {
        val classes = IntArray(to - from) { index.classOf[objects[from + it]] }
        classes.sort()
        var k = 0
        while (k < classes.size) {
            var e = k
            while (e < classes.size && classes[e] == classes[k]) e++
            action(classes[k], e - k)
            k = e
        }
    }

    // ---------------------------------------------------------------- rows

    fun page(ref: Ref, offset: Int, limit: Int, labels: HeapLabels): TreePage {
        require(offset >= 0) { "offset must be >= 0, was $offset" }
        require(limit >= 0) { "limit must be >= 0, was $limit" }
        val list = childList(ref)
        val total = list.entries.size
        val from = minOf(offset, total)
        val to = minOf(total.toLong(), from.toLong() + limit).toInt()
        val slice = list.entries.copyOfRange(from, to)
        val rows = when {
            ref == Ref.Root -> slice.map { categoryRow(Category.entries[it]) }
            ref is Ref.Cat && ref.category == Category.STATIC -> slice.map { objectRow(it, null, RowKind.CLASS_STATICS) }
            ref is Ref.Cat && ref.category == Category.THREADS -> slice.map { threadRow(it, labels) }
            else -> {
                val labelParent = if (ref is Ref.Group) ref.parent else ref
                val objects = slice.filter { it >= 0 }.toIntArray()
                val edgeLabels = edgeLabels(labelParent, objects, labels)
                slice.map { e ->
                    if (e >= 0) objectRow(e, edgeLabels[e], RowKind.OBJECT) else groupRow(ref, list.groups.getValue(-e - 1))
                }
            }
        }
        return TreePage(rows, total)
    }

    private fun edgeLabels(parent: Ref, children: IntArray, labels: HeapLabels): Map<Int, String?> = when (parent) {
        is Ref.Obj -> objectEdgeLabels(parent.obj, children, labels)
        is Ref.Thread -> children.associateWith { c ->
            when (index.rootKinds[rootPos(c)].toInt()) {
                RootKind.THREAD.ordinal -> "thread"
                RootKind.JNI_LOCAL.ordinal -> "jni local"
                else -> "local"
            }
        }
        else -> emptyMap()
    }

    /** Field name / `[i]` for children [parent] references directly, otherwise "via N hops". */
    private fun objectEdgeLabels(parent: Int, children: IntArray, labels: HeapLabels): Map<Int, String?> {
        if (children.isEmpty()) return emptyMap()
        val wanted = children.toHashSet()
        val direct = HashSet<Int>()
        val g = index.graph
        for (e in g.edgeOffsets[parent] until g.edgeOffsets[parent + 1]) {
            val t = g.edgeTargets[e]
            if (t in wanted) direct += t
        }
        val result = HashMap<Int, String?>()
        if (direct.isNotEmpty()) {
            val targets = direct.toIntArray()
            val names = labels.directLabels(parent, targets)
            for (k in targets.indices) result[targets[k]] = names[k]
        }
        for (c in children) if (c !in direct) result[c] = "via ${hops(parent, c)} hops"
        return result
    }

    /**
     * Length of the shortest-path-tree segment from [ancestor] down to [d]. [ancestor] dominates [d],
     * so every path from a root to [d], the BFS one included, passes through it.
     */
    fun hops(ancestor: Int, d: Int): Int {
        var x = d
        var steps = 0
        while (x != ancestor && x != superRoot && x != Dominators.UNREACHABLE) {
            x = index.bfsParent[x]
            steps++
        }
        return steps
    }

    fun objectRow(d: Int, edgeLabel: String?, kind: RowKind): TreeRow {
        val name = displayName(d)
        val retained = index.retained[d]
        return TreeRow(
            ref = "obj:$d", kind = kind, label = name, edgeLabel = edgeLabel, className = name, count = 1,
            shallowBytes = index.shallow[d].toLong(), retainedBytes = retained,
            nativeEstBytes = index.retainedWithNative[d] - retained, percentOfHeap = percent(retained),
            childCount = objectChildCount(d), isAppCode = isAppCode(name), appHeapBytes = appRetained[d],
        )
    }

    private fun groupRow(parent: Ref, g: GroupStats): TreeRow {
        val name = index.classNames[g.classIndex]
        return TreeRow(
            ref = Ref.Group(parent, g.classIndex).text, kind = RowKind.GROUP,
            label = String.format(java.util.Locale.US, "%,d × %s", g.count, name), edgeLabel = null,
            className = name, count = g.count, shallowBytes = g.shallow, retainedBytes = g.retained,
            nativeEstBytes = g.native, percentOfHeap = percent(g.retained), childCount = g.count,
            isAppCode = isAppCode(name), appHeapBytes = g.app,
        )
    }

    private fun aggregateRow(ref: String, kind: RowKind, label: String, className: String?, members: IntArray, childCount: Int): TreeRow {
        var retained = 0L
        var native = 0L
        var app = 0L
        for (m in members) {
            retained += index.retained[m]
            native += index.retainedWithNative[m] - index.retained[m]
            app += appRetained[m]
        }
        return TreeRow(
            ref = ref, kind = kind, label = label, edgeLabel = null, className = className, count = 1,
            shallowBytes = 0, retainedBytes = retained, nativeEstBytes = native, percentOfHeap = percent(retained),
            childCount = childCount, isAppCode = className != null && isAppCode(className), appHeapBytes = app,
        )
    }

    private val categoryRows = HashMap<Category, TreeRow>()

    private fun categoryRow(c: Category): TreeRow = categoryRows.getOrPut(c) {
        aggregateRow(
            c.ref, if (c == Category.SHARED) RowKind.SHARED else RowKind.CATEGORY, c.label, null,
            roots.members[c.ordinal], childList(Ref.Cat(c)).entries.size,
        )
    }

    private fun threadRow(t: Int, labels: HeapLabels): TreeRow {
        val members = roots.threadMembers.getValue(t)
        val name = labels.threadName(t) ?: "Thread@0x${java.lang.Long.toHexString(index.objectIds[t])}"
        return aggregateRow(Ref.Thread(t).text, RowKind.THREAD, name, displayName(t), members, childList(Ref.Thread(t)).entries.size)
    }

    // ---------------------------------------------------------------- reveal

    /**
     * Refs from "root" down to [ref]; each one is among the `children` of the previous one.
     * Throws [RevealTooDeepException] when the object's dominator-tree depth exceeds [maxDepth]
     * (checked on the int chain before any ref string is built).
     */
    fun revealPath(ref: Ref, maxDepth: Int): List<String> = when (ref) {
        Ref.Root -> listOf("root")
        is Ref.Cat -> listOf("root", ref.text)
        is Ref.Thread -> listOf("root", Category.THREADS.ref, ref.text)
        is Ref.Group -> revealPath(ref.parent, maxDepth) + ref.text
        is Ref.Obj -> revealObject(ref.obj, maxDepth)
    }

    /** Number of objects on the dominator-tree chain from the super-root's child down to [d] (reachable [d]). */
    private fun dominatorDepth(d: Int): Int {
        var depth = 0
        var x = d
        while (x != superRoot) { depth++; x = index.idom[x] }
        return depth
    }

    private fun revealObject(d: Int, maxDepth: Int): List<String> {
        require(index.idom[d] != Dominators.UNREACHABLE) { "Object obj:$d is unreachable and not in the tree" }
        val depth = dominatorDepth(d)
        if (depth > maxDepth) throw RevealTooDeepException(depth)
        val chain = IntBuf(depth) // d up to the dominator-tree child of the super-root
        var x = d
        while (x != superRoot) { chain.add(x); x = index.idom[x] }
        val top = chain[chain.size - 1]
        val path = arrayListOf("root")
        var parent = parentOfRootChild(top)
        if (parent is Ref.Thread) path += Category.THREADS.ref
        path += parent.text
        for (k in chain.size - 1 downTo 0) {
            val node = chain[k]
            val cls = index.classOf[node]
            val mayGroup = parent !is Ref.Obj ||
                index.domChildOffsets[parent.obj + 1] - index.domChildOffsets[parent.obj] >= GROUP_MIN
            if (mayGroup && childList(parent).groups.containsKey(cls)) {
                val group = Ref.Group(parent, cls)
                path += group.text
            }
            val obj = Ref.Obj(node)
            path += obj.text
            parent = obj
        }
        return path
    }

    /** Row offset of [child] within the children of [parent], or -1 if it is not one of them. */
    fun indexInParent(parent: Ref, child: Ref): Int {
        val entries = childList(parent).entries
        val wanted = when (child) {
            is Ref.Cat -> if (parent == Ref.Root) child.category.ordinal else return -1
            is Ref.Obj -> if (parent != Ref.Root && !(parent is Ref.Cat && parent.category == Category.THREADS)) child.obj else return -1
            is Ref.Thread -> if (parent is Ref.Cat && parent.category == Category.THREADS) child.obj else return -1
            is Ref.Group -> if (child.parent.text == parent.text) -(child.classIndex + 1) else return -1
            Ref.Root -> return -1
        }
        return entries.indexOf(wanted)
    }
}

/** Sorts [a] in place by [key] descending, ties by value ascending (stable bottom-up merge sort; [a] ascending on entry). */
internal fun sortByKeyDesc(a: IntArray, key: LongArray) {
    val len = a.size
    var src = a
    var dst = IntArray(len)
    var width = 1
    while (width < len) {
        var lo = 0
        while (lo < len) {
            val mid = minOf(lo + width, len)
            val hi = minOf(lo + 2 * width, len)
            var i = lo
            var j = mid
            var o = lo
            while (i < mid && j < hi) dst[o++] = if (key[src[j]] > key[src[i]]) src[j++] else src[i++]
            while (i < mid) dst[o++] = src[i++]
            while (j < hi) dst[o++] = src[j++]
            lo = hi
        }
        val t = src; src = dst; dst = t
        width *= 2
    }
    if (src !== a) System.arraycopy(src, 0, a, 0, len)
}
