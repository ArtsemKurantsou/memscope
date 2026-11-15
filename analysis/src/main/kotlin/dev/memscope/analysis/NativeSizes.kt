package dev.memscope.analysis

/**
 * Estimates native memory owned by Java objects.
 *
 * Primary source: `sun.misc.Cleaner` instances whose `thunk` is a
 * `libcore.util.NativeAllocationRegistry$CleanerThunk`; its `this$0` is the `NativeAllocationRegistry`
 * whose `size` (low bit used as a flag, so `size & ~1`) is attributed to the cleaner's `referent`.
 * Several cleaners for one owner are summed. Fallback: an `android.graphics.Bitmap` with no cleaner
 * gets `mWidth * mHeight * 4`.
 *
 * The indexer feeds the relevant field values while streaming; [compute] resolves the chains.
 * Only these few classes are recorded, so the buffers stay small.
 */
internal class NativeSizes {
    companion object {
        const val CLEANER = "sun.misc.Cleaner"
        const val THUNK = "libcore.util.NativeAllocationRegistry\$CleanerThunk"
        const val REGISTRY = "libcore.util.NativeAllocationRegistry"
        const val BITMAP = "android.graphics.Bitmap"
    }

    // Cleaner: hprof ids of referent and thunk.
    private val cleanerReferent = LongBuf()
    private val cleanerThunk = LongBuf()
    // CleanerThunk: own id -> this$0 id.
    private val thunkId = LongBuf()
    private val thunkRegistry = LongBuf()
    // NativeAllocationRegistry: own id -> size.
    private val registryId = LongBuf()
    private val registrySize = LongBuf()
    // Bitmap: dense index, width, height.
    private val bitmapIndex = IntBuf()
    private val bitmapPixels = LongBuf()

    fun addCleaner(referentId: Long, thunkObjectId: Long) {
        if (referentId == 0L || thunkObjectId == 0L) return
        cleanerReferent.add(referentId); cleanerThunk.add(thunkObjectId)
    }

    fun addThunk(id: Long, registryObjectId: Long) {
        if (registryObjectId == 0L) return
        thunkId.add(id); thunkRegistry.add(registryObjectId)
    }

    fun addRegistry(id: Long, size: Long) {
        registryId.add(id); registrySize.add(size)
    }

    fun addBitmap(denseIndex: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        bitmapIndex.add(denseIndex); bitmapPixels.add(width.toLong() * height.toLong())
    }

    /** nativeEst per dense index; [indexOf] maps an hprof id to a dense index or -1. */
    fun compute(n: Int, indexOf: (Long) -> Int): IntArray {
        val est = IntArray(n)
        val thunks = SortedLongMap(thunkId.trimmed(), thunkRegistry.trimmed())
        val registries = SortedLongMap(registryId.trimmed(), registrySize.trimmed())
        for (i in 0 until cleanerReferent.size) {
            val owner = indexOf(cleanerReferent[i])
            if (owner < 0) continue
            val registry = thunks.get(cleanerThunk[i]) ?: continue
            val size = registries.get(registry) ?: continue
            est[owner] = saturatingAdd(est[owner], size and 1L.inv())
        }
        for (i in 0 until bitmapIndex.size) {
            val b = bitmapIndex[i]
            if (est[b] == 0) est[b] = saturatingAdd(0, bitmapPixels[i] * 4)
        }
        return est
    }

    private fun saturatingAdd(a: Int, b: Long): Int =
        (a.toLong() + b.coerceAtLeast(0)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** id -> value lookup over parallel arrays, which it sorts in place (heapsort: no boxing, no recursion). */
    private class SortedLongMap(private val k: LongArray, private val v: LongArray) {
        init {
            val n = k.size
            for (i in n / 2 - 1 downTo 0) siftDown(i, n)
            for (end in n - 1 downTo 1) {
                swap(0, end)
                siftDown(0, end)
            }
        }

        private fun siftDown(start: Int, end: Int) {
            var root = start
            while (true) {
                var child = 2 * root + 1
                if (child >= end) return
                if (child + 1 < end && k[child + 1] > k[child]) child++
                if (k[root] >= k[child]) return
                swap(root, child)
                root = child
            }
        }

        private fun swap(a: Int, b: Int) {
            val tk = k[a]; k[a] = k[b]; k[b] = tk
            val tv = v[a]; v[a] = v[b]; v[b] = tv
        }

        fun get(key: Long): Long? {
            val i = java.util.Arrays.binarySearch(k, key)
            return if (i >= 0) v[i] else null
        }
    }
}
