package dev.memscope.server

import dev.memscope.analysis.ClassStat
import dev.memscope.analysis.DiffResult
import dev.memscope.analysis.SnapshotDiff
import dev.memscope.analysis.HeapSummary
import dev.memscope.analysis.ObjectDetails
import dev.memscope.analysis.Progress
import dev.memscope.analysis.RevealTooDeepException
import dev.memscope.analysis.Snapshot
import dev.memscope.analysis.TreePage
import java.io.File

/** The queries the routes need from an opened snapshot; [Snapshot] is final, so tests substitute this. */
interface SnapshotView : AutoCloseable {
    fun summary(): HeapSummary
    fun children(ref: String, offset: Int, limit: Int): TreePage
    fun details(ref: String): ObjectDetails
    fun classes(): List<ClassStat>
    fun instances(className: String, offset: Int, limit: Int): TreePage
    fun revealPath(ref: String): List<String>
    fun indexInParent(parentRef: String, childRef: String): Int
    /** What grew between [base] (before) and this snapshot (after); [base] comes from the same registry. */
    fun diff(base: SnapshotView): DiffResult
    /** Rule-based findings of this snapshot; [onRule] is called with each rule id before it runs. Can take minutes on a big dump. */
    fun findings(screen: dev.memscope.analysis.findings.ScreenSize, onRule: (String) -> Unit): List<dev.memscope.analysis.findings.Finding> = emptyList()
    /** The compact summary the session rules use (read from, or written to, the sidecar next to the hprof). */
    fun snapshotSummary(): dev.memscope.analysis.findings.SnapshotSummary? = null
}

/** A view over a real [Snapshot]; [SnapshotView.diff] needs the base's snapshot. */
interface SnapshotBacked { val snapshot: Snapshot }

/** Thrown by a view whose path from the root is longer than [REVEAL_MAX_DEPTH]. */
class TooDeepToReveal : Exception("too deep to reveal")

const val REVEAL_MAX_DEPTH = 2000

fun interface SnapshotOpener {
    fun open(file: File, appPackagePrefixes: List<String>, progress: Progress): SnapshotView

    companion object {
        val real = SnapshotOpener { file, prefixes, progress -> Snapshot.open(file, prefixes, progress).asView() }
    }
}

fun Snapshot.asView(): SnapshotView = object : SnapshotView, SnapshotBacked {
    override val snapshot get() = this@asView
    override fun summary() = this@asView.summary()
    override fun children(ref: String, offset: Int, limit: Int) = this@asView.children(ref, offset, limit)
    override fun details(ref: String) = this@asView.details(ref)
    override fun classes() = this@asView.classes()
    override fun instances(className: String, offset: Int, limit: Int) = this@asView.instances(className, offset, limit)
    override fun revealPath(ref: String) = try {
        this@asView.revealPath(ref, REVEAL_MAX_DEPTH)
    } catch (e: RevealTooDeepException) {
        throw TooDeepToReveal()
    }
    override fun indexInParent(parentRef: String, childRef: String) = this@asView.indexInParent(parentRef, childRef)
    override fun diff(base: SnapshotView): DiffResult {
        val before = (base as? SnapshotBacked)?.snapshot ?: throw IllegalArgumentException("the base snapshot cannot be compared: it is not backed by a real analysis")
        return SnapshotDiff.diff(before, this@asView)
    }
    override fun findings(screen: dev.memscope.analysis.findings.ScreenSize, onRule: (String) -> Unit) =
        dev.memscope.analysis.findings.Findings.forSnapshot(this@asView, screen = screen, progress = onRule)
    override fun snapshotSummary() = dev.memscope.analysis.findings.SnapshotSummaries.readOrCompute(this@asView)
    override fun close() = this@asView.close()
}
