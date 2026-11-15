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
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import shark.HprofVersion
import shark.HprofWriter
import shark.ValueHolder
import java.io.File
import java.nio.ByteBuffer

/**
 * A tiny Android-style hprof (4-byte ids) written with Shark's HprofWriter. Object ids are listed
 * below; objects are written out of id order on purpose to exercise the regrouping path.
 *
 * Heap "image": all class objects. Heap "app": all instances.
 *
 *   HOLDER (test.Holder)  a -> PLAIN, b -> BYTES          roots: JAVA_FRAME (thread 1) + JNI_GLOBAL
 *   Holder.held (static) -> ARRAY (Object[]) [PLAIN, BITMAP]
 *   BITMAP 10x10, CLEANER(referent BITMAP, thunk THUNK), THUNK.this$0 -> REGISTRY (size 4001)
 *   BITMAP2 2x3 without cleaner                          root: JNI_GLOBAL
 *   WEAK (java.lang.ref.WeakReference) referent -> WEAK_ONLY   root: JNI_GLOBAL (WEAK_ONLY unreachable)
 *   THREAD (java.lang.Thread)                           root: THREAD_OBJECT serial 1
 *   CLEANER                                             root: JNI_GLOBAL
 */
object SyntheticHprof {
    const val OBJECT = 100L; const val CLASS = 101L; const val REFERENCE = 102L; const val PHANTOM = 103L
    const val CLEANER_CLASS = 104L; const val REGISTRY_CLASS = 105L; const val THUNK_CLASS = 106L
    const val BITMAP_CLASS = 107L; const val HOLDER_CLASS = 108L; const val BYTE_ARRAY_CLASS = 109L
    const val OBJECT_ARRAY_CLASS = 110L; const val WEAK_CLASS = 111L; const val THREAD_CLASS = 112L

    const val HOLDER = 1000L; const val PLAIN = 1001L; const val BYTES = 1002L; const val ARRAY = 1003L
    const val BITMAP = 1004L; const val CLEANER = 1005L; const val THUNK = 1006L; const val REGISTRY = 1007L
    const val BITMAP2 = 1008L; const val WEAK_ONLY = 1009L; const val WEAK = 1010L; const val THREAD = 1011L

    const val CLASS_INSTANCE_SIZE = 120
    const val BYTES_LENGTH = 100

    private const val OBJ = 2
    private const val INT = 10
    private const val LONG = 11

