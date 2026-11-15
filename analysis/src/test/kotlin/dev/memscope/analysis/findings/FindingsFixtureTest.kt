package dev.memscope.analysis.findings

import dev.memscope.analysis.Snapshot
import dev.memscope.analysis.TestDumps
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** The planted problems of the fixture app, found by each rule. Needs emulator-5554 unless the dumps are captured. */
@Tag("emulator")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FindingsFixtureTest {
    private val prefixes = listOf("dev.memscope.fixture")
    private val screen = ScreenSize(1080, 2400)
    private val basic by lazy { Snapshot.open(TestDumps.fixtureBasic(), prefixes) }
    private val findings by lazy { Findings.forSnapshot(basic, screen = screen) }
    private val opened = ArrayList<Snapshot>()

    @AfterAll fun close() {
        runCatching { basic.close() }
        opened.forEach { runCatching { it.close() } }
    }

    private fun byRule(id: String) = findings.filter { it.ruleId == id }

    @Test fun leakedActivityWithTheChainThroughLeaksActivity() {
        val f = byRule("leaked-component").single { "LeakyActivity" in it.title }
        assertEquals(Severity.HIGH, f.severity)
        assertTrue("dev.memscope.fixture.Leaks.activity" in f.evidence && "suspect reference" in f.evidence, f.evidence)
        assertEquals(1, f.refs.size)
        assertTrue(basic.details(f.refs[0]).className.endsWith("LeakyActivity"))
    }

    @Test fun oversizedBitmapIsTheFourThousandSquare() {
        val f = byRule("oversized-bitmap").single()
        assertTrue("4000x4000" in f.title, f.title)
        assertTrue(f.bytes >= 4000L * 4000 * 4 - 1024, "bytes ${f.bytes}")
    }

    @Test fun twentyPossiblyDuplicateBitmaps() {
        val f = byRule("duplicate-bitmaps").single { "512x512" in it.title }
        assertTrue(f.title.startsWith("Possible duplicate bitmaps: 20 x 512x512"), f.title)
    }

    @Test fun twoThousandDuplicateStrings() {
        val f = byRule("duplicate-strings").single()
        assertTrue("2000 x" in f.evidence, f.evidence)
    }

    @Test fun theMillionSlotListHoldsThreeItems() {
        val f = byRule("sparse-collection").single()
        assertTrue("holds 3 items" in f.title && "1000000 slots" in f.evidence, f.title + " / " + f.evidence)
    }

    @Test fun staticCacheAndApplicationBlobAreLargeStatics() {
        val titles = byRule("large-static").map { it.title }
        assertTrue(titles.any { it.startsWith("Caches.imageCache retains 19.") }, titles.toString())
        assertTrue(titles.any { it.startsWith("FixtureApp.appBlob retains 10.") }, titles.toString())
    }

    @Test fun plantedArraysAreDistinctSoNoDuplicateArraysFinding() {
        assertTrue(byRule("duplicate-arrays").isEmpty(), byRule("duplicate-arrays").toString())
    }

    @Test fun aPlainLaunchHasNoHighFindingsExceptLargeStatics() {
        Snapshot.open(TestDumps.fixturePlain(), prefixes).also { opened += it }.let { plain ->
            val all = Findings.forSnapshot(plain, screen = screen)
            val high = all.filter { it.severity == Severity.HIGH }
            assertTrue(high.all { it.ruleId == "large-static" }, high.toString())
            assertFalse(all.any { it.ruleId == "leaked-component" && it.severity == Severity.HIGH })
        }
    }

    @Test fun growthIsMonotonicAcrossThreeDumpsBetweenGrowActions() {
        val summaries = TestDumps.fixtureGrow().mapIndexed { i, f ->
            val s = Snapshot.open(f, prefixes).also { opened += it }
            SummarizedSnapshot("s${i + 1}", "Grow ${i + 1}", null, (i + 1) * 1000L, SnapshotSummaries.compute(s))
        }
        val f = Findings.forSession(summaries, startup = false).single { it.ruleId == "monotonic-growth" && it.title.startsWith("Growth ") }
        assertEquals("s3", f.snapshotId)
        assertTrue(f.bytes >= 4_000_000, "grew ${f.bytes}")
    }

    @Test fun startupIntervalListsWhatEachMilestoneAdded() {
        // No debugger needed: the plain launch and the dump after grow x5 + static_cache stand for two milestones.
        val (before, after) = TestDumps.fixtureBeforeAfter()
        val summaries = listOf("Process start" to before, "Before Application.onCreate" to after).mapIndexed { i, (label, f) ->
            val s = Snapshot.open(f, prefixes).also { opened += it }
            SummarizedSnapshot("s${i + 1}", label, "m$i", (i + 1) * 1000L, SnapshotSummaries.compute(s))
        }
        val f = Findings.forSession(summaries, startup = true).single { it.ruleId == "startup-interval" }
        assertTrue(f.title.startsWith("Process start → Before Application.onCreate"), f.title)
        assertTrue("dev.memscope.fixture.Caches (static fields)" in f.evidence && "dev.memscope.fixture.Growth (static fields)" in f.evidence, f.evidence)
    }
}
