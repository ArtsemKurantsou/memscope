package dev.memscope.analysis

import shark.CloseableHeapGraph
import shark.HeapObject
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.HeapValue
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump
import shark.ValueHolder
import java.io.File
import java.io.IOException
import java.util.BitSet

/**
 * Query facade over one heap dump: the collapsed tree from GC roots (see [TreeModel]), object details,
 * the class table and instance lists. Calls are serialised (the lazily opened Shark graph is not
 * thread-safe).
 *
 * Refs are opaque strings, stable within a snapshot; every method that takes one accepts any ref
 * previously returned and throws [IllegalArgumentException] for malformed or out-of-range refs.
 *
 * [index] answers all tree and size queries. Field names, field values and thread names are read
 * from the hprof on demand through a Shark heap graph that is opened on the first such request
 * (not in [open]: indexing it costs time and memory on a large dump) and closed by [close].
 */
class Snapshot private constructor(private val hprof: File, val index: HeapIndex, val appPackagePrefixes: List<String>) : AutoCloseable {
    companion object {
        /** Siblings of one class under one parent numbering at least this many collapse into a GROUP row. */
        const val GROUP_MIN = TreeModel.GROUP_MIN
        /** Caps of the details lists. */
        const val MAX_REFERRERS = 100
        const val MAX_ARRAY_ELEMENTS = 100
        const val MAX_ROOTS_REACHING = 10
        /** Longer paths from a root keep this many steps at each end around one elision step (ref null, [PathStep.elided] > 0). */
        const val PATH_END_STEPS = 50
        private const val MAX_STRING_CHARS = 200
        private const val ROOTS_REACHING_VISIT_BUDGET = 2_000_000

        fun open(hprof: File, appPackagePrefixes: List<String>, progress: Progress = Progress { _, _ -> }): Snapshot =
            Snapshot(hprof, IndexCache.loadOrBuild(hprof, progress), appPackagePrefixes)
    }

