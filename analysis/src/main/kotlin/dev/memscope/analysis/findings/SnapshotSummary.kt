package dev.memscope.analysis.findings

import dev.memscope.analysis.Dominators
import dev.memscope.analysis.HeapKind
import dev.memscope.analysis.Snapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class ClassSummary(
    val name: String, val isApp: Boolean, val count: Int, val appShallow: Long, val appRetained: Long,
    /** Ref (valid in that snapshot) of the instance with the largest app-retained size. */
    val topRef: String?,
)

/** A class's statics as a GC root ("Static fields / <class>" row). [ref] is the class object's ref. */
@Serializable
data class StaticSummary(val className: String, val retained: Long, val appRetained: Long, val ref: String, val isApp: Boolean = false)

/**
 * What the session rules need of one snapshot, small enough to keep next to the hprof
 * (`<snapshot>.summary.json`) so no snapshot has to be open to compare a session.
 */
@Serializable
data class SnapshotSummary(
    val version: Int = VERSION,
    val reachableBytes: Long,
    val appHeapBytes: Long,
    val classes: List<ClassSummary>,
    val statics: List<StaticSummary>,
) {
    companion object { const val VERSION = 1 }
}

object SnapshotSummaries {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val MAX_STATICS = 300

    fun file(hprof: File): File = File(hprof.path + ".summary.json")

    /** The stored summary, or null if missing, unreadable or from another version. */
    fun read(hprof: File): SnapshotSummary? = try {
        val f = file(hprof)
        if (!f.isFile) null else json.decodeFromString(SnapshotSummary.serializer(), f.readText())
            .takeIf { it.version == SnapshotSummary.VERSION }
    } catch (_: Exception) {
        null
    }

    fun write(hprof: File, summary: SnapshotSummary) {
        val target = file(hprof)
        val tmp = File(target.path + ".tmp")
        tmp.writeText(json.encodeToString(SnapshotSummary.serializer(), summary))
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Reads the sidecar or computes (and stores, best effort) it. */
    fun readOrCompute(s: Snapshot): SnapshotSummary = read(s.hprofFile) ?: compute(s).also { runCatching { write(s.hprofFile, it) } }

    fun compute(s: Snapshot): SnapshotSummary {
        val idx = s.index
        val heap = s.summary()
        // Largest app-retained instance per class table entry.
        val best = IntArray(idx.classNames.size) { -1 }
        val appRet = s.appRetained
        for (i in 0 until idx.objectCount) {
            if (idx.idom[i] == Dominators.UNREACHABLE) continue
            val k = idx.heapKind[i]
            if (k != HeapKind.APP && k != HeapKind.UNKNOWN) continue
            val c = idx.classOf[i]
            if (best[c] < 0 || appRet[i] > appRet[best[c]]) best[c] = i
        }
        val topByName = HashMap<String, Int>()
        for (c in best.indices) {
            val b = best[c]
            if (b < 0) continue
            val name = idx.classNames[c]
            val cur = topByName[name]
            if (cur == null || appRet[b] > appRet[cur]) topByName[name] = b
        }
        val classes = s.classes().filter { it.appRetainedBytes >= Thresholds.SUMMARY_CLASS_FLOOR }.map {
            ClassSummary(it.className, it.isAppCode, it.appInstanceCount, it.appShallowBytes, it.appRetainedBytes, topByName[it.className]?.let { d -> "obj:$d" })
        }
        val statics = ArrayList<StaticSummary>()
        val categories = s.children("root", 0, Int.MAX_VALUE).rows
        categories.firstOrNull { it.label == "Static fields" }?.let { cat ->
            for (r in s.children(cat.ref, 0, Int.MAX_VALUE).rows) {
                if (r.appHeapBytes > 0) statics += StaticSummary(r.label, r.retainedBytes, r.appHeapBytes, r.ref, s.isAppCode(r.label))
            }
        }
        statics.sortByDescending { it.appRetained }
        return SnapshotSummary(
            reachableBytes = heap.reachableBytes, appHeapBytes = heap.appHeapBytes,
            classes = classes, statics = statics.take(MAX_STATICS),
        )
    }
}
