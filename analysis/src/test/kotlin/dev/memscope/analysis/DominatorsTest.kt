package dev.memscope.analysis

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class DominatorsTest {
    private fun graph(n: Int, edges: List<Pair<Int, Int>>, roots: IntArray): Graph {
        val offsets = IntArray(n + 1)
        for ((u, _) in edges) offsets[u + 1]++
        for (i in 0 until n) offsets[i + 1] += offsets[i]
        val cursor = offsets.copyOf()
        val targets = IntArray(edges.size)
        for ((u, v) in edges) targets[cursor[u]++] = v
        return Graph(n, offsets, targets, roots)
    }

    private fun tens(n: Int) = IntArray(n) { 10 }

    private fun diamondGraph() =
        graph(5, listOf(0 to 1, 0 to 2, 1 to 3, 2 to 3, 3 to 4), intArrayOf(0))

    @Test
    fun diamond() {
        val g = diamondGraph()
        val idom = Dominators.immediateDominators(g)
        assertArrayEquals(intArrayOf(5, 0, 0, 0, 3), idom)
        val retained = Dominators.retainedSizes(idom, tens(5), 5)
        assertArrayEquals(longArrayOf(50, 10, 10, 20, 10), retained)
    }

    @Test
    fun sharedBetweenRoots() {
        val g = graph(4, listOf(0 to 2, 1 to 2, 2 to 3), intArrayOf(0, 1))
        val idom = Dominators.immediateDominators(g)
        assertArrayEquals(intArrayOf(4, 4, 4, 2), idom)
        assertArrayEquals(longArrayOf(10, 10, 20, 10), Dominators.retainedSizes(idom, tens(4), 4))
    }

    @Test
    fun cycle() {
        val g = graph(3, listOf(0 to 1, 1 to 2, 2 to 1), intArrayOf(0))
        val idom = Dominators.immediateDominators(g)
        assertArrayEquals(intArrayOf(3, 0, 1), idom)
        assertArrayEquals(longArrayOf(30, 20, 10), Dominators.retainedSizes(idom, tens(3), 3))
    }

    @Test
    fun unreachableNode() {
        val g = graph(3, listOf(0 to 1, 2 to 1), intArrayOf(0))
        val idom = Dominators.immediateDominators(g)
        assertArrayEquals(intArrayOf(3, 0, Dominators.UNREACHABLE), idom)
        assertArrayEquals(longArrayOf(20, 10, 0), Dominators.retainedSizes(idom, tens(3), 3))
    }

    @Test
    fun duplicateEdgesSelfLoopsAndDuplicateRoots() {
        val g = graph(
            5,
            listOf(0 to 1, 0 to 1, 0 to 2, 1 to 3, 2 to 3, 3 to 3, 3 to 4, 4 to 4, 4 to 4),
            intArrayOf(0, 0, 0),
        )
        val idom = Dominators.immediateDominators(g)
        assertArrayEquals(intArrayOf(5, 0, 0, 0, 3), idom)
        assertArrayEquals(longArrayOf(50, 10, 10, 20, 10), Dominators.retainedSizes(idom, tens(5), 5))
    }

    @Test
    fun selfLoopOnRoot() {
        val g = graph(2, listOf(0 to 0, 0 to 1), intArrayOf(0))
        assertArrayEquals(intArrayOf(2, 0), Dominators.immediateDominators(g))
    }

    @Test
    fun deepChain() {
        val n = 2_000_000
        val offsets = IntArray(n + 1) { minOf(it, n - 1) }
        val targets = IntArray(n - 1) { it + 1 }
        val g = Graph(n, offsets, targets, intArrayOf(0))
        val idom = Dominators.immediateDominators(g)
        assertEquals(n, idom[0])
        assertEquals(n - 2, idom[n - 1])
        val retained = Dominators.retainedSizes(idom, tens(n), n)
        assertEquals(20_000_000L, retained[0])
        assertEquals(10L, retained[n - 1])
        val bfs = Dominators.bfsParents(g)
        assertEquals(n - 2, bfs[n - 1])
    }

    @Test
    fun childrenCsrSortedByRetainedDescThenIndex() {
        val g = diamondGraph()
        val idom = Dominators.immediateDominators(g)
        val retained = Dominators.retainedSizes(idom, tens(5), 5)
        val (offsets, children) = Dominators.childrenCsr(idom, retained, 5)
        assertEquals(7, offsets.size)
        assertArrayEquals(intArrayOf(0, 3, 3, 3, 4, 4, 5), offsets)
        // node 0 children: 3 (20), 1 (10), 2 (10); node 3 child: 4; super-root child: 0
        assertArrayEquals(intArrayOf(3, 1, 2), children.copyOfRange(offsets[0], offsets[1]))
        assertArrayEquals(intArrayOf(4), children.copyOfRange(offsets[3], offsets[4]))
        assertArrayEquals(intArrayOf(0), children.copyOfRange(offsets[5], offsets[6]))
    }

    @Test
    fun childrenCsrSkipsUnreachable() {
        val g = graph(3, listOf(0 to 1, 2 to 1), intArrayOf(0))
        val idom = Dominators.immediateDominators(g)
        val retained = Dominators.retainedSizes(idom, tens(3), 3)
        val (offsets, children) = Dominators.childrenCsr(idom, retained, 3)
        assertEquals(2, children.size)
        assertEquals(1, offsets[3 + 1] - offsets[3])
    }

    @Test
    fun bfsParentsOnDiamond() {
        val g = diamondGraph()
        val p = Dominators.bfsParents(g)
        assertEquals(5, p[0])
        assertEquals(0, p[1])
        assertEquals(0, p[2])
        assertTrue(p[3] == 1 || p[3] == 2)
        assertEquals(3, p[4])
        // D is two hops from root A
        assertEquals(0, p[p[3]])
    }

    @Test
    fun bfsParentsUnreachableAndDuplicateRoots() {
        val g = graph(3, listOf(0 to 1), intArrayOf(0, 0))
        assertArrayEquals(intArrayOf(3, 0, Dominators.UNREACHABLE), Dominators.bfsParents(g))
    }

    @Test
    fun randomGraphsMatchBruteForce() {
        val rnd = Random(42)
        repeat(200) { iter ->
            val n = 30
            val roots = IntArray(1 + rnd.nextInt(3)) { rnd.nextInt(n) }
            // Bias toward reachability: a random spanning forest hanging off the roots, plus extra random
            // edges (forward, back, self-loops, duplicates).
            val edges = ArrayList<Pair<Int, Int>>()
            val order = (0 until n).shuffled(rnd)
            val placed = mutableListOf<Int>()
            for (r in roots.distinct()) placed.add(r)
            for (v in order) {
                if (v in placed) continue
                if (rnd.nextInt(10) == 0) continue // occasionally leave a node unreachable
                edges.add(placed[rnd.nextInt(placed.size)] to v)
                placed.add(v)
            }
            repeat(10 + rnd.nextInt(60)) { edges.add(rnd.nextInt(n) to rnd.nextInt(n)) }
            val g = graph(n, edges, roots)
            val expected = bruteForceIdom(n, edges, roots)
            val actual = Dominators.immediateDominators(g)
            assertArrayEquals(expected, actual, "graph #$iter edges=$edges roots=${roots.toList()}")
        }
    }

    @Test
    fun largeIrregularGraph() {
        val n = 5_000_000
        val m = 20_000_000
        val rnd = Random(7)
        val src = IntArray(m)
        val dst = IntArray(m)
        val rootCount = 5
        var e = 0
        // Spanning tree: each node hangs off an earlier node, 30% of the time its immediate predecessor (deep paths).
        for (v in rootCount until n) {
            src[e] = if (rnd.nextInt(10) < 3) v - 1 else rnd.nextInt(v)
            dst[e++] = v
        }
        val hubs = IntArray(20) { rnd.nextInt(n) }
        while (e < m) {
            val r = rnd.nextInt(10)
            when {
                r < 2 -> { src[e] = rnd.nextInt(n); dst[e++] = hubs[rnd.nextInt(hubs.size)] } // many refs to hubs
                else -> { src[e] = rnd.nextInt(n); dst[e++] = rnd.nextInt(n) } // forward and back edges
            }
        }
        val offsets = IntArray(n + 1)
        for (i in 0 until m) offsets[src[i] + 1]++
        for (i in 0 until n) offsets[i + 1] += offsets[i]
        val cur = offsets.copyOf(n)
        val targets = IntArray(m)
        for (i in 0 until m) targets[cur[src[i]]++] = dst[i]
        val g = Graph(n, offsets, targets, IntArray(rootCount) { it })
        val shallow = IntArray(n) { 1 + (it and 15) }

        fun <T> timed(name: String, log: StringBuilder, f: () -> T): T {
            val t0 = System.nanoTime()
            val r = f()
            log.append("$name: ${(System.nanoTime() - t0) / 1_000_000} ms\n")
            return r
        }
        val log = StringBuilder()
        val total0 = System.nanoTime()
        val idom = timed("immediateDominators", log) { Dominators.immediateDominators(g) }
        log.append("chkPasses: ${Dominators.lastChkPasses}\n")
        val retained = timed("retainedSizes", log) { Dominators.retainedSizes(idom, shallow, n) }
        val (offs, children) = timed("childrenCsr", log) { Dominators.childrenCsr(idom, retained, n) }
        val bfs = timed("bfsParents", log) { Dominators.bfsParents(g) }
        val totalMs = (System.nanoTime() - total0) / 1_000_000
        val peak = java.lang.management.ManagementFactory.getMemoryPoolMXBeans()
            .filter { it.type == java.lang.management.MemoryType.HEAP }.sumOf { it.peakUsage.used } shr 20
        log.append("totalMs: $totalMs\npeakHeapMb(sum of pool peaks, incl. test graph): $peak\n")
        java.io.File("build").mkdirs()
        java.io.File("build/dominators-large.txt").writeText(log.toString())

        assertTrue(totalMs < 60_000, "took $totalMs ms")
        var reachableShallow = 0L
        var reachableCount = 0
        for (v in 0 until n) if (idom[v] != Dominators.UNREACHABLE) { reachableShallow += shallow[v]; reachableCount++ }
        assertTrue(reachableCount > n / 2, "graph should be mostly reachable: $reachableCount")
        var rootSum = 0L
        for (k in offs[n] until offs[n + 1]) rootSum += retained[children[k]]
        assertEquals(reachableShallow, rootSum)
        assertEquals(reachableCount, children.size)
        for (v in 0 until n) assertEquals(idom[v] == Dominators.UNREACHABLE, bfs[v] == Dominators.UNREACHABLE)
    }

    /** Naive oracle: X dominates v iff v is reachable but stops being reachable once X is removed. */
    private fun bruteForceIdom(n: Int, edges: List<Pair<Int, Int>>, roots: IntArray): IntArray {
        val adj = Array(n + 1) { mutableListOf<Int>() }
        for ((u, v) in edges) adj[u].add(v)
        for (r in roots) adj[n].add(r)
        fun reach(removed: Int): BooleanArray {
            val seen = BooleanArray(n + 1)
            if (removed == n) return seen
            val stack = ArrayDeque<Int>()
            seen[n] = true; stack.add(n)
            while (stack.isNotEmpty()) {
                val u = stack.removeLast()
                for (v in adj[u]) if (v != removed && !seen[v]) { seen[v] = true; stack.add(v) }
            }
            return seen
        }
        val all = reach(-1)
        val without = Array(n + 1) { reach(it) }
        // doms[v] = set of strict dominators of v
        val result = IntArray(n) { Dominators.UNREACHABLE }
        for (v in 0 until n) {
            if (!all[v]) continue
            val strict = (0..n).filter { x -> x != v && !without[x][v] }
            // The immediate dominator is the strict dominator that has the most strict dominators itself.
            result[v] = strict.maxByOrNull { x -> (0..n).count { y -> y != x && all[x] && !without[y][x] } }!!
        }
        return result
    }
}