    private val tree = TreeModel(index, appPackagePrefixes)
    private var graph: CloseableHeapGraph? = null
    private var classStats: List<ClassStat>? = null
    private var heapSummary: HeapSummary? = null
    private val instanceLists = object : LinkedHashMap<String, IntArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>) = size > 4
    }
    private var predOffsets: IntArray? = null
    private var preds: IntArray? = null

    /** Milliseconds the lazy Shark graph took to open, or -1 while it is not open. */
    @Volatile var graphOpenMillis: Long = -1
        private set

    private var closed = false

    /** Every query after [close] fails: a closed snapshot never silently reopens the hprof. */
    private fun checkOpen() = check(!closed) { "snapshot closed" }

    private inline fun <T> whileOpen(block: () -> T): T {
        checkOpen()
        return block()
    }

    @Synchronized
    fun summary(): HeapSummary = whileOpen {
        heapSummary ?: computeSummary().also { heapSummary = it }
    }

    private fun computeSummary(): HeapSummary {
        var native = 0L
        var app = 0L
        var objects = 0
        for (i in 0 until index.objectCount) {
            if (index.idom[i] == Dominators.UNREACHABLE) continue
            objects++
            native += index.nativeEst[i]
            val kind = index.heapKind[i]
            if (kind == HeapKind.APP || kind == HeapKind.UNKNOWN) app += index.shallow[i]
        }
        return HeapSummary(
            reachableBytes = tree.reachableBytes, unreachableBytes = tree.unreachableBytes, nativeEstBytes = native,
            objectCount = objects, classCount = classes().size, appHeapBytes = app,
        )
    }

    /** One page of the rows under [ref] ("root" for the top level). */
    @Synchronized
    fun children(ref: String, offset: Int = 0, limit: Int = 25): TreePage {
        checkOpen()
        require(offset >= 0 && limit >= 0) { "offset and limit must be >= 0 (were $offset, $limit)" }
        return tree.page(tree.parse(ref), offset, limit, labels)
    }

    /** Details of an `obj:` ref, or of the Thread object of a `thr:` ref; other refs name no single object. */
    @Synchronized
    fun details(ref: String): ObjectDetails {
        checkOpen()
        val d = when (val r = tree.parse(ref)) {
            is Ref.Obj -> r.obj
            is Ref.Thread -> r.obj
            else -> throw IllegalArgumentException("Ref \"$ref\" does not name a single object")
        }
        val sharedChild = index.idom[d] == index.superRoot && tree.rootPos(d) < 0
        val (referrers, referrerCount) = referrers(d)
        return ObjectDetails(
            ref = "obj:$d", className = tree.displayName(d), shallowBytes = index.shallow[d].toLong(),
            retainedBytes = index.retained[d], nativeEstBytes = index.retainedWithNative[d] - index.retained[d],
            pathFromRoot = pathFromRoot(d), fields = fields(d), referrers = referrers, referrerCount = referrerCount,
            rootsReaching = if (sharedChild) rootsReaching(d) else emptyList(),
        )
    }

    /**
     * One entry per class name with reachable instances, by retained desc. A class's retained bytes sum
     * the instances that have no instance of the same class among their dominator-tree ancestors.
     */
    @Synchronized
    fun classes(): List<ClassStat> = whileOpen { classStats ?: computeClasses().also { classStats = it } }

    /** Reachable instances of [className] as OBJECT rows, by retained desc. Unknown names give an empty page. */
    @Synchronized
    fun instances(className: String, offset: Int, limit: Int): TreePage {
        checkOpen()
        require(offset >= 0 && limit >= 0) { "offset and limit must be >= 0 (were $offset, $limit)" }
        val list = instanceLists.getOrPut(className) {
            val named = BooleanArray(index.classNames.size) { index.classNames[it] == className } // one name, several loaders
            val buf = IntBuf()
            for (i in 0 until index.objectCount) {
                if (index.idom[i] != Dominators.UNREACHABLE && named[index.classOf[i]]) buf.add(i)
            }
            buf.trimmed().also { sortByKeyDesc(it, index.retained) }
        }
        val from = minOf(offset, list.size)
        val to = minOf(list.size.toLong(), from.toLong() + limit).toInt()
        return TreePage((from until to).map { tree.objectRow(list[it], null, RowKind.OBJECT) }, list.size)
    }

    /**
     * Refs from "root" down to [ref]; each one is among the `children` of the previous one (possibly past
     * the first page). Throws [RevealTooDeepException] when the object is more than [maxDepth] levels
     * deep in the dominator tree (checked before the path is built).
     */
    @Synchronized
    fun revealPath(ref: String, maxDepth: Int = 2000): List<String> = whileOpen {
        require(maxDepth >= 0) { "maxDepth must be >= 0, was $maxDepth" }
        tree.revealPath(tree.parse(ref), maxDepth)
    }

    /** Row offset of [childRef] within the children of [parentRef], or -1 if it is not one of them. */
    @Synchronized
    fun indexInParent(parentRef: String, childRef: String): Int = whileOpen {
        tree.indexInParent(tree.parse(parentRef), tree.parse(childRef))
    }

    @Synchronized
    override fun close() {
        closed = true
        graph?.close()
        graph = null
        graphOpenMillis = -1
    }

    // ---------------------------------------------------------------- findings support

    /** The hprof this snapshot was indexed from. */
    val hprofFile: File get() = hprof

    internal val appRetained: LongArray get() = tree.appRetained

    internal fun displayName(d: Int): String = tree.displayName(d)

    internal fun isAppCode(className: String): Boolean = tree.isAppCode(className)

    /**
     * Runs [block] on the lazily opened Shark graph, serialised with every other query. Keep blocks short:
     * the snapshot is locked meanwhile. Failures reading a damaged hprof become [HprofUnreadableException],
     * other runtime errors [AnalysisBugException].
     */
    @Synchronized
    fun <T> withGraph(block: (shark.HeapGraph) -> T): T = whileOpen { readingHprof { block(graph()) } }

    /** Field or element name of the edge from [parent] to each of [targets] (null if not found). */
    @Synchronized
    internal fun edgeLabels(parent: Int, targets: IntArray): Array<String?> = whileOpen { labels.directLabels(parent, targets) }

    // ---------------------------------------------------------------- hprof access

    private fun graph(): CloseableHeapGraph = graph ?: run {
        val start = System.nanoTime()
        val opened = try {
            hprof.openHeapGraph()
        } catch (e: IOException) {
            throw HprofUnreadableException(hprof, "Heap dump ${hprof.path} could not be opened for details: ${e.message}", e)
        } catch (e: RuntimeException) {
            throw HprofFiles.classify(hprof, e, "opening it for details")
        }
        graphOpenMillis = (System.nanoTime() - start) / 1_000_000
        graph = opened
        opened
    }

    private fun heapObject(d: Int): HeapObject? = readingHprof { graph().findObjectByIdOrNull(index.objectIds[d]) }

    /**
     * Runs Shark reads of object records. I/O errors and runtime errors on a file that fails [HprofFiles.problem]
     * (changed, truncated or corrupt since the index was built) become [HprofUnreadableException]; other runtime
     * errors are bugs ([AnalysisBugException]) and say nothing about the file.
     */
    private inline fun <T> readingHprof(block: () -> T): T = try {
        block()
    } catch (e: HprofUnreadableException) {
        throw e
    } catch (e: IOException) {
        throw HprofUnreadableException(hprof, "Heap dump ${hprof.path} could not be read: ${e.message}", e)
    } catch (e: RuntimeException) {
        throw HprofFiles.classify(hprof, e, "reading it")
    }

    private val labels = object : HeapLabels {
        override fun directLabels(parent: Int, targets: IntArray): Array<String?> = readingHprof { readDirectLabels(parent, targets) }

        private fun readDirectLabels(parent: Int, targets: IntArray): Array<String?> {
            val result = arrayOfNulls<String>(targets.size)
            val wanted = HashMap<Long, Int>()
            for (k in targets.indices) wanted[index.objectIds[targets[k]]] = k
            fun found(id: Long, label: String) {
                val k = wanted.remove(id) ?: return
                result[k] = label
            }
            when (val o = heapObject(parent)) {
                is HeapClass -> {
                    for (f in o.readStaticFields()) {
                        if (wanted.isEmpty()) break
                        f.value.asNonNullObjectId?.let { found(it, f.name) }
                    }
                    found(o.readRecord().classLoaderId, "<class loader>")
                }
                is HeapInstance -> for (f in o.readFields()) {
                    if (wanted.isEmpty()) break
                    f.value.asNonNullObjectId?.let { found(it, f.name) }
                }
                is HeapObjectArray -> {
                    val ids = o.readRecord().elementIds
                    for (i in ids.indices) {
                        if (wanted.isEmpty()) break
                        found(ids[i], "[$i]")
                    }
                }
                is HeapPrimitiveArray, null -> {}
            }
            return result
        }

        override fun threadName(thread: Int): String? = try {
            (heapObject(thread) as? HeapInstance)?.get("java.lang.Thread", "name")?.value?.readAsJavaString()
        } catch (e: RuntimeException) {
            null // unexpected String layout: fall back to Thread@<id>
        }
    }

    private fun label(from: Int, to: Int): String? = labels.directLabels(from, intArrayOf(to))[0]

    // ---------------------------------------------------------------- details

    /** Shortest path from a GC root, root first; each step's edgeLabel names the edge to the next step. */
    private fun pathFromRoot(d: Int): List<PathStep> {
        if (index.idom[d] == Dominators.UNREACHABLE) return emptyList()
        val chain = IntBuf()
        var x = d
        while (x != index.superRoot) { chain.add(x); x = index.bfsParent[x] }
        val nodes = IntArray(chain.size) { chain[chain.size - 1 - it] }
        fun step(k: Int) = PathStep("obj:${nodes[k]}", tree.displayName(nodes[k]), if (k + 1 < nodes.size) label(nodes[k], nodes[k + 1]) else null)
        if (nodes.size <= 2 * PATH_END_STEPS + 1) return nodes.indices.map { step(it) }
        val omitted = nodes.size - 2 * PATH_END_STEPS
        return (0 until PATH_END_STEPS).map { step(it) } +
            PathStep(null, String.format(java.util.Locale.US, "… %,d more steps …", omitted), null, elided = omitted) +
            (nodes.size - PATH_END_STEPS until nodes.size).map { step(it) }
    }

    /** Incoming strong references (distinct referrers), lazily building the predecessor CSR. */
    private fun referrers(d: Int): Pair<List<PathStep>, Int> {
        val offsets = predOffsets ?: buildPredecessors()
        val all = preds!!
        val sorted = all.copyOfRange(offsets[d], offsets[d + 1]).also { it.sort() }
        val shown = ArrayList<PathStep>()
        var distinct = 0
        for (k in sorted.indices) {
            if (k > 0 && sorted[k] == sorted[k - 1]) continue // one referrer, several fields
            distinct++
            val r = sorted[k]
            if (shown.size < MAX_REFERRERS) shown += PathStep("obj:$r", tree.displayName(r), label(r, d))
        }
        return shown to distinct
    }

    private fun buildPredecessors(): IntArray {
        val g = index.graph
        val n = g.nodeCount
        val offsets = IntArray(n + 1)
        for (t in g.edgeTargets) offsets[t + 1]++
        for (i in 0 until n) offsets[i + 1] += offsets[i]
        val cursor = offsets.copyOf(n)
        val out = IntArray(g.edgeTargets.size)
        for (u in 0 until n) for (e in g.edgeOffsets[u] until g.edgeOffsets[u + 1]) out[cursor[g.edgeTargets[e]]++] = u
        predOffsets = offsets
        preds = out
        return offsets
    }

    /** Up to [MAX_ROOTS_REACHING] distinct GC roots that reach [d], nearest first (reverse BFS over referrers). */
    private fun rootsReaching(d: Int): List<PathStep> {
        val offsets = predOffsets ?: buildPredecessors()
        val all = preds!!
        val visited = BitSet(index.objectCount)
        val queue = IntBuf()
        visited.set(d); queue.add(d)
        val found = ArrayList<PathStep>()
        var head = 0
        while (head < queue.size && found.size < MAX_ROOTS_REACHING && head < ROOTS_REACHING_VISIT_BUDGET) {
            val x = queue[head++]
            for (k in offsets[x] until offsets[x + 1]) {
                val p = all[k]
                if (visited.get(p) || index.idom[p] == Dominators.UNREACHABLE) continue
                visited.set(p)
                if (tree.rootPos(p) >= 0) {
                    found += PathStep("obj:$p", tree.displayName(p), label(p, x))
                    if (found.size == MAX_ROOTS_REACHING) break
                }
                queue.add(p)
            }
        }
        return found
    }

    private fun fields(d: Int): List<FieldValue> = readingHprof { readFields(d) }

    private fun readFields(d: Int): List<FieldValue> = when (val o = heapObject(d)) {
        is HeapInstance -> o.readFields().map { fieldValue(it.name, it.value) }.toList()
        is HeapClass -> o.readStaticFields().map { fieldValue(it.name, it.value) }.toList()
        is HeapObjectArray -> {
            val ids = o.readRecord().elementIds
            listOf(FieldValue("length", "int", "${ids.size}", null)) +
                (0 until minOf(ids.size, MAX_ARRAY_ELEMENTS)).map { referenceValue("[$it]", ids[it]) }
        }
        is HeapPrimitiveArray -> primitiveElements(o)
        null -> emptyList()
    }

    private fun primitiveElements(o: HeapPrimitiveArray): List<FieldValue> {
        val type = o.primitiveType.name.lowercase()
        val values: List<String> = when (val r = o.readRecord()) {
            is BooleanArrayDump -> r.array.take(MAX_ARRAY_ELEMENTS).map { it.toString() }
            is CharArrayDump -> r.array.take(MAX_ARRAY_ELEMENTS).map { "'$it'" }
            is FloatArrayDump -> r.array.take(MAX_ARRAY_ELEMENTS).map { it.toString() }
            is DoubleArrayDump -> r.array.take(MAX_ARRAY_ELEMENTS).map { it.toString() }
            is ByteArrayDump -> r.array.take(MAX_ARRAY_ELEMENTS).map { it.toString() }
            is ShortArrayDump -> r.array.take(MAX_ARRAY_ELEMENTS).map { it.toString() }
            is IntArrayDump -> r.array.take(MAX_ARRAY_ELEMENTS).map { it.toString() }
            is LongArrayDump -> r.array.take(MAX_ARRAY_ELEMENTS).map { it.toString() }
        }
        return listOf(FieldValue("length", "int", "${o.readRecord().size}", null)) +
            values.mapIndexed { i, v -> FieldValue("[$i]", type, v, null) }
    }

    private fun fieldValue(name: String, value: HeapValue): FieldValue = when (val h = value.holder) {
        is ValueHolder.ReferenceHolder -> referenceValue(name, h.value)
        is ValueHolder.BooleanHolder -> FieldValue(name, "boolean", "${h.value}", null)
        is ValueHolder.CharHolder -> FieldValue(name, "char", "'${h.value}'", null)
        is ValueHolder.FloatHolder -> FieldValue(name, "float", "${h.value}", null)
        is ValueHolder.DoubleHolder -> FieldValue(name, "double", "${h.value}", null)
        is ValueHolder.ByteHolder -> FieldValue(name, "byte", "${h.value}", null)
        is ValueHolder.ShortHolder -> FieldValue(name, "short", "${h.value}", null)
        is ValueHolder.IntHolder -> FieldValue(name, "int", "${h.value}", null)
        is ValueHolder.LongHolder -> FieldValue(name, "long", "${h.value}", null)
    }

    /** A reference: type is the referenced object's class ("Object" for null), Strings show their text. */
    private fun referenceValue(name: String, id: Long): FieldValue {
        if (id == 0L) return FieldValue(name, "Object", "null", null)
        val d = index.indexOfObjectId(id)
        val hex = "0x${java.lang.Long.toHexString(id)}"
        if (d < 0) return FieldValue(name, "Object", "<missing object $hex>", null)
        val type = tree.displayName(d)
        val text = if (type == "java.lang.String") stringValue(d) else null
        return FieldValue(name, type, text ?: "$type@$hex", "obj:$d")
    }

    private fun stringValue(d: Int): String? {
        val s = try {
            (heapObject(d) as? HeapInstance)?.readAsJavaString()
        } catch (e: RuntimeException) {
            null
        } ?: return null
        return if (s.length <= MAX_STRING_CHARS) "\"$s\"" else "\"${s.take(MAX_STRING_CHARS)}…\" (${s.length} chars)"
    }

    // ---------------------------------------------------------------- classes

    private fun computeClasses(): List<ClassStat> {
        // Classes are merged by name (one name can have several class objects, e.g. per class loader).
        val nameIds = HashMap<String, Int>()
        val names = ArrayList<String>()
        val nameOf = IntArray(index.classNames.size) { t -> nameIds.getOrPut(index.classNames[t]) { names += index.classNames[t]; names.size - 1 } }
        val count = IntArray(names.size)
        val shallow = LongArray(names.size)
        val retained = LongArray(names.size)
        val appCount = IntArray(names.size)
        val appShallow = LongArray(names.size)
        val appRetained = LongArray(names.size)
        val active = IntArray(names.size) // instances of the name on the current dominator-tree path

        // Iterative DFS over the dominator tree from the super-root.
        val off = index.domChildOffsets
        val children = index.domChildren
        var stackNode = IntArray(1024)
        var stackCursor = IntArray(1024)
        stackNode[0] = index.superRoot
        stackCursor[0] = off[index.superRoot]
        var sp = 1
        while (sp > 0) {
            val top = sp - 1
            val u = stackNode[top]
            if (stackCursor[top] < off[u + 1]) {
                val v = children[stackCursor[top]++]
                val c = nameOf[index.classOf[v]]
                count[c]++
                shallow[c] += index.shallow[v]
                if (active[c] == 0) retained[c] += index.retained[v]
                val kind = index.heapKind[v]
                if (kind == HeapKind.APP || kind == HeapKind.UNKNOWN) { appCount[c]++; appShallow[c] += index.shallow[v] }
                if (active[c] == 0) appRetained[c] += tree.appRetained[v]
                active[c]++
                if (sp == stackNode.size) {
                    stackNode = stackNode.copyOf(sp * 2)
                    stackCursor = stackCursor.copyOf(sp * 2)
                }
                stackNode[sp] = v
                stackCursor[sp] = off[v]
                sp++
            } else {
                if (u != index.superRoot) active[nameOf[index.classOf[u]]]--
                sp--
            }
        }
        return names.indices.filter { count[it] > 0 }
            .map { ClassStat(names[it], count[it], shallow[it], retained[it], tree.isAppCode(names[it]), appCount[it], appShallow[it], appRetained[it]) }
            .sortedWith(compareByDescending<ClassStat> { it.retainedBytes }.thenBy { it.className })
    }
}

/** [Snapshot.revealPath] refused an object whose dominator-tree [depth] exceeds the requested maximum. */
class RevealTooDeepException(val depth: Int) :
    RuntimeException("Object is $depth levels deep in the dominator tree; too deep to reveal")
