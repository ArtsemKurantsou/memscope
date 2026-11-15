package dev.memscope.analysis

import shark.GcRoot
import shark.HprofHeader
import shark.HprofRecord
import shark.HprofRecord.HeapDumpRecord.GcRootRecord
import shark.HprofRecord.HeapDumpRecord.HeapDumpInfoRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.StaticFieldRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import shark.HprofVersion
import shark.HprofWriter
import shark.ValueHolder
import java.io.File
import java.nio.ByteBuffer

/**
 * Small synthetic dumps that differ in known ways, for [SnapshotDiffTest]. Class objects are in heap
 * "image", every instance in heap "app".
 *
 *   test.App.items (static) -> Object[] [ITEM_0 .. ITEM_{itemCount-1}, LATE_0.., GONE_0..]
 *   ITEM_i (test.Item).payload -> byte[payloadBytes]
 *   test.Cache.data (static) -> byte[cacheBytes]
 *   test.Late instances exist only when [lateCount] > 0; test.Gone instances only when [goneCount] > 0.
 */
object DiffHprof {
    const val ITEM_SIZE = 16
    const val LATE_SIZE = 24
    const val GONE_SIZE = 40

    private const val OBJ = 2
    private const val C_OBJECT = 1L; private const val C_CLASS = 2L; private const val C_OBJARR = 3L; private const val C_BYTES = 4L
    private const val C_APP = 5L; private const val C_CACHE = 6L; private const val C_ITEM = 7L; private const val C_LATE = 8L; private const val C_GONE = 9L
    private const val ITEMS = 100L; private const val CACHE_DATA = 101L
    private fun item(i: Int) = 1_000L + i
    private fun payload(i: Int) = 2_000L + i
    private fun late(i: Int) = 3_000L + i
    private fun gone(i: Int) = 4_000L + i

    fun write(file: File, itemCount: Int, payloadBytes: Int = 100, cacheBytes: Int = 16, lateCount: Int = 0, goneCount: Int = 0) {
        var nextString = 1L
        val strings = LinkedHashMap<String, Long>()
        fun s(value: String) = strings.getOrPut(value) { nextString++ }

        class K(val id: Long, val name: String, val size: Int, val fields: List<String>, val statics: List<Pair<String, Long>> = emptyList())
        val classes = listOf(
            K(C_OBJECT, "java.lang.Object", 8, emptyList()),
            K(C_CLASS, "java.lang.Class", SyntheticHprof.CLASS_INSTANCE_SIZE, emptyList()),
            K(C_OBJARR, "java.lang.Object[]", 0, emptyList()),
            K(C_BYTES, "byte[]", 0, emptyList()),
            K(C_APP, "test.App", 8, emptyList(), listOf("items" to ITEMS)),
            K(C_CACHE, "test.Cache", 8, emptyList(), listOf("data" to CACHE_DATA)),
            K(C_ITEM, "test.Item", ITEM_SIZE, listOf("payload")),
            K(C_LATE, "test.Late", LATE_SIZE, emptyList()),
            K(C_GONE, "test.Gone", GONE_SIZE, emptyList()),
        )
        for (c in classes) { s(c.name); c.fields.forEach { s(it) }; c.statics.forEach { s(it.first) } }
        s("app"); s("image")

        val header = HprofHeader(System.currentTimeMillis(), HprofVersion.ANDROID, 4)
        HprofWriter.openWriterFor(file, header).use { w ->
            for ((value, id) in strings) w.write(HprofRecord.StringRecord(id, value))
            classes.forEachIndexed { i, c -> w.write(HprofRecord.LoadClassRecord(i + 1, c.id, 0, s(c.name))) }
            w.write(HeapDumpInfoRecord('I'.code, s("image")))
            for (c in classes) {
                w.write(
                    ClassDumpRecord(
                        c.id, 0, if (c.id == C_OBJECT) 0 else C_OBJECT, 0, 0, 0, c.size,
                        c.statics.map { StaticFieldRecord(s(it.first), OBJ, ValueHolder.ReferenceHolder(it.second)) },
                        c.fields.map { FieldRecord(s(it), OBJ) },
                    ),
                )
            }
            w.write(HeapDumpInfoRecord('A'.code, s("app")))
            fun inst(id: Long, classId: Long, vararg values: Int) {
                val buf = ByteBuffer.allocate(values.size * 4)
                for (v in values) buf.putInt(v)
                w.write(InstanceDumpRecord(id, 0, classId, buf.array()))
            }
            val elements = (0 until itemCount).map { item(it) } + (0 until lateCount).map { late(it) } + (0 until goneCount).map { gone(it) }
            w.write(ObjectArrayDumpRecord(ITEMS, 0, C_OBJARR, elements.toLongArray()))
            for (i in 0 until itemCount) {
                inst(item(i), C_ITEM, payload(i).toInt())
                w.write(ByteArrayDump(payload(i), 0, ByteArray(payloadBytes)))
            }
            for (i in 0 until lateCount) inst(late(i), C_LATE)
            for (i in 0 until goneCount) inst(gone(i), C_GONE)
            w.write(ByteArrayDump(CACHE_DATA, 0, ByteArray(cacheBytes)))
        }
    }
}
