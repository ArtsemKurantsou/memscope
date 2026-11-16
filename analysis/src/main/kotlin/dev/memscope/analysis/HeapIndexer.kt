package dev.memscope.analysis

import shark.HprofHeader
import shark.HprofRecordReader
import shark.HprofRecordTag
import shark.HprofRecordTag.*
import shark.OnHprofRecordTagListener
import shark.StreamingHprofReader
import shark.ValueHolder
import java.io.File
import java.io.IOException

/**
 * Builds a [HeapIndex] from an hprof file (Android or JVM) with two sequential passes of Shark's
 * [StreamingHprofReader] over the raw records:
 *
 * 1. collect strings, class definitions, GC roots and every object id;
 *    ids are sorted into the dense numbering.
 * 2. per object: class, shallow size, heap kind and outgoing strong references (CSR).
 *
 * Per-object data lives only in primitive arrays; per-class data uses small objects (one per class).
 * Then dominators, retained sizes, dominator children and BFS parents come from [Dominators].
 */
object HeapIndexer {
    /** Bump whenever the meaning or layout of the index changes; part of the cache key. */
    const val FORMAT_VERSION = 1

    fun build(hprof: File, progress: Progress = Progress { _, _ -> }): HeapIndex {
        if (!hprof.isFile) throw HprofUnreadableException(hprof, "Heap dump ${hprof.path} does not exist", null)
        if (hprof.length() == 0L) throw HprofUnreadableException(hprof, "Heap dump ${hprof.path} is empty (0 bytes)", null)
        val parsed = try {
            HprofParser(hprof, progress).parse()
        } catch (e: HprofUnreadableException) {
            throw e
        } catch (e: IOException) {
            throw unreadable(hprof, e)
        } catch (e: RuntimeException) {
            // Shark reports malformed records with IllegalStateException / IllegalArgumentException etc., but so
            // does a bug of ours: only a file that fails the cheap checks (header, final HEAP_DUMP_END) is unreadable.
            throw HprofFiles.classify(hprof, e, "while indexing")
        }
        return finish(parsed, progress)
    }

    private fun unreadable(hprof: File, e: Exception) = HprofUnreadableException(
        hprof, "Heap dump ${hprof.path} is truncated or corrupt: ${e.javaClass.simpleName}: ${e.message}", e,
    )

    private fun finish(p: ParsedHeap, progress: Progress): HeapIndex {
        val n = p.graph.nodeCount
        progress.report("dominators", 0.0)
        val idom = Dominators.immediateDominators(p.graph)
        progress.report("dominators", 1.0)
        progress.report("retained", 0.0)
        val retained = Dominators.retainedSizes(idom, p.shallow, n)
        val withNative = IntArray(n)
        for (i in 0 until n) {
            withNative[i] = (p.shallow[i].toLong() + p.nativeEst[i]).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val retainedWithNative = Dominators.retainedSizes(idom, withNative, n)
        progress.report("retained", 1.0)
        progress.report("tree", 0.0)
        val (childOffsets, children) = Dominators.childrenCsr(idom, retained, n)
        val bfsParent = Dominators.bfsParents(p.graph)
        progress.report("tree", 1.0)
        return HeapIndex(
            objectIds = p.objectIds, classOf = p.classOf, classNames = p.classNames,
            classObjectIndex = p.classObjectIndex, shallow = p.shallow, nativeEst = p.nativeEst,
            heapKind = p.heapKind, graph = p.graph, rootKinds = p.rootKinds, rootThreadObj = p.rootThreadObj,
            idom = idom, retained = retained, retainedWithNative = retainedWithNative,
            domChildOffsets = childOffsets, domChildren = children, bfsParent = bfsParent,
        )
    }
}

internal class ParsedHeap(
    val objectIds: LongArray,
    val classOf: IntArray,
    val classNames: Array<String>,
    val classObjectIndex: IntArray,
    val shallow: IntArray,
    val nativeEst: IntArray,
    val heapKind: ByteArray,
    val graph: Graph,
    val rootKinds: ByteArray,
    val rootThreadObj: IntArray,
)

/** One class dump, as needed for instance layouts. One per class, never per object. */
private class ClassDef(
    val id: Long,
    val superId: Long,
    val instanceSize: Int,
    val fieldTypes: IntArray,
    val fieldNameIds: LongArray,
    val staticBytes: Int,
)

private class HprofParser(private val file: File, private val progress: Progress) {
    private val header = HprofHeader.parseHeaderOf(file)
    private val idSize = header.identifierByteSize
    private val fileLength = file.length().toDouble()

