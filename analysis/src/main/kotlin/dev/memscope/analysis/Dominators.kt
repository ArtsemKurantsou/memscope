package dev.memscope.analysis

/** Graph in CSR form over nodes 0 until n. Super-root is the virtual node `n`. */
class Graph(val nodeCount: Int, val edgeOffsets: IntArray /* size n+1 */, val edgeTargets: IntArray, val roots: IntArray)

/**
 * Dominator tree and retained sizes over primitive arrays. Everything is iterative (no recursion),
 * so graphs with millions of nodes in a single chain are fine.
 */
object Dominators {
    const val UNREACHABLE = -1

    /** Number of CHK sweeps in the most recent immediateDominators call (diagnostic; not thread-safe). */
    @Volatile internal var lastChkPasses = 0

    /**
     * idom[i] in 0..n (n = super-root, the idom of every root) or UNREACHABLE.
     * Iterative Cooper-Harvey-Kennedy over a reverse-postorder numbering of the graph plus super-root.
     * Duplicate edges, self-loops and duplicate roots are tolerated.
     */
    fun immediateDominators(g: Graph): IntArray {
        val n = g.nodeCount
        val total = n + 1 // including super-root
        val roots = g.roots
        val offsets = g.edgeOffsets
        val targets = g.edgeTargets

        // Postorder numbering by iterative DFS from the super-root.
        val post = IntArray(total) { -1 } // node -> postorder number
        val order = IntArray(total) // postorder number -> node
        val cursor = IntArray(total) // next edge index to explore per node (relative to the node's edge range)
        val stack = IntArray(total)
        var sp = 0
        var counter = 0
        val visited = BooleanArray(total)
        stack[sp++] = n
        visited[n] = true
        while (sp > 0) {
            val u = stack[sp - 1]
            val degree = if (u == n) roots.size else offsets[u + 1] - offsets[u]
            if (cursor[u] < degree) {
                val v = if (u == n) roots[cursor[u]] else targets[offsets[u] + cursor[u]]
                cursor[u]++
                if (!visited[v]) {
                    visited[v] = true
                    stack[sp++] = v
                }
            } else {
                sp--
                post[u] = counter
                order[counter++] = u
            }
        }
        val reachable = counter

        // Predecessor CSR, indexed by postorder number; self-loops are irrelevant to dominance and skipped.
        val predOffsets = IntArray(reachable + 1)
        var predCursor = IntArray(0)
        var preds = IntArray(0)
        // Two passes (count, then fill) written out directly: no lambdas, so no boxing per edge.
        for (pass in 0..1) {
            for (i in 0 until reachable) {
                val u = order[i]
                val from = i
                val begin = if (u == n) 0 else offsets[u]
                val end = if (u == n) roots.size else offsets[u + 1]
                for (e in begin until end) {
                    val to = post[if (u == n) roots[e] else targets[e]]
                    if (from == to) continue
                    if (pass == 0) predOffsets[to + 1]++ else preds[predCursor[to]++] = from
                }
            }
            if (pass == 0) {
                for (i in 0 until reachable) predOffsets[i + 1] += predOffsets[i]
                predCursor = predOffsets.copyOf(reachable)
                preds = IntArray(predOffsets[reachable])
            }
        }


        // CHK on postorder numbers: the super-root has the highest number, `reachable - 1`.
        val rootPo = reachable - 1
        val doms = IntArray(reachable) { -1 }
        doms[rootPo] = rootPo
        var changed = true
        var passes = 0
        while (changed) {
            changed = false
            passes++
            for (b in rootPo - 1 downTo 0) {
                var newIdom = -1
                for (k in predOffsets[b] until predOffsets[b + 1]) {
                    val p = preds[k]
                    if (doms[p] == -1) continue
                    newIdom = if (newIdom == -1) p else intersect(doms, p, newIdom)
                }
                if (newIdom != doms[b]) {
                    doms[b] = newIdom
                    changed = true
                }
            }
        }

        lastChkPasses = passes
        val idom = IntArray(n) { UNREACHABLE }
        for (po in 0 until rootPo) idom[order[po]] = order[doms[po]]
        return idom
    }

