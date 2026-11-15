package dev.memscope.server

import dev.memscope.capture.DebugAppGuard
import dev.memscope.capture.Job
import dev.memscope.capture.MeminfoPoller
import dev.memscope.capture.OnDemandDumper
import dev.memscope.capture.SessionStore
import dev.memscope.capture.SessionMeta
import dev.memscope.capture.StartupConfig
import dev.memscope.capture.StartupListener
import dev.memscope.capture.StartupSession
import dev.memscope.device.Adb

/** Runs one startup recording on the calling (worker) thread; tests substitute a fake. */
fun interface StartupRunner {
    fun run(deps: ServerDeps, sessionId: String, config: StartupConfig, job: Job)

    companion object {
        val real = StartupRunner { deps, sessionId, config, job ->
            StartupSession(deps.adb, deps.store, deps.guard, listener = deps.pollerListener(sessionId, config.processName)).run(sessionId, config, job)
        }
    }
}

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
    val startupRunner: StartupRunner = StartupRunner.real,
    /** False in tests that must not see background `dumpsys meminfo` traffic. */
    private val memoryPolling: Boolean = true,
) : AutoCloseable {
    val adb: Adb get() = adbProvider()
    val dumper: OnDemandDumper get() = OnDemandDumper(adb, store)
    /** Pending-debug-app markers live next to the sessions (`MEMSCOPE_HOME`). */
    val guard: DebugAppGuard get() = DebugAppGuard(adb, store.home)

    /** The startup recording in progress (at most one: a device has one debug app), or null. */
    data class RunningStartup(val sessionId: String, val job: Job)

    @Volatile private var startup: RunningStartup? = null

    fun runningStartup(): RunningStartup? = startup?.takeIf { it.job.snapshot().state == Job.State.RUNNING }

    fun setRunningStartup(r: RunningStartup) { startup = r }

    private val pollerLock = Any()
    private var poller: MeminfoPoller? = null
    private var polledSession: String? = null

    /** Polls the memory of [processName] (default: the package) for [sessionId]; the one poller moves to this session. Never throws. */
    fun startPolling(sessionId: String, processName: String? = null) {
        if (!memoryPolling) return
        try {
            synchronized(pollerLock) {
                val session = store.get(sessionId) ?: return
                val p = poller ?: MeminfoPoller(adb, store).also { poller = it }
                polledSession = sessionId
                p.start(sessionId, processName ?: session.packageName)
            }
        } catch (e: Exception) {
            ServerLog.error("Could not start memory polling for session $sessionId: ${e.message}")
        }
    }

    /** Stops polling if it is for [sessionId]. */
    fun stopPolling(sessionId: String) = synchronized(pollerLock) {
        if (polledSession == sessionId) { poller?.stop(); polledSession = null }
    }

    /** Resumes polling the active session after a server restart (the app may be gone: polls then just back off). */
    fun resumePolling() {
        store.list().firstOrNull { it.state == SessionMeta.State.ACTIVE }?.let { startPolling(it.id) }
    }

    /** Feeds a startup recording's pid and held-at-milestone state to the poller of [sessionId]. */
    fun pollerListener(sessionId: String, processName: String?) = object : StartupListener {
        override fun onPid(pid: Int) = startPolling(sessionId, processName)
        override fun onHeld(held: Boolean) { synchronized(pollerLock) { if (polledSession == sessionId) poller?.setPaused(held) } }
    }

    /** The start-up clean-up of debug apps left by a crashed run; a new recording waits for it (it clears the debug app). */
    @Volatile var staleRecovery: Thread? = null

    fun awaitStaleRecovery() { staleRecovery?.join(STALE_RECOVERY_WAIT_MS) }

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
        // A running startup recording must detach and clear the debug app before the JVM exits.
        runningStartup()?.job?.cancel?.cancel()
        synchronized(pollerLock) { poller?.stop(); polledSession = null }
        jobs.close()
        snapshots.close()
    }

    private companion object {
        const val APPS_TTL_MS = 30_000L
        const val STALE_RECOVERY_WAIT_MS = 30_000L
    }
}
