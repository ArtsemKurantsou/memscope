package dev.memscope.server

import dev.memscope.analysis.AnalysisBugException
import dev.memscope.analysis.DiffJson
import dev.memscope.analysis.DiffResult
import dev.memscope.analysis.HprofFiles
import dev.memscope.analysis.HprofUnreadableException
import dev.memscope.capture.DeviceArgs
import dev.memscope.capture.Job
import dev.memscope.capture.MemTimeline
import dev.memscope.capture.Milestones
import dev.memscope.capture.OnDemandDumper
import dev.memscope.capture.SessionMeta
import dev.memscope.capture.SnapshotMeta
import dev.memscope.capture.StartupConfig
import dev.memscope.capture.StartupSession
import dev.memscope.device.DeviceException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.request.contentType
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
import io.ktor.server.response.respondText
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.routing.RoutingContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.nio.file.Files

/** An error with a fixed HTTP status, rendered as `{error, hint, kind}`. */
class ApiException(val status: HttpStatusCode, message: String, val hint: String, val kind: String) : Exception(message)

internal fun notFound(what: String) = ApiException(HttpStatusCode.NotFound, "$what not found", "Check the id; sessions and jobs from an earlier run may be gone.", "NOT_FOUND")

internal fun unreadable(message: String) = ApiException(
    HttpStatusCode.UnprocessableEntity, message,
    "The heap dump is truncated or corrupt. Capture a new snapshot and open that instead.", "UNREADABLE",
)

/**
 * The whole server: JSON API under `/api`, UI from the classpath `ui/` directory at `/`.
 * Only requests whose Host header is in [allowedHosts] are served (DNS-rebinding protection); no CORS.
 */
fun Application.memscopeModule(deps: ServerDeps, allowedHosts: Set<String>) {
    install(ContentNegotiation) { json(apiJson) }
    // A diff of a large app is megabytes of repetitive JSON (about 1% gzipped).
    install(Compression) {
        gzip { minimumSize(1024) }
    }

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
        // Cross-site request forgery: a web page can POST to localhost, and its browser says where it came from.
        val origin = call.request.headers[HttpHeaders.Origin]
        if (call.request.local.method != HttpMethod.Get && origin != null && origin !in allowedHosts.map { "http://$it" }) {
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorBody("Cross-site request refused", "Use the MemScope page itself (http://localhost:<port>); other origins may not change state.", "FORBIDDEN_ORIGIN"),
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
            milestoneRoutes()
            jobRoutes(deps)
            snapshotRoutes(deps)
            findingsRoutes(deps)
            deleteRoutes(deps)
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
        is AnalysisBugException -> {
            ServerLog.error("analysis bug on ${request.local.method.value} ${request.local.uri}: ${t.cause}", t)
            body(HttpStatusCode.InternalServerError, AnalysisBugException.MESSAGE, AnalysisBugException.HINT, "ANALYSIS_BUG")
        }
        is TooDeepToReveal -> body(HttpStatusCode.UnprocessableEntity, "too deep to reveal", "use the path shown in the details panel", "TOO_DEEP")
        is NoSuchElementException -> body(HttpStatusCode.NotFound, msg, notFound("Item").hint, "NOT_FOUND")
        is IllegalArgumentException, is SerializationException, is ContentTransformationException,
        is ContentConvertException, is BadRequestException ->
            body(HttpStatusCode.BadRequest, msg, "Check the request body and query parameters; refs come from earlier tree/object responses.", "BAD_REQUEST")
        else -> {
            ServerLog.error("internal error on ${request.local.method.value} ${request.local.uri}: $msg", t)
            body(HttpStatusCode.InternalServerError, msg, "This is a bug in MemScope; the terminal running the server has the stack trace.", "INTERNAL")
        }
    }
}

/** Refuses a body that is not JSON (cross-site forms can only send form or text types). */
private fun requireJson(call: ApplicationCall) {
    val type = call.request.contentType()
    if (!type.match(ContentType.Application.Json)) {
        throw ApiException(HttpStatusCode.UnsupportedMediaType, "Content-Type must be application/json", "Send the body as JSON with Content-Type: application/json.", "UNSUPPORTED_MEDIA_TYPE")
    }
}

/** Runs blocking work (adb, disk, heap queries) off the event loop. */
private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

