package dev.memscope.analysis

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

/**
 * On-disk cache of a [HeapIndex] next to the dump: `<hprof>.idx`.
 *
 * The cache is valid only for the same hprof size, modification time (nanoseconds) and
 * [HeapIndexer.FORMAT_VERSION] / [LAYOUT_VERSION]. A stale or unreadable cache is deleted before
 * rebuilding, so a dump that has become unreadable never keeps an old index. Writes go to a temp
 * file in the same directory and are renamed into place; a failed write leaves nothing behind.
 * A failed cache write does not fail the analysis (the cache is only an optimisation).
 */
object IndexCache {
    /** Bump when the file layout below changes. */
    const val LAYOUT_VERSION = 1
    private const val MAGIC = 0x4D534958 // "MSIX"
    private const val END_MAGIC = 0x58494D53

    fun cacheFileFor(hprof: File): File = File(hprof.path + ".idx")

    fun loadOrBuild(hprof: File, progress: Progress): HeapIndex {
        val cache = cacheFileFor(hprof)
        val key = keyOf(hprof)
        if (key != null && cache.isFile) {
            val loaded = try {
                read(cache, key, progress)
            } catch (e: IOException) {
                null
            } catch (e: RuntimeException) {
                null // corrupt cache content (bad lengths etc.): rebuild
            }
            if (loaded != null) return loaded
        }
        cache.delete()
        val index = HeapIndexer.build(hprof, progress)
        if (key != null) write(index, key, cache, progress)
        return index
    }

    private class Key(val size: Long, val mtimeNanos: Long)

    private fun keyOf(hprof: File): Key? = try {
        Key(Files.size(hprof.toPath()), Files.getLastModifiedTime(hprof.toPath()).to(TimeUnit.NANOSECONDS))
    } catch (e: IOException) {
        null
    }

    // ---------------------------------------------------------------- write

