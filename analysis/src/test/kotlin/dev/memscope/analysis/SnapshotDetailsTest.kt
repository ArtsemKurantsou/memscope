package dev.memscope.analysis

import dev.memscope.analysis.SyntheticHprof.Tree
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files

/** Device-free details tests on [SyntheticHprof.Tree]. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotDetailsTest {
    private val dir: File = Files.createTempDirectory("snapshot-details").toFile()
    private val snapshot: Snapshot by lazy {
        val file = File(dir, "tree.hprof").also { Tree.write(it) }
        Snapshot.open(file, listOf("test."))
    }
    private val index get() = snapshot.index

    @AfterAll
    fun cleanup() {
        snapshot.close()
        dir.deleteRecursively()
    }

    private fun obj(id: Long) = "obj:${index.indexOfObjectId(id).also { assertTrue(it >= 0) }}"
    private fun classObj(name: String) = "obj:${index.classObjectIndex[index.classNames.indexOf(name)]}"

    @Test
    fun pathFromRootAndReferrers() {
        val d = snapshot.details(obj(Tree.C))
        assertEquals("test.Leaf", d.className)
        assertEquals(40L, d.shallowBytes)
        assertEquals(40L, d.retainedBytes)
        assertEquals(
            listOf(
                PathStep(classObj("test.App"), "test.App", "diamond"),
                PathStep(obj(Tree.A), "test.Node", "left"),
                PathStep(obj(Tree.B), "test.Node", "left"),
                PathStep(obj(Tree.C), "test.Leaf", null),
            ),
            d.pathFromRoot,
        )
        assertEquals(2, d.referrerCount)
        assertEquals(
            setOf(PathStep(obj(Tree.B), "test.Node", "left"), PathStep(obj(Tree.D), "test.Node", "left")),
            d.referrers.toSet(),
        )
        assertEquals(emptyList<PathStep>(), d.rootsReaching, "not a child of the Shared row")
    }

    @Test
    fun sharedObjectNamesTheRootsReachingIt() {
        val d = snapshot.details(obj(Tree.SHARED))
        assertEquals(
            setOf(PathStep(classObj("test.SharedA"), "test.SharedA", "ref"), PathStep(classObj("test.SharedB"), "test.SharedB", "ref")),
            d.rootsReaching.toSet(),
        )
        // Primitive array: its length, then the first 100 elements.
        assertEquals(FieldValue("length", "int", "${Tree.SHARED_LENGTH}", null), d.fields.first())
        assertEquals(1 + 100, d.fields.size)
        assertEquals(FieldValue("[7]", "byte", "7", null), d.fields[8])
    }

    @Test
    fun instanceFieldsWithReferences() {
        val d = snapshot.details(obj(Tree.item(3)))
        val payload = d.fields.single { it.name == "payload" }
        assertEquals("byte[]", payload.type)
        assertEquals(obj(Tree.payload(3)), payload.ref)
        assertTrue(payload.value.startsWith("byte[]@"), payload.value)
        assertEquals(1, d.referrerCount)
        assertEquals(listOf(PathStep(obj(Tree.ITEMS), "java.lang.Object[]", "[3]")), d.referrers)

        val b = snapshot.details(obj(Tree.B)).fields
        assertEquals(FieldValue("right", "Object", "null", null), b.single { it.name == "right" })

        val thread = snapshot.details(obj(Tree.THREAD))
        val name = thread.fields.single { it.name == "name" }
        assertEquals("\"worker\"", name.value)
        assertEquals("java.lang.String", name.type)
        val string = snapshot.details(obj(Tree.NAME)).fields
        assertEquals(FieldValue("count", "int", "6", null), string.single { it.name == "count" })
    }

    @Test
    fun objectArrayElementsAndClassStatics() {
        val items = snapshot.details(obj(Tree.ITEMS)).fields
        assertEquals(FieldValue("length", "int", "${Tree.ITEM_COUNT + Tree.OTHER_COUNT}", null), items.first())
        assertEquals(1 + Tree.ITEM_COUNT + Tree.OTHER_COUNT, items.size)
        assertEquals(obj(Tree.item(0)), items[1].ref)
        assertEquals("[0]", items[1].name)

        val app = snapshot.details(classObj("test.App"))
        assertEquals("test.App", app.className)
        assertEquals(listOf("items", "diamond"), app.fields.map { it.name })
        assertEquals(obj(Tree.A), app.fields[1].ref)
        assertEquals(listOf(PathStep(classObj("test.App"), "test.App", null)), app.pathFromRoot)
    }

    @Test
    fun threadRowDetailsDescribeTheThreadObject() {
        val worker = snapshot.children("cat:threads").rows.single()
        val d = snapshot.details(worker.ref)
        assertEquals(obj(Tree.THREAD), d.ref)
        assertEquals("java.lang.Thread", d.className)
    }

    @Test
    fun rowsThatAreNotObjectsHaveNoDetails() {
        val group = snapshot.children(obj(Tree.ITEMS)).rows.first().ref
        for (ref in listOf("root", "cat:static", group)) assertThrows<IllegalArgumentException>(ref) { snapshot.details(ref) }
    }

    @Test
    fun unreachableObjectHasDetailsButNoPath() {
        val d = snapshot.details(obj(Tree.GARBAGE))
        assertEquals("test.Other", d.className)
        assertEquals(emptyList<PathStep>(), d.pathFromRoot)
        assertEquals(0L, d.retainedBytes)
    }
}

/** Details tests on the fixture dump (see [TestDumps]). */
@Tag("emulator")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotDetailsFixtureTest {
    private val snapshot: Snapshot by lazy { Snapshot.open(TestDumps.fixtureBasic(), listOf("dev.memscope.fixture")) }

    @AfterAll
    fun cleanup() = snapshot.close()

    @Test
    fun sharedArrayIsUnderSharedAndNamesBothRoots() {
        val shared = snapshot.allChildren("cat:shared").single { it.className == "byte[]" && it.shallowBytes == 1024L * 1024L }
        val roots = snapshot.details(shared.ref).rootsReaching.map { it.className }.toSet()
        assertTrue("dev.memscope.fixture.SharedA" in roots, "$roots")
        assertTrue("dev.memscope.fixture.SharedB" in roots, "$roots")
    }

    @Test
    fun leakedActivityPathStartsAtTheLeaksStaticField() {
        val activity = snapshot.instances("dev.memscope.fixture.LeakyActivity", 0, 25).rows.single()
        val path = snapshot.details(activity.ref).pathFromRoot
        assertEquals("dev.memscope.fixture.Leaks", path.first().className)
        assertEquals("activity", path.first().edgeLabel)
        assertEquals(activity.ref, path.last().ref)
    }
}