    // Pass 1 results.
    private val strings = HashMap<Long, String>()
    private val classNameIds = HashMap<Long, Long>()
    private val classes = ArrayList<ClassDef>()
    private val rawRootIds = LongBuf()
    private val rawRootKinds = IntBuf()
    private val rawRootThreadSerials = IntBuf()
    private val threadBySerial = HashMap<Int, Long>()
    private var recordCount = 0
    /** File order is strictly ascending id order: record k is dense index k. */
    private var fileOrderIsIdOrder = false

    // Class table and layouts (built between the passes).
    private lateinit var ids: IdIndex
    private val classIdx = LongIntMap()
    private val tableNames = ArrayList<String>()
    private val tableObjectIds = LongBuf() // class object id per table entry, 0 for synthetic
    private lateinit var fieldBytes: IntArray
    private lateinit var refOffsets: Array<IntArray>
    private lateinit var special: ByteArray
    private val primitiveClass = IntArray(12) { -1 }
    private var javaLangClass = -1
    private var unknownClass = -1
    private var cleanerReferentOff = -1; private var cleanerThunkOff = -1
    private var thunkRegistryOff = -1; private var registrySizeOff = -1
    private var bitmapWidthOff = -1; private var bitmapHeightOff = -1

    fun parse(): ParsedHeap {
        val objectIds = passOne()
        buildClassTable()
        return passTwo(objectIds)
    }

    private fun stream(tags: Set<HprofRecordTag>, listener: OnHprofRecordTagListener) {
        StreamingHprofReader.readerFor(file, header).readRecords(tags, listener)
    }

    private fun reportParse(pass: Int, reader: HprofRecordReader) {
        progress.report("parse", (pass + reader.bytesRead / fileLength) / 2.0)
    }

    // ---------------------------------------------------------------- pass 1

    private fun passOne(): LongArray {
        val objIds = LongBuf(1 shl 20)
        var sorted = true
        var last = Long.MIN_VALUE
        var sawEnd = false
        fun addObject(id: Long, reader: HprofRecordReader) {
            if (id <= last) sorted = false
            last = id
            objIds.add(id)
            if (objIds.size and 0xFFFF == 0) reportParse(0, reader)
        }
        progress.report("parse", 0.0)
        val tags = OBJECT_TAGS + ROOT_TAGS + setOf(STRING_IN_UTF8, LOAD_CLASS, HEAP_DUMP_END)
        stream(tags) { tag, length, r ->
            when (tag) {
                STRING_IN_UTF8 -> { val s = r.readStringRecord(length); strings[s.id] = s.string }
                LOAD_CLASS -> { val l = r.readLoadClassRecord(); classNameIds[l.id] = l.classNameStringId }
                CLASS_DUMP -> {
                    val c = r.readClassDumpRecord()
                    var staticBytes = 0
                    for (s in c.staticFields) staticBytes += typeSize(s.type)
                    classes += ClassDef(
                        c.id, c.superclassId, c.instanceSize,
                        IntArray(c.fields.size) { c.fields[it].type },
                        LongArray(c.fields.size) { c.fields[it].nameStringId },
                        staticBytes,
                    )
                    addObject(c.id, r)
                }
                INSTANCE_DUMP -> {
                    val id = r.readId(); r.skip(4); r.skipId()
                    r.skip(r.readInt().toLong())
                    addObject(id, r)
                }
                OBJECT_ARRAY_DUMP -> {
                    val id = r.readId(); r.skip(4)
                    val len = r.readInt(); r.skipId()
                    r.skip(len.toLong() * idSize)
                    addObject(id, r)
                }
                PRIMITIVE_ARRAY_DUMP -> {
                    val id = r.readId(); r.skip(4)
                    val len = r.readInt(); val type = r.readUnsignedByte()
                    r.skip(len.toLong() * typeSize(type))
                    addObject(id, r)
                }
                HEAP_DUMP_END -> sawEnd = true
                else -> readRoot(tag, r)
            }
        }
        if (!sawEnd) throw HprofUnreadableException(
            file, "Heap dump ${file.path} is truncated: it ends before the HEAP_DUMP_END record", null,
        )
        recordCount = objIds.size
        if (recordCount == 0) throw HprofUnreadableException(file, "Heap dump ${file.path} contains no objects", null)
        val all = objIds.trimmed()
        fileOrderIsIdOrder = sorted
        if (sorted) return all
        java.util.Arrays.parallelSort(all)
        var unique = 1
        for (i in 1 until all.size) if (all[i] != all[unique - 1]) all[unique++] = all[i]
        return if (unique == all.size) all else all.copyOf(unique)
    }

