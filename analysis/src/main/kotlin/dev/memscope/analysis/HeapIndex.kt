package dev.memscope.analysis

import java.io.File

/** How an object is held as a GC root. Stored in [HeapIndex.rootKinds] as the ordinal. */
enum class RootKind { CLASS, THREAD, JNI_GLOBAL, JNI_LOCAL, JAVA_FRAME, MONITOR, OTHER }

/** Values of [HeapIndex.heapKind]: the ART heap an object lives in, from the hprof HEAP_DUMP_INFO records. */
object HeapKind {
    const val APP: Byte = 0
    const val ZYGOTE: Byte = 1
    const val IMAGE: Byte = 2
    const val UNKNOWN: Byte = 3
}

/**
 * Compact index of one heap dump. Every per-object array is indexed by the dense object index
 * `0 until objectCount`; dense indices follow ascending hprof object id. Class objects, instances,
 * object arrays and primitive arrays are all objects (nodes).
 *
 * Sizes follow Shark's conventions: an instance's shallow size is its class's instance size from the
 * hprof, an array's is `length * element size` (no header), a class object's is the instance size of
 * `java.lang.Class` plus the bytes of its static fields.
 */
class HeapIndex(
    /** Dense index -> hprof object id (as Shark reads it), strictly ascending. */
    val objectIds: LongArray,
    /** Dense index -> class table index (into [classNames]). */
    val classOf: IntArray,
    /** Class table: one entry per class dump in the hprof, plus synthetic entries for missing primitive array classes. */
    val classNames: Array<String>,
    /** Class table index -> dense index of that class's class object, or -1 for synthetic entries. */
    val classObjectIndex: IntArray,
    val shallow: IntArray,
    /** Estimated native bytes owned (NativeAllocationRegistry cleaners, Bitmap fallback); 0 if none. */
    val nativeEst: IntArray,
    /** [HeapKind] per object; all [HeapKind.UNKNOWN] when the dump has no HEAP_DUMP_INFO records. */
    val heapKind: ByteArray,
    /** Strong references only (Reference.referent is not an edge). */
    val graph: Graph,
    /** [RootKind] ordinal, parallel to `graph.roots` (one root entry per object). */
    val rootKinds: ByteArray,
    /** For JAVA_FRAME / JNI_LOCAL roots the dense index of the owning Thread object, else -1. Parallel to `graph.roots`. */
    val rootThreadObj: IntArray,
    /** Immediate dominator per object: 0..objectCount ([superRoot]) or [Dominators.UNREACHABLE]. */
    val idom: IntArray,
    val retained: LongArray,
    /** Retained size computed over `shallow + nativeEst`. */
    val retainedWithNative: LongArray,
    /** Dominator-tree children CSR (size objectCount + 2, super-root at [superRoot]), sorted by retained desc. */
    val domChildOffsets: IntArray,
    val domChildren: IntArray,
    /** Shortest-path parent from the roots: [superRoot] for roots, [Dominators.UNREACHABLE] if unreachable. */
    val bfsParent: IntArray,
) {
    val objectCount: Int get() = graph.nodeCount
    val superRoot: Int get() = graph.nodeCount

    /** Dense index of the object with this hprof id, or -1. */
    fun indexOfObjectId(id: Long): Int {
        val i = java.util.Arrays.binarySearch(objectIds, id)
        return if (i >= 0) i else -1
    }
}

fun interface Progress {
    /** [fraction] is in 0.0..1.0 within [step]. */
    fun report(step: String, fraction: Double)
}

/** The heap dump could not be read: missing, empty, truncated or corrupt. [message] names the file. */
class HprofUnreadableException(val file: File, message: String, cause: Throwable?) : Exception(message, cause)

/**
 * MemScope failed on a heap dump whose file is intact (an index out of bounds, an illegal state...): a bug,
 * never a reason to call the dump unreadable. [cause] is the original error.
 */
class AnalysisBugException(cause: Throwable) : RuntimeException(MESSAGE, cause) {
    companion object {
        const val MESSAGE = "MemScope hit a bug while analysing this snapshot"
        const val HINT = "the snapshot file is intact; please report this with the server's terminal output"
    }
}

/** Cheap checks of an hprof file that tell a damaged file from an analysis bug. */
object HprofFiles {
    private val MAGIC = "JAVA PROFILE ".toByteArray(Charsets.US_ASCII)
    /** HEAP_DUMP_END record: tag 0x2C, 4-byte time, 4-byte length 0. */
    private const val END_TAG = 0x2C
    private const val END_RECORD_BYTES = 9

    /** Why [file] is not an hprof at all (missing, empty, no "JAVA PROFILE" header), or null. */
    fun headerProblem(file: File): String? {
        if (!file.isFile) return "Heap dump ${file.path} is missing"
        if (file.length() == 0L) return "Heap dump ${file.path} is empty (0 bytes)"
        val head = ByteArray(64)
        val n = try {
            java.io.FileInputStream(file).use { input -> input.readNBytes(head, 0, head.size) }
        } catch (e: java.io.IOException) {
            return "Heap dump ${file.path} could not be read: ${e.message}"
        }
        val nul = (0 until n).firstOrNull { head[it] == 0.toByte() }
        val magicOk = n >= MAGIC.size && MAGIC.indices.all { head[it] == MAGIC[it] }
        // header: version string, NUL, 4-byte identifier size, 8-byte timestamp
        if (!magicOk || nul == null || file.length() < nul + 1 + 12) return "Heap dump ${file.path} is not an hprof file (bad header)"
        return null
    }

    /** Why [file] is not a complete hprof ([headerProblem], or it does not end with a HEAP_DUMP_END record), or null. */
    fun problem(file: File): String? {
        headerProblem(file)?.let { return it }
        val tail = ByteArray(END_RECORD_BYTES)
        try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < END_RECORD_BYTES) return "Heap dump ${file.path} is truncated"
                raf.seek(raf.length() - END_RECORD_BYTES)
                raf.readFully(tail)
            }
        } catch (e: java.io.IOException) {
            return "Heap dump ${file.path} could not be read: ${e.message}"
        }
        val ends = tail[0].toInt() and 0xFF == END_TAG && (5 until 9).all { tail[it] == 0.toByte() }
        return if (ends) null else "Heap dump ${file.path} is truncated: it ends before the HEAP_DUMP_END record"
    }

    /**
     * Classifies a runtime error from reading [file]: [HprofUnreadableException] if the file itself is damaged
     * ([problem]), otherwise [AnalysisBugException].
     */
    fun classify(file: File, e: RuntimeException, context: String): Exception {
        if (e is AnalysisBugException) return e
        val problem = problem(file) ?: return AnalysisBugException(e)
        return HprofUnreadableException(file, "$problem ($context: ${e.javaClass.simpleName}: ${e.message})", e)
    }
}