private fun Route.deviceRoutes(deps: ServerDeps) {
    get("/devices") {
        call.respond(io { deps.adb.devices().map { it.toDto() } })
    }
    get("/devices/{serial}/apps") {
        val serial = call.parameters["serial"]!!.also(DeviceArgs::requireSerial)
        val refresh = call.request.queryParameters["refresh"] == "1"
        call.respond(io { deps.apps(serial, refresh) })
    }
    get("/devices/{serial}/apps/{pkg}/launcher") {
        val serial = call.parameters["serial"]!!.also(DeviceArgs::requireSerial)
        val pkg = call.parameters["pkg"]!!.also(DeviceArgs::requirePackage)
        call.respond(LauncherDto(io { deps.adb.launcherComponent(serial, pkg) }))
    }
}

private val sessionLock = Any()

/** A device has one debug app and the recorded app is held by the debugger: one recording at a time, no other session meanwhile. */
private fun requireNoStartupRunning(deps: ServerDeps) {
    val running = deps.runningStartup() ?: return
    throw ApiException(
        HttpStatusCode.Conflict, "A startup recording is still running (job ${running.job.id})",
        "Wait until it has finished, or cancel it, then retry.", "STARTUP_RUNNING",
    )
}

private fun Route.milestoneRoutes() {
    get("/milestones/defaults") { call.respond(Milestones.defaults) }
}