    private fun readRoot(tag: HprofRecordTag, r: HprofRecordReader) {
        var serial = Int.MIN_VALUE
        val (id, kind) = when (tag) {
            ROOT_STICKY_CLASS -> r.readStickyClassGcRootRecord().id to RootKind.CLASS
            ROOT_THREAD_OBJECT -> {
                val t = r.readThreadObjectGcRootRecord()
                threadBySerial[t.threadSerialNumber] = t.id
                t.id to RootKind.THREAD
            }
            ROOT_JAVA_FRAME -> r.readJavaFrameGcRootRecord().let { serial = it.threadSerialNumber; it.id to RootKind.JAVA_FRAME }
            ROOT_JNI_LOCAL -> r.readJniLocalGcRootRecord().let { serial = it.threadSerialNumber; it.id to RootKind.JNI_LOCAL }
            ROOT_JNI_GLOBAL -> r.readJniGlobalGcRootRecord().id to RootKind.JNI_GLOBAL
            ROOT_MONITOR_USED -> r.readMonitorUsedGcRootRecord().id to RootKind.MONITOR
            ROOT_JNI_MONITOR -> r.readJniMonitorGcRootRecord().id to RootKind.MONITOR
            ROOT_UNKNOWN -> r.readUnknownGcRootRecord().id to RootKind.OTHER
            ROOT_NATIVE_STACK -> r.readNativeStackGcRootRecord().id to RootKind.OTHER
            ROOT_THREAD_BLOCK -> r.readThreadBlockGcRootRecord().id to RootKind.OTHER
            ROOT_INTERNED_STRING -> r.readInternedStringGcRootRecord().id to RootKind.OTHER
            ROOT_FINALIZING -> r.readFinalizingGcRootRecord().id to RootKind.OTHER
            ROOT_DEBUGGER -> r.readDebuggerGcRootRecord().id to RootKind.OTHER
            ROOT_REFERENCE_CLEANUP -> r.readReferenceCleanupGcRootRecord().id to RootKind.OTHER
            ROOT_VM_INTERNAL -> r.readVmInternalGcRootRecord().id to RootKind.OTHER
            else -> error("unexpected record tag $tag")
        }
        if (id == 0L) return
        rawRootIds.add(id); rawRootKinds.add(kind.ordinal); rawRootThreadSerials.add(serial)
    }

    // ---------------------------------------------------------------- class table

    private fun className(classId: Long): String {
        val nameId = classNameIds[classId] ?: return "<unknown class 0x${java.lang.Long.toHexString(classId)}>"
        return (strings[nameId] ?: "<unknown class 0x${java.lang.Long.toHexString(classId)}>").replace('/', '.')
    }