    fun write(file: File) {
        var nextString = 1L
        val strings = LinkedHashMap<String, Long>()
        fun s(value: String) = strings.getOrPut(value) { nextString++ }

        val classes = listOf(
            cls(OBJECT, "java.lang.Object", 0, 8, emptyList()),
            cls(CLASS, "java.lang.Class", OBJECT, CLASS_INSTANCE_SIZE, emptyList()),
            cls(REFERENCE, "java.lang.ref.Reference", OBJECT, 16, listOf("referent" to OBJ, "queueNext" to OBJ)),
            cls(PHANTOM, "java.lang.ref.PhantomReference", REFERENCE, 16, emptyList()),
            cls(WEAK_CLASS, "java.lang.ref.WeakReference", REFERENCE, 16, emptyList()),
            cls(CLEANER_CLASS, "sun.misc.Cleaner", PHANTOM, 24, listOf("thunk" to OBJ, "next" to OBJ)),
            cls(REGISTRY_CLASS, "libcore.util.NativeAllocationRegistry", OBJECT, 24, listOf("size" to LONG)),
            cls(THUNK_CLASS, "libcore.util.NativeAllocationRegistry\$CleanerThunk", OBJECT, 12, listOf("this\$0" to OBJ)),
            cls(BITMAP_CLASS, "android.graphics.Bitmap", OBJECT, 40, listOf("mWidth" to INT, "mHeight" to INT)),
            cls(HOLDER_CLASS, "test.Holder", OBJECT, 16, listOf("a" to OBJ, "b" to OBJ), static = "held" to ARRAY),
            cls(BYTE_ARRAY_CLASS, "byte[]", OBJECT, 0, emptyList()),
            cls(OBJECT_ARRAY_CLASS, "java.lang.Object[]", OBJECT, 0, emptyList()),
            cls(THREAD_CLASS, "java.lang.Thread", OBJECT, 32, emptyList()),
        )
        for (c in classes) { s(c.name); c.fields.forEach { s(it.first) }; c.static?.let { s(it.first) } }
        s("app"); s("image")

        val header = HprofHeader(System.currentTimeMillis(), HprofVersion.ANDROID, 4)
        HprofWriter.openWriterFor(file, header).use { w ->
            for ((value, id) in strings) w.write(HprofRecord.StringRecord(id, value))
            classes.forEachIndexed { i, c -> w.write(HprofRecord.LoadClassRecord(i + 1, c.id, 0, s(c.name))) }

            w.write(GcRootRecord(GcRoot.ThreadObject(THREAD, 1, 0)))
            w.write(GcRootRecord(GcRoot.JniGlobal(HOLDER, 1)))
            w.write(GcRootRecord(GcRoot.JavaFrame(HOLDER, 1, 0)))
            w.write(GcRootRecord(GcRoot.JniGlobal(BITMAP2, 2)))
            w.write(GcRootRecord(GcRoot.JniGlobal(WEAK, 3)))
            w.write(GcRootRecord(GcRoot.JniGlobal(CLEANER, 4)))
            w.write(GcRootRecord(GcRoot.StickyClass(OBJECT)))

            w.write(HeapDumpInfoRecord('I'.code, s("image")))
            for (c in classes) {
                w.write(
                    ClassDumpRecord(
                        c.id, 0, c.superId, 0, 0, 0, c.instanceSize,
                        listOfNotNull(c.static?.let { StaticFieldRecord(s(it.first), OBJ, ValueHolder.ReferenceHolder(it.second)) }),
                        c.fields.map { FieldRecord(s(it.first), it.second) },
                    ),
                )
            }

            w.write(HeapDumpInfoRecord('A'.code, s("app")))
            fun inst(id: Long, classId: Long, vararg values: Any) {
                val buf = ByteBuffer.allocate(values.fold(0) { acc, v -> acc + if (v is Long) 8 else 4 })
                for (v in values) when (v) { is Long -> buf.putLong(v); is Int -> buf.putInt(v) }
                w.write(InstanceDumpRecord(id, 0, classId, buf.array()))
            }
            // Ids: Ref values are written as Int (4-byte ids); the registry size is a Long.
            inst(THREAD, THREAD_CLASS)
            inst(HOLDER, HOLDER_CLASS, PLAIN.toInt(), BYTES.toInt())
            w.write(ByteArrayDump(BYTES, 0, ByteArray(BYTES_LENGTH)))
            inst(PLAIN, OBJECT)
            w.write(ObjectArrayDumpRecord(ARRAY, 0, OBJECT_ARRAY_CLASS, longArrayOf(PLAIN, BITMAP)))
            inst(BITMAP, BITMAP_CLASS, 10, 10)
            // Cleaner layout: own fields (thunk, next) then Reference's (referent, queueNext).
            inst(CLEANER, CLEANER_CLASS, THUNK.toInt(), 0, BITMAP.toInt(), 0)
            inst(THUNK, THUNK_CLASS, REGISTRY.toInt())
            inst(REGISTRY, REGISTRY_CLASS, 4001L)
            inst(BITMAP2, BITMAP_CLASS, 2, 3)
            inst(WEAK, WEAK_CLASS, WEAK_ONLY.toInt(), 0)
            inst(WEAK_ONLY, OBJECT)
        }
    }

    private class Cls(
        val id: Long, val name: String, val superId: Long, val instanceSize: Int,
        val fields: List<Pair<String, Int>>, val static: Pair<String, Long>?,
    )

