package dev.memscope.analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.math.abs

/** Tests on a real dump of the fixture app (see [TestDumps]); needs emulator-5554 unless the dump is already captured. */
@Tag("emulator")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeapIndexerFixtureTest {
    @TempDir
    lateinit var dir: File

    private val index: HeapIndex by lazy { HeapIndexer.build(TestDumps.fixtureBasic()) }

    private val mb = 1024L * 1024L

    private fun classObject(name: String): Int {
        val entries = index.classNames.indices.filter { index.classNames[it] == name }
        assertEquals(1, entries.size, "exactly one class object for $name")
        val obj = index.classObjectIndex[entries.single()]
        assertTrue(obj >= 0)
        return obj
    }

    private fun targets(node: Int): List<Int> =
        (index.graph.edgeOffsets[node] until index.graph.edgeOffsets[node + 1]).map { index.graph.edgeTargets[it] }

    private fun className(node: Int) = index.classNames[index.classOf[node]]

    /**
     * The byte[] held in the planted static field of [holder]: the largest byte[] the class object
     * references (ART also dumps a small `$classOverhead` byte[] per class).
     */
    private fun staticByteArray(holder: String): Int =
        targets(classObject(holder)).filter { className(it) == "byte[]" }.maxBy { index.shallow[it] }

    @Test
    fun classStaticsRetainTheirBlobs() {
        for ((name, size) in listOf(
            "dev.memscope.fixture.InitProvider" to 5 * mb,
            "dev.memscope.fixture.FixtureApp" to 10 * mb,
            "dev.memscope.fixture.MainActivity" to 3 * mb,
        )) {
            val retained = index.retained[classObject(name)]
            assertTrue(retained >= size, "$name retains $retained, expected >= $size")
            assertTrue(retained < size + mb / 5, "$name retains $retained, expected < ${size + mb / 5}")
        }
    }

    @Test
    fun sharedArrayIsDominatedBySuperRoot() {
        val shared = staticByteArray("dev.memscope.fixture.SharedA")
        assertEquals(shared, staticByteArray("dev.memscope.fixture.SharedB"))
        assertEquals(mb.toInt(), index.shallow[shared])
        assertEquals(index.superRoot, index.idom[shared])
        assertFalse(shared in index.graph.roots, "the shared array is not itself a root")
    }

    @Test
    fun topLevelRetainedEqualsReachableShallow() {
        var reachableShallow = 0L
        for (i in 0 until index.objectCount) if (index.idom[i] != Dominators.UNREACHABLE) reachableShallow += index.shallow[i]
        var topRetained = 0L
        val sr = index.superRoot
        for (k in index.domChildOffsets[sr] until index.domChildOffsets[sr + 1]) topRetained += index.retained[index.domChildren[k]]
        assertEquals(reachableShallow, topRetained)
    }

    @Test
    fun fixtureBitmapsHaveNativeSizes() {
        // Actions.dupBitmaps (ArrayList) -> elementData (Object[]) -> 20 Bitmaps.
        val actions = classObject("dev.memscope.fixture.Actions")
        val bitmaps = targets(actions)
            .filter { className(it) == "java.util.ArrayList" }
            .flatMap { list -> targets(list).filter { className(it) == "java.lang.Object[]" } }
            .flatMap { arr -> targets(arr).filter { className(it) == "android.graphics.Bitmap" } }
        assertEquals(20, bitmaps.size)
        val expected = 512 * 512 * 4
        for (b in bitmaps) {
            val est = index.nativeEst[b]
            assertTrue(abs(est - expected) <= expected * 0.05, "bitmap nativeEst $est, expected ~$expected")
        }
        val big = targets(actions).single { className(it) == "android.graphics.Bitmap" } // Actions.bigBitmap
        assertTrue(abs(index.nativeEst[big] - 4000L * 4000 * 4) <= 4000L * 4000 * 4 * 0.05)
    }

    @Test
    fun heapKinds() {
        val kinds = index.heapKind
        assumeTrue(kinds.any { it != HeapKind.UNKNOWN }) { "dump has no HEAP_DUMP_INFO records: heapKind is UNKNOWN" }
        assertEquals(HeapKind.APP, kinds[staticByteArray("dev.memscope.fixture.InitProvider")])
        assertEquals(HeapKind.APP, kinds[staticByteArray("dev.memscope.fixture.FixtureApp")])
        assertEquals(HeapKind.APP, kinds[staticByteArray("dev.memscope.fixture.SharedA")])
        assertTrue(kinds[classObject("java.lang.Object")] != HeapKind.APP)
    }

    @Test
    fun rootsAreUniqueAndAnnotated() {
        val roots = index.graph.roots
        assertEquals(roots.size, roots.toSet().size)
        assertEquals(roots.size, index.rootKinds.size)
        assertEquals(roots.size, index.rootThreadObj.size)
        var frames = 0
        for (k in roots.indices) {
            val kind = RootKind.values()[index.rootKinds[k].toInt()]
            val thread = index.rootThreadObj[k]
            if (kind == RootKind.JAVA_FRAME || kind == RootKind.JNI_LOCAL) {
                if (thread >= 0) { frames++; assertTrue(className(thread).endsWith("Thread"), className(thread)) }
            } else {
                assertEquals(-1, thread)
            }
        }
        assertTrue(frames > 0, "some JAVA_FRAME roots resolve to their thread")
    }

    @Test
    fun cacheRoundTripOnRealDump() {
        val copy = File(dir, "basic.hprof")
        TestDumps.fixtureBasic().copyTo(copy)
        val steps = mutableListOf<String>()
        val built = IndexCache.loadOrBuild(copy) { s, _ -> steps += s }
        assertTrue("parse" in steps)
        steps.clear()
        val loaded = IndexCache.loadOrBuild(copy) { s, _ -> steps += s }
        assertFalse("parse" in steps, "$steps")
        HeapIndexerTest.assertSameIndex(built, loaded)
        Files.setLastModifiedTime(copy.toPath(), FileTime.fromMillis(copy.lastModified() + 5_000))
        steps.clear()
        IndexCache.loadOrBuild(copy) { s, _ -> steps += s }
        assertTrue("parse" in steps, "touching the hprof invalidates the cache")
    }

    @Test
    fun truncatedAndEmptyDumpsAreUnreadable() {
        val truncated = File(dir, "truncated.hprof")
        FileInputStream(TestDumps.fixtureBasic()).use { input ->
            FileOutputStream(truncated).use { out ->
                val buf = ByteArray(1 shl 20)
                var read = 0
                while (read < buf.size) { val k = input.read(buf, read, buf.size - read); if (k < 0) break; read += k }
                out.write(buf, 0, read)
            }
        }
        val empty = File(dir, "empty.hprof").also { it.createNewFile() }
        for (f in listOf(truncated, empty)) {
            val e = assertThrows<HprofUnreadableException> { IndexCache.loadOrBuild(f, Progress { _, _ -> }) }
            assertTrue(e.message!!.contains(f.path), e.message)
            assertFalse(IndexCache.cacheFileFor(f).exists())
        }
        assertTrue(dir.listFiles()!!.none { it.name.contains(".idx") })
    }
}