    private fun buildClassTable() {
        for ((i, c) in classes.withIndex()) {
            classIdx.put(c.id, i)
            tableNames += className(c.id)
            tableObjectIds.add(c.id)
        }
        val count = classes.size
        fieldBytes = IntArray(count)
        special = ByteArray(count)
        val noRefs = IntArray(0)
        refOffsets = Array(count) { noRefs }
        val refs = IntBuf()
        for (i in 0 until count) {
            refs.clear()
            val kind = when (tableNames[i]) {
                NativeSizes.CLEANER -> SPECIAL_CLEANER
                NativeSizes.THUNK -> SPECIAL_THUNK
                NativeSizes.REGISTRY -> SPECIAL_REGISTRY
                NativeSizes.BITMAP -> SPECIAL_BITMAP
                else -> 0
            }
            special[i] = kind.toByte()
            var offset = 0
            var c = i
            var depth = 0
            while (c >= 0 && depth++ <= count) {
                val def = classes[c]
                val isReference = tableNames[c] == "java.lang.ref.Reference"
                for (f in def.fieldTypes.indices) {
                    val type = def.fieldTypes[f]
                    val name = if (kind != 0 || isReference) strings[def.fieldNameIds[f]] else null
                    if (type == TYPE_OBJECT) {
                        // Reference.referent is weak/soft/phantom/finalizer: not a strong edge.
                        if (!(isReference && name == "referent")) refs.add(offset)
                    }
                    if (kind != 0 && name != null) recordSpecialOffset(kind, name, type, offset)
                    offset += typeSize(type)
                }
                c = if (def.superId == 0L) -1 else classIdx.get(def.superId)
            }
            fieldBytes[i] = offset
            if (refs.size > 0) refOffsets[i] = refs.trimmed()
        }
        javaLangClass = tableNames.indexOf("java.lang.Class")
        for ((type, name) in PRIMITIVE_ARRAY_NAMES) {
            var idx = tableNames.indexOf(name)
            if (idx < 0) idx = addSyntheticClass(name)
            primitiveClass[type] = idx
        }
        if (javaLangClass < 0) javaLangClass = addSyntheticClass("java.lang.Class")
    }

    private fun recordSpecialOffset(kind: Int, name: String, type: Int, offset: Int) {
        // First match wins: fields of the most derived class come first in the instance layout.
        when (kind) {
            SPECIAL_CLEANER -> {
                if (name == "referent" && type == TYPE_OBJECT && cleanerReferentOff < 0) cleanerReferentOff = offset
                if (name == "thunk" && type == TYPE_OBJECT && cleanerThunkOff < 0) cleanerThunkOff = offset
            }
            SPECIAL_THUNK -> if (name == "this\$0" && type == TYPE_OBJECT && thunkRegistryOff < 0) thunkRegistryOff = offset
            SPECIAL_REGISTRY -> if (name == "size" && type == TYPE_LONG && registrySizeOff < 0) registrySizeOff = offset
            SPECIAL_BITMAP -> {
                if (name == "mWidth" && type == TYPE_INT && bitmapWidthOff < 0) bitmapWidthOff = offset
                if (name == "mHeight" && type == TYPE_INT && bitmapHeightOff < 0) bitmapHeightOff = offset
            }
        }
    }

    private fun addSyntheticClass(name: String): Int {
        tableNames += name
        tableObjectIds.add(0L)
        return tableNames.size - 1
    }

    private fun classIndexOf(classId: Long): Int {
        val c = classIdx.get(classId)
        if (c >= 0) return c
        if (unknownClass < 0) unknownClass = addSyntheticClass("<unknown class>")
        return unknownClass
    }

    // ---------------------------------------------------------------- pass 2

