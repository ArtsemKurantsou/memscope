package dev.memscope.analysis.findings

import dev.memscope.analysis.Snapshot
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Locale

@Serializable
enum class Severity { HIGH, MEDIUM, LOW }

/**
 * One thing that looks wrong. [refs] are tree refs valid for the snapshot the finding belongs to
 * ([snapshotId] names it for findings that span a session; null for single-snapshot findings).
 */
@Serializable
data class Finding(
    val ruleId: String,
    val severity: Severity,
    val title: String,
    val evidence: String,
    val suggestion: String,
    val bytes: Long,
    val refs: List<String>,
    val snapshotId: String? = null,
)

/** Device screen size in pixels, for the oversized-bitmap rule. */
data class ScreenSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height.toLong()

    companion object {
        val FALLBACK = ScreenSize(1080, 2400)
    }
}

/** What a snapshot rule may use. [leakTimeoutMs] bounds the Shark leak pass. */
class RuleContext(
    val snapshot: Snapshot,
    val hprof: File = snapshot.hprofFile,
    val screen: ScreenSize = ScreenSize.FALLBACK,
    val leakTimeoutMs: Long = Thresholds.LEAK_ANALYSIS_TIMEOUT_MS,
) {
    /** One streaming pass over the hprof, shared by the duplicate-strings and duplicate-arrays rules. */
    internal val content: dev.memscope.analysis.findings.rules.ContentScan by lazy {
        dev.memscope.analysis.findings.rules.ContentScan.run(snapshot)
    }

    /** The large-static findings, which `dominant-class` skips a class for when a static already explains its bytes. */
    internal val largeStatics: List<Finding> by lazy { dev.memscope.analysis.findings.rules.LargeStaticRule().evaluate(this) }
}

interface Rule {
    val id: String
    fun evaluate(c: RuleContext): List<Finding>
}

/** One snapshot of a session as the session rules see it: its summary, not the opened heap. */
class SummarizedSnapshot(val id: String, val label: String, val milestoneId: String?, val takenAtMs: Long, val summary: SnapshotSummary)

interface SessionRule {
    val id: String
    /** [startup] is true for STARTUP sessions. [snapshots] are the OK snapshots in time order. */
    fun evaluate(snapshots: List<SummarizedSnapshot>, startup: Boolean): List<Finding>
}

/** Every threshold of every rule, in one place. Sizes are bytes. */
object Thresholds {
    const val KB = 1024L
    const val MB = 1024L * 1024L

    /** `MEMSCOPE_LEAK_TIMEOUT_SEC` (default 120). */
    val LEAK_ANALYSIS_TIMEOUT_MS: Long get() = System.getenv("MEMSCOPE_LEAK_TIMEOUT_SEC")?.toLongOrNull()?.takeIf { it > 0 }?.times(1000) ?: 120_000L
    /** oversized-bitmap: pixels above this multiple of the screen's pixels. */
    const val OVERSIZED_BITMAP_SCREEN_FACTOR = 2L
    const val DUPLICATE_BITMAP_MIN_COUNT = 5
    /** A group of duplicate bitmaps is reported when it holds at least this much pixel memory. */
    const val DUPLICATE_BITMAP_MIN_BYTES = 1 * MB
    const val DUPLICATE_STRING_MIN_WASTED = 256 * KB
    const val DUPLICATE_STRING_TOP = 10
    /** Hash groups whose text is read back to confirm (best by wasted bytes first), and members sampled per group. */
    const val STRING_VERIFY_MAX = 50
    const val STRING_VERIFY_SAMPLES = 3
    const val DUPLICATE_ARRAY_MIN_BYTES = 16 * KB
    const val DUPLICATE_ARRAY_MIN_COPIES = 2
    const val DUPLICATE_ARRAY_MIN_WASTED = 1 * MB
    /** Arrays larger than this are hashed on their first bytes plus length, then confirmed. */
    const val HASH_PREFIX_BYTES = 1 * MB
    const val SPARSE_MIN_BACKING = 64 * KB
    const val SPARSE_MAX_USED = 0.10
    const val LARGE_STATIC_MIN_PERCENT = 5.0
    const val LARGE_STATIC_MIN_BYTES = 20 * MB
    const val LARGE_STATIC_HIGH_PERCENT = 15.0
    const val LARGE_STATIC_TOP = 10
    const val DOMINANT_CLASS_PERCENT = 30.0
    /** Classes whose retained bytes are within this fraction of the largest in a cluster are one finding. */
    const val DOMINANT_CLUSTER_TOLERANCE = 0.05
    /** A class is "covered" when one large-static finding keeps at least this share of its bytes. */
    const val DOMINANT_COVERED_SHARE = 0.9
    val DOMINANT_CLASS_EXCLUDED = setOf("byte[]", "java.lang.Object[]", "java.lang.String", "java.lang.Class")
    const val GROWTH_MIN_SNAPSHOTS = 3
    const val GROWTH_MIN_BYTES = 2 * MB
    const val GROWTH_TOP = 5
    const val STARTUP_TOP = 5
    /** Classes smaller than this (app-retained) are left out of snapshot summaries. */
    const val SUMMARY_CLASS_FLOOR = 64 * KB
    /** Class entries kept per snapshot summary (largest app-retained first). */
    const val SUMMARY_MAX_CLASSES = 2000
}

/** "19.5 MB" style sizes (binary units, one decimal). */
fun formatBytes(bytes: Long): String {
    val abs = kotlin.math.abs(bytes).toDouble()
    val sign = if (bytes < 0) "-" else ""
    return when {
        abs >= Thresholds.MB -> String.format(Locale.US, "%s%.1f MB", sign, abs / Thresholds.MB)
        abs >= Thresholds.KB -> String.format(Locale.US, "%s%.1f KB", sign, abs / Thresholds.KB)
        else -> "$bytes B"
    }
}

/** Last segment of a class name: `a.b.Caches` -> `Caches`. */
fun simpleName(className: String): String = className.substringAfterLast('.').substringAfterLast('$').ifEmpty { className }
