package dev.memscope.analysis

import dev.memscope.analysis.SyntheticHprof.ARRAY
import dev.memscope.analysis.SyntheticHprof.BITMAP
import dev.memscope.analysis.SyntheticHprof.BITMAP2
import dev.memscope.analysis.SyntheticHprof.BYTES
import dev.memscope.analysis.SyntheticHprof.CLEANER
import dev.memscope.analysis.SyntheticHprof.HOLDER
import dev.memscope.analysis.SyntheticHprof.HOLDER_CLASS
import dev.memscope.analysis.SyntheticHprof.OBJECT
import dev.memscope.analysis.SyntheticHprof.PLAIN
import dev.memscope.analysis.SyntheticHprof.THREAD
import dev.memscope.analysis.SyntheticHprof.WEAK
import dev.memscope.analysis.SyntheticHprof.WEAK_ONLY
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.FileTime

/** Device-free tests on a synthetic hprof (see [SyntheticHprof]). */
class HeapIndexerTest {
    @TempDir
    lateinit var dir: File

    private fun synthetic(): File = File(dir, "synthetic.hprof").also { SyntheticHprof.write(it) }

    private fun HeapIndex.d(id: Long): Int = indexOfObjectId(id).also { assertTrue(it >= 0, "object $id not indexed") }

    private fun HeapIndex.targets(id: Long): List<Int> {
        val i = d(id)
        return (graph.edgeOffsets[i] until graph.edgeOffsets[i + 1]).map { graph.edgeTargets[it] }
    }

    private fun HeapIndex.className(id: Long) = classNames[classOf[d(id)]]

    @Test
    fun indexesObjectsClassesAndSizes() {
        val index = HeapIndexer.build(synthetic())
        assertEquals(13 + 12, index.objectCount)
        for (i in 1 until index.objectCount) assertTrue(index.objectIds[i - 1] < index.objectIds[i])

        assertEquals("test.Holder", index.className(HOLDER))
        assertEquals("byte[]", index.className(BYTES))
        assertEquals("java.lang.Object[]", index.className(ARRAY))
        assertEquals("java.lang.Class", index.className(HOLDER_CLASS))
        val holderTable = index.classNames.indexOf("test.Holder")
        assertEquals(index.d(HOLDER_CLASS), index.classObjectIndex[holderTable])

        assertEquals(16, index.shallow[index.d(HOLDER)])
        assertEquals(SyntheticHprof.BYTES_LENGTH, index.shallow[index.d(BYTES)])
        assertEquals(2 * 4, index.shallow[index.d(ARRAY)])
        assertEquals(SyntheticHprof.CLASS_INSTANCE_SIZE + 4, index.shallow[index.d(HOLDER_CLASS)], "Class size + one static ref")
    }

    @Test
    fun strongEdgesOnly() {
        val index = HeapIndexer.build(synthetic())
        assertEquals(listOf(index.d(PLAIN), index.d(BYTES)), index.targets(HOLDER))
        assertEquals(listOf(index.d(ARRAY)), index.targets(HOLDER_CLASS), "static field edge from the class object")
        assertEquals(listOf(index.d(PLAIN), index.d(BITMAP)), index.targets(ARRAY))
        assertEquals(emptyList<Int>(), index.targets(WEAK), "Reference.referent is not an edge")
        assertFalse(index.d(BITMAP) in index.targets(CLEANER), "Cleaner.referent is not an edge")
        assertEquals(Dominators.UNREACHABLE, index.idom[index.d(WEAK_ONLY)])
        assertEquals(0L, index.retained[index.d(WEAK_ONLY)])
    }

    @Test
    fun dominatorsAndRetainedSizes() {
        val index = HeapIndexer.build(synthetic())
        assertEquals(index.superRoot, index.idom[index.d(PLAIN)], "PLAIN is reachable from two roots")
        assertEquals(index.d(HOLDER), index.idom[index.d(BYTES)])
        assertEquals(index.d(ARRAY), index.idom[index.d(BITMAP)])
        assertEquals(16L + 100, index.retained[index.d(HOLDER)])
        assertEquals(124L + 8 + 40, index.retained[index.d(HOLDER_CLASS)])
        assertEquals(124L + 8 + 40 + 4000, index.retainedWithNative[index.d(HOLDER_CLASS)])
        assertEquals(24L + 12 + 24, index.retained[index.d(CLEANER)], "cleaner retains thunk and registry")

        var reachableShallow = 0L
        for (i in 0 until index.objectCount) if (index.idom[i] != Dominators.UNREACHABLE) reachableShallow += index.shallow[i]
        var topRetained = 0L
        for (k in index.domChildOffsets[index.superRoot] until index.domChildOffsets[index.superRoot + 1]) {
            topRetained += index.retained[index.domChildren[k]]
        }
        assertEquals(reachableShallow, topRetained)
        assertEquals(index.superRoot, index.bfsParent[index.d(HOLDER)])
        assertEquals(index.d(HOLDER), index.bfsParent[index.d(BYTES)])
    }