    private fun passTwo(objectIds: LongArray): ParsedHeap {
        ids = IdIndex(objectIds)
        val n = objectIds.size
        val identity = fileOrderIsIdOrder
        val classOf = IntArray(n)
        val shallow = IntArray(n)
        val heapKind = ByteArray(n) { HeapKind.UNKNOWN }
        val recordOffsets = IntArray(recordCount + 1)
        val recordDense = if (identity) null else IntArray(recordCount)
        val targets = IntBuf(maxOf(16, n * 2))
        val natives = NativeSizes()
        var record = 0
        var currentHeap = HeapKind.UNKNOWN
        var fieldBuf = ByteArray(256)

        fun edge(targetId: Long) {
            if (targetId == 0L) return
            val t = ids.indexOf(targetId)
            if (t >= 0) targets.add(t)
        }

        fun begin(id: Long, reader: HprofRecordReader): Int {
            if (record >= recordCount) throw IllegalStateException("object count differs between passes (file changed?)")
            val d = ids.indexOf(id)
            check(d >= 0) { "object 0x${java.lang.Long.toHexString(id)} missing from pass 1 (file changed?)" }
            recordOffsets[record] = targets.size
            recordDense?.set(record, d)
            record++
            heapKind[d] = currentHeap
            if (record and 0xFFFF == 0) reportParse(1, reader)
            return d
        }

        stream(OBJECT_TAGS + HEAP_DUMP_INFO) { tag, _, r ->
            when (tag) {
                HEAP_DUMP_INFO -> {
                    val info = r.readHeapDumpInfoRecord()
                    currentHeap = when (strings[info.heapNameStringId]) {
                        "app" -> HeapKind.APP
                        "zygote" -> HeapKind.ZYGOTE
                        "image" -> HeapKind.IMAGE
                        else -> HeapKind.UNKNOWN
                    }
                }
                CLASS_DUMP -> {
                    val c = r.readClassDumpRecord()
                    val d = begin(c.id, r)
                    val table = classIdx.get(c.id)
                    classOf[d] = javaLangClass
                    val classInstance = if (javaLangClass < classes.size) classes[javaLangClass].instanceSize else 0
                    shallow[d] = classInstance + (if (table >= 0) classes[table].staticBytes else 0)
                    for (s in c.staticFields) {
                        val v = s.value
                        if (v is ValueHolder.ReferenceHolder) edge(v.value)
                    }
                    edge(c.classLoaderId)
                }
                INSTANCE_DUMP -> {
                    val id = r.readId(); r.skip(4)
                    val c = classIndexOf(r.readId())
                    val len = r.readInt()
                    val d = begin(id, r)
                    classOf[d] = c
                    val known = c < classes.size
                    shallow[d] = if (known) classes[c].instanceSize else len
                    if (known && special[c].toInt() == 0 && len == fieldBytes[c]) {
                        var pos = 0
                        for (off in refOffsets[c]) {
                            r.skip(off - pos)
                            edge(r.readId())
                            pos = off + idSize
                        }
                        r.skip(len - pos)
                    } else {
                        if (fieldBuf.size < len) fieldBuf = ByteArray(maxOf(len, fieldBuf.size * 2))
                        readFully(r, fieldBuf, len)
                        if (known) {
                            for (off in refOffsets[c]) if (off + idSize <= len) edge(idAt(fieldBuf, off))
                            if (special[c].toInt() != 0) recordNative(special[c].toInt(), id, d, fieldBuf, len, natives)
                        }
                    }
                }
                OBJECT_ARRAY_DUMP -> {
                    val id = r.readId(); r.skip(4)
                    val len = r.readInt()
                    val c = classIndexOf(r.readId())
                    val d = begin(id, r)
                    classOf[d] = c
                    shallow[d] = saturate(len.toLong() * idSize)
                    for (k in 0 until len) edge(r.readId())
                }
                PRIMITIVE_ARRAY_DUMP -> {
                    val id = r.readId(); r.skip(4)
                    val len = r.readInt(); val type = r.readUnsignedByte()
                    val bytes = len.toLong() * typeSize(type)
                    val d = begin(id, r)
                    classOf[d] = if (type in primitiveClass.indices && primitiveClass[type] >= 0) primitiveClass[type] else classIndexOf(0L)
                    shallow[d] = saturate(bytes)
                    r.skip(bytes)
                }
                else -> error("unexpected record tag $tag")
            }
        }
        check(record == recordCount) { "object count differs between passes (file changed?)" }
        recordOffsets[recordCount] = targets.size
        progress.report("parse", 1.0)

        val edgeTargets = targets.trimmed()
        val (offsets, finalTargets) =
            if (recordDense == null) recordOffsets to edgeTargets
            else regroupByDense(n, recordOffsets, recordDense, edgeTargets)

        val (roots, rootKinds, rootThreads) = resolveRoots(n)
        val nativeEst = natives.compute(n) { ids.indexOf(it) }
        val classObjectIndex = IntArray(tableNames.size) { t ->
            val id = tableObjectIds[t]
            if (id == 0L) -1 else ids.indexOf(id)
        }
        return ParsedHeap(
            objectIds = objectIds, classOf = classOf, classNames = tableNames.toTypedArray(),
            classObjectIndex = classObjectIndex, shallow = shallow, nativeEst = nativeEst, heapKind = heapKind,
            graph = Graph(n, offsets, finalTargets, roots), rootKinds = rootKinds, rootThreadObj = rootThreads,
        )
    }