    private fun cls(
        id: Long, name: String, superId: Long, instanceSize: Int, fields: List<Pair<String, Int>>,
        static: Pair<String, Long>? = null,
    ) = Cls(id, name, superId, instanceSize, fields, static)

    /**
     * A second synthetic dump shaped for the snapshot tree (see [Tree.write]). Class objects are in heap
     * "image", every instance in heap "app".
     *
     *   test.App.items (static)   -> ITEMS (Object[]) [ITEM_0..ITEM_29, OTHER_0..OTHER_2]
     *   ITEM_i (test.Item).payload -> PAYLOAD_i (byte[(i + 1) * 10])
     *   test.App.diamond (static) -> A (test.Node) left -> B, right -> D; B.left -> C, D.left -> C (C: test.Leaf)
     *   test.SharedA.ref, test.SharedB.ref -> SHARED (byte[1000])
     *   THREAD (java.lang.Thread, name "worker")   root THREAD_OBJECT serial 1
     *   LOCAL (test.Local)    root JAVA_FRAME of thread serial 1
     *   LOCAL2 (test.Local)   root JAVA_FRAME of unknown thread serial 9 (-> "Other roots")
     *   GLOBAL (test.Global)  root JNI_GLOBAL
     *   UNKNOWN (test.Unknown) root UNKNOWN
     *   GARBAGE (test.Other)  unreachable
     */
    object Tree {
        const val C_OBJECT = 200L; const val C_CLASS = 201L; const val C_STRING = 202L; const val C_OBJARR = 203L
        const val C_THREAD = 204L; const val C_APP = 205L; const val C_ITEM = 206L; const val C_OTHER = 207L
        const val C_NODE = 208L; const val C_LEAF = 209L; const val C_SHARED_A = 210L; const val C_SHARED_B = 211L
        const val C_LOCAL = 212L; const val C_GLOBAL = 213L; const val C_UNKNOWN = 214L

        const val ITEMS = 3000L; const val A = 3001L; const val B = 3002L; const val C = 3003L; const val D = 3004L
        const val SHARED = 3005L; const val THREAD = 3006L; const val NAME = 3007L; const val NAME_CHARS = 3008L
        const val LOCAL = 3009L; const val LOCAL2 = 3010L; const val GLOBAL = 3011L; const val UNKNOWN = 3012L
        const val GARBAGE = 3013L
        const val ITEM_COUNT = 30
        const val OTHER_COUNT = 3
        fun item(i: Int): Long = 4000L + i
        fun payload(i: Int): Long = 5000L + i
        fun other(i: Int): Long = 6000L + i

        const val ITEM_SIZE = 16
        const val SHARED_LENGTH = 1000