    @Test
    fun nativeSizes() {
        val index = HeapIndexer.build(synthetic())
        assertEquals(4000, index.nativeEst[index.d(BITMAP)], "registry size 4001 & ~1 via cleaner")
        assertEquals(2 * 3 * 4, index.nativeEst[index.d(BITMAP2)], "Bitmap without cleaner: w*h*4")
        assertEquals(0, index.nativeEst[index.d(HOLDER)])
        assertEquals(4000 + 4 * 6, index.nativeEst.sum())
    }

    @Test
    fun rootsAreDeduplicatedWithKindAndThread() {
        val index = HeapIndexer.build(synthetic())
        val roots = index.graph.roots
        assertEquals(roots.toSet().size, roots.size, "one root entry per object")
        assertEquals(13 + 1 + 1 + 3, roots.size, "13 classes, thread, holder, 3 JNI globals")
        fun kindOf(id: Long): RootKind {
            val k = roots.indexOf(index.d(id))
            assertTrue(k >= 0, "$id is not a root")
            return RootKind.values()[index.rootKinds[k].toInt()]
        }
        assertEquals(RootKind.JAVA_FRAME, kindOf(HOLDER), "JAVA_FRAME beats JNI_GLOBAL")
        assertEquals(index.d(THREAD), index.rootThreadObj[roots.indexOf(index.d(HOLDER))])
        assertEquals(RootKind.THREAD, kindOf(THREAD))
        assertEquals(-1, index.rootThreadObj[roots.indexOf(index.d(THREAD))])
        assertEquals(RootKind.CLASS, kindOf(HOLDER_CLASS))
        assertEquals(RootKind.CLASS, kindOf(OBJECT))
        assertEquals(RootKind.JNI_GLOBAL, kindOf(BITMAP2))
        assertFalse(index.d(PLAIN) in roots)
    }

    @Test
    fun heapKindsFromHeapDumpInfo() {
        val index = HeapIndexer.build(synthetic())
        assertEquals(HeapKind.IMAGE, index.heapKind[index.d(OBJECT)])
        assertEquals(HeapKind.IMAGE, index.heapKind[index.d(HOLDER_CLASS)])
        assertEquals(HeapKind.APP, index.heapKind[index.d(HOLDER)])
        assertEquals(HeapKind.APP, index.heapKind[index.d(BYTES)])
    }

    @Test
    fun zeroLengthFileIsUnreadableAndLeavesNoCache() {
        val f = File(dir, "empty.hprof").also { it.createNewFile() }
        val e = assertThrows<HprofUnreadableException> { IndexCache.loadOrBuild(f, Progress { _, _ -> }) }
        assertEquals(f, e.file)
        assertTrue(e.message!!.contains(f.path), e.message)
        assertFalse(IndexCache.cacheFileFor(f).exists())
        assertTrue(dir.listFiles()!!.none { it.name.contains(".idx") })
    }

    @Test
    fun truncatedFileIsUnreadableAndRemovesStaleCache() {
        val f = synthetic()
        IndexCache.loadOrBuild(f, Progress { _, _ -> })
        assertTrue(IndexCache.cacheFileFor(f).exists())
        RandomAccessFile(f, "rw").use { it.setLength(it.length() / 2) }
        val e = assertThrows<HprofUnreadableException> { IndexCache.loadOrBuild(f, Progress { _, _ -> }) }
        assertTrue(e.message!!.contains(f.path), e.message)
        assertFalse(IndexCache.cacheFileFor(f).exists(), "stale cache must be removed")
        assertTrue(dir.listFiles()!!.none { it.name.contains(".idx") })
    }

    @Test
    fun missingHeapDumpEndIsTruncation() {
        val f = synthetic()
        // HprofWriter ends the file with a 9-byte HEAP_DUMP_END record (tag, time, length 0).
        RandomAccessFile(f, "rw").use { it.setLength(it.length() - 9) }
        val e = assertThrows<HprofUnreadableException> { HeapIndexer.build(f) }
        assertTrue(e.message!!.contains("truncated"), e.message)
    }

