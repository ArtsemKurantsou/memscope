package dev.memscope.analysis.findings

import dev.memscope.analysis.findings.rules.ContentGroup
import dev.memscope.analysis.findings.rules.DuplicateStringsRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StringVerifyTest {
    @Test fun manySmallGroupsCostABoundedNumberOfReads() {
        // 120,000 duplicate pairs of a small string; one group of 300 copies is the one that matters.
        val groups = ArrayList<ContentGroup>()
        for (g in 0 until 120_000) groups += ContentGroup(intArrayOf(2 * g, 2 * g + 1))
        groups += ContentGroup(IntArray(300) { 1_000_000 + it })
        var reads = 0
        val dups = DuplicateStringsRule.verify(groups, { it }, { _, _ -> 40L }, { reads++; "text-of-${it / 1_000_000}-${if (it < 1_000_000) it / 2 else -1}" })
        assertTrue(reads <= Thresholds.STRING_VERIFY_MAX * Thresholds.STRING_VERIFY_SAMPLES, "reads $reads")
        assertEquals(300, dups.first().copies, "the biggest waste is verified first")
        assertEquals(299 * 40L, dups.first().wasted, "counts come from the hash group")
    }

    @Test fun aGroupWhoseSampledTextsDifferIsACollision() {
        val dups = DuplicateStringsRule.verify(listOf(ContentGroup(intArrayOf(1, 2, 3))), { it }, { _, _ -> 10L }, { if (it == 3) "other" else "same" })
        assertTrue(dups.isEmpty())
    }
}
