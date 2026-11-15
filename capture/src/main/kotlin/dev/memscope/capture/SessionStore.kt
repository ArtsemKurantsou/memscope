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