    private fun recordNative(kind: Int, id: Long, dense: Int, buf: ByteArray, len: Int, natives: NativeSizes) {
        fun ref(off: Int) = if (off >= 0 && off + idSize <= len) idAt(buf, off) else 0L
        when (kind) {
            SPECIAL_CLEANER -> natives.addCleaner(ref(cleanerReferentOff), ref(cleanerThunkOff))
            SPECIAL_THUNK -> natives.addThunk(id, ref(thunkRegistryOff))
            SPECIAL_REGISTRY -> if (registrySizeOff >= 0 && registrySizeOff + 8 <= len) {
                natives.addRegistry(id, longAt(buf, registrySizeOff))
            }
            SPECIAL_BITMAP -> if (bitmapWidthOff >= 0 && bitmapHeightOff >= 0 &&
                bitmapWidthOff + 4 <= len && bitmapHeightOff + 4 <= len
            ) {
                natives.addBitmap(dense, intAt(buf, bitmapWidthOff), intAt(buf, bitmapHeightOff))
            }
        }
    }

    /** Builds the dense CSR when file order differs from id order; duplicates keep the first record. */
    private fun regroupByDense(
        n: Int, recordOffsets: IntArray, recordDense: IntArray, edgeTargets: IntArray,
    ): Pair<IntArray, IntArray> {
        val firstRecord = IntArray(n) { -1 }
        for (k in recordDense.indices) if (firstRecord[recordDense[k]] < 0) firstRecord[recordDense[k]] = k
        val offsets = IntArray(n + 1)
        for (d in 0 until n) {
            val k = firstRecord[d]
            offsets[d + 1] = offsets[d] + if (k < 0) 0 else recordOffsets[k + 1] - recordOffsets[k]
        }
        val out = IntArray(offsets[n])
        for (d in 0 until n) {
            val k = firstRecord[d]
            if (k >= 0) System.arraycopy(edgeTargets, recordOffsets[k], out, offsets[d], recordOffsets[k + 1] - recordOffsets[k])
        }
        return offsets to out
    }

    /**
     * One root per object, sorted by dense index; all class objects are CLASS roots (ART keeps classes
     * alive from native class tables, so there is no heap path to them). Priority when an object is
     * rooted several ways: CLASS, THREAD, JAVA_FRAME / JNI_LOCAL, JNI_GLOBAL, MONITOR, OTHER.
     */
    private fun resolveRoots(n: Int): Triple<IntArray, ByteArray, IntArray> {
        val raw = rawRootIds.size
        val total = raw + classes.size
        // Pack (dense, rank, entry) so a primitive sort groups by object and puts the best kind first.
        val packed = LongArray(total)
        var count = 0
        for (i in 0 until total) {
            val id: Long
            val kind: Int
            if (i < raw) { id = rawRootIds[i]; kind = rawRootKinds[i] } else { id = classes[i - raw].id; kind = RootKind.CLASS.ordinal }
            val d = ids.indexOf(id)
            if (d < 0) continue
            packed[count++] = (d.toLong() shl 32) or (RANK[kind].toLong() shl 28) or i.toLong()
        }
        require(total < (1 shl 28)) { "too many GC roots: $total" }
        java.util.Arrays.sort(packed, 0, count)
        val roots = IntBuf(); val kinds = IntBuf(); val threads = IntBuf()
        var lastDense = -1
        for (j in 0 until count) {
            val d = (packed[j] ushr 32).toInt()
            if (d == lastDense) continue
            lastDense = d
            val i = (packed[j] and 0x0FFFFFFF).toInt()
            val kind = if (i < raw) rawRootKinds[i] else RootKind.CLASS.ordinal
            var thread = -1
            if (i < raw && (kind == RootKind.JAVA_FRAME.ordinal || kind == RootKind.JNI_LOCAL.ordinal)) {
                val threadId = threadBySerial[rawRootThreadSerials[i]]
                if (threadId != null) thread = ids.indexOf(threadId)
            }
            roots.add(d); kinds.add(kind); threads.add(thread)
        }
        val kindBytes = ByteArray(kinds.size) { kinds[it].toByte() }
        return Triple(roots.trimmed(), kindBytes, threads.trimmed())
    }