    private fun intersect(doms: IntArray, a: Int, b: Int): Int {
        var x = a
        var y = b
        while (x != y) {
            while (x < y) x = doms[x]
            while (y < x) y = doms[y]
        }
        return x
    }

    /** retained[i] = shallow[i] + sum of retained of dominator-tree children; 0 for unreachable. */
    fun retainedSizes(idom: IntArray, shallow: IntArray, nodeCount: Int): LongArray {
        val n = nodeCount
        val offsets = IntArray(n + 2)
        for (v in 0 until n) if (idom[v] != UNREACHABLE) offsets[idom[v] + 1]++
        for (i in 0..n) offsets[i + 1] += offsets[i]
        val fill = offsets.copyOf(n + 1)
        val children = IntArray(offsets[n + 1])
        for (v in 0 until n) if (idom[v] != UNREACHABLE) children[fill[idom[v]]++] = v

        // Breadth-first order over the dominator tree: parents always precede their children.
        val queue = IntArray(children.size)
        var tail = 0
        for (k in offsets[n] until offsets[n + 1]) queue[tail++] = children[k]
        var head = 0
        while (head < tail) {
            val u = queue[head++]
            for (k in offsets[u] until offsets[u + 1]) queue[tail++] = children[k]
        }

        val retained = LongArray(n)
        for (i in tail - 1 downTo 0) {
            val v = queue[i]
            retained[v] += shallow[v].toLong()
            val p = idom[v]
            if (p != n) retained[p] += retained[v]
        }
        return retained
    }

    /**
     * Children of each node (and of the super-root at index n) in CSR form, each list sorted by
     * retained desc, ties by node index asc. offsets has size nodeCount + 2.
     */
    fun childrenCsr(idom: IntArray, retained: LongArray, nodeCount: Int): Pair<IntArray, IntArray> {
        val n = nodeCount
        var reachable = 0
        for (v in 0 until n) if (idom[v] != UNREACHABLE) reachable++

        // Sort all reachable nodes once, then distribute stably by parent: each list stays sorted.
        val sorted = IntArray(reachable)
        var k = 0
        for (v in 0 until n) if (idom[v] != UNREACHABLE) sorted[k++] = v
        mergeSort(sorted, retained)

        val offsets = IntArray(n + 2)
        for (v in sorted) offsets[idom[v] + 1]++
        for (i in 0..n) offsets[i + 1] += offsets[i]
        val fill = offsets.copyOf(n + 1)
        val children = IntArray(reachable)
        for (v in sorted) children[fill[idom[v]]++] = v
        return offsets to children
    }

    /** Bottom-up merge sort of node ids by (retained desc, id asc); input is ascending by id. */
    private fun mergeSort(a: IntArray, retained: LongArray) {
        val len = a.size
        var src = a
        var dst = IntArray(len)
        var width = 1
        while (width < len) {
            var lo = 0
            while (lo < len) {
                val mid = minOf(lo + width, len)
                val hi = minOf(lo + 2 * width, len)
                var i = lo
                var j = mid
                var o = lo
                while (i < mid && j < hi) {
                    // Stable: take from the left run unless the right one is strictly larger.
                    dst[o++] = if (retained[src[j]] > retained[src[i]]) src[j++] else src[i++]
                }
                while (i < mid) dst[o++] = src[i++]
                while (j < hi) dst[o++] = src[j++]
                lo = hi
            }
            val t = src; src = dst; dst = t
            width *= 2
        }
        if (src !== a) System.arraycopy(src, 0, a, 0, len)
    }

    /** BFS parent from the roots (shortest path), UNREACHABLE if none, nodeCount for roots. */
    fun bfsParents(g: Graph): IntArray {
        val n = g.nodeCount
        val parent = IntArray(n) { UNREACHABLE }
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        for (r in g.roots) {
            if (parent[r] == UNREACHABLE) {
                parent[r] = n
                queue[tail++] = r
            }
        }
        while (head < tail) {
            val u = queue[head++]
            for (e in g.edgeOffsets[u] until g.edgeOffsets[u + 1]) {
                val v = g.edgeTargets[e]
                if (parent[v] == UNREACHABLE) {
                    parent[v] = u
                    queue[tail++] = v
                }
            }
        }
        return parent
    }
}
