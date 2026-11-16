package dev.memscope.analysis.findings

import dev.memscope.analysis.Snapshot
import dev.memscope.analysis.findings.rules.DominantClassRule
import dev.memscope.analysis.findings.rules.DuplicateArraysRule
import dev.memscope.analysis.findings.rules.DuplicateBitmapsRule
import dev.memscope.analysis.findings.rules.DuplicateStringsRule
import dev.memscope.analysis.findings.rules.LargeStaticRule
import dev.memscope.analysis.findings.rules.LeakedComponentRule
import dev.memscope.analysis.findings.rules.OversizedBitmapRule
import dev.memscope.analysis.findings.rules.SparseCollectionRule
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FindingsRulesTest {
    private val dir: File = java.nio.file.Files.createTempDirectory("findings-test").toFile()
    private val hprof by lazy { File(dir, "findings.hprof").also { FindingsHprof.write(it) } }
    private val snap by lazy { Snapshot.open(hprof, listOf("test.")) }
    private val screen = ScreenSize(1080, 2400)
    private fun ctx() = RuleContext(snap, hprof, screen)
    private fun ref(id: Long) = "obj:${snap.index.indexOfObjectId(id)}"

    @AfterAll fun close() { runCatching { snap.close() }; dir.deleteRecursively() }

    @Test fun oversizedBitmapFindsTheBigBitmap() {
        val f = OversizedBitmapRule().evaluate(ctx()).single()
        assertEquals(Severity.MEDIUM, f.severity)
        assertTrue("3000x2000" in f.title, f.title)
        assertEquals(listOf(ref(FindingsHprof.BIG_BITMAP)), f.refs)
        assertEquals(3000L * 2000 * 4, f.bytes)
    }

    @Test fun duplicateBitmapsCountsSixSameSized() {
        val f = DuplicateBitmapsRule().evaluate(ctx()).single()
        assertTrue(f.title.startsWith("Possible duplicate bitmaps: 6 x 100x100"), f.title)
        assertEquals(5L * 100 * 100 * 4, f.bytes)
        assertTrue(f.refs.size == 3 && f.refs.all { it.startsWith("obj:") })
    }

    @Test fun duplicateStringsFindsTheHundredCopies() {
        val f = DuplicateStringsRule().evaluate(ctx()).single()
        assertEquals(Severity.LOW, f.severity)
        assertTrue("100 x \"abcdefghijklmnopqrstuvwx" in f.evidence, f.evidence)
        assertTrue(f.bytes >= 99L * 4000, "wasted ${f.bytes}")
        assertTrue(f.refs.first() == ref(FindingsHprof.string(0)))
    }

    @Test fun duplicateArraysConfirmsContentAndSplitsOffTheNearDuplicate() {
        // A, B equal; C differs only in the last byte (past the 1 MB hashed prefix).
        val f = DuplicateArraysRule().evaluate(ctx()).single()
        assertEquals(Severity.MEDIUM, f.severity)
        assertTrue(f.title.startsWith("2 identical byte[] arrays"), f.title)
        assertEquals(setOf(ref(FindingsHprof.ARR_A), ref(FindingsHprof.ARR_B)), f.refs.toSet())
        assertEquals(FindingsHprof.BIG_LENGTH.toLong(), f.bytes)
    }

    @Test fun sparseCollectionFindsTheMostlyEmptyList() {
        val f = SparseCollectionRule().evaluate(ctx()).single()
        assertEquals(Severity.LOW, f.severity)
        assertTrue("holds 3 items" in f.title, f.title)
        assertEquals(listOf(ref(FindingsHprof.LIST)), f.refs)
    }

    @Test fun largeStaticNamesClassAndField() {
        val f = LargeStaticRule().evaluate(ctx()).single()
        assertEquals(Severity.HIGH, f.severity)
        assertTrue(f.title.startsWith("Caches.bag retains "), f.title)
        assertEquals(listOf(ref(FindingsHprof.BAG)), f.refs)
    }

    @Test fun dominantClassIsSilentWhenAStaticFieldAlreadyExplainsIt() {
        assertTrue(DominantClassRule().evaluate(ctx()).isEmpty(), "Bag is the whole Caches.bag large-static")
    }

    @Test fun theHashMapCacheShapeIsOneClusterNotThree() {
        fun stat(n: String, app: Boolean, b: Long) = dev.memscope.analysis.ClassStat(n, 1, b, b, app, 1, b, b)
        val groups = DominantClassRule.cluster(listOf(stat("java.util.HashMap", false, 20_000_000), stat("java.util.HashMap\$Node[]", false, 19_900_000), stat("java.util.HashMap\$Node", false, 19_500_000), stat("app.Cache", true, 8_000_000)))
        assertEquals(listOf(3, 1), groups.map { it.size })
    }

    @Test fun leakedComponentReportsTheDestroyedActivityWithItsChain() {
        val leakHprof = File(dir, "leak.hprof").also { FindingsHprof.write(it, withLeak = true) }
        Snapshot.open(leakHprof, listOf("test.")).use { s ->
            val f = LeakedComponentRule().evaluate(RuleContext(s, leakHprof, screen)).single()
            assertEquals(Severity.HIGH, f.severity, f.title)
            assertTrue("LeakyActivity" in f.title, f.title)
            assertTrue("test.Caches.leaked" in f.evidence && "suspect reference" in f.evidence, f.evidence)
            assertEquals(listOf("obj:${s.index.indexOfObjectId(FindingsHprof.LEAKY)}"), f.refs)
        }
    }

    @Test fun forSnapshotSortsBySeverityThenBytesAndRunsEveryRule() {
        val ran = ArrayList<String>()
        val all = Findings.forSnapshot(snap, hprof, screen, progress = { ran += it })
        assertEquals(Findings.snapshotRuleIds, ran)
        assertEquals(all.sortedWith(compareBy<Finding> { it.severity.ordinal }.thenByDescending { it.bytes }), all)
        assertTrue(all.map { it.ruleId }.containsAll(listOf("oversized-bitmap", "duplicate-bitmaps", "duplicate-strings", "duplicate-arrays", "sparse-collection", "large-static")))
    }
}
