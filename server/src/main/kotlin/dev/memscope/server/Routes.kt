package dev.memscope.server

import dev.memscope.analysis.HprofUnreadableException
import dev.memscope.capture.Job
import dev.memscope.capture.OnDemandDumper
import dev.memscope.capture.SessionMeta
import dev.memscope.capture.SnapshotMeta
import dev.memscope.device.DeviceException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.ContentTransformationException
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.pipeline.PipelineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.nio.file.Files

/** An error with a fixed HTTP status, rendered as `{error, hint, kind}`. */
class ApiException(val status: HttpStatusCode, message: String, val hint: String, val kind: String) : Exception(message)

private fun notFound(what: String) = ApiException(HttpStatusCode.NotFound, "$what not found", "Check the id; sessions and jobs from an earlier run may be gone.", "NOT_FOUND")

private fun unreadable(message: String) = ApiException(
    HttpStatusCode.UnprocessableEntity, message,
    "The heap dump is truncated or corrupt. Capture a new snapshot and open that instead.", "UNREADABLE",
)

/**
 * The whole server: JSON API under `/api`, UI from the classpath `ui/` directory at `/`.
 * Only requests whose Host header is in [allowedHosts] are served (DNS-rebinding protection); no CORS.
 */
fun Application.memscopeModule(deps: ServerDeps, allowedHosts: Set<String>) {
    install(ContentNegotiation) { json(apiJson) }

    intercept(ApplicationCallPipeline.Plugins) {
        // HTTP/1.1 always carries Host; without it (HTTP/1.0, the in-process test engine) the address the engine saw is used.
        val host = call.request.headers[HttpHeaders.Host] ?: call.request.origin.serverHost
        if (host !in allowedHosts) {
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorBody("Unexpected Host header", "MemScope only answers on localhost; open it at http://localhost:<port>.", "FORBIDDEN_HOST"),
            )
            finish()
            return@intercept
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
    }

    routing {
        route("/api") {
            intercept(ApplicationCallPipeline.Call) {
                try {
                    proceed()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    call.respondError(t)
                }
            }
            deviceRoutes(deps)
            sessionRoutes(deps)
            jobRoutes(deps)
            snapshotRoutes(deps)
        }
        staticResources("/", "ui")
    }
}

private suspend fun ApplicationCall.respondError(t: Throwable) {
    suspend fun body(status: HttpStatusCode, e: String, hint: String, kind: String) = respond(status, ErrorBody(e, hint, kind))
    val msg = t.message ?: t.javaClass.simpleName
    when (t) {
        is ApiException -> body(t.status, msg, t.hint, t.kind)
        is DeviceException -> body(HttpStatusCode.BadGateway, msg, t.hint, t.kind.name)
        is HprofUnreadableException -> body(HttpStatusCode.UnprocessableEntity, msg, unreadable(msg).hint, "UNREADABLE")
        is TooDeepToReveal -> body(HttpStatusCode.UnprocessableEntity, "too deep to reveal", "use the path shown in the details panel", "TOO_DEEP")
        is NoSuchElementException -> body(HttpStatusCode.NotFound, msg, notFound("Item").hint, "NOT_FOUND")
        is IllegalArgumentException, is SerializationException, is ContentTransformationException,
        is ContentConvertException, is BadRequestException ->
            body(HttpStatusCode.BadRequest, msg, "Check the request body and query parameters; refs come from earlier tree/object responses.", "BAD_REQUEST")
        else -> body(HttpStatusCode.InternalServerError, msg, "This is a bug in MemScope; the server log has the details.", "INTERNAL")
    }
}

/** Runs blocking work (adb, disk, heap queries) off the event loop. */
private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

private fun Route.deviceRoutes(deps: ServerDeps) {
    get("/devices") {
        call.respond(io { deps.adb.devices().map { it.toDto() } })
    }
    get("/devices/{serial}/apps") {
        val serial = call.parameters["serial"]!!
        val refresh = call.request.queryParameters["refresh"] == "1"
        call.respond(io { deps.apps(serial, refresh) })
    }
}

private val sessionLock = Any()

