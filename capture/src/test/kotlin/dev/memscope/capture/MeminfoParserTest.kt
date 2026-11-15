package dev.memscope.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MeminfoParserTest {
    private fun resource(name: String) = checkNotNull(javaClass.getResource("/$name")) { "missing test resource $name" }.readText()

    @Test
    fun `real API 37 output of the fixture app is parsed exactly`() {
        val s = MeminfoParser.parse(resource("meminfo-api37-fixture.txt"), 4200)!!
        assertEquals(
            MemSample(
                tMs = 4200, javaHeapKb = 10544, nativeHeapKb = 25568, graphicsKb = 0, codeKb = 3904,
                stackKb = 880, otherKb = 8928 + 12995, totalPssKb = 62819, paused = false,
            ),
            s,
        )
    }

    @Test
    fun `older layout with a plain TOTAL line, no Graphics line and no Unknown line`() {
        val text = """
            Applications Memory Usage (in kB):
            Uptime: 1 Realtime: 1

            ** MEMINFO in pid 4321 [com.example.old] **
                               Pss  Private  Private  Swapped     Heap     Heap     Heap
                             Total    Dirty    Clean    Dirty     Size    Alloc     Free
                            ------   ------   ------   ------   ------   ------   ------
              Native Heap     6604     6580        0        0    10240     7000     3240
                    TOTAL    34032    20000     9000        0    20480    12000     8000

             App Summary
                                   Pss(KB)
                                    ------
                       Java Heap:     5152
                     Native Heap:     6604
                            Code:    12780
                           Stack:      384
                   Private Other:     4152
                          System:     4960

                           TOTAL:    34032       TOTAL SWAP PSS:        0

             Objects
                           Views:        0         ViewRootImpl:        0
        """.trimIndent()
        val s = MeminfoParser.parse(text, 7)!!
        assertEquals(
            MemSample(7, 5152, 6604, 0, 12780, 384, 4152 + 4960, 34032, false),
            s,
        )
    }

    @Test
    fun `missing lines count as zero and a missing total falls back to the sum`() {
        val s = MeminfoParser.parse(" App Summary\n   Java Heap:  100\n   Native Heap: 50\n", 0)!!
        assertEquals(MemSample(0, 100, 50, 0, 0, 0, 0, 150, false), s)
    }

    @Test
    fun `values come from the first column even with extra columns and odd spacing`() {
        val text = "App Summary\nJava Heap:3000 9999 8888\n  Native Heap:\t4000\t1\nCode: 5\nTOTAL PSS:12005 TOTAL RSS: 1\n"
        val s = MeminfoParser.parse(text, 1)!!
        assertEquals(3000, s.javaHeapKb)
        assertEquals(4000, s.nativeHeapKb)
        assertEquals(5, s.codeKb)
        assertEquals(12005, s.totalPssKb)
    }

    @Test
    fun `lines after the summary block are not read`() {
        val text = "App Summary\n Java Heap: 10\n TOTAL PSS: 10\n\n Objects\n  Views: 5\n Native Heap: 777\n"
        assertEquals(0, MeminfoParser.parse(text, 0)!!.nativeHeapKb)
    }

    @Test
    fun `absent process and unusable output give null`() {
        assertNull(MeminfoParser.parse("No process found for: dev.memscope.nope\n", 0))
        assertNull(MeminfoParser.parse("", 0))
        assertNull(MeminfoParser.parse("Applications Memory Usage (in Kilobytes):\n** MEMINFO in pid 1 **\n", 0))
    }

    @Test
    fun `the timeout text of a suspended app is not a sample`() {
        val text = "Applications Memory Usage (in Kilobytes):\nGot IoException! java.io.IOException: Timeout\n"
        assertNull(MeminfoParser.parse(text, 0))
        assertTrue(MeminfoParser.isTimeoutText(text))
    }

    @Test
    fun `smaps_rollup Pss line`() {
        val text = "00400000-ffffffff ---p 00000000 00:00 0   [rollup]\nRss:  91000 kB\nPss:   55123 kB\nPss_Anon:  100 kB\nShared_Clean: 3 kB\n"
        assertEquals(55123, MeminfoParser.parseSmapsPss(text))
        assertNull(MeminfoParser.parseSmapsPss("run-as: nothing here\n"))
        assertNotNull(MeminfoParser.parseSmapsPss("Pss: 1 kB"))
    }

    private fun assertTrue(b: Boolean) = org.junit.jupiter.api.Assertions.assertTrue(b)
}
