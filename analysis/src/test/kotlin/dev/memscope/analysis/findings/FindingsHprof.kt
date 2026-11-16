package dev.memscope.analysis.findings

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
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import shark.HprofVersion
import shark.HprofWriter
import shark.ValueHolder
import java.io.File
import java.nio.ByteBuffer

/**
 * A synthetic Android-style hprof shaped for the findings rules. Static `test.Caches.bag` -> `test.Bag`
 * whose `items` Object[] holds: 3 byte[1,150,000] (two equal, the third differing only in its last byte),
 * 100 equal 2000-char Strings, 6 Bitmaps 300x300, one Bitmap 3000x2000, and an ArrayList with a 20,000-slot
 * backing array holding 3 items. `test.Caches.leaked` -> a destroyed `test.LeakyActivity`
 * (android.app.Activity.mDestroyed = true) when [withLeak]. Classes are in heap "image", instances in "app".
 */
object FindingsHprof {
    const val BIG_LENGTH = 1_150_000
    const val STRING_COPIES = 100
    const val STRING_CHARS = 2000
    const val SPARSE_SLOTS = 20_000

    const val C_OBJECT = 200L; const val C_CLASS = 201L; const val C_STRING = 202L; const val C_OBJARR = 203L
    const val C_BITMAP = 204L; const val C_LIST = 205L; const val C_BAG = 206L; const val C_CACHES = 207L
    const val C_ACTIVITY = 208L; const val C_LEAKY = 209L; const val C_BUILD = 210L; const val C_VERSION = 211L
    const val MANUFACTURER = 3010L; const val MANUFACTURER_CHARS = 3011L

    const val BAG = 3000L; const val ITEMS = 3001L; const val ARR_A = 3002L; const val ARR_B = 3003L; const val ARR_C = 3004L
    const val LIST = 3005L; const val LIST_DATA = 3006L; const val BIG_BITMAP = 3007L; const val LEAKY = 3008L
    fun string(i: Int) = 7000L + i
    fun chars(i: Int) = 8000L + i
    fun bitmap(i: Int) = 9000L + i

    private const val OBJ = 2; private const val INT = 10; private const val BOOL = 4

    fun bigArray(last: Int = 0) = ByteArray(BIG_LENGTH) { if (it == BIG_LENGTH - 1) last.toByte() else ((it * 31) % 251).toByte() }

