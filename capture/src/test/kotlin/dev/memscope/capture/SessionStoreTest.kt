package dev.memscope.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionStoreTest {
    @TempDir lateinit var tmp: Path

    private fun snap(id: String) = SnapshotMeta(id, "label $id", null, "dev.app", 1L, "$id.hprof", 10, SnapshotMeta.Status.OK)

    @Test
    fun `screen size is optional and survives a round trip`() {
        val store = SessionStore(tmp)
        val s = store.create("emulator-5554", "dev.app", SessionMeta.Mode.ATTACH)
        assertNull(s.screenWidthPx)
        store.update(s.id) { it.copy(screenWidthPx = 1080, screenHeightPx = 2400) }
        assertEquals(1080 to 2400, SessionStore(tmp).get(s.id)!!.let { it.screenWidthPx to it.screenHeightPx })
        // a session.json written before the fields existed still loads
        val dir = store.dir(s.id)
        val old = Files.readString(dir.resolve("session.json")).lines().filterNot { "screen" in it }.joinToString("\n").replace(Regex(",(\\s*)\\}\\s*\$"), "$1}")
        Files.writeString(dir.resolve("session.json"), old)
        assertNull(SessionStore(tmp).get(s.id)!!.screenWidthPx)
    }

    @Test
    fun `deleting a snapshot removes its hprof, every sidecar and its entry, nothing else`() {
        val store = SessionStore(tmp)
        val s = store.create("emulator-5554", "dev.app", SessionMeta.Mode.ATTACH)
        store.addSnapshot(s.id, snap("s1"))
        store.addSnapshot(s.id, snap("s10"))
        val dir = store.dir(s.id)
        val mine = listOf("s1.hprof", "s1.hprof.idx", "s1.hprof.summary.json", "s1.hprof.findings.json", "s1.hprof.idx.123.tmp", "s1.hprof.part")
        val others = listOf("s10.hprof", "s10.hprof.idx", "timeline.jsonl")
        (mine + others).forEach { Files.write(dir.resolve(it), ByteArray(100)) }
        assertEquals(100L * 6, store.snapshotBytes(s.id, "s1"))
        fun dataBytes() = store.diskBytes(s.id) - Files.size(dir.resolve("session.json"))
        val before = dataBytes()
        val after = store.deleteSnapshot(s.id, "s1")
        assertEquals(listOf("s10"), after.snapshots.map { it.id })
        assertEquals(listOf("s10"), SessionStore(tmp).get(s.id)!!.snapshots.map { it.id })
        mine.forEach { assertTrue(Files.notExists(dir.resolve(it)), it) }
        others.forEach { assertTrue(Files.exists(dir.resolve(it)), it) }
        assertEquals(before - 600, dataBytes())
        assertThrows<NoSuchElementException> { store.deleteSnapshot(s.id, "s1") }
        assertThrows<IllegalArgumentException> { store.deleteSnapshot(s.id, "../x") }
    }

    @Test
    fun `deleting a session removes its directory but never follows a link out of it`() {
        val store = SessionStore(tmp)
        val outside = Files.createDirectories(tmp.resolve("outside"))
        val precious = Files.writeString(outside.resolve("precious.txt"), "keep")
        val s = store.create("emulator-5554", "dev.app", SessionMeta.Mode.ATTACH)
        val keep = store.create("emulator-5554", "dev.app", SessionMeta.Mode.ATTACH)
        Files.write(store.dir(s.id).resolve("s1.hprof"), ByteArray(1000))
        Files.createSymbolicLink(store.dir(s.id).resolve("link"), outside)
        assertTrue(store.diskBytes(s.id) >= 1000)
        assertTrue(store.deleteSession(s.id))
        assertTrue(Files.notExists(store.dir(s.id)))
        assertNull(store.get(s.id))
        assertEquals("keep", Files.readString(precious), "the link target is untouched")
        assertTrue(store.get(keep.id) != null)
        assertEquals(false, store.deleteSession(s.id))
        assertThrows<IllegalArgumentException> { store.deleteSession("..") }
    }

    @Test
    fun `session round-trips through disk`() {
        val store = SessionStore(tmp)
        val s = store.create("emulator-5554", "dev.app", SessionMeta.Mode.ATTACH)
        assertTrue(Regex("\\d{8}-\\d{6}-[a-z0-9]{4}").matches(s.id), s.id)
        store.addSnapshot(s.id, snap("s1"))
        val reread = SessionStore(tmp).get(s.id)!!   // fresh store instance: really read from disk
        assertEquals(s.id, reread.id)
        assertEquals("emulator-5554", reread.serial)
        assertEquals(SessionMeta.State.ACTIVE, reread.state)
        assertEquals(listOf("s1"), reread.snapshots.map { it.id })
        assertEquals(tmp.resolve("sessions/${s.id}/s1.hprof"), store.snapshotFile(s.id, "s1"))
        assertEquals(listOf(s.id), SessionStore(tmp).list().map { it.id })
    }

    @Test
    fun `get of unknown or hostile id returns null or refuses`() {
        val store = SessionStore(tmp)
        assertNull(store.get("nope"))
        assertThrows<IllegalArgumentException> { store.dir("../x") }
    }

    @Test
    fun `update changes state and leaves no temp files`() {
        val store = SessionStore(tmp)
        val s = store.create("e", "dev.app", SessionMeta.Mode.STARTUP)
        val ended = store.update(s.id) { it.copy(state = SessionMeta.State.ENDED, note = "app exited") }
        assertEquals(SessionMeta.State.ENDED, store.get(s.id)!!.state)
        assertEquals("app exited", ended.note)
        assertEquals(listOf("session.json"), Files.list(store.dir(s.id)).use { st -> st.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `concurrent addSnapshot calls lose nothing and ids are unique`() {
        val store = SessionStore(tmp)
        val s = store.create("e", "dev.app", SessionMeta.Mode.ATTACH)
        val pool = Executors.newFixedThreadPool(8)
        val ids = (1..40).map { pool.submit<String> { store.nextSnapshotId(s.id).also { id -> store.addSnapshot(s.id, snap(id)) } } }.map { it.get() }
        pool.shutdown(); pool.awaitTermination(10, TimeUnit.SECONDS)
        assertEquals(40, ids.toSet().size)
        assertEquals(40, store.get(s.id)!!.snapshots.size)
    }

    @Test
    fun `two sessions created in the same second get different ids`() {
        val store = SessionStore(tmp)
        val ids = (1..20).map { store.create("e", "p", SessionMeta.Mode.ATTACH).id }
        assertEquals(20, ids.toSet().size)
        assertNotEquals(ids[0], ids[1])
    }

    @Test
    fun `memscopeHome prefers env then falls back to dot memscope`() {
        assertEquals(Path.of("/x/y"), memscopeHome(mapOf("MEMSCOPE_HOME" to "/x/y"), "/home/u"))
        assertEquals(Path.of("/home/u/.memscope"), memscopeHome(emptyMap(), "/home/u"))
    }
}
