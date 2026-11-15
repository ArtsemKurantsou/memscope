package dev.memscope.analysis

import dev.memscope.analysis.SyntheticHprof.Tree
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files

/** Every row under [ref], paging with the default page size. */
internal fun Snapshot.allChildren(ref: String): List<TreeRow> {
    val rows = ArrayList<TreeRow>()
    while (true) {
        val page = children(ref, rows.size, 25)
        rows += page.rows
        if (page.rows.isEmpty() || rows.size >= page.total) return rows
    }
}

/** Asserts that [path] starts at "root" and every element is a child of the previous one. */
internal fun Snapshot.assertChildChain(path: List<String>) {
    assertEquals("root", path.first())
    for (k in 1 until path.size) {
        val siblings = allChildren(path[k - 1]).map { it.ref }
        assertTrue(path[k] in siblings, "${path[k]} is not a child of ${path[k - 1]}")
        assertEquals(siblings.indexOf(path[k]), indexInParent(path[k - 1], path[k]))
    }
}

/** Device-free tree tests on [SyntheticHprof.Tree]. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotTreeTest {
    private val dir: File = Files.createTempDirectory("snapshot-tree").toFile()
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
    private fun category(label: String) = snapshot.children("root").rows.single { it.label == label }
    private fun row(parent: String, ref: String) = snapshot.allChildren(parent).single { it.ref == ref }

    @Test
    fun rootCategoriesInFixedOrderSumToReachableHeap() {
        val rows = snapshot.children("root").rows
        assertEquals(
            listOf("Static fields", "Thread stacks", "JNI globals", "Other roots", "Shared (held by several roots)"),
            rows.map { it.label },
        )
        assertEquals(snapshot.summary().reachableBytes, rows.sumOf { it.retainedBytes })
        assertEquals(100.0, rows.sumOf { it.percentOfHeap }, 1e-9)
        assertEquals(RowKind.SHARED, rows.last().kind)
        for (r in rows.dropLast(1)) assertEquals(RowKind.CATEGORY, r.kind)
        for (r in rows) {
            assertFalse(r.isAppCode)
            assertEquals(r.retainedBytes, snapshot.allChildren(r.ref).sumOf { it.retainedBytes }, "category = sum of children")
            assertEquals(snapshot.allChildren(r.ref).size, r.childCount)
        }
    }

    @Test
    fun summaryCountsReachableAndAppHeap() {
        val s = snapshot.summary()
        assertEquals(24L, s.unreachableBytes, "only GARBAGE (test.Other, 24 bytes) is unreachable")
        var reachable = 0L; var app = 0L; var count = 0
        for (i in 0 until index.objectCount) if (index.idom[i] != Dominators.UNREACHABLE) {
            reachable += index.shallow[i]; count++
            if (index.heapKind[i] == HeapKind.APP) app += index.shallow[i]
        }
        assertEquals(reachable, s.reachableBytes)
        assertEquals(app, s.appHeapBytes)
        assertEquals(count, s.objectCount)
        assertTrue(app < reachable, "class objects live in the image heap")
    }

    @Test
    fun staticFieldsListClassesWithFieldLabelledChildren() {
        val app = row(category("Static fields").ref, classObj("test.App"))
        assertEquals(RowKind.CLASS_STATICS, app.kind)
        assertEquals("test.App", app.label)
        assertTrue(app.isAppCode)
        val children = snapshot.allChildren(app.ref)
        assertEquals("items", children.single { it.ref == obj(Tree.ITEMS) }.edgeLabel)
        assertEquals("diamond", children.single { it.ref == obj(Tree.A) }.edgeLabel)
        // The class object itself is in the image heap: only its subtree's app objects count.
        val classShallow = index.shallow[index.classObjectIndex[index.classNames.indexOf("test.App")]]
        assertEquals(app.retainedBytes - classShallow, app.appHeapBytes)
    }

    @Test
    fun sameClassSiblingsCollapseIntoAGroupRow() {
        val rows = snapshot.allChildren(obj(Tree.ITEMS))
        assertEquals(1 + Tree.OTHER_COUNT, rows.size)
        val group = rows.first()
        assertEquals(RowKind.GROUP, group.kind)
        assertEquals("grp:${obj(Tree.ITEMS)}|${index.classNames.indexOf("test.Item")}", group.ref)
        assertEquals("30 × test.Item", group.label)
        assertEquals("test.Item", group.className)
        assertEquals(Tree.ITEM_COUNT, group.count)
        assertEquals(Tree.ITEM_COUNT, group.childCount)
        assertTrue(group.isAppCode)
        val expected = (0 until Tree.ITEM_COUNT).sumOf { index.retained[index.indexOfObjectId(Tree.item(it))] }
        assertEquals(expected, group.retainedBytes)
        assertEquals(Tree.ITEM_COUNT.toLong() * Tree.ITEM_SIZE, group.shallowBytes)
        // Fewer than GROUP_MIN siblings of one class stay individual rows.
        assertEquals(10, Snapshot.GROUP_MIN)
        val others = rows.drop(1)
        assertTrue(others.all { it.kind == RowKind.OBJECT && it.className == "test.Other" && it.count == 1 })
        assertEquals(setOf("[30]", "[31]", "[32]"), others.map { it.edgeLabel }.toSet())
    }

    @Test
    fun pagingThroughAGroup() {
        val group = snapshot.children(obj(Tree.ITEMS)).rows.first().ref
        val first = snapshot.children(group, 0, 25)
        val second = snapshot.children(group, 25, 25)
        assertEquals(25, first.rows.size)
        assertEquals(Tree.ITEM_COUNT, first.total)
        assertEquals(Tree.ITEM_COUNT, second.total)
        assertEquals(Tree.ITEM_COUNT - 25, second.rows.size)
        val all = first.rows + second.rows
        assertEquals(all.size, all.map { it.ref }.toSet().size, "no overlap")
        assertEquals(all.sortedByDescending { it.retainedBytes }, all)
        assertEquals(obj(Tree.item(Tree.ITEM_COUNT - 1)), all.first().ref)
        assertEquals("[29]", all.first().edgeLabel)
        assertTrue(all.all { it.kind == RowKind.OBJECT && it.className == "test.Item" })
        assertEquals(0, snapshot.children(group, 40, 25).rows.size)
    }

    @Test
    fun indirectChildIsLabelledViaHops() {
        val rows = snapshot.allChildren(obj(Tree.A))
        assertEquals("left", rows.single { it.ref == obj(Tree.B) }.edgeLabel)
        assertEquals("right", rows.single { it.ref == obj(Tree.D) }.edgeLabel)
        val c = rows.single { it.ref == obj(Tree.C) }
        assertTrue(Regex("via \\d+ hops").matches(c.edgeLabel!!), c.edgeLabel)
        assertEquals("via 2 hops", c.edgeLabel)
    }

    @Test
    fun threadStacksHaveOneRowPerThread() {
        val threads = snapshot.allChildren(category("Thread stacks").ref)
        val worker = threads.single()
        assertEquals(RowKind.THREAD, worker.kind)
        assertEquals("worker", worker.label)
        val rows = snapshot.allChildren(worker.ref)
        assertEquals("local", rows.single { it.ref == obj(Tree.LOCAL) }.edgeLabel)
        assertEquals("thread", rows.single { it.ref == obj(Tree.THREAD) }.edgeLabel)
        assertEquals(worker.retainedBytes, rows.sumOf { it.retainedBytes })

        val other = snapshot.allChildren(category("Other roots").ref).map { it.ref }.toSet()
        assertTrue(obj(Tree.LOCAL2) in other, "frame root of an unknown thread")
        assertTrue(obj(Tree.UNKNOWN) in other)
        assertEquals(listOf(obj(Tree.GLOBAL)), snapshot.allChildren(category("JNI globals").ref).map { it.ref })
        assertEquals(listOf(obj(Tree.SHARED)), snapshot.allChildren(category("Shared (held by several roots)").ref).map { it.ref })
    }

    @Test
    fun percentAndNativeColumns() {
        val reachable = snapshot.summary().reachableBytes.toDouble()
        val items = row(classObj("test.App"), obj(Tree.ITEMS))
        assertEquals(items.retainedBytes * 100.0 / reachable, items.percentOfHeap, 1e-9)
        assertEquals(items.retainedBytes, items.appHeapBytes, "all instances are in the app heap")
        assertEquals(0L, items.nativeEstBytes)
        assertEquals(1, items.count)
    }

    @Test
    fun revealPathIsAChainOfChildrenEvenBeyondTheFirstPage() {
        val target = obj(Tree.item(0)) // the smallest item: offset 29 inside its group
        val path = snapshot.revealPath(target)
        val group = "grp:${obj(Tree.ITEMS)}|${index.classNames.indexOf("test.Item")}"
        assertEquals(listOf("root", "cat:static", classObj("test.App"), obj(Tree.ITEMS), group, target), path)
        snapshot.assertChildChain(path)
        assertEquals(Tree.ITEM_COUNT - 1, snapshot.indexInParent(group, target))

        for (id in listOf(Tree.C, Tree.LOCAL, Tree.THREAD, Tree.LOCAL2, Tree.GLOBAL, Tree.SHARED, Tree.NAME_CHARS)) {
            val p = snapshot.revealPath(obj(id))
            assertEquals(obj(id), p.last())
            snapshot.assertChildChain(p)
        }
        for (ref in listOf("root", "cat:threads", group)) assertEquals(ref, snapshot.revealPath(ref).last())
        assertEquals(-1, snapshot.indexInParent(obj(Tree.A), obj(Tree.ITEMS)))
    }

    @Test
    fun refsRoundTripAndMalformedRefsAreRejected() {
        val worker = snapshot.allChildren("cat:threads").single().ref
        for (ref in listOf("root", "cat:static", worker, obj(Tree.ITEMS), snapshot.children(obj(Tree.ITEMS)).rows.first().ref)) {
            assertNotNull(snapshot.children(ref))
            assertEquals(ref, snapshot.revealPath(ref).last())
        }
        val bad = listOf(
            "", "obj:", "obj:x", "obj:-1", "obj:${index.objectCount}", "obj:99999999999", "cat:nope", "thr:${index.indexOfObjectId(Tree.A)}",
            "grp:", "grp:obj:1", "grp:obj:1|x", "grp:obj:1|${index.classNames.size}", "grp:root|1", "grp:cat:static|1",
            "grp:grp:obj:1|1|1", "nonsense", "obj:1 ",
        )
        for (ref in bad) {
            assertThrows<IllegalArgumentException>(ref) { snapshot.children(ref) }
            assertThrows<IllegalArgumentException>(ref) { snapshot.revealPath(ref) }
            assertThrows<IllegalArgumentException>(ref) { snapshot.details(ref) }
        }
        assertThrows<IllegalArgumentException> { snapshot.children("root", -1, 25) }
        assertThrows<IllegalArgumentException> { snapshot.children("root", 0, -1) }
        assertThrows<IllegalArgumentException> { snapshot.revealPath(obj(Tree.GARBAGE)) }
    }

    @Test
    fun classStatisticsDoNotDoubleCountNestedInstances() {
        val classes = snapshot.classes()
        assertEquals(classes.sortedByDescending { it.retainedBytes }, classes)
        val node = classes.single { it.className == "test.Node" }
        assertEquals(3, node.instanceCount)
        assertEquals(index.retained[index.indexOfObjectId(Tree.A)], node.retainedBytes, "B, C, D are inside A")
        assertTrue(node.isAppCode)
        val bytes = classes.single { it.className == "byte[]" }
        assertEquals(Tree.ITEM_COUNT + 1, bytes.instanceCount)
        assertEquals((1..Tree.ITEM_COUNT).sumOf { it * 10L } + Tree.SHARED_LENGTH, bytes.shallowBytes)
        assertFalse(bytes.isAppCode)
        assertEquals(Tree.OTHER_COUNT, classes.single { it.className == "test.Other" }.instanceCount, "unreachable GARBAGE is not counted")
        assertTrue(classes === snapshot.classes(), "computed once")
        assertEquals(snapshot.summary().classCount, classes.size)
    }

    @Test
    fun instancesAreSortedByRetainedAndPaged() {
        val first = snapshot.instances("test.Item", 0, 25)
        assertEquals(Tree.ITEM_COUNT, first.total)
        assertEquals(25, first.rows.size)
        assertEquals(obj(Tree.item(Tree.ITEM_COUNT - 1)), first.rows.first().ref)
        val rest = snapshot.instances("test.Item", 25, 25).rows
        assertEquals(Tree.ITEM_COUNT - 25, rest.size)
        val all = first.rows + rest
        assertEquals(all.sortedByDescending { it.retainedBytes }, all)
        assertEquals(0, snapshot.instances("no.such.Class", 0, 25).total)
    }
}

/** Tree tests on the fixture dump (see [TestDumps]). */
@Tag("emulator")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotTreeFixtureTest {
    private val snapshot: Snapshot by lazy { Snapshot.open(TestDumps.fixtureBasic(), listOf("dev.memscope.fixture")) }
    private val mb = 1024.0 * 1024.0

    @AfterAll
    fun cleanup() = snapshot.close()

    private fun staticsRow(className: String): TreeRow =
        snapshot.allChildren("cat:static").single { it.label == className }

    @Test
    fun rootCategoriesSumToReachableHeap() {
        assertEquals(snapshot.summary().reachableBytes, snapshot.children("root").rows.sumOf { it.retainedBytes })
    }

    @Test
    fun staticCacheDrillsDownToAGroupOf200() {
        val caches = staticsRow("dev.memscope.fixture.Caches")
        assertEquals(RowKind.CLASS_STATICS, caches.kind)
        assertTrue(caches.isAppCode)
        var row = caches
        var depth = 0
        while (row.kind != RowKind.GROUP) {
            row = snapshot.children(row.ref, 0, 1).rows.single()
            assertTrue(++depth < 10, "no group within 10 levels")
        }
        assertEquals(200, row.count)
        assertEquals(20 * mb, row.retainedBytes.toDouble(), 20 * mb * 0.05)
        // Each 100 KB byte[] value is dominated by its own HashMap entry, so the 200 siblings are the
        // entries (the brief expected byte[], which cannot be siblings in the dominator tree).
        assertEquals("java.util.HashMap\$Node", row.className)
        val entry = snapshot.children(row.ref, 0, 1).rows.single()
        val value = snapshot.allChildren(entry.ref).single { it.className == "byte[]" }
        assertEquals(100L * 1024, value.shallowBytes)
        assertEquals("value", value.edgeLabel)
    }

    @Test
    fun bitmapsAreOneGroupUnderTheListBackingArray() {
        val list = snapshot.allChildren(staticsRow("dev.memscope.fixture.Actions").ref).single { it.edgeLabel == "dupBitmaps" }
        val backing = snapshot.allChildren(list.ref).single { it.edgeLabel == "elementData" }
        val group = snapshot.allChildren(backing.ref).single { it.kind == RowKind.GROUP }
        assertEquals(20, group.count)
        assertEquals("android.graphics.Bitmap", group.className)
        assertTrue(group.nativeEstBytes >= 20L * 512 * 512 * 4, "pixels are native: ${group.nativeEstBytes}")
    }

    @Test
    fun pagingHasNoOverlap() {
        for (ref in listOf("cat:other", "cat:static")) {
            val first = snapshot.children(ref, 0, 25)
            val second = snapshot.children(ref, 25, 25)
            assertTrue(first.rows.size <= 25)
            assertEquals(first.total, second.total)
            assertTrue(first.total > 50, "$ref has ${first.total} rows")
            assertEquals(25, second.rows.size)
            assertTrue(first.rows.map { it.ref }.intersect(second.rows.map { it.ref }.toSet()).isEmpty())
            assertTrue(first.rows.last().retainedBytes >= second.rows.first().retainedBytes)
        }
    }

    @Test
    fun revealPathOfTheLeakedActivity() {
        val activity = snapshot.instances("dev.memscope.fixture.LeakyActivity", 0, 25).rows.single()
        val path = snapshot.revealPath(activity.ref)
        assertEquals(activity.ref, path.last())
        snapshot.assertChildChain(path)
    }

    @Test
    fun byteArraysHaveTheLargestShallowSize() {
        assertEquals("byte[]", snapshot.classes().maxBy { it.shallowBytes }.className)
    }
}