    @Test
    fun aRuntimeErrorWhileIndexingAnIntactFileIsABugNotAnUnreadableDump() {
        val f = synthetic()
        val e = assertThrows<AnalysisBugException> {
            HeapIndexer.build(f) { step, _ -> if (step == "parse") throw IndexOutOfBoundsException("indexer bug") }
        }
        assertTrue(e.cause is IndexOutOfBoundsException)
        assertEquals(AnalysisBugException.MESSAGE, e.message)
    }

    @Test
    fun aRuntimeErrorOnAFileWithoutItsEndRecordIsStillTruncation() {
        val f = synthetic()
        RandomAccessFile(f, "rw").use { it.setLength(it.length() - 9) }
        assertThrows<HprofUnreadableException> {
            HeapIndexer.build(f) { step, _ -> if (step == "parse") throw IllegalStateException("bad record") }
        }
    }

    @Test
    fun fileProblemChecksHeaderSizeAndEndRecord() {
        val f = synthetic()
        assertEquals(null, HprofFiles.problem(f))
        assertEquals(null, HprofFiles.headerProblem(f))
        RandomAccessFile(f, "rw").use { it.setLength(it.length() - 9) }
        assertTrue(HprofFiles.problem(f)!!.contains("HEAP_DUMP_END"))
        assertEquals(null, HprofFiles.headerProblem(f))
        val zeros = File(dir, "zeros.hprof").also { it.writeBytes(ByteArray(64)) }
        assertTrue(HprofFiles.headerProblem(zeros)!!.contains("not an hprof"), HprofFiles.headerProblem(zeros))
        val empty = File(dir, "empty.hprof").also { it.createNewFile() }
        assertTrue(HprofFiles.headerProblem(empty)!!.contains("empty"))
        assertTrue(HprofFiles.headerProblem(File(dir, "missing.hprof"))!!.contains("missing"))
    }

    @Test
    fun cacheIsReusedAndInvalidatedByTouch() {
        val f = synthetic()
        val steps = mutableListOf<String>()
        val first = IndexCache.loadOrBuild(f) { step, _ -> steps += step }
        assertTrue("parse" in steps)
        assertTrue(IndexCache.cacheFileFor(f).exists())

        steps.clear()
        val second = IndexCache.loadOrBuild(f) { step, _ -> steps += step }
        assertFalse("parse" in steps, "second load must not run the indexer: $steps")
        assertTrue("load" in steps)
        assertSameIndex(first, second)

        Files.setLastModifiedTime(f.toPath(), FileTime.fromMillis(f.lastModified() + 5_000))
        steps.clear()
        val third = IndexCache.loadOrBuild(f) { step, _ -> steps += step }
        assertTrue("parse" in steps, "touching the hprof invalidates the cache")
        assertSameIndex(first, third)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") }, "no temp files left")
    }

    @Test
    fun corruptCacheIsRebuilt() {
        val f = synthetic()
        val first = IndexCache.loadOrBuild(f, Progress { _, _ -> })
        val cache = IndexCache.cacheFileFor(f)
        RandomAccessFile(cache, "rw").use { it.setLength(it.length() - 7) }
        val steps = mutableListOf<String>()
        val again = IndexCache.loadOrBuild(f) { step, _ -> steps += step }
        assertTrue("parse" in steps)
        assertSameIndex(first, again)
        assertSameIndex(first, IndexCache.loadOrBuild(f, Progress { _, _ -> }))
    }

    companion object {
        fun assertSameIndex(a: HeapIndex, b: HeapIndex) {
            assertArrayEquals(a.objectIds, b.objectIds)
            assertArrayEquals(a.classOf, b.classOf)
            assertArrayEquals(a.classNames, b.classNames)
            assertArrayEquals(a.classObjectIndex, b.classObjectIndex)
            assertArrayEquals(a.shallow, b.shallow)
            assertArrayEquals(a.nativeEst, b.nativeEst)
            assertArrayEquals(a.heapKind, b.heapKind)
            assertEquals(a.graph.nodeCount, b.graph.nodeCount)
            assertArrayEquals(a.graph.edgeOffsets, b.graph.edgeOffsets)
            assertArrayEquals(a.graph.edgeTargets, b.graph.edgeTargets)
            assertArrayEquals(a.graph.roots, b.graph.roots)
            assertArrayEquals(a.rootKinds, b.rootKinds)
            assertArrayEquals(a.rootThreadObj, b.rootThreadObj)
            assertArrayEquals(a.idom, b.idom)
            assertArrayEquals(a.retained, b.retained)
            assertArrayEquals(a.retainedWithNative, b.retainedWithNative)
            assertArrayEquals(a.domChildOffsets, b.domChildOffsets)
            assertArrayEquals(a.domChildren, b.domChildren)
            assertArrayEquals(a.bfsParent, b.bfsParent)
        }
    }
}