    // ---------------------------------------------------------------- byte helpers

    private fun readFully(r: HprofRecordReader, buf: ByteArray, len: Int) {
        // HprofRecordReader only offers allocating array reads; per-byte reads keep this allocation-free.
        for (k in 0 until len) buf[k] = r.readByte()
    }

    private fun idAt(b: ByteArray, off: Int): Long = when (idSize) {
        4 -> intAt(b, off).toLong()
        8 -> longAt(b, off)
        2 -> ((b[off].toInt() shl 8) or (b[off + 1].toInt() and 0xFF)).toShort().toLong()
        else -> b[off].toLong()
    }

    private fun intAt(b: ByteArray, off: Int): Int =
        (b[off].toInt() shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun longAt(b: ByteArray, off: Int): Long =
        (intAt(b, off).toLong() shl 32) or (intAt(b, off + 4).toLong() and 0xFFFFFFFFL)

    private fun typeSize(type: Int): Int = if (type == TYPE_OBJECT) idSize else PRIMITIVE_SIZES.getOrElse(type) { 0 }

    private fun saturate(v: Long): Int = v.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    companion object {
        const val TYPE_OBJECT = 2
        const val TYPE_INT = 10
        const val TYPE_LONG = 11
        /** Byte size by hprof basic type (index); 0 = invalid. */
        val PRIMITIVE_SIZES = intArrayOf(0, 0, 0, 0, 1, 2, 4, 8, 1, 2, 4, 8)
        val PRIMITIVE_ARRAY_NAMES = listOf(
            4 to "boolean[]", 5 to "char[]", 6 to "float[]", 7 to "double[]",
            8 to "byte[]", 9 to "short[]", 10 to "int[]", 11 to "long[]",
        )
        const val SPECIAL_CLEANER = 1
        const val SPECIAL_THUNK = 2
        const val SPECIAL_REGISTRY = 3
        const val SPECIAL_BITMAP = 4

        /** Root priority by RootKind ordinal (lower wins). */
        val RANK = IntArray(RootKind.values().size).also {
            it[RootKind.CLASS.ordinal] = 0
            it[RootKind.THREAD.ordinal] = 1
            it[RootKind.JAVA_FRAME.ordinal] = 2
            it[RootKind.JNI_LOCAL.ordinal] = 2
            it[RootKind.JNI_GLOBAL.ordinal] = 3
            it[RootKind.MONITOR.ordinal] = 4
            it[RootKind.OTHER.ordinal] = 5
        }

        val OBJECT_TAGS: Set<HprofRecordTag> = setOf(CLASS_DUMP, INSTANCE_DUMP, OBJECT_ARRAY_DUMP, PRIMITIVE_ARRAY_DUMP)

        /** All GC root tags except ROOT_UNREACHABLE (ART marks objects that are NOT reachable with it). */
        val ROOT_TAGS: Set<HprofRecordTag> = setOf(
            ROOT_UNKNOWN, ROOT_JNI_GLOBAL, ROOT_JNI_LOCAL, ROOT_JAVA_FRAME, ROOT_NATIVE_STACK,
            ROOT_STICKY_CLASS, ROOT_THREAD_BLOCK, ROOT_MONITOR_USED, ROOT_THREAD_OBJECT,
            ROOT_INTERNED_STRING, ROOT_FINALIZING, ROOT_DEBUGGER, ROOT_REFERENCE_CLEANUP,
            ROOT_VM_INTERNAL, ROOT_JNI_MONITOR,
        )
    }
}

/**
 * Sorted object ids with a bucket table over the id range, so a lookup is a short binary search
 * inside one bucket instead of ~25 cache-missing probes over the whole array.
 */
internal class IdIndex(private val ids: LongArray) {
    private val min = if (ids.isEmpty()) 0L else ids[0]
    private val max = if (ids.isEmpty()) -1L else ids[ids.size - 1]
    private val shift: Int
    private val buckets: IntArray