    fun write(file: File, withLeak: Boolean = false) {
        var nextString = 1L
        val strings = LinkedHashMap<String, Long>()
        fun s(v: String) = strings.getOrPut(v) { nextString++ }

        class K(val id: Long, val name: String, val superId: Long, val size: Int, val fields: List<Pair<String, Int>>, val statics: List<Pair<String, Long>> = emptyList(), val sdkInt: Int? = null)
        val classes = listOf(
            K(C_OBJECT, "java.lang.Object", 0, 8, emptyList()),
            K(C_CLASS, "java.lang.Class", C_OBJECT, 120, emptyList()),
            K(C_STRING, "java.lang.String", C_OBJECT, 16, listOf("value" to OBJ, "count" to INT)),
            K(C_OBJARR, "java.lang.Object[]", C_OBJECT, 0, emptyList()),
            K(C_BITMAP, "android.graphics.Bitmap", C_OBJECT, 40, listOf("mWidth" to INT, "mHeight" to INT)),
            K(C_LIST, "java.util.ArrayList", C_OBJECT, 24, listOf("elementData" to OBJ, "size" to INT)),
            K(C_BAG, "test.Bag", C_OBJECT, 16, listOf("items" to OBJ)),
            K(C_CACHES, "test.Caches", C_OBJECT, 8, emptyList(), listOfNotNull("bag" to BAG, if (withLeak) "leaked" to LEAKY else null)),
            K(C_ACTIVITY, "android.app.Activity", C_OBJECT, 24, listOf("mDestroyed" to BOOL, "mFinished" to BOOL)),
            K(C_LEAKY, "test.LeakyActivity", C_ACTIVITY, 24, emptyList()),
            K(C_BUILD, "android.os.Build", C_OBJECT, 8, emptyList(), listOf("MANUFACTURER" to MANUFACTURER, "ID" to MANUFACTURER)),
            K(C_VERSION, "android.os.Build\$VERSION", C_OBJECT, 8, emptyList(), emptyList(), sdkInt = 34),
        )
        for (c in classes) { s(c.name); c.fields.forEach { s(it.first) }; c.statics.forEach { s(it.first) }; if (c.sdkInt != null) s("SDK_INT") }
        s("app"); s("image")

        HprofWriter.openWriterFor(file, HprofHeader(System.currentTimeMillis(), HprofVersion.ANDROID, 4)).use { w ->
            for ((value, id) in strings) w.write(HprofRecord.StringRecord(id, value))
            classes.forEachIndexed { i, c -> w.write(HprofRecord.LoadClassRecord(i + 1, c.id, 0, s(c.name))) }
            w.write(HeapDumpInfoRecord('I'.code, s("image")))
            for (c in classes) {
                w.write(
                    ClassDumpRecord(
                        c.id, 0, c.superId, 0, 0, 0, c.size,
                        c.statics.map { StaticFieldRecord(s(it.first), OBJ, ValueHolder.ReferenceHolder(it.second)) } +
                            listOfNotNull(c.sdkInt?.let { v -> StaticFieldRecord(s("SDK_INT"), INT, ValueHolder.IntHolder(v)) }),
                        c.fields.map { FieldRecord(s(it.first), it.second) },
                    ),
                )
            }
            for (k in listOf(C_OBJECT, C_CACHES, C_BUILD, C_VERSION)) w.write(GcRootRecord(GcRoot.StickyClass(k)))
            w.write(HeapDumpInfoRecord('A'.code, s("app")))
            fun inst(id: Long, classId: Long, build: ByteBuffer.() -> Unit, size: Int) {
                val buf = ByteBuffer.allocate(size); buf.build(); w.write(InstanceDumpRecord(id, 0, classId, buf.array()))
            }
            val items = ArrayList<Long>()
            w.write(ByteArrayDump(ARR_A, 0, bigArray())); items += ARR_A
            w.write(ByteArrayDump(ARR_B, 0, bigArray())); items += ARR_B
            w.write(ByteArrayDump(ARR_C, 0, bigArray(last = 99))); items += ARR_C
            for (i in 0 until STRING_COPIES) {
                inst(string(i), C_STRING, { putInt(chars(i).toInt()); putInt(STRING_CHARS) }, 8)
                w.write(CharArrayDump(chars(i), 0, CharArray(STRING_CHARS) { ('a' + it % 26) }))
                items += string(i)
            }
            for (i in 0 until 6) { inst(bitmap(i), C_BITMAP, { putInt(300); putInt(300) }, 8); items += bitmap(i) }
            inst(BIG_BITMAP, C_BITMAP, { putInt(3000); putInt(2000) }, 8); items += BIG_BITMAP
            inst(LIST, C_LIST, { putInt(LIST_DATA.toInt()); putInt(3) }, 8); items += LIST
            w.write(ObjectArrayDumpRecord(LIST_DATA, 0, C_OBJARR, LongArray(SPARSE_SLOTS)))
            w.write(ObjectArrayDumpRecord(ITEMS, 0, C_OBJARR, items.toLongArray()))
            inst(BAG, C_BAG, { putInt(ITEMS.toInt()) }, 4)
            inst(MANUFACTURER, C_STRING, { putInt(MANUFACTURER_CHARS.toInt()); putInt(6) }, 8)
            w.write(CharArrayDump(MANUFACTURER_CHARS, 0, "Google".toCharArray()))
            if (withLeak) inst(LEAKY, C_LEAKY, { put(1); put(1) }, 2)
        }
    }
}
