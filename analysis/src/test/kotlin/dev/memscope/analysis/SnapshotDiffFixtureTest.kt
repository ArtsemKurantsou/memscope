package dev.memscope.analysis

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The diff of two real dumps of one fixture process: `after` ran `grow` x5 (Growth.list, 5 x 1 MB) and
 * `static_cache` (StaticCache, 200 x 100 KB). Needs emulator-5554 unless the dumps are already captured.
 */
@Tag("emulator")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotDiffFixtureTest {
    private val prefixes = listOf("dev.memscope.fixture")
    private val dumps by lazy { TestDumps.fixtureBeforeAfter() }
    private val before by lazy { Snapshot.open(dumps.first, prefixes) }
    private val after by lazy { Snapshot.open(dumps.second, prefixes) }
    private val diff by lazy { SnapshotDiff.diff(before, after) }
    private val mb = 1024L * 1024L

    @AfterAll
    fun close() {
        // The lazies are only initialised when a test ran (a skipped one never touched them).
        runCatching { before.close() }
        runCatching { after.close() }
    }

    @Test
    fun byteArraysGrewByTheGrowthAndTheStaticCache() {
        // 5 x 1 MiB + 200 x 100 KiB = 24.5 MiB = 25.7 MB: the brief's "25 MB" is decimal.
        val bytes = diff.classes.single { it.className == "byte[]" }
        val grown = bytes.shallowAfter - bytes.shallowBefore
        assertTrue(grown >= 25_000_000, "byte[] grew by $grown bytes")
        assertTrue(bytes.appShallowAfter - bytes.appShallowBefore >= 25_000_000, "and all of it is app heap")
        assertTrue(diff.appHeapAfter - diff.appHeapBefore >= 25_000_000)
    }

    @Test
    fun growthStaticsRowGrewByAboutFiveMb() {
        val growth = diff.roots.single { it.label == "Static fields / dev.memscope.fixture.Growth" }
        val grown = growth.retainedAfter - growth.retainedBefore
        assertEquals(5.0 * mb, grown.toDouble(), 0.05 * 5 * mb, "Growth grew by $grown bytes")
        assertEquals(grown, growth.appRetainedAfter - growth.appRetainedBefore, "Growth.list holds app-heap objects only")
    }

    @Test
    fun classesAbsentBeforeHaveCountBeforeZero() {
        val old = before.classes().map { it.className }.toSet()
        val new = after.classes().map { it.className }.filter { it !in old }
        val absent = diff.classes.filter { it.countBefore == 0 }
        assertEquals(new.toSet(), absent.map { it.className }.toSet())
        assertTrue(absent.all { it.countAfter > 0 && it.shallowBefore == 0L && it.retainedBefore == 0L })
    }

    @Test
    fun aSnapshotDiffedWithItselfHasAllZeroDeltas() {
        val self = SnapshotDiff.diff(after, after)
        assertTrue(self.classes.isEmpty())
        assertTrue(self.roots.all { it.retainedBefore == it.retainedAfter && it.appRetainedBefore == it.appRetainedAfter })
        assertEquals(self.reachableBefore, self.reachableAfter)
        assertEquals(self.appHeapBefore, self.appHeapAfter)
    }
}
