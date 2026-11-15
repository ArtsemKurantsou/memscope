package dev.memscope.server

import dev.memscope.analysis.HprofUnreadableException
import dev.memscope.analysis.findings.Finding
import dev.memscope.analysis.findings.Findings
import dev.memscope.analysis.findings.ScreenSize
import dev.memscope.analysis.findings.SnapshotSummaries
import dev.memscope.analysis.findings.SummarizedSnapshot
import dev.memscope.capture.Job
import dev.memscope.capture.SessionMeta
import dev.memscope.capture.SnapshotMeta
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class FindingsResponse(val findings: List<Finding>)

/** [pending] are the OK snapshots without a summary yet (not analysed): open them to include them. */
@Serializable
data class SessionFindingsResponse(val findings: List<Finding>, val pending: List<String>)

/**
 * `GET /api/snapshots/{sid}/{snap}/findings`: 200 `{findings}` once computed, otherwise 202 `{jobId}` of the job
 * computing them (started by the first request; the snapshot must be open, else 409 NOT_ANALYSED). The UI polls
 * the job and asks again. Results are cached in memory per snapshot (a snapshot file never changes).
 *
 * `GET /api/sessions/{id}/findings`: `{findings, pending}` of the session rules (monotonic growth, startup
 * intervals) computed from the per-snapshot summaries, without opening any snapshot.
 */
internal fun Route.findingsRoutes(deps: ServerDeps) {
    val store = deps.store
    val cache = ConcurrentHashMap<String, List<Finding>>()
    val running = HashMap<String, Job>()

    fun session(sid: String): SessionMeta = store.get(sid) ?: throw notFound("Session")

    fun screenOf(s: SessionMeta): ScreenSize =
        if (s.screenWidthPx != null && s.screenHeightPx != null) ScreenSize(s.screenWidthPx!!, s.screenHeightPx!!) else ScreenSize.FALLBACK

    get("/snapshots/{sid}/{snap}/findings") {
        val sid = call.parameters["sid"]!!
        val snap = call.parameters["snap"]!!
        val key = "$sid/$snap"
        val reply: Any = withContext(Dispatchers.IO) {
            val s = session(sid)
            val meta = s.snapshots.firstOrNull { it.id == snap } ?: throw notFound("Snapshot")
            if (meta.status == SnapshotMeta.Status.UNREADABLE) throw unreadable(meta.error ?: "Snapshot $snap is unreadable")
            cache[key]?.let { return@withContext FindingsResponse(it) }
            synchronized(running) {
                cache[key]?.let { return@withContext FindingsResponse(it) }
                running[key]?.takeIf { it.snapshot().state == Job.State.RUNNING }?.let { return@withContext JobRef(it.id) }
                if (!deps.snapshots.contains(key)) throw ApiException(HttpStatusCode.Conflict, "not analysed", SnapshotRegistry.notAnalysed().hint, "NOT_ANALYSED")
                val job = deps.jobs.create("Findings for $key", Findings.snapshotRuleIds)
                running[key] = job
                deps.jobs.submit(job) {
                    try {
                        var previous: String? = null
                        val list = deps.snapshots.use(key) { view ->
                            view.findings(screenOf(s)) { ruleId ->
                                previous?.let { p -> job.step(p) { } }
                                previous = ruleId
                                job.note(ruleId, "running")
                            }
                        }
                        previous?.let { p -> job.step(p) { } }
                        cache[key] = list
                        job.complete(key)
                    } catch (e: HprofUnreadableException) {
                        throw unreadable(e.message ?: "unreadable")
                    } finally {
                        synchronized(running) { running.remove(key, job) }
                    }
                }
                JobRef(job.id)
            }
        }
        when (reply) {
            is FindingsResponse -> call.respond(reply)
            else -> call.respond(HttpStatusCode.Accepted, reply as JobRef)
        }
    }

    get("/sessions/{id}/findings") {
        val id = call.parameters["id"]!!
        call.respond(withContext(Dispatchers.IO) {
            val s = session(id)
            val pending = ArrayList<String>()
            val summarized = ArrayList<SummarizedSnapshot>()
            for (m in s.snapshots.filter { it.status == SnapshotMeta.Status.OK }.sortedBy { it.takenAtMs }) {
                val file = store.snapshotFile(id, m.id).toFile()
                val summary = SnapshotSummaries.read(file) ?: try {
                    // Open but its sidecar is missing (opened before summaries existed): compute it now.
                    if (deps.snapshots.contains("$id/${m.id}")) deps.snapshots.use("$id/${m.id}") { it.snapshotSummary() } else null
                } catch (e: ApiException) {
                    null
                } catch (e: Exception) {
                    ServerLog.error("could not summarise snapshot $id/${m.id}: ${e.message}")
                    null
                }
                if (summary == null) pending += m.id else summarized += SummarizedSnapshot(m.id, m.label, m.milestoneId, m.takenAtMs, summary)
            }
            SessionFindingsResponse(Findings.forSession(summarized, s.mode == SessionMeta.Mode.STARTUP), pending)
        })
    }
}
