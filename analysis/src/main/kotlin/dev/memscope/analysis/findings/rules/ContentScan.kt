package dev.memscope.analysis.findings.rules

import dev.memscope.analysis.IntBuf
import dev.memscope.analysis.LongBuf
import dev.memscope.analysis.Snapshot
import dev.memscope.analysis.findings.Thresholds
import shark.HprofHeader
import shark.HprofRecordTag
import shark.StreamingHprofReader
import java.util.BitSet

/** Objects with identical content: [members] are dense indices (arrays; for strings the value arrays). */
internal class ContentGroup(val members: IntArray)

/**
 * Finds identical primitive arrays and identical String values with one streaming pass over the
 * hprof's PRIMITIVE_ARRAY_DUMP records. Per candidate only (dense index, 64-bit hash) is kept, in
 * primitive arrays; groups are the runs of equal (length, hash). Arrays over [Thresholds.HASH_PREFIX_BYTES]
 * are hashed on their first bytes and length only, so every group is [confirm]ed afterwards.
 */
internal class ContentScan private constructor(
    val strings: List<ContentGroup>,
    val arrays: List<ContentGroup>,
    /** String instance (dense) owning a value array (dense), or -1. */
    private val ownerOfValue: IntArray?,
) {
    fun stringOf(valueArray: Int): Int = ownerOfValue?.get(valueArray)?.let { it - 1 } ?: -1

    companion object {
        private val STRING_VALUE_CLASSES = setOf("byte[]", "char[]")
        private val PRIMITIVE_ARRAY_CLASSES = setOf("boolean[]", "char[]", "float[]", "double[]", "byte[]", "short[]", "int[]", "long[]")
        private val PRIMITIVE_SIZES = intArrayOf(0, 0, 0, 0, 1, 2, 4, 8, 1, 2, 4, 8)

        fun run(s: Snapshot): ContentScan {
            val idx = s.index
            val n = idx.objectCount
            val isString = idx.classTableIndices("java.lang.String")
            val isValueClass = BooleanArray(idx.classNames.size) { idx.classNames[it] in STRING_VALUE_CLASSES }
            val stringValue = BitSet(n)
            val owner = IntArray(n)
            var anyString = false
            for (d in 0 until n) {
                if (!isString[idx.classOf[d]] || !idx.isReachableApp(d)) continue
                for (e in idx.graph.edgeOffsets[d] until idx.graph.edgeOffsets[d + 1]) {
                    val t = idx.graph.edgeTargets[e]
                    if (isValueClass[idx.classOf[t]] && idx.isReachableApp(t)) { stringValue.set(t); owner[t] = d + 1; anyString = true }
                }
            }
            val bigArray = BitSet(n)
            val isPrimitiveArray = BooleanArray(idx.classNames.size) { idx.classNames[it] in PRIMITIVE_ARRAY_CLASSES }
            for (d in 0 until n) {
                if (idx.shallow[d] >= Thresholds.DUPLICATE_ARRAY_MIN_BYTES && isPrimitiveArray[idx.classOf[d]] && idx.isReachableApp(d)) bigArray.set(d)
            }
            val stringHashes = LongBuf(); val stringIdx = IntBuf()
            val arrayHashes = LongBuf(); val arrayIdx = IntBuf()
            if (anyString || !bigArray.isEmpty) {
                val header = HprofHeader.parseHeaderOf(s.hprofFile)
                StreamingHprofReader.readerFor(s.hprofFile, header).readRecords(setOf(HprofRecordTag.PRIMITIVE_ARRAY_DUMP)) { _, _, r ->
                    val id = r.readId(); r.skip(4)
                    val len = r.readInt(); val type = r.readUnsignedByte()
                    val size = len.toLong() * (PRIMITIVE_SIZES.getOrElse(type) { 0 })
                    val d = idx.indexOfObjectId(id)
                    val wantString = d >= 0 && stringValue.get(d)
                    val wantArray = d >= 0 && bigArray.get(d)
                    if (!wantString && !wantArray) {
                        r.skip(size)
                    } else {
                        val take = minOf(size, Thresholds.HASH_PREFIX_BYTES).toInt()
                        val bytes = r.readByteArray(take)
                        r.skip(size - take)
                        val h = hash(bytes, take, size, type)
                        if (wantString) { stringHashes.add(h); stringIdx.add(d) }
                        if (wantArray && !wantString) { arrayHashes.add(h); arrayIdx.add(d) }
                    }
                }
            }
            return ContentScan(group(stringHashes, stringIdx), group(arrayHashes, arrayIdx), if (anyString) owner else null)
        }

        private fun group(hashes: LongBuf, ids: IntBuf): List<ContentGroup> {
            val n = ids.size
            val keys = hashes.trimmed(); val values = ids.trimmed()
            sortParallel(keys, values, n)
            val out = ArrayList<ContentGroup>()
            var i = 0
            while (i < n) {
                var j = i + 1
                while (j < n && keys[j] == keys[i]) j++
                if (j - i >= 2) out += ContentGroup(values.copyOfRange(i, j).also { it.sort() })
                i = j
            }
            return out
        }

        /** 64-bit multiply-xorshift hash over 8-byte words; the total size and element type are mixed in. */
        fun hash(b: ByteArray, len: Int, totalSize: Long, type: Int): Long {
            var h = totalSize * -0x61c8864680b583ebL + type
            var i = 0
            while (i + 8 <= len) {
                var w = 0L
                for (k in 0 until 8) w = (w shl 8) or (b[i + k].toLong() and 0xFF)
                h = (h xor w) * -0x61c8864680b583ebL
                h = h xor (h ushr 29)
                i += 8
            }
            var w = 0L
            while (i < len) { w = (w shl 8) or (b[i].toLong() and 0xFF); i++ }
            h = (h xor w) * -0x3b314601e57a13adL
            return h xor (h ushr 32)
        }
    }
}