    private fun write(index: HeapIndex, key: Key, cache: File, progress: Progress) {
        val dir = cache.absoluteFile.parentFile
        var tmp: File? = null
        try {
            tmp = File.createTempFile(cache.name + ".", ".tmp", dir)
            progress.report("cache", 0.0)
            FileChannel.open(tmp.toPath(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
                val out = Out(ch)
                out.int(MAGIC); out.int(HeapIndexer.FORMAT_VERSION); out.int(LAYOUT_VERSION)
                out.long(key.size); out.long(key.mtimeNanos)
                out.int(index.classNames.size)
                for (name in index.classNames) out.bytes(name.toByteArray(Charsets.UTF_8))
                out.longs(index.objectIds); out.ints(index.classOf); out.ints(index.classObjectIndex)
                out.ints(index.shallow); out.ints(index.nativeEst); out.bytes(index.heapKind)
                out.int(index.graph.nodeCount)
                out.ints(index.graph.edgeOffsets); out.ints(index.graph.edgeTargets); out.ints(index.graph.roots)
                out.bytes(index.rootKinds); out.ints(index.rootThreadObj)
                out.ints(index.idom); out.longs(index.retained); out.longs(index.retainedWithNative)
                out.ints(index.domChildOffsets); out.ints(index.domChildren); out.ints(index.bfsParent)
                out.int(END_MAGIC)
                out.flush()
                ch.force(true)
            }
            Files.move(tmp.toPath(), cache.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            tmp = null
            progress.report("cache", 1.0)
        } catch (e: IOException) {
            // Disk full, read-only directory, ...: analysis still succeeds, just without a cache.
        } finally {
            tmp?.delete()
        }
    }

    private class Out(private val ch: FileChannel) {
        private val buf = ByteBuffer.allocate(1 shl 20)

        private fun room(n: Int) { if (buf.remaining() < n) flush() }

        fun flush() {
            buf.flip()
            while (buf.hasRemaining()) ch.write(buf)
            buf.clear()
        }

        fun int(v: Int) { room(4); buf.putInt(v) }
        fun long(v: Long) { room(8); buf.putLong(v) }

        fun bytes(a: ByteArray) {
            int(a.size)
            var off = 0
            while (off < a.size) {
                room(1)
                val k = minOf(buf.remaining(), a.size - off)
                buf.put(a, off, k); off += k
            }
        }

        fun ints(a: IntArray) {
            int(a.size)
            var off = 0
            while (off < a.size) {
                room(4)
                val k = minOf(buf.remaining() / 4, a.size - off)
                buf.asIntBuffer().put(a, off, k)
                buf.position(buf.position() + k * 4); off += k
            }
        }

        fun longs(a: LongArray) {
            int(a.size)
            var off = 0
            while (off < a.size) {
                room(8)
                val k = minOf(buf.remaining() / 8, a.size - off)
                buf.asLongBuffer().put(a, off, k)
                buf.position(buf.position() + k * 8); off += k
            }
        }
    }

    // ---------------------------------------------------------------- read

    /** The index, or null when the cache does not match [key]. Throws on corrupt content. */
    private fun read(cache: File, key: Key, progress: Progress): HeapIndex? =
        FileChannel.open(cache.toPath(), StandardOpenOption.READ).use { ch ->
            val inp = In(ch)
            if (inp.int() != MAGIC) return null
            if (inp.int() != HeapIndexer.FORMAT_VERSION || inp.int() != LAYOUT_VERSION) return null
            if (inp.long() != key.size || inp.long() != key.mtimeNanos) return null
            progress.report("load", 0.0)
            val classNames = Array(inp.length(1)) { String(inp.bytes(), Charsets.UTF_8) }
            val objectIds = inp.longs(); val classOf = inp.ints(); val classObjectIndex = inp.ints()
            val shallow = inp.ints(); val nativeEst = inp.ints(); val heapKind = inp.bytes()
            val nodeCount = inp.int()
            val graph = Graph(nodeCount, inp.ints(), inp.ints(), inp.ints())
            val rootKinds = inp.bytes(); val rootThreadObj = inp.ints()
            val idom = inp.ints(); val retained = inp.longs(); val retainedWithNative = inp.longs()
            val domChildOffsets = inp.ints(); val domChildren = inp.ints(); val bfsParent = inp.ints()
            if (inp.int() != END_MAGIC) throw IOException("cache ${cache.path} has no end marker")
            val n = objectIds.size
            if (nodeCount != n || graph.edgeOffsets.size != n + 1 || domChildOffsets.size != n + 2) {
                throw IOException("cache ${cache.path} is inconsistent")
            }
            progress.report("load", 1.0)
            HeapIndex(
                objectIds, classOf, classNames, classObjectIndex, shallow, nativeEst, heapKind, graph,
                rootKinds, rootThreadObj, idom, retained, retainedWithNative, domChildOffsets, domChildren, bfsParent,
            )
        }

    private class In(private val ch: FileChannel) {
        private val buf = ByteBuffer.allocate(1 shl 20).also { it.flip() }
        private val fileSize = ch.size()

        private fun need(n: Int) {
            if (buf.remaining() >= n) return
            buf.compact()
            while (buf.position() < n) {
                if (ch.read(buf) < 0) throw IOException("unexpected end of cache file")
            }
            buf.flip()
        }

        fun int(): Int { need(4); return buf.int }
        fun long(): Long { need(8); return buf.long }

        /** A length prefix, checked against the file size so a corrupt value cannot trigger a huge allocation. */
        fun length(elementSize: Int): Int {
            val len = int()
            if (len < 0 || len.toLong() * elementSize > fileSize) throw IOException("corrupt length $len in cache file")
            return len
        }

        fun bytes(): ByteArray {
            val a = ByteArray(length(1))
            var off = 0
            while (off < a.size) {
                need(1)
                val k = minOf(buf.remaining(), a.size - off)
                buf.get(a, off, k); off += k
            }
            return a
        }

        fun ints(): IntArray {
            val a = IntArray(length(4))
            var off = 0
            while (off < a.size) {
                need(4)
                val k = minOf(buf.remaining() / 4, a.size - off)
                buf.asIntBuffer().get(a, off, k)
                buf.position(buf.position() + k * 4); off += k
            }
            return a
        }

        fun longs(): LongArray {
            val a = LongArray(length(8))
            var off = 0
            while (off < a.size) {
                need(8)
                val k = minOf(buf.remaining() / 8, a.size - off)
                buf.asLongBuffer().get(a, off, k)
                buf.position(buf.position() + k * 8); off += k
            }
            return a
        }
    }
}