private fun Route.sessionRoutes(deps: ServerDeps) {
    val store = deps.store

    post("/sessions") {
        val req = call.receive<CreateSessionRequest>()
        require(req.serial.isNotBlank() && req.packageName.isNotBlank()) { "serial and packageName are required" }
        if (req.mode == SessionMeta.Mode.STARTUP) {
            throw ApiException(HttpStatusCode.NotImplemented, "startup recording not available yet", "Use ATTACH mode for now.", "NOT_IMPLEMENTED")
        }
        val session = io {
            deps.adb.requireDebuggable(req.serial, req.packageName) // before anything changes
            synchronized(sessionLock) {
                store.list().filter { it.state == SessionMeta.State.ACTIVE }.forEach { other ->
                    store.update(other.id) { it.copy(state = SessionMeta.State.ENDED) }
                }
                val created = store.create(req.serial, req.packageName, req.mode)
                req.appPackagePrefixes?.takeIf { it.isNotEmpty() }
                    ?.let { prefixes -> store.update(created.id) { it.copy(appPackagePrefixes = prefixes) } }
                    ?: created
            }
        }
        call.respond(session)
    }
    get("/sessions") { call.respond(io { store.list() }) }
    get("/sessions/{id}") {
        call.respond(io { store.get(call.parameters["id"]!!) } ?: throw notFound("Session"))
    }
    post("/sessions/{id}/end") {
        val id = call.parameters["id"]!!
        call.respond(io { store.update(id) { it.copy(state = SessionMeta.State.ENDED) } })
    }
    post("/sessions/{id}/dump") {
        val id = call.parameters["id"]!!
        val text = call.receiveText()
        val req = if (text.isBlank()) DumpRequest() else apiJson.decodeFromString(DumpRequest.serializer(), text)
        val session = io { store.get(id) } ?: throw notFound("Session")
        if (session.state != SessionMeta.State.ACTIVE) {
            throw ApiException(HttpStatusCode.Conflict, "Session ${session.id} has ended", "Start a new session to capture more snapshots.", "SESSION_ENDED")
        }
        val process = req.processName?.takeIf { it.isNotBlank() } ?: session.packageName
        val label = req.label?.takeIf { it.isNotBlank() } ?: "Snapshot ${session.snapshots.size + 1}"
        val job = deps.jobs.create("Dump $process", OnDemandDumper.STEP_NAMES)
        deps.jobs.submit(job) { deps.dumper.dump(id, process, label, job) }
        call.respond(JobRef(job.id))
    }
}

private fun Route.jobRoutes(deps: ServerDeps) {
    get("/jobs/{id}") {
        call.respond((deps.jobs.get(call.parameters["id"]!!) ?: throw notFound("Job")).snapshot())
    }
    post("/jobs/{id}/cancel") {
        val job = deps.jobs.get(call.parameters["id"]!!) ?: throw notFound("Job")
        job.cancel.cancel()
        call.respond(job.snapshot())
    }
}

private val openLock = Any()
private val openJobs = HashMap<String, Job>()

