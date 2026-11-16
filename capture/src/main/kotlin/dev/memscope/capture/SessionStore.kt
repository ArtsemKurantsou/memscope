package dev.memscope.capture

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** `MEMSCOPE_HOME` if set and non-blank, else `~/.memscope`. Parameters exist so tests never touch the real home. */
fun memscopeHome(
    env: Map<String, String> = System.getenv(),
    userHome: String = System.getProperty("user.home"),
): Path = env["MEMSCOPE_HOME"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: Path.of(userHome, ".memscope")

@Serializable
data class SnapshotMeta(
    val id: String,
    val label: String,
    val milestoneId: String?,
    val processName: String,
    val takenAtMs: Long,
    /** File name relative to the session directory (e.g. `s1.hprof`). */
    val file: String,
    val sizeBytes: Long,
    val status: Status,
    val error: String? = null,
) {
    enum class Status { OK, FAILED, UNREADABLE }
}

@Serializable
data class SessionMeta(
    val id: String,
    val serial: String,
    val packageName: String,
    val mode: Mode,
    val createdAtMs: Long,
    val appPackagePrefixes: List<String>,
    val snapshots: List<SnapshotMeta>,
    val state: State,
    val note: String? = null,
    /** Device screen in pixels (`wm size`) when the session was created; null if it could not be read. */
    val screenWidthPx: Int? = null,
    val screenHeightPx: Int? = null,
) {
    enum class Mode { ATTACH, STARTUP }
    enum class State { ACTIVE, ENDED }
}

/**
 * Sessions on disk: `<home>/sessions/<id>/session.json` plus the snapshot files next to it.
 * Thread-safe within a process; every write replaces session.json atomically.
 */
class SessionStore(val home: Path = memscopeHome()) {
    private val lock = Any()
    private val random = SecureRandom()
    private val reservedSnapshotNumbers = HashMap<String, Int>()

    private val sessionsRoot: Path get() = home.resolve("sessions")

    fun create(serial: String, packageName: String, mode: SessionMeta.Mode): SessionMeta = synchronized(lock) {
        Files.createDirectories(sessionsRoot)
        val now = System.currentTimeMillis()
        val stamp = LocalDateTime.now().format(STAMP)
        while (true) {
            val id = "$stamp-${randomSuffix()}"
            try {
                Files.createDirectory(sessionsRoot.resolve(id)) // atomic: fails if the id is taken
            } catch (_: FileAlreadyExistsException) {
                continue
            }
            val meta = SessionMeta(
                id, serial, packageName, mode, now, listOf(packageName), emptyList(), SessionMeta.State.ACTIVE,
            )
            write(meta)
            return meta
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    fun get(id: String): SessionMeta? = synchronized(lock) { read(id) }

    /** All readable sessions, newest first. Directories without a valid session.json are skipped. */
    fun list(): List<SessionMeta> = synchronized(lock) {
        if (!Files.isDirectory(sessionsRoot)) return emptyList()
        Files.list(sessionsRoot).use { stream ->
            stream.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.toList()
        }.mapNotNull { id -> runCatching { read(id) }.getOrNull() }.sortedByDescending { it.createdAtMs }
    }

    fun addSnapshot(sessionId: String, meta: SnapshotMeta): SessionMeta = update(sessionId) { s ->
        require(s.snapshots.none { it.id == meta.id }) { "snapshot ${meta.id} already exists in session $sessionId" }
        s.copy(snapshots = s.snapshots + meta)
    }

    /** Read-modify-write under the store lock. Throws [NoSuchElementException] for an unknown session. */
    fun update(id: String, f: (SessionMeta) -> SessionMeta): SessionMeta = synchronized(lock) {
        val current = read(id) ?: throw NoSuchElementException("no such session: $id")
        val next = f(current)
        require(next.id == id) { "update must not change the session id" }
        write(next)
        next
    }

    /** Reserves and returns the next free snapshot id (`s1`, `s2`, ...); concurrent callers never get the same one. */
    fun nextSnapshotId(sessionId: String): String = synchronized(lock) {
        val existing = (read(sessionId) ?: throw NoSuchElementException("no such session: $sessionId"))
            .snapshots.mapNotNull { it.id.removePrefix("s").toIntOrNull() }.maxOrNull() ?: 0
        val next = maxOf(existing, reservedSnapshotNumbers[sessionId] ?: 0) + 1
        reservedSnapshotNumbers[sessionId] = next
        "s$next"
    }

    fun dir(id: String): Path {
        require(ID_PATTERN.matches(id)) { "invalid session id: $id" }
        return sessionsRoot.resolve(id)
    }

    fun snapshotFile(sessionId: String, snapshotId: String): Path {
        require(ID_PATTERN.matches(snapshotId)) { "invalid snapshot id: $snapshotId" }
        return dir(sessionId).resolve("$snapshotId.hprof")
    }

    /** Bytes of the regular files directly in the session directory (hprofs, analysis sidecars, timeline); 0 if it is gone. */
    fun diskBytes(id: String): Long = synchronized(lock) { filesIn(dir(id)).sumOf { sizeOf(it) } }

    /** Bytes of snapshot [snapshotId]'s hprof and its sidecars. */
    fun snapshotBytes(sessionId: String, snapshotId: String): Long = synchronized(lock) {
        snapshotFiles(sessionId, snapshotId).sumOf { sizeOf(it) }
    }

    /**
     * Removes snapshot [snapshotId] from session.json, then deletes `<id>.hprof` and every `<id>.hprof.*` file next to it
     * (index, summary and findings sidecars, temp and partial files). Throws [NoSuchElementException] for an unknown
     * session or snapshot.
     */
    fun deleteSnapshot(sessionId: String, snapshotId: String): SessionMeta = synchronized(lock) {
        val files = snapshotFiles(sessionId, snapshotId)
        val current = read(sessionId) ?: throw NoSuchElementException("no such session: $sessionId")
        if (current.snapshots.none { it.id == snapshotId }) throw NoSuchElementException("no such snapshot: $sessionId/$snapshotId")
        val next = current.copy(snapshots = current.snapshots.filter { it.id != snapshotId })
        write(next)
        files.forEach { Files.deleteIfExists(it) }
        next
    }

    /**
     * Deletes the session directory and everything in it. A symbolic link inside is removed itself, its target never
     * touched. False if the session did not exist.
     */
    fun deleteSession(id: String): Boolean = synchronized(lock) {
        val dir = dir(id)
        if (!Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false
        if (Files.isSymbolicLink(dir)) { Files.delete(dir); return true }
        Files.walkFileTree(dir, object : java.nio.file.SimpleFileVisitor<Path>() { // does not follow links
            override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.delete(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(d: Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
                if (exc != null) throw exc
                Files.delete(d)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
        reservedSnapshotNumbers.remove(id)
        true
    }

    private fun snapshotFiles(sessionId: String, snapshotId: String): List<Path> {
        val hprof = snapshotFile(sessionId, snapshotId).fileName.toString()
        return filesIn(dir(sessionId)).filter { val n = it.fileName.toString(); n == hprof || n.startsWith("$hprof.") }
    }

    /** Files and symbolic links (never followed) directly in [dir]. */
    private fun filesIn(dir: Path): List<Path> {
        if (!Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return Files.list(dir).use { s -> s.filter { !Files.isDirectory(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.toList() }
    }

    /** Size of [p] itself (a link's own size, not its target's). */
    private fun sizeOf(p: Path): Long = try {
        Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS).size()
    } catch (_: java.io.IOException) {
        0L
    }

    private fun read(id: String): SessionMeta? {
        val file = dir(id).resolve(SESSION_FILE)
        if (!Files.isRegularFile(file)) return null
        return json.decodeFromString(SessionMeta.serializer(), Files.readString(file))
    }

    private fun write(meta: SessionMeta) {
        val dir = dir(meta.id)
        val tmp = Files.createTempFile(dir, "session", ".tmp")
        try {
            Files.writeString(tmp, json.encodeToString(SessionMeta.serializer(), meta))
            try {
                Files.move(tmp, dir.resolve(SESSION_FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, dir.resolve(SESSION_FILE), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun randomSuffix() = buildString { repeat(4) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    private companion object {
        const val SESSION_FILE = "session.json"
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