    init {
        val range = max - min // unsigned
        var s = 0
        val limit = maxOf(1L, ids.size.toLong()) // about one bucket per object at most
        while (s < 63 && java.lang.Long.compareUnsigned(range ushr s, limit) >= 0) s++
        shift = s
        val count = if (ids.isEmpty()) 1 else ((range ushr s) + 1).toInt()
        buckets = IntArray(count + 1)
        for (id in ids) buckets[((id - min) ushr s).toInt() + 1]++
        for (b in 0 until count) buckets[b + 1] += buckets[b]
    }

    fun indexOf(id: Long): Int {
        if (id < min || id > max) return -1
        val b = ((id - min) ushr shift).toInt()
        var lo = buckets[b]
        var hi = buckets[b + 1] - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = ids[mid]
            if (v < id) lo = mid + 1 else if (v > id) hi = mid - 1 else return mid
        }
        return -1
    }
}

/** Open-addressing Long -> Int map (non-negative values), for the class table. */
internal class LongIntMap(initialCapacity: Int = 1024) {
    private var keys = LongArray(capacityFor(initialCapacity))
    private var values = IntArray(keys.size) { -1 }
    private var used = BooleanArray(keys.size)
    private var size = 0

    private fun capacityFor(n: Int): Int = Integer.highestOneBit(maxOf(4, n * 2 - 1)) shl 1

    private fun slot(key: Long, mask: Int): Int {
        var h = key * -0x61c8864680b583ebL
        h = h xor (h ushr 32)
        return h.toInt() and mask
    }

    fun put(key: Long, value: Int) {
        if ((size + 1) * 2 > keys.size) grow()
        val mask = keys.size - 1
        var i = slot(key, mask)
        while (used[i] && keys[i] != key) i = (i + 1) and mask
        if (!used[i]) { used[i] = true; keys[i] = key; size++ }
        values[i] = value
    }

    /** Value or -1. */
    fun get(key: Long): Int {
        val mask = keys.size - 1
        var i = slot(key, mask)
        while (used[i]) {
            if (keys[i] == key) return values[i]
            i = (i + 1) and mask
        }
        return -1
    }

    private fun grow() {
        val oldKeys = keys; val oldValues = values; val oldUsed = used
        keys = LongArray(oldKeys.size * 2); values = IntArray(keys.size) { -1 }; used = BooleanArray(keys.size)
        size = 0
        for (i in oldKeys.indices) if (oldUsed[i]) put(oldKeys[i], oldValues[i])
    }
}

/** Growable IntArray. */
internal class IntBuf(initialCapacity: Int = 16) {
    private var a = IntArray(maxOf(1, initialCapacity))
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(grownCapacity(a.size))
        a[size++] = v
    }

    operator fun get(i: Int): Int = a[i]
    fun clear() { size = 0 }
    fun trimmed(): IntArray = if (size == a.size) a else a.copyOf(size)
}

/** Growable LongArray. */
internal class LongBuf(initialCapacity: Int = 16) {
    private var a = LongArray(maxOf(1, initialCapacity))
    var size = 0
        private set

    fun add(v: Long) {
        if (size == a.size) a = a.copyOf(grownCapacity(a.size))
        a[size++] = v
    }

    operator fun get(i: Int): Long = a[i]
    fun trimmed(): LongArray = if (size == a.size) a else a.copyOf(size)
}

/** 1.5x growth (less peak memory than doubling for the big edge buffer), capped below the array limit. */
private fun grownCapacity(current: Int): Int {
    val next = current.toLong() + (current shr 1) + 16
    check(current < Int.MAX_VALUE - 8) { "buffer exceeds the maximum array size" }
    return next.coerceAtMost(Int.MAX_VALUE - 8L).toInt()
}
