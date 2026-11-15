package dev.memscope.analysis.findings.rules

import dev.memscope.analysis.Dominators
import dev.memscope.analysis.HeapIndex
import dev.memscope.analysis.HeapKind

/** Reachable object on the app heap (or any heap when the dump has no heap info). */
internal fun HeapIndex.isReachableApp(d: Int): Boolean {
    if (idom[d] == Dominators.UNREACHABLE) return false
    val k = heapKind[d]
    return k == HeapKind.APP || k == HeapKind.UNKNOWN
}

/** Table indices of the classes named [name] (one name can have several class objects). */
internal fun HeapIndex.classTableIndices(name: String): BooleanArray =
    BooleanArray(classNames.size) { classNames[it] == name }

/** In-place heapsort of [keys] with [values] moved alongside (no boxing, no recursion). */
internal fun sortParallel(keys: LongArray, values: IntArray, size: Int = keys.size) {
    fun swap(a: Int, b: Int) {
        val tk = keys[a]; keys[a] = keys[b]; keys[b] = tk
        val tv = values[a]; values[a] = values[b]; values[b] = tv
    }
    fun siftDown(start: Int, end: Int) {
        var root = start
        while (true) {
            var child = 2 * root + 1
            if (child >= end) return
            if (child + 1 < end && keys[child + 1] > keys[child]) child++
            if (keys[root] >= keys[child]) return
            swap(root, child)
            root = child
        }
    }
    for (i in size / 2 - 1 downTo 0) siftDown(i, size)
    for (end in size - 1 downTo 1) { swap(0, end); siftDown(0, end) }
}
