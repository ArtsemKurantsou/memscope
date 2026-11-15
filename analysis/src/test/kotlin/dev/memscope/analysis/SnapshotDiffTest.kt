package dev.memscope.analysis

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files

/** Device-free diff tests on two small [DiffHprof] dumps that differ in known ways. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotDiffTest {
    private val dir: File = Files.createTempDirectory("snapshot-diff").toFile()
    private val prefixes = listOf("test.")

    // before: 5 items, 16 B cache, 3 test.Gone.  after: 8 items, ~1 MB cache, 2 test.Late, no test.Gone.
    private val before: Snapshot by lazy {
        DiffHprof.write(File(dir, "before.hprof"), itemCount = 5, cacheBytes = 16, goneCount = 3)
        Snapshot.open(File(dir, "before.hprof"), prefixes)
    }
    private val after: Snapshot by lazy {
        DiffHprof.write(File(dir, "after.hprof"), itemCount = 8, cacheBytes = 1_000_000, lateCount = 2)
        Snapshot.open(File(dir, "after.hprof"), prefixes)
    }
    private val diff: DiffResult by lazy { SnapshotDiff.diff(before, after) }

    @AfterAll
    fun cleanup() {
        before.close()
        after.close()
        dir.deleteRecursively()
    }

    private fun cls(name: String) = diff.classes.single { it.className == name }
    private fun root(label: String) = diff.roots.single { it.label == label }

    @Test
    fun instanceCountChangeIsReportedWithShallowAndRetainedDeltas() {
        val item = cls("test.Item")
        assertEquals(5, item.countBefore)
        assertEquals(8, item.countAfter)
        assertEquals(5L * DiffHprof.ITEM_SIZE, item.shallowBefore)
        assertEquals(8L * DiffHprof.ITEM_SIZE, item.shallowAfter)
        assertTrue(item.isAppCode)
        // Each item dominates its payload array, so the class retains item + payload.
        val perItem = item.retainedAfter / 8
        assertEquals(5 * perItem, item.retainedBefore)
        assertEquals(8 * perItem, item.retainedAfter)
        assertEquals((DiffHprof.ITEM_SIZE + 100).toLong(), perItem)
        // Instances are in the app heap: the app numbers equal the plain ones for test.Item.
        assertEquals(item.countBefore, item.appCountBefore)
        assertEquals(item.shallowAfter, item.appShallowAfter)
        assertEquals(item.retainedAfter, item.appRetainedAfter)
        // byte[] grew by 3 payloads plus the big cache array.
        val bytes = cls("byte[]")
        assertEquals(5 + 1, bytes.countBefore)
        assertEquals(8 + 1, bytes.countAfter)
        assertTrue(bytes.shallowAfter - bytes.shallowBefore >= 1_000_000 - 16)
    }

    @Test
    fun classAbsentBeforeHasCountBeforeZero() {
        val late = cls("test.Late")
        assertEquals(0, late.countBefore)
        assertEquals(0L, late.shallowBefore)
        assertEquals(0L, late.retainedBefore)
        assertEquals(2, late.countAfter)
        assertEquals(2L * DiffHprof.LATE_SIZE, late.shallowAfter)
        assertEquals(2L * DiffHprof.LATE_SIZE, late.retainedAfter)
    }

    @Test
    fun classAbsentAfterIsKeptWithNegativeDeltas() {
        val gone = cls("test.Gone")
        assertEquals(3, gone.countBefore)
        assertEquals(0, gone.countAfter)
        assertEquals(3L * DiffHprof.GONE_SIZE, gone.shallowBefore)
        assertEquals(0L, gone.shallowAfter)
        assertEquals(0L, gone.appRetainedAfter)
    }

    @Test
    fun classStaticsSubtreeGrowthShowsInRootDeltas() {
        val cache = root("Static fields / test.Cache")
        val grown = cache.retainedAfter - cache.retainedBefore
        assertTrue(grown in (1_000_000 - 16L)..(1_000_000 - 16L + 64), "cache grew by $grown")
        assertEquals(grown, cache.appRetainedAfter - cache.appRetainedBefore, "the array is an app-heap object")
        // The category row carries the same growth plus the App.items growth.
        val statics = root("Static fields")
        val appRoot = root("Static fields / test.App")
        assertEquals(grown + (appRoot.retainedAfter - appRoot.retainedBefore), statics.retainedAfter - statics.retainedBefore)
        assertEquals(after.summary().reachableBytes - before.summary().reachableBytes, statics.retainedAfter - statics.retainedBefore)
    }

    @Test
    fun totalsComeFromTheSummaries() {
        assertEquals(before.summary().reachableBytes, diff.reachableBefore)
        assertEquals(after.summary().reachableBytes, diff.reachableAfter)
        assertEquals(before.summary().appHeapBytes, diff.appHeapBefore)
        assertEquals(after.summary().appHeapBytes, diff.appHeapAfter)
        assertTrue(diff.appHeapAfter > diff.appHeapBefore + 1_000_000 - 16)
    }

    @Test
    fun diffOfASnapshotWithItselfIsAllZero() {
        val self = SnapshotDiff.diff(after, after)
        assertTrue(self.classes.isEmpty(), "unchanged classes are not listed: ${self.classes.map { it.className }}")
        assertTrue(self.roots.isNotEmpty())
        for (r in self.roots) {
            assertEquals(r.retainedBefore, r.retainedAfter, r.label)
            assertEquals(r.appRetainedBefore, r.appRetainedAfter, r.label)
        }
        assertEquals(self.reachableBefore, self.reachableAfter)
        assertEquals(self.appHeapBefore, self.appHeapAfter)
        assertNotNull(self.roots.firstOrNull { it.label == "Static fields / test.Cache" })
    }

    @Test
    fun everyListedClassActuallyChanged() {
        for (c in diff.classes) {
            val same = c.countBefore == c.countAfter && c.shallowBefore == c.shallowAfter && c.retainedBefore == c.retainedAfter &&
                c.appCountBefore == c.appCountAfter && c.appShallowBefore == c.appShallowAfter && c.appRetainedBefore == c.appRetainedAfter
            assertTrue(!same, "${c.className} did not change")
        }
    }
}
