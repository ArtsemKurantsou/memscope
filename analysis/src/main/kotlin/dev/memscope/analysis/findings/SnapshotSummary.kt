package dev.memscope.analysis.findings

import dev.memscope.analysis.Dominators
import dev.memscope.analysis.HeapIndexer
import dev.memscope.analysis.HeapKind
import dev.memscope.analysis.Snapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

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
 * What a sidecar was computed from; any difference means the sidecar is stale and counts as missing:
 * the hprof's size and modification time, the sidecar format, the heap index format and the app packages
 * (which decide `isApp`).
 */
@Serializable
data class SidecarKey(val hprofLength: Long, val hprofMtimeMs: Long, val format: Int, val indexFormat: Int, val prefixes: List<String>) {
    companion object {
        fun of(hprof: File, prefixes: List<String>) =
            SidecarKey(hprof.length(), hprof.lastModified(), SnapshotSummary.VERSION, HeapIndexer.FORMAT_VERSION, prefixes)
    }
}

/**
 * What the session rules need of one snapshot, small enough to keep next to the hprof
 * (`<snapshot>.summary.json`) so no snapshot has to be open to compare a session.
 */
@Serializable
data class SnapshotSummary(
    val key: SidecarKey? = null,
    val reachableBytes: Long,
    val appHeapBytes: Long,
    val classes: List<ClassSummary>,
    val statics: List<StaticSummary>,
) {
    companion object { const val VERSION = 2 }
}

/** Atomic, serialised sidecar files shared by the summary and the persisted findings. */
internal object Sidecars {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val locks = ConcurrentHashMap<String, Any>()

    fun lockFor(file: File): Any = locks.computeIfAbsent(file.path) { Any() }

    /** Writes via a unique temp file in the same directory and an atomic move; one writer per file at a time. */
    fun write(target: File, text: String) = synchronized(lockFor(target)) {
        val tmp = File.createTempFile(target.name, ".tmp", target.parentFile)
        try {
            tmp.writeText(text)
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete()
        }
    }

    /** Decoded content, or null when missing, stale ([keyOf] differs from [expected]) or corrupt (a corrupt file is deleted). */
    fun <T> read(file: File, decode: (String) -> T, keyOf: (T) -> SidecarKey?, expected: SidecarKey): T? = synchronized(lockFor(file)) {
        if (!file.isFile) return null
        val value = try { decode(file.readText()) } catch (_: Exception) { file.delete(); return null }
        if (keyOf(value) != expected) null else value
    }
}

object SnapshotSummaries {
    private const val MAX_STATICS = 300

    fun file(hprof: File): File = File(hprof.path + ".summary.json")

    /** The stored summary, or null if missing, stale (hprof, formats or [prefixes] changed) or corrupt. */
    fun read(hprof: File, prefixes: List<String>): SnapshotSummary? =
        Sidecars.read(file(hprof), { Sidecars.json.decodeFromString(SnapshotSummary.serializer(), it) }, { it.key }, SidecarKey.of(hprof, prefixes))

    fun write(hprof: File, summary: SnapshotSummary) = Sidecars.write(file(hprof), Sidecars.json.encodeToString(SnapshotSummary.serializer(), summary))

    /** Reads the sidecar or computes (and stores, best effort) it. */
    fun readOrCompute(s: Snapshot): SnapshotSummary =
        read(s.hprofFile, s.appPackagePrefixes) ?: compute(s).also { runCatching { write(s.hprofFile, it) } }

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
        val classes = s.classes().filter { it.appRetainedBytes >= Thresholds.SUMMARY_CLASS_FLOOR }
            .sortedByDescending { it.appRetainedBytes }.take(Thresholds.SUMMARY_MAX_CLASSES).map {
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
            key = SidecarKey.of(s.hprofFile, s.appPackagePrefixes),
            reachableBytes = heap.reachableBytes, appHeapBytes = heap.appHeapBytes,
            classes = classes, statics = statics.take(MAX_STATICS),
        )
    }
}