private fun Route.snapshotRoutes(deps: ServerDeps) {
    val store = deps.store

    fun PipelineContext<Unit, ApplicationCall>.ids() = call.parameters["sid"]!! to call.parameters["snap"]!!

    fun lookup(sid: String, snap: String): Pair<SessionMeta, SnapshotMeta> {
        val session = store.get(sid) ?: throw notFound("Session")
        return session to (session.snapshots.firstOrNull { it.id == snap } ?: throw notFound("Snapshot"))
    }

    fun markUnreadable(sid: String, snap: String, message: String) {
        store.update(sid) { s ->
            s.copy(snapshots = s.snapshots.map { if (it.id == snap) it.copy(status = SnapshotMeta.Status.UNREADABLE, error = message) else it })
        }
    }

    /** Runs a query on the opened snapshot: 404 unknown, 422 unreadable, 409 not (or no longer) open. */
    fun <T> query(sid: String, snap: String, f: (SnapshotView) -> T): T {
        val (_, meta) = lookup(sid, snap)
        if (meta.status == SnapshotMeta.Status.UNREADABLE) throw unreadable(meta.error ?: "Snapshot $snap is unreadable")
        return try {
            deps.snapshots.use("$sid/$snap", f)
        } catch (e: HprofUnreadableException) {
            markUnreadable(sid, snap, e.message ?: "unreadable")
            throw unreadable(e.message ?: "Snapshot $snap is unreadable")
        }
    }

    fun ApplicationCall.str(name: String): String =
        request.queryParameters[name] ?: throw IllegalArgumentException("missing query parameter '$name'")

    fun ApplicationCall.int(name: String, default: Int): Int =
        request.queryParameters[name]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("query parameter '$name' must be an integer") } ?: default

    route("/snapshots/{sid}/{snap}") {
        post("/open") {
            val (sid, snap) = ids()
            val job = io {
                val (session, meta) = lookup(sid, snap)
                val key = "$sid/$snap"
                if (meta.status == SnapshotMeta.Status.UNREADABLE) throw unreadable(meta.error ?: "Snapshot $snap is unreadable")
                val file = store.snapshotFile(sid, snap)
                if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
                    val message = "Heap dump ${file.fileName} is missing or empty"
                    markUnreadable(sid, snap, message)
                    throw unreadable(message)
                }
                synchronized(openLock) {
                    openJobs[key]?.takeIf { it.snapshot().state == Job.State.RUNNING }?.let { return@synchronized it }
                    val created = deps.jobs.create("Analyse $key", ANALYSIS_STEPS)
                    if (deps.snapshots.contains(key)) {
                        ANALYSIS_STEPS.forEach { created.step(it) { } }
                        created.complete(key)
                        return@synchronized created
                    }
                    openJobs[key] = created
                    deps.jobs.submit(created) {
                        try {
                            val view = runAnalysis(created) { progress -> deps.opener.open(file.toFile(), session.appPackagePrefixes, progress) }
                            deps.snapshots.put(key, view)
                            created.complete(key)
                            deps.jobs.background { warmUp(deps, key) }
                        } catch (e: HprofUnreadableException) {
                            markUnreadable(sid, snap, e.message ?: "unreadable")
                            throw DeviceException(DeviceException.Kind.COMMAND_FAILED, e.message ?: "unreadable", unreadable("").hint)
                        } finally {
                            synchronized(openLock) { openJobs.remove(key) }
                        }
                    }
                    created
                }
            }
            call.respond(JobRef(job.id))
        }
        get("/summary") { val (s, n) = ids(); call.respond(io { query(s, n) { it.summary() } }) }
        get("/tree") {
            val (s, n) = ids()
            val ref = call.str("ref")
            val offset = call.int("offset", 0)
            val limit = call.int("limit", 25).coerceIn(0, 1000)
            call.respond(io { query(s, n) { it.children(ref, offset, limit) } })
        }
        get("/object") { val (s, n) = ids(); val ref = call.str("ref"); call.respond(io { query(s, n) { it.details(ref) } }) }
        get("/reveal") {
            val (s, n) = ids()
            val ref = call.str("ref")
            val path = io { query(s, n) { it.revealPath(ref) } }
            if (path.size > REVEAL_MAX_DEPTH) throw TooDeepToReveal()
            call.respond(path)
        }
        get("/indexInParent") {
            val (s, n) = ids()
            val parent = call.str("parent")
            val child = call.str("child")
            call.respond(IndexBody(io { query(s, n) { it.indexInParent(parent, child) } }))
        }
        get("/classes") { val (s, n) = ids(); call.respond(io { query(s, n) { it.classes() } }) }
        get("/instances") {
            val (s, n) = ids()
            val cls = call.str("class")
            val offset = call.int("offset", 0)
            val limit = call.int("limit", 25).coerceIn(0, 1000)
            call.respond(io { query(s, n) { it.instances(cls, offset, limit) } })
        }
    }
}

/** The first details/label call opens a Shark graph (seconds on a big dump): do it before the first click. */
private fun warmUp(deps: ServerDeps, key: String) {
    deps.snapshots.use(key) { view ->
        val first = view.children("root", 0, 1).rows.firstOrNull() ?: return@use
        view.details(first.ref)
    }
}
