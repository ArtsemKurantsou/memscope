package dev.memscope.server

import dev.memscope.capture.OnDemandDumper
import dev.memscope.capture.SessionStore
import dev.memscope.device.Adb

/** Everything the routes use, injectable so tests can substitute fakes. [adbProvider] may throw ADB_NOT_FOUND per request. */
class ServerDeps(
    val store: SessionStore,
    private val adbProvider: () -> Adb,
    val opener: SnapshotOpener = SnapshotOpener.real,
    val clock: () -> Long = System::currentTimeMillis,
    /** How long a re-open waits for a cancelled analysis of the same snapshot to stop before failing. */
    val previousAnalysisTimeoutMs: Long = 60_000,
    val jobs: JobRegistry = JobRegistry(),
    val snapshots: SnapshotRegistry = SnapshotRegistry(capacity = 2),
) : AutoCloseable {
    val adb: Adb get() = adbProvider()
    val dumper: OnDemandDumper get() = OnDemandDumper(adb, store)

    /** Per-serial cache of the debuggable-app listing (one `run-as` per package is slow). */
    private val appsCache = HashMap<String, Pair<Long, List<AppDto>>>()

    fun apps(serial: String, refresh: Boolean): List<AppDto> = synchronized(appsCache) {
        val cached = appsCache[serial]
        if (!refresh && cached != null && clock() - cached.first < APPS_TTL_MS) return cached.second
        val adb = adb
        val fresh = adb.debuggablePackages(serial).map { pkg ->
            val procs = adb.processes(serial, pkg)
            AppDto(pkg, procs.isNotEmpty(), procs.map { it.toDto() })
        }
        appsCache[serial] = clock() to fresh
        fresh
    }

    override fun close() {
        jobs.close()
        snapshots.close()
    }

    private companion object {
        const val APPS_TTL_MS = 30_000L
    }
}