        fun write(file: File) {
            var nextString = 1L
            val strings = LinkedHashMap<String, Long>()
            fun s(value: String) = strings.getOrPut(value) { nextString++ }

            class K(val id: Long, val name: String, val size: Int, val fields: List<String>, val statics: List<Pair<String, Long>> = emptyList())
            val classes = listOf(
                K(C_OBJECT, "java.lang.Object", 8, emptyList()),
                K(C_CLASS, "java.lang.Class", CLASS_INSTANCE_SIZE, emptyList()),
                K(C_STRING, "java.lang.String", 16, listOf("value", "count")),
                K(C_OBJARR, "java.lang.Object[]", 0, emptyList()),
                K(C_THREAD, "java.lang.Thread", 32, listOf("name")),
                K(C_APP, "test.App", 8, emptyList(), listOf("items" to ITEMS, "diamond" to A)),
                K(C_ITEM, "test.Item", ITEM_SIZE, listOf("payload")),
                K(C_OTHER, "test.Other", 24, emptyList()),
                K(C_NODE, "test.Node", 16, listOf("left", "right")),
                K(C_LEAF, "test.Leaf", 40, emptyList()),
                K(C_SHARED_A, "test.SharedA", 8, emptyList(), listOf("ref" to SHARED)),
                K(C_SHARED_B, "test.SharedB", 8, emptyList(), listOf("ref" to SHARED)),
                K(C_LOCAL, "test.Local", 24, emptyList()),
                K(C_GLOBAL, "test.Global", 32, emptyList()),
                K(C_UNKNOWN, "test.Unknown", 8, emptyList()),
            )
            // Field types: "count" is an int, everything else an object reference.
            fun type(field: String) = if (field == "count") INT else OBJ
            for (c in classes) { s(c.name); c.fields.forEach { s(it) }; c.statics.forEach { s(it.first) } }
            s("app"); s("image")

            val header = HprofHeader(System.currentTimeMillis(), HprofVersion.ANDROID, 4)
            HprofWriter.openWriterFor(file, header).use { w ->
                for ((value, id) in strings) w.write(HprofRecord.StringRecord(id, value))
                classes.forEachIndexed { i, c -> w.write(HprofRecord.LoadClassRecord(i + 1, c.id, 0, s(c.name))) }

                w.write(GcRootRecord(GcRoot.ThreadObject(THREAD, 1, 0)))
                w.write(GcRootRecord(GcRoot.JavaFrame(LOCAL, 1, 0)))
                w.write(GcRootRecord(GcRoot.JavaFrame(LOCAL2, 9, 0)))
                w.write(GcRootRecord(GcRoot.JniGlobal(GLOBAL, 1)))
                w.write(GcRootRecord(GcRoot.Unknown(UNKNOWN)))

                w.write(HeapDumpInfoRecord('I'.code, s("image")))
                for (c in classes) {
                    w.write(
                        ClassDumpRecord(
                            c.id, 0, if (c.id == C_OBJECT) 0 else C_OBJECT, 0, 0, 0, c.size,
                            c.statics.map { StaticFieldRecord(s(it.first), OBJ, ValueHolder.ReferenceHolder(it.second)) },
                            c.fields.map { FieldRecord(s(it), type(it)) },
                        ),
                    )
                }

                w.write(HeapDumpInfoRecord('A'.code, s("app")))
                fun inst(id: Long, classId: Long, vararg values: Int) {
                    val buf = ByteBuffer.allocate(values.size * 4)
                    for (v in values) buf.putInt(v)
                    w.write(InstanceDumpRecord(id, 0, classId, buf.array()))
                }
                val elements = LongArray(ITEM_COUNT + OTHER_COUNT) { if (it < ITEM_COUNT) item(it) else other(it - ITEM_COUNT) }
                w.write(ObjectArrayDumpRecord(ITEMS, 0, C_OBJARR, elements))
                for (i in 0 until ITEM_COUNT) {
                    inst(item(i), C_ITEM, payload(i).toInt())
                    w.write(ByteArrayDump(payload(i), 0, ByteArray((i + 1) * 10)))
                }
                for (i in 0 until OTHER_COUNT) inst(other(i), C_OTHER)
                inst(A, C_NODE, B.toInt(), D.toInt())
                inst(B, C_NODE, C.toInt(), 0)
                inst(D, C_NODE, C.toInt(), 0)
                inst(C, C_LEAF)
                w.write(ByteArrayDump(SHARED, 0, ByteArray(SHARED_LENGTH) { it.toByte() }))
                inst(THREAD, C_THREAD, NAME.toInt())
                inst(NAME, C_STRING, NAME_CHARS.toInt(), "worker".length)
                w.write(CharArrayDump(NAME_CHARS, 0, "worker".toCharArray()))
                inst(LOCAL, C_LOCAL)
                inst(LOCAL2, C_LOCAL)
                inst(GLOBAL, C_GLOBAL)
                inst(UNKNOWN, C_UNKNOWN)
                inst(GARBAGE, C_OTHER)
            }
        }
    }
}
