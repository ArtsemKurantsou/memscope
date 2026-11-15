package dev.memscope.analysis.findings

import dev.memscope.analysis.Snapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SessionRulesTest {
    private val mb = 1024L * 1024L

    private fun snap(id: String, label: String, app: Long, statics: Map<String, Long> = emptyMap(), classes: Map<String, Long> = emptyMap()) =
        SummarizedSnapshot(
            id, label, null, id.removePrefix("s").toLong() * 1000,
            SnapshotSummary(
                reachableBytes = app + 35 * mb, appHeapBytes = app,
                classes = classes.map { (n, b) -> ClassSummary(n, n.startsWith("app."), 1, b, b, "obj:7") },
                statics = statics.map { (n, b) -> StaticSummary(n, b, b, "obj:3", isApp = n.startsWith("app.")) },
            ),
        )

    @Test fun monotonicGrowthFiresOnThreeIncreasingSnapshots() {
        val snaps = listOf(
            snap("s1", "one", 10 * mb, statics = mapOf("app.Growth" to 1 * mb, "app.Flat" to 4 * mb)),
            snap("s2", "two", 12 * mb, statics = mapOf("app.Growth" to 2 * mb, "app.Flat" to 4 * mb)),
            snap("s3", "three", 14 * mb, statics = mapOf("app.Growth" to 3 * mb, "app.Flat" to 4 * mb)),
        )
        val f = MonotonicGrowthTest.run(snaps).single()
        assertEquals("monotonic-growth", f.ruleId)
        assertEquals(Severity.MEDIUM, f.severity)
        assertTrue(f.title.startsWith("Growth grew by 2.0 MB across 3 snapshots"), f.title)
        assertEquals("s3", f.snapshotId)
        assertEquals(listOf("obj:3"), f.refs)
    }

    @Test fun monotonicGrowthIgnoresTwoSnapshotsSmallGrowthAndPlateaus() {
        val two = listOf(snap("s1", "a", 1, statics = mapOf("app.G" to 1 * mb)), snap("s2", "b", 1, statics = mapOf("app.G" to 9 * mb)))
        assertTrue(MonotonicGrowthTest.run(two).isEmpty())
        val small = (1..3).map { snap("s$it", "x$it", 1, statics = mapOf("app.G" to it * 100_000L)) }
        assertTrue(MonotonicGrowthTest.run(small).isEmpty(), "growth under 2 MB")
        val plateau = listOf(1L, 5L, 5L).mapIndexed { i, v -> snap("s${i + 1}", "p$i", 1, statics = mapOf("app.G" to v * mb)) }
        assertTrue(MonotonicGrowthTest.run(plateau).isEmpty(), "not strictly increasing over 3")
    }

    @Test fun startupIntervalListsTheTopAppContributorsPerPair() {
        val snaps = listOf(
            snap("s1", "Process start", 1 * mb),
            snap("s2", "Before Application.onCreate", 6 * mb, statics = mapOf("app.InitProvider" to 5 * mb)),
            snap("s3", "After Application.onCreate", 16 * mb, statics = mapOf("app.InitProvider" to 5 * mb, "app.FixtureApp" to 10 * mb)),
        )
        val fs = StartupIntervalTest.run(snaps)
        assertEquals(2, fs.size)
        assertTrue(fs.all { it.severity == Severity.LOW && it.ruleId == "startup-interval" })
        val first = fs.single { it.title.startsWith("Process start → Before Application.onCreate") }
        assertTrue("app.InitProvider (static fields)  +5.0 MB" in first.evidence, first.evidence)
        assertEquals("s2", first.snapshotId)
        val second = fs.single { it.title.startsWith("Before Application.onCreate → After") }
        assertTrue("app.FixtureApp" in second.evidence && "InitProvider" !in second.evidence, second.evidence)
    }

    @Test fun startupIntervalIsSilentOutsideStartupSessions() {
        val snaps = listOf(snap("s1", "a", 1), snap("s2", "b", 2 * mb))
        assertTrue(Findings.forSession(snaps, startup = false).none { it.ruleId == "startup-interval" })
        assertEquals(1, Findings.forSession(snaps, startup = true).count { it.ruleId == "startup-interval" })
    }

    @Test fun summaryRoundTripsAndIsComputedFromARealSnapshot() {
        val dir = java.nio.file.Files.createTempDirectory("summary-test").toFile()
        try {
            val hprof = File(dir, "s.hprof").also { FindingsHprof.write(it) }
            Snapshot.open(hprof, listOf("test.")).use { s ->
                assertNotNull(s.hprofFile)
                val summary = SnapshotSummaries.readOrCompute(s)
                assertTrue(summary.appHeapBytes > 3_000_000)
                val bag = summary.classes.single { it.name == "test.Bag" }
                assertTrue(bag.isApp && bag.appRetained > 3_000_000 && bag.topRef!!.startsWith("obj:"))
                assertTrue(summary.statics.any { it.className == "test.Caches" && it.isApp })
                assertEquals(summary, SnapshotSummaries.read(hprof), "sidecar written and read back")
                assertTrue(SnapshotSummaries.file(hprof).name == "s.hprof.summary.json")
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}

private object MonotonicGrowthTest {
    fun run(s: List<SummarizedSnapshot>) = Findings.forSession(s, startup = false).filter { it.ruleId == "monotonic-growth" }
}

private object StartupIntervalTest {
    fun run(s: List<SummarizedSnapshot>) = Findings.forSession(s, startup = true).filter { it.ruleId == "startup-interval" }
}