private fun Route.sessionRoutes(deps: ServerDeps) {
    val store = deps.store

    /** The screen size the oversized-bitmap rule compares against; best effort (the rule falls back to 1080x2400). Never under [sessionLock]: `wm size` can be slow. */
    fun screenOf(req: CreateSessionRequest) = try { deps.adb.screenSize(req.serial) } catch (e: Exception) { null }

    /** Ends the other ACTIVE sessions and creates this one; caller holds [sessionLock]. */
    fun createExclusive(req: CreateSessionRequest, screen: dev.memscope.device.ScreenPx?): SessionMeta {
        store.list().filter { it.state == SessionMeta.State.ACTIVE }.forEach { other ->
            store.update(other.id) { it.copy(state = SessionMeta.State.ENDED) }
            deps.stopPolling(other.id)
        }
        var created = store.create(req.serial, req.packageName, req.mode)
        if (screen != null) created = store.update(created.id) { it.copy(screenWidthPx = screen.width, screenHeightPx = screen.height) }
        return req.appPackagePrefixes?.takeIf { it.isNotEmpty() }
            ?.let { prefixes -> store.update(created.id) { it.copy(appPackagePrefixes = prefixes) } }
            ?: created
    }

    /** STARTUP mode: answers `{session, jobId}`; the recording runs as a job on the worker pool. */
    suspend fun ApplicationCall.startStartup(req: CreateSessionRequest) {
        val config = req.startup ?: StartupConfig()
        StartupSession.validate(config, req.packageName) // 400 before anything is checked or changed on the device
        if (deps.staleRecoveryRunning()) {
            throw ApiException(
                HttpStatusCode.ServiceUnavailable, "MemScope is still cleaning up a previous recording",
                "It clears a debug app left by an interrupted run; try again in a moment.", "RECOVERING",
            )
        }
        val started = io {
            synchronized(sessionLock) { requireNoStartupRunning(deps) }
            deps.adb.requireDebuggable(req.serial, req.packageName) // before anything changes
            val screen = screenOf(req)
            synchronized(sessionLock) {
                requireNoStartupRunning(deps)
                val session = createExclusive(req, screen)
                val job = deps.jobs.create(
                    "Record startup of ${config.processName ?: req.packageName}", StartupSession.stepNames(config),
                    JobRegistry.Scope(session.id, null, "startup recording"),
                )
                deps.setRunningStartup(ServerDeps.RunningStartup(session.id, job))
                deps.jobs.submit(
                    job, JobRegistry.Lane.DEVICE,
                    // cancelled while queued behind a dump: nothing happened on the device, the empty session just ends
                    onSkipped = { store.update(session.id) { it.copy(state = SessionMeta.State.ENDED, note = "recording cancelled before it started") } },
                ) { deps.startupRunner.run(deps, session.id, config, job) }
                StartupStarted(session, job.id)
            }
        }
        respond(started)
    }

    post("/sessions") {
        requireJson(call)
        val req = call.receive<CreateSessionRequest>()
        require(req.serial.isNotBlank() && req.packageName.isNotBlank()) { "serial and packageName are required" }
        DeviceArgs.requireSerial(req.serial) // both reach `adb shell` command lines
        DeviceArgs.requirePackage(req.packageName)
        if (req.mode == SessionMeta.Mode.STARTUP) {
            call.startStartup(req)
            return@post
        }
        val session = io {
            deps.adb.requireDebuggable(req.serial, req.packageName) // before anything changes
            val screen = screenOf(req)
            synchronized(sessionLock) {
                requireNoStartupRunning(deps)
                createExclusive(req, screen)
            }
        }
        deps.startPolling(session.id)
        call.respond(session)
    }
    get("/sessions") {
        // each row is the session plus `diskBytes`: its hprofs, analysis sidecars and timeline
        call.respond(io {
            kotlinx.serialization.json.JsonArray(store.list().map { m ->
                val row = apiJson.encodeToJsonElement(SessionMeta.serializer(), m) as kotlinx.serialization.json.JsonObject
                kotlinx.serialization.json.JsonObject(row + ("diskBytes" to kotlinx.serialization.json.JsonPrimitive(store.diskBytes(m.id))))
            })
        })
    }
    get("/sessions/{id}") {
        call.respond(io { store.get(call.parameters["id"]!!) } ?: throw notFound("Session"))
    }
    get("/sessions/{id}/timeline") {
        val id = call.parameters["id"]!!
        val since = call.request.queryParameters["sinceMs"]?.let { it.toLongOrNull() ?: throw IllegalArgumentException("query parameter 'sinceMs' must be an integer") }
        call.respond(io { MemTimeline.read(store, id, since) })
    }
    post("/sessions/{id}/end") {
        val id = call.parameters["id"]!!
        deps.runningStartup()?.takeIf { it.sessionId == id }?.job?.cancel?.cancel() // the recording detaches and cleans up
        // a dump of an ended session must not append its snapshot: cancel it (queued or running)
        deps.jobs.runningFor(id).filter { (_, scope) -> scope.snapshotId == null }.forEach { (job, _) -> job.cancel.cancel() }
        deps.stopPolling(id)
        call.respond(io { store.update(id) { it.copy(state = SessionMeta.State.ENDED) } })
    }
    post("/sessions/{id}/dump") {
        val id = call.parameters["id"]!!
        val text = call.receiveText()
        if (text.isNotBlank()) requireJson(call)
        val req = if (text.isBlank()) DumpRequest() else apiJson.decodeFromString(DumpRequest.serializer(), text)
        val session = io { store.get(id) } ?: throw notFound("Session")
        if (session.state != SessionMeta.State.ACTIVE) {
            throw ApiException(HttpStatusCode.Conflict, "Session ${session.id} has ended", "Start a new session to capture more snapshots.", "SESSION_ENDED")
        }
        if (deps.runningStartup()?.sessionId == id) {
            throw ApiException(
                HttpStatusCode.Conflict, "The startup recording of this session is still running",
                "Wait until it has finished (or cancel it), then dump.", "STARTUP_RUNNING",
            )
        }
        val process = req.processName?.takeIf { it.isNotBlank() } ?: session.packageName
        DeviceArgs.requireProcessName(process)
        val label = req.label?.takeIf { it.isNotBlank() } ?: "Snapshot ${session.snapshots.size + 1}"
        val job = deps.jobs.create("Dump $process", OnDemandDumper.STEP_NAMES, JobRegistry.Scope(id, null, "heap dump"))
        deps.jobs.submit(job, JobRegistry.Lane.DEVICE) { deps.dumper.dump(id, process, label, job) }
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

/** Analysis threads still running per snapshot key, including ones abandoned by a cancelled job. Guarded by [openLock]. */
private val analysisThreads = HashMap<String, java.util.concurrent.CompletableFuture<Unit>>()

private fun Route.snapshotRoutes(deps: ServerDeps) {
    val store = deps.store

    fun RoutingContext.ids() = call.parameters["sid"]!! to call.parameters["snap"]!!

    fun lookup(sid: String, snap: String): Pair<SessionMeta, SnapshotMeta> {
        val session = store.get(sid) ?: throw notFound("Session")
        return session to (session.snapshots.firstOrNull { it.id == snap } ?: throw notFound("Snapshot"))
    }

    /** Never throws: bookkeeping must not mask the error being reported. */
    fun markUnreadable(sid: String, snap: String, message: String) {
        try {
            store.update(sid) { s ->
                s.copy(snapshots = s.snapshots.map { if (it.id == snap) it.copy(status = SnapshotMeta.Status.UNREADABLE, error = message) else it })
            }
        } catch (t: Exception) {
            ServerLog.error("could not record snapshot $sid/$snap as UNREADABLE: ${t.message}")
        }
    }

    /** Clears an UNREADABLE mark after a successful analysis. Never throws. */
    fun markReadable(sid: String, snap: String) {
        try {
            if (store.get(sid)?.snapshots?.any { it.id == snap && it.status == SnapshotMeta.Status.UNREADABLE } != true) return
            store.update(sid) { s ->
                s.copy(snapshots = s.snapshots.map { if (it.id == snap) it.copy(status = SnapshotMeta.Status.OK, error = null) else it })
            }
        } catch (t: Exception) {
            ServerLog.error("could not record snapshot $sid/$snap as readable again: ${t.message}")
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

    /** Both snapshots (same session) must be open unless the result is cached; the 409 names the one that is not. */
    fun notAnalysedNamed(id: String) =
        ApiException(HttpStatusCode.Conflict, "not analysed: $id", SnapshotRegistry.notAnalysed().hint, "NOT_ANALYSED")

    fun diff(sid: String, snap: String, base: String): DiffResult {
        lookup(sid, base)
        for (id in listOf(base, snap)) {
            val meta = lookup(sid, id).second
            if (meta.status == SnapshotMeta.Status.UNREADABLE) throw unreadable(meta.error ?: "Snapshot $id is unreadable")
        }
        return deps.diffs.getOrCompute("$sid/$snap|$base") {
            for (id in listOf(base, snap)) {
                if (!deps.snapshots.contains("$sid/$id")) throw notAnalysedNamed(id)
            }
            try {
                query(sid, snap) { after -> query(sid, base) { before -> after.diff(before) } }
            } catch (e: ApiException) {
                // A snapshot was evicted between the check above and the query: say which one.
                if (e.kind != "NOT_ANALYSED" || e.message != "not analysed") throw e
                throw notAnalysedNamed(listOf(base, snap).firstOrNull { !deps.snapshots.contains("$sid/$it") } ?: snap)
            }
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
                val file = store.snapshotFile(sid, snap)
                if (meta.status == SnapshotMeta.Status.UNREADABLE) {
                    // An earlier failure may not have been the file's fault: re-validate it and retry if it looks intact.
                    HprofFiles.headerProblem(file.toFile())?.let { problem ->
                        markUnreadable(sid, snap, problem)
                        throw unreadable(problem)
                    }
                }
                if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
                    val message = "Heap dump ${file.fileName} is missing or empty"
                    markUnreadable(sid, snap, message)
                    throw unreadable(message)
                }
                synchronized(openLock) {
                    openJobs[key]?.takeIf { it.snapshot().state == Job.State.RUNNING }?.let { return@synchronized it }
                    val created = deps.jobs.create("Analyse $key", ANALYSIS_STEPS, JobRegistry.Scope(sid, snap, "analysis"))
                    if (deps.snapshots.contains(key)) {
                        ANALYSIS_STEPS.forEach { created.step(it) { } }
                        markReadable(sid, snap)
                        created.complete(key)
                        return@synchronized created
                    }
                    openJobs[key] = created
                    deps.jobs.submit(created) {
                        val mine = java.util.concurrent.CompletableFuture<Unit>()
                        val prior = synchronized(openLock) { analysisThreads.put(key, mine) }
                        mine.whenComplete { _, _ -> synchronized(openLock) { analysisThreads.remove(key, mine) } }
                        var started = false
                        try {
                            if (prior != null) awaitPreviousAnalysis(created, prior, deps.previousAnalysisTimeoutMs)
                            deps.snapshots.makeRoomForOne() // never a third index in memory while two are open
                            started = true // runAnalysis starts its thread at once; that thread completes `mine`
                            val view = runAnalysis(created, mine) { progress -> deps.opener.open(file.toFile(), session.appPackagePrefixes, progress) }
                            deps.snapshots.put(key, view)
                            markReadable(sid, snap)
                            // The session rules read this summary instead of opening the snapshot.
                            try { deps.snapshots.use(key) { it.snapshotSummary() } } catch (e: Exception) { ServerLog.error("could not summarise $key: ${e.message}") }
                            created.complete(key)
                            deps.jobs.background { warmUp(deps, key) }
                        } catch (e: HprofUnreadableException) {
                            markUnreadable(sid, snap, e.message ?: "unreadable")
                            throw DeviceException(DeviceException.Kind.COMMAND_FAILED, e.message ?: "unreadable", unreadable("").hint)
                        } catch (e: OutOfMemoryError) {
                            ServerLog.error("analysis of $key ran out of memory: $e")
                            throw DeviceException(
                                DeviceException.Kind.OUT_OF_MEMORY, "ran out of memory analysing this snapshot",
                                "start MemScope with a larger heap: MEMSCOPE_XMX=10g ./memscope — or analyse one snapshot at a time", e,
                            )
                        } catch (e: DeviceException) {
                            throw e // cancelled, or timed out waiting for an abandoned analysis
                        } catch (e: Exception) {
                            // Not the file's fault (that is HprofUnreadableException): the snapshot stays OK and can be re-opened.
                            ServerLog.error("analysis of $key hit a bug: $e", e)
                            throw DeviceException(DeviceException.Kind.COMMAND_FAILED, AnalysisBugException.MESSAGE, AnalysisBugException.HINT, e)
                        } finally {
                            synchronized(openLock) { openJobs.remove(key, created) }
                            if (!started) {
                                // never leave `mine` pending, but never let it finish before the older analysis really has
                                if (prior == null) mine.complete(Unit) else prior.whenComplete { _, _ -> mine.complete(Unit) }
                            }
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
        get("/diff") {
            val (s, n) = ids()
            val base = call.str("base")
            call.respondText(DiffJson.encode(io { diff(s, n, base) }), ContentType.Application.Json)
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

/**
 * `DELETE /api/sessions/{id}` and `DELETE /api/snapshots/{sid}/{snap}`: free the disk (and remove the data). 409 BUSY while
 * a recording, dump, analysis or findings job that would touch it runs (or an abandoned analysis of it is still
 * stopping). A session is ended first (its memory polling stops); its open snapshots are closed and cached diffs and
 * findings dropped. Only files inside the session directory are deleted (ids are validated; links are not followed).
 */
private fun Route.deleteRoutes(deps: ServerDeps) {
    val store = deps.store

    fun requireIdle(sid: String, snap: String?) {
        val (job, scope) = deps.jobs.runningFor(sid, snap).firstOrNull()
            ?: synchronized(openLock) {
                analysisThreads.keys.firstOrNull { it == "$sid/$snap" || (snap == null && it.startsWith("$sid/")) }
            }?.let { key ->
                throw ApiException(
                    HttpStatusCode.Conflict, "An earlier analysis of $key is still stopping",
                    "Wait a moment, then delete again.", "BUSY",
                )
            }
            ?: return
        throw ApiException(
            HttpStatusCode.Conflict, "A ${scope.what} of ${if (snap == null) "this session" else "this snapshot"} is still running (job ${job.id})",
            "Wait until it has finished, or cancel it, then delete again.", "BUSY",
        )
    }

    /** Closes open snapshots and drops cached results of [sid] ([snap] null: all of its snapshots). */
    fun forget(sid: String, snap: String?) {
        val prefix = if (snap == null) "$sid/" else "$sid/$snap"
        fun matches(key: String) = if (snap == null) key.startsWith(prefix) else key == prefix
        deps.snapshots.keys().filter(::matches).forEach { deps.snapshots.remove(it) }
        deps.findingsCache.keys.removeIf(::matches)
        // diff keys are `<sid>/<snap>|<base>`; the base is a snapshot id of the same session
        deps.diffs.removeIf { key ->
            val (after, base) = key.substringBefore('|') to key.substringAfter('|')
            if (snap == null) after.startsWith(prefix) else after == prefix || (after.startsWith("$sid/") && base == snap)
        }
    }

    delete("/sessions/{id}") {
        val id = call.parameters["id"]!!
        val freed = io {
            synchronized(sessionLock) {
                val session = store.get(id) ?: throw notFound("Session")
                requireIdle(id, null)
                if (session.state == SessionMeta.State.ACTIVE) deps.stopPolling(id)
                forget(id, null)
                val bytes = store.diskBytes(id)
                store.deleteSession(id)
                bytes
            }
        }
        call.respond(DeletedDto(id, freed))
    }

    delete("/snapshots/{sid}/{snap}") {
        val sid = call.parameters["sid"]!!
        val snap = call.parameters["snap"]!!
        val freed = io {
            synchronized(sessionLock) {
                val session = store.get(sid) ?: throw notFound("Session")
                store.snapshotFile(sid, snap) // validates the id
                if (session.snapshots.none { it.id == snap }) throw notFound("Snapshot")
                requireIdle(sid, snap)
                forget(sid, snap)
                val bytes = store.snapshotBytes(sid, snap)
                store.deleteSnapshot(sid, snap)
                bytes
            }
        }
        call.respond(DeletedDto("$sid/$snap", freed))
    }
}
