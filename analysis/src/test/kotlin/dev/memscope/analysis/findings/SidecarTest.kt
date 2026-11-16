package dev.memscope.analysis.findings

import dev.memscope.analysis.Snapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.Executors

class SidecarTest {
    private val dir = java.nio.file.Files.createTempDirectory("sidecar-test").toFile()
    private val prefixes = listOf("test.")
    private val hprof = File(dir, "s.hprof").also { FindingsHprof.write(it) }
    private val summary = Snapshot.open(hprof, prefixes).use { SnapshotSummaries.compute(it) }

    @Test fun aSidecarWithAnotherKeyIsStaleAndTreatedAsMissing() {
        SnapshotSummaries.write(hprof, summary)
        assertNotNull(SnapshotSummaries.read(hprof, prefixes))
        assertNull(SnapshotSummaries.read(hprof, listOf("other.")), "app packages changed")
        hprof.setLastModified(hprof.lastModified() + 5000)
        assertNull(SnapshotSummaries.read(hprof, prefixes), "hprof modification time changed")
        assertTrue(SnapshotSummaries.file(hprof).isFile, "a stale file is kept for the recompute to replace")
    }

    @Test fun aCorruptSidecarIsDeletedAndReportedMissing() {
        SnapshotSummaries.file(hprof).writeText("{ not json")
        assertNull(SnapshotSummaries.read(hprof, prefixes))
        assertFalse(SnapshotSummaries.file(hprof).exists())
    }

    @Test fun concurrentWritersNeverLeaveATornFile() {
        val pool = Executors.newFixedThreadPool(8)
        val jobs = (1..40).map { pool.submit { SnapshotSummaries.write(hprof, summary) } }
        val reads = (1..40).map { pool.submit<SnapshotSummary?> { SnapshotSummaries.read(hprof, prefixes) } }
        jobs.forEach { it.get() }
        reads.forEach { r -> r.get()?.let { assertEquals(summary, it) } }
        pool.shutdown()
        assertEquals(summary, SnapshotSummaries.read(hprof, prefixes))
        assertEquals(listOf("s.hprof", "s.hprof.summary.json"), dir.list()!!.filter { !it.endsWith(".idx") }.sorted(), "no temp files left")
    }

    @Test fun classEntriesAreCapped() {
        assertTrue(summary.classes.size <= Thresholds.SUMMARY_MAX_CLASSES)
    }
}
