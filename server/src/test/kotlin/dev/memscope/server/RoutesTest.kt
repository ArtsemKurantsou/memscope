package dev.memscope.server

import dev.memscope.analysis.HprofUnreadableException
import dev.memscope.capture.Job
import dev.memscope.capture.Milestones
import dev.memscope.capture.SessionMeta
import dev.memscope.capture.SessionStore
import dev.memscope.capture.SnapshotMeta
import dev.memscope.capture.StartupConfig
import dev.memscope.device.DeviceException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RoutesTest {
    @TempDir lateinit var home: Path

    private val runner = FakeAdbRunner()
    private val opener = FakeOpener()
    private lateinit var store: SessionStore
    private lateinit var deps: ServerDeps
    private var now = 1_000_000L
    private var waitTimeoutMs = 60_000L
    private var memoryPolling = false
    private val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
    private lateinit var http: HttpClient

    private fun server(block: suspend ApplicationTestBuilder.() -> Unit) {
        store = SessionStore(home)
        deps = ServerDeps(store, { runner.adb }, opener, clock = { now }, previousAnalysisTimeoutMs = waitTimeoutMs, startupRunner = startupRunner, memoryPolling = memoryPolling)
        ServerLog.sink = { logs += it }
        try {
            testApplication {
                application { memscopeModule(deps, setOf("localhost")) }
                http = client
                block()
            }
        } finally {
            deps.close()
            ServerLog.sink = { System.err.println(it) }
        }
    }

    private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)
    private suspend fun HttpResponse.json(): JsonElement = parse(bodyAsText())
    private suspend fun HttpResponse.obj(): JsonObject = json().jsonObject
    private suspend fun HttpResponse.array(): JsonArray = json().jsonArray

    private suspend fun HttpClient.postJson(url: String, body: String = "{}") =
        post(url) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.waitJob(jobId: String, timeoutMs: Long = 10_000): JsonObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val job = get("/api/jobs/$jobId").obj()
            if (job["state"]!!.jsonPrimitive.content != "RUNNING" || System.currentTimeMillis() > deadline) return job
            Thread.sleep(20)
        }
    }

    private suspend fun HttpClient.openSnapshot(sid: String, snap: String): JsonObject {
        val r = postJson("/api/snapshots/$sid/$snap/open")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return waitJob(r.obj()["jobId"]!!.jsonPrimitive.content)
    }

    private fun str(o: JsonObject, k: String) = o[k]!!.jsonPrimitive.content

    // ---------------------------------------------------------------- devices and apps

    @Test fun devicesListsAttachedDevices() = server {
        val r = client.get("/api/devices")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val d = r.array().single().jsonObject
        assertEquals("emulator-5554", str(d, "serial"))
        assertEquals("device", str(d, "state"))
        assertEquals("sdk_gphone64", str(d, "model"))
        assertEquals(35, d["apiLevel"]!!.jsonPrimitive.int)
    }

    @Test fun deviceFailureIs502WithHintAndKind() = server {
        runner.noDevices = true
        val r = client.get("/api/devices")
        assertEquals(HttpStatusCode.BadGateway, r.status)
        val e = r.obj()
        assertEquals("NO_DEVICE", str(e, "kind"))
        assertTrue(str(e, "hint").isNotBlank())
        assertTrue(str(e, "error").isNotBlank())
    }

    @Test fun appsListsOnlyDebuggablePackagesWithProcesses() = server {
        val apps = client.get("/api/devices/emulator-5554/apps").array()
        val app = apps.single().jsonObject
        assertEquals("dev.app", str(app, "packageName"))
        assertEquals(true, app["running"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf("dev.app", "dev.app:remote"), app["processes"]!!.jsonArray.map { str(it.jsonObject, "name") })
        assertEquals(100, app["processes"]!!.jsonArray[0].jsonObject["pid"]!!.jsonPrimitive.int)
    }

    @Test fun appsAreCachedPerSerialUntilRefresh() = server {
        client.get("/api/devices/emulator-5554/apps")
        val after1 = runner.count("shell", "pm")
        client.get("/api/devices/emulator-5554/apps")
        assertEquals(after1, runner.count("shell", "pm"), "second call within 30 s is served from the cache")
        client.get("/api/devices/emulator-5554/apps?refresh=1")
        assertEquals(after1 + 1, runner.count("shell", "pm"))
        now += 31_000
        client.get("/api/devices/emulator-5554/apps")
        assertEquals(after1 + 2, runner.count("shell", "pm"), "cache expires after ~30 s")
    }

    // ---------------------------------------------------------------- sessions

    @Test fun createAttachSessionDefaultsPrefixesToPackage() = server {
        val r = client.postJson("/api/sessions", """{"serial":"emulator-5554","packageName":"dev.app","mode":"ATTACH"}""")
        assertEquals(HttpStatusCode.OK, r.status)
        val s = r.obj()
        assertEquals("ACTIVE", str(s, "state"))
        assertEquals("ATTACH", str(s, "mode"))
        assertEquals(listOf("dev.app"), s["appPackagePrefixes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(s, client.get("/api/sessions/${str(s, "id")}").obj())
        assertEquals(1, client.get("/api/sessions").array().size)
    }

    @Test fun createSessionHonoursCustomPrefixes() = server {
        val r = client.postJson(
            "/api/sessions",
            """{"serial":"emulator-5554","packageName":"dev.app","mode":"ATTACH","appPackagePrefixes":["dev.app","com.lib"]}""",
        )
        assertEquals(listOf("dev.app", "com.lib"), r.obj()["appPackagePrefixes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("dev.app", "com.lib"), store.list().single().appPackagePrefixes)
    }

    @Test fun nonDebuggablePackageIsRefusedWithoutCreatingASession() = server {
        val r = client.postJson("/api/sessions", """{"serial":"emulator-5554","packageName":"dev.other","mode":"ATTACH"}""")
        assertEquals(HttpStatusCode.BadGateway, r.status)
        assertEquals("NOT_DEBUGGABLE", str(r.obj(), "kind"))
        assertTrue(store.list().isEmpty())
    }

    // ---------------------------------------------------------------- startup recording

    private val startupRuns = java.util.concurrent.CopyOnWriteArrayList<Pair<String, StartupConfig>>()
    /** Fake recording: records its input; blocks until cancelled while [startupGate] is set, else completes. */
    @Volatile private var startupGate: CountDownLatch? = null
    @Volatile private var startupCleanedUp = false
    private var startupRunner = StartupRunner { _, sessionId, config, job ->
        startupRuns += sessionId to config
        startupGate?.let { gate ->
            while (!job.cancel.isCancelled && !gate.await(10, TimeUnit.MILLISECONDS)) { /* wait */ }
            if (job.cancel.isCancelled) {
                Thread.sleep(300) // detach: dispose, remove forward, clear the debug app
                startupCleanedUp = true
                val e = DeviceException(DeviceException.Kind.CANCELLED, "Startup recording was cancelled", "The operation was cancelled.")
                job.fail(e)
                throw e
            }
        }
        job.complete(sessionId)
    }

    private val startupBody = """{"serial":"emulator-5554","packageName":"dev.app","mode":"STARTUP"}"""

    @Test fun startupModeCreatesTheSessionAndRunsTheRecordingAsAJob() = server {
        val other = store.newAttachSession()
        val body = """{"serial":"emulator-5554","packageName":"dev.app","mode":"STARTUP","startup":{
            "processName":"dev.app:remote","launch":"-n dev.app/.Main --es actions x","milestoneTimeoutSec":30,
            "milestones":[{"id":"custom:a.b.C#m","label":"C#m","hook":{"className":"a.b.C","methodName":"m"},"custom":true}]}}"""
        val r = client.postJson("/api/sessions", body)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val o = r.obj()
        val session = o["session"]!!.jsonObject
        assertEquals("STARTUP", str(session, "mode"))
        val job = client.waitJob(str(o, "jobId"))
        assertEquals("DONE", str(job, "state"))
        assertEquals(str(session, "id"), str(job, "resultRef"))
        assertEquals(listOf("Prepare", "Launch app", "Attach debugger", "Dump: C#m", "Detach"), job["steps"]!!.jsonArray.map { str(it.jsonObject, "name") })
        val (sid, config) = startupRuns.single()
        assertEquals(str(session, "id"), sid)
        assertEquals(StartupConfig(listOf(Milestones.parseCustom("a.b.C#m")), "dev.app:remote", "-n dev.app/.Main --es actions x", 30), config)
        assertEquals(SessionMeta.State.ENDED, store.get(other.id)!!.state, "one active session at a time")
    }

    @Test fun startupWithoutConfigUsesTheDefaults() = server {
        val r = client.postJson("/api/sessions", startupBody)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        client.waitJob(str(r.obj(), "jobId"))
        assertEquals(StartupConfig(), startupRuns.single().second)
    }

    @Test fun invalidStartupConfigIs400BeforeAnyDeviceCommand() = server {
        for (startup in listOf(
            """{"launch":"dev.app/.Main; reboot"}""",
            """{"processName":"com.other"}""",
            """{"milestones":[]}""",
            """{"milestoneTimeoutSec":0}""",
            """{"milestones":[{"id":"x","label":"x","hook":{"className":"nodots","methodName":"m"}}]}""",
        )) {
            val r = client.postJson("/api/sessions", """{"serial":"emulator-5554","packageName":"dev.app","mode":"STARTUP","startup":$startup}""")
            assertEquals(HttpStatusCode.BadRequest, r.status, startup)
            assertEquals("BAD_REQUEST", str(r.obj(), "kind"))
        }
        assertTrue(store.list().isEmpty())
        assertTrue(startupRuns.isEmpty())
        assertEquals(0, runner.count("shell", "run-as"), "nothing is checked or changed on the device")
    }

    @Test fun startupOfANonDebuggableAppIsRefusedWithoutASession() = server {
        val r = client.postJson("/api/sessions", """{"serial":"emulator-5554","packageName":"dev.other","mode":"STARTUP"}""")
        assertEquals(HttpStatusCode.BadGateway, r.status)
        assertEquals("NOT_DEBUGGABLE", str(r.obj(), "kind"))
        assertTrue(store.list().isEmpty())
        assertTrue(startupRuns.isEmpty())
    }

    @Test fun whileARecordingRunsOtherSessionsAndDumpsAreRefusedAndCancelEndsIt() = server {
        startupGate = CountDownLatch(1)
        val o = client.postJson("/api/sessions", startupBody).obj()
        val sid = str(o["session"]!!.jsonObject, "id")
        val jobId = str(o, "jobId")
        for (body in listOf(startupBody, """{"serial":"emulator-5554","packageName":"dev.app","mode":"ATTACH"}""")) {
            val r = client.postJson("/api/sessions", body)
            assertEquals(HttpStatusCode.Conflict, r.status)
            assertEquals("STARTUP_RUNNING", str(r.obj(), "kind"))
        }
        val dump = client.postJson("/api/sessions/$sid/dump")
        assertEquals(HttpStatusCode.Conflict, dump.status)
        assertEquals("STARTUP_RUNNING", str(dump.obj(), "kind"))
        assertEquals(1, store.list().size)

        client.postJson("/api/jobs/$jobId/cancel")
        assertEquals("CANCELLED", str(client.waitJob(jobId), "state"))
        assertEquals(HttpStatusCode.OK, client.postJson("/api/sessions", startupBody).status, "a new recording may start once it ended")
    }

    @Test fun endingTheSessionCancelsItsRecording() = server {
        startupGate = CountDownLatch(1)
        val o = client.postJson("/api/sessions", startupBody).obj()
        client.postJson("/api/sessions/${str(o["session"]!!.jsonObject, "id")}/end")
        assertEquals("CANCELLED", str(client.waitJob(str(o, "jobId")), "state"))
    }

    @Test fun launcherComponentForTheDialogPlaceholder() = server {
        val r = client.get("/api/devices/emulator-5554/apps/dev.app/launcher")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("dev.app/.Main", str(r.obj(), "component"))
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/devices/emulator-5554/apps/x;y/launcher").status)
    }

    @Test fun defaultMilestonesAreServedForTheDialog() = server {
        val r = client.get("/api/milestones/defaults")
        assertEquals(HttpStatusCode.OK, r.status)
        val list = r.array().map { it.jsonObject }
        assertEquals(Milestones.defaults.map { it.id }, list.map { str(it, "id") })
        assertEquals("Before Application.onCreate", str(list[1], "label"))
        assertEquals("android.app.Instrumentation", str(list[1]["hook"]!!.jsonObject, "className"))
    }

    @Test fun recoverStaleAtStartClearsAMarkerLeftByACrash() {
        store = SessionStore(home)
        java.nio.file.Files.writeString(
            home.resolve("pending-debug-app.json"),
            """[{"serial":"emulator-5554","packageName":"dev.app","processName":"dev.app","released":true}]""",
        )
        val d = ServerDeps(store, { runner.adb }, opener)
        ServerLog.sink = { logs += it }
        try {
            recoverStaleDebugApps(d).join(10_000)
        } finally {
            ServerLog.sink = { System.err.println(it) }
        }
        assertEquals(1, runner.count("shell", "am", "clear-debug-app"))
        assertFalse(java.nio.file.Files.exists(home.resolve("pending-debug-app.json")))
        assertTrue(logs.any { "cleared" in it }, logs.toString())
        d.close()
    }

    @Test fun shellCharactersInPackageSerialOrProcessAre400BeforeAnyDeviceCommand() = server {
        for (mode in listOf("ATTACH", "STARTUP")) {
            for (body in listOf(
                """{"serial":"emulator-5554","packageName":"dev.app;reboot","mode":"$mode"}""",
                """{"serial":"emulator-5554;reboot","packageName":"dev.app","mode":"$mode"}""",
            )) {
                val r = client.postJson("/api/sessions", body)
                assertEquals(HttpStatusCode.BadRequest, r.status, body)
            }
        }
        assertTrue(store.list().isEmpty())
        assertEquals(0, runner.count("shell"), runner.calls.toString())
        val s = store.newAttachSession()
        val r = client.postJson("/api/sessions/${s.id}/dump", """{"processName":"dev.app;reboot"}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals(0, runner.count("shell"), runner.calls.toString())
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/devices/x;y/apps").status)
    }

    @Test fun startupIsRefusedWith503WhileStaleRecoveryRuns() = server {
        val release = CountDownLatch(1)
        val recovery = Thread { release.await() }.apply { isDaemon = true; start() }
        deps.staleRecovery = recovery
        val r = client.postJson("/api/sessions", startupBody)
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertEquals("RECOVERING", str(r.obj(), "kind"))
        assertTrue("try again" in str(r.obj(), "hint"))
        assertTrue(store.list().isEmpty())
        release.countDown(); recovery.join(5_000)
        assertEquals(HttpStatusCode.OK, client.postJson("/api/sessions", startupBody).status)
    }

    @Test fun theGuardIsOneSharedInstance() = server {
        assertTrue(deps.guard === deps.guard)
    }

    @Test fun closeWaitsForARunningRecordingToCleanUp() = server {
        startupGate = CountDownLatch(1)
        val o = client.postJson("/api/sessions", startupBody).obj()
        val jobId = str(o, "jobId")
        while (startupRuns.isEmpty()) Thread.sleep(10)
        deps.close()
        assertTrue(startupCleanedUp, "close() returned before the recording detached")
        assertEquals(Job.State.CANCELLED, deps.jobs.get(jobId)!!.snapshot().state)
    }

    @Test fun malformedSessionRequestIs400() = server {
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/sessions", """{"serial":"x"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/sessions", "not json").status)
    }

    @Test fun creatingASessionEndsTheOtherActiveOne() = server {
        val first = store.newAttachSession()
        val second = client.postJson("/api/sessions", """{"serial":"emulator-5554","packageName":"dev.app","mode":"ATTACH"}""").obj()
        assertNotEquals(first.id, str(second, "id"))
        assertEquals(SessionMeta.State.ENDED, store.get(first.id)!!.state)
        assertEquals(1, store.list().count { it.state == SessionMeta.State.ACTIVE })
    }

    @Test fun endSessionAndUnknownSession() = server {
        val s = store.newAttachSession()
        val r = client.postJson("/api/sessions/${s.id}/end")
        assertEquals("ENDED", str(r.obj(), "state"))
        assertEquals(HttpStatusCode.NotFound, client.get("/api/sessions/nope").status)
        assertEquals(HttpStatusCode.NotFound, client.postJson("/api/sessions/nope/end").status)
        assertEquals(HttpStatusCode.NotFound, client.postJson("/api/sessions/nope/dump").status)
    }

    @Test fun traversalLookingSessionIdIs400Or404() = server {
        val status = client.get("/api/sessions/..%2F..").status
        assertTrue(status == HttpStatusCode.BadRequest || status == HttpStatusCode.NotFound, "was $status")
    }

    // ---------------------------------------------------------------- timeline

    private val summary = " App Summary\n   Java Heap:  1000\n Native Heap:  2000\n Code: 300\n Stack: 40\n Graphics: 500\n Private Other: 60\n System: 70\n\n TOTAL PSS: 3970\n\n Objects\n"

    private fun SessionStore.addSnap(sid: String, id: String, milestone: String?, atMs: Long) =
        addSnapshot(sid, SnapshotMeta(id, "label $id", milestone, "dev.app", atMs, "$id.hprof", 1, SnapshotMeta.Status.OK))

    @Test fun timelineServesSamplesSinceAndAllMarkers() = server {
        val s = store.newAttachSession()
        val file = store.dir(s.id).resolve("meminfo.jsonl")
        java.nio.file.Files.writeString(file, (1..3).joinToString("") { "{\"tMs\":${it * 1000},\"javaHeapKb\":$it,\"nativeHeapKb\":0,\"graphicsKb\":0,\"codeKb\":0,\"stackKb\":0,\"otherKb\":0,\"totalPssKb\":$it,\"paused\":false}\n" })
        store.addSnap(s.id, "s1", "application_oncreate", s.createdAtMs + 1500)
        store.addSnap(s.id, "s2", null, s.createdAtMs + 2500)
        val all = client.get("/api/sessions/${s.id}/timeline").obj()
        assertEquals(3, all["samples"]!!.jsonArray.size)
        val markers = all["markers"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("milestone", "dump"), markers.map { str(it, "kind") })
        assertEquals(listOf("s1", "s2"), markers.map { str(it, "snapshotId") })
        assertEquals(1500, markers[0]["tMs"]!!.jsonPrimitive.content.toInt())
        val since = client.get("/api/sessions/${s.id}/timeline?sinceMs=2000").obj()
        assertEquals(listOf(3000), since["samples"]!!.jsonArray.map { it.jsonObject["tMs"]!!.jsonPrimitive.int })
        assertEquals(2, since["markers"]!!.jsonArray.size)
    }

    @Test fun timelineOfASessionWithoutSamplesIsEmptyNotAnError() = server {
        val s = store.newAttachSession()
        val t = client.get("/api/sessions/${s.id}/timeline").obj()
        assertEquals(0, t["samples"]!!.jsonArray.size)
        assertEquals(0, t["markers"]!!.jsonArray.size)
    }

    @Test fun timelineErrors() = server {
        assertEquals(HttpStatusCode.NotFound, client.get("/api/sessions/nope/timeline").status)
        val s = store.newAttachSession()
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/sessions/${s.id}/timeline?sinceMs=abc").status)
    }

    @Test fun attachSessionStartsPollingMemoryAndEndingItStops() {
        memoryPolling = true
        runner.meminfo = summary
        server {
            val s = client.postJson("/api/sessions", """{"serial":"emulator-5554","packageName":"dev.app","mode":"ATTACH"}""").obj()
            val id = str(s, "id")
            waitUntil { client.get("/api/sessions/$id/timeline").obj()["samples"]!!.jsonArray.isNotEmpty() }
            assertEquals(3970, client.get("/api/sessions/$id/timeline").obj()["samples"]!!.jsonArray[0].jsonObject["totalPssKb"]!!.jsonPrimitive.int)
            client.postJson("/api/sessions/$id/end")
            Thread.sleep(100)
            val n = client.get("/api/sessions/$id/timeline").obj()["samples"]!!.jsonArray.size
            Thread.sleep(1300)
            assertEquals(n, client.get("/api/sessions/$id/timeline").obj()["samples"]!!.jsonArray.size)
        }
    }

    @Test fun theStartupListenerStartsPollingOnPidAndPausesWhileHeld() {
        memoryPolling = true
        runner.meminfo = summary
        runner.processes = listOf("100 dev.app")
        server {
            val s = store.newAttachSession()
            val l = deps.pollerListener(s.id, null)
            assertTrue(client.get("/api/sessions/${s.id}/timeline").obj()["samples"]!!.jsonArray.isEmpty())
            l.onPid(100) // frozen in waitForDebugger: held from the start
            waitUntil { client.get("/api/sessions/${s.id}/timeline").obj()["samples"]!!.jsonArray.any { it.jsonObject["paused"]!!.jsonPrimitive.content == "true" } }
            assertEquals(1, client.get("/api/sessions/${s.id}/timeline").obj()["held"]!!.jsonArray.size)
            l.onHeld(false)
            waitUntil { client.get("/api/sessions/${s.id}/timeline").obj()["samples"]!!.jsonArray.any { it.jsonObject["paused"]!!.jsonPrimitive.content == "false" } }
            val held = client.get("/api/sessions/${s.id}/timeline").obj()["held"]!!.jsonArray.single().jsonObject
            assertTrue(held["endMs"]!!.jsonPrimitive.content != "null")
        }
    }

    // ---------------------------------------------------------------- dump and jobs

    @Test fun dumpRunsAsAJobAndRecordsTheSnapshot() = server {
        val s = store.newAttachSession()
        val r = client.postJson("/api/sessions/${s.id}/dump", """{"label":"before login"}""")
        assertEquals(HttpStatusCode.OK, r.status)
        val job = client.waitJob(str(r.obj(), "jobId"))
        assertEquals("DONE", str(job, "state"), job.toString())
        assertEquals("s1", str(job, "resultRef"))
        val snap = store.get(s.id)!!.snapshots.single()
        assertEquals("before login", snap.label)
        assertEquals("dev.app", snap.processName, "process defaults to the session's package")
        assertEquals(SnapshotMeta.Status.OK, snap.status)
        assertEquals(listOf("Check app", "Dump heap on device", "Pull dump", "Clean up"), job["steps"]!!.jsonArray.map { str(it.jsonObject, "name") })
    }

    @Test fun dumpAcceptsAnExplicitProcessAndAnEmptyBody() = server {
        val s = store.newAttachSession()
        val withProcess = client.postJson("/api/sessions/${s.id}/dump", """{"processName":"dev.app:remote"}""")
        assertEquals("DONE", str(client.waitJob(str(withProcess.obj(), "jobId")), "state"))
        assertEquals("dev.app:remote", store.get(s.id)!!.snapshots.single().processName)
        val empty = client.post("/api/sessions/${s.id}/dump")
        assertEquals(HttpStatusCode.OK, empty.status)
        assertEquals("DONE", str(client.waitJob(str(empty.obj(), "jobId")), "state"))
    }

    @Test fun deviceFailureDuringDumpFailsTheJobWithAHint() = server {
        val s = store.newAttachSession()
        runner.disconnected = true
        val job = client.waitJob(str(client.postJson("/api/sessions/${s.id}/dump").obj(), "jobId"))
        assertEquals("FAILED", str(job, "state"))
        assertTrue(str(job, "errorHint").contains("disconnected", ignoreCase = true), job.toString())
        assertTrue(store.get(s.id)!!.snapshots.isEmpty())
    }

    @Test fun dumpOnAnEndedSessionIsRefused() = server {
        val s = store.newAttachSession()
        store.update(s.id) { it.copy(state = SessionMeta.State.ENDED) }
        assertEquals(HttpStatusCode.Conflict, client.postJson("/api/sessions/${s.id}/dump").status)
    }

    @Test fun unknownJobIs404AndCancelFlagsTheJob() = server {
        assertEquals(HttpStatusCode.NotFound, client.get("/api/jobs/nope").status)
        assertEquals(HttpStatusCode.NotFound, client.postJson("/api/jobs/nope/cancel").status)
        val job = deps.jobs.create("x", listOf("a"))
        val r = client.postJson("/api/jobs/${job.id}/cancel")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(job.id, str(r.obj(), "id"))
        assertTrue(job.cancel.isCancelled)
    }

    @Test fun jobRegistryKeepsOnlyTheLastFinishedJobs() {
        val reg = JobRegistry(workers = 1, keepFinished = 3)
        try {
            val ids = (1..6).map { reg.create("j$it", emptyList()).also { j -> j.complete(null) }.id }
            assertNull(reg.get(ids[0]))
            assertNull(reg.get(ids[1]))
            assertTrue(ids.drop(2).all { reg.get(it) != null })
            val running = reg.create("running", emptyList())
            repeat(10) { reg.create("f$it", emptyList()).complete(null) }
            assertEquals(running, reg.get(running.id), "unfinished jobs are never dropped")
        } finally {
            reg.close()
        }
    }

    // ---------------------------------------------------------------- snapshots

    @Test fun queriesBeforeOpenAre409NotAnalysed() = server {
        val s = store.newAttachSession()
        val snap = store.addFakeSnapshot(s.id)
        for (path in listOf("summary", "tree?ref=root", "object?ref=obj:1", "reveal?ref=obj:1", "classes", "instances?class=a.B", "indexInParent?parent=root&child=obj:1")) {
            val r = client.get("/api/snapshots/${s.id}/$snap/$path")
            assertEquals(HttpStatusCode.Conflict, r.status, path)
            assertEquals("not analysed", str(r.obj(), "error"), path)
        }
    }

    @Test fun unknownSnapshotIs404() = server {
        val s = store.newAttachSession()
        assertEquals(HttpStatusCode.NotFound, client.get("/api/snapshots/${s.id}/s9/summary").status)
        assertEquals(HttpStatusCode.NotFound, client.postJson("/api/snapshots/${s.id}/s9/open").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/snapshots/nope/s1/summary").status)
    }

    @Test fun openRunsAnalysisJobWithThreeStepsAndUnlocksQueries() = server {
        val s = store.newAttachSession().let { store.update(it.id) { m -> m.copy(appPackagePrefixes = listOf("dev.app", "com.lib")) } }
        val snap = store.addFakeSnapshot(s.id)
        val job = client.openSnapshot(s.id, snap)
        assertEquals("DONE", str(job, "state"), job.toString())
        val steps = job["steps"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Parse", "Index", "Dominators"), steps.map { str(it, "name") })
        assertTrue(steps.all { str(it, "state") == "DONE" }, steps.toString())
        assertEquals(listOf("dev.app", "com.lib"), opener.seenPrefixes, "prefixes come from the session")

        val summary = client.get("/api/snapshots/${s.id}/$snap/summary").obj()
        assertEquals(1000, summary["reachableBytes"]!!.jsonPrimitive.int)
        assertEquals(42, summary["objectCount"]!!.jsonPrimitive.int)

        val tree = client.get("/api/snapshots/${s.id}/$snap/tree?ref=root&offset=5&limit=10").obj()
        assertEquals("obj:1", str(tree["rows"]!!.jsonArray[0].jsonObject, "ref"))
        assertTrue(Triple("root", 5, 10) in opener.opened.single().childrenCalls)

        client.get("/api/snapshots/${s.id}/$snap/tree?ref=root")
        assertTrue(Triple("root", 0, 25) in opener.opened.single().childrenCalls, "default offset 0, limit 25")

        val obj = client.get("/api/snapshots/${s.id}/$snap/object?ref=obj:7").obj()
        assertEquals("obj:7", str(obj, "ref"))
        assertEquals("java.lang.String", client.get("/api/snapshots/${s.id}/$snap/classes").array()[0].jsonObject["className"]!!.jsonPrimitive.content)
        val inst = client.get("/api/snapshots/${s.id}/$snap/instances?class=java.lang.String&offset=0&limit=5").obj()
        assertEquals(1, inst["total"]!!.jsonPrimitive.int)
        assertEquals(listOf("root", "cat:static", "obj:3"), client.get("/api/snapshots/${s.id}/$snap/reveal?ref=obj:3").array().map { it.jsonPrimitive.content })
        assertEquals(4, client.get("/api/snapshots/${s.id}/$snap/indexInParent?parent=root&child=obj:3").obj()["index"]!!.jsonPrimitive.int)
    }

    @Test fun openingAnAlreadyOpenSnapshotCompletesImmediatelyWithoutReopening() = server {
        val s = store.newAttachSession()
        val snap = store.addFakeSnapshot(s.id)
        client.openSnapshot(s.id, snap)
        val again = client.openSnapshot(s.id, snap)
        assertEquals("DONE", str(again, "state"))
        assertTrue(again["steps"]!!.jsonArray.all { str(it.jsonObject, "state") == "DONE" })
        assertEquals(1, opener.calls.get())
    }

    @Test fun concurrentOpensOfTheSameSnapshotIndexOnce() = server {
        val s = store.newAttachSession()
        val snap = store.addFakeSnapshot(s.id)
        val gate = CountDownLatch(1)
        opener.gate = gate
        val a = str(client.postJson("/api/snapshots/${s.id}/$snap/open").obj(), "jobId")
        val b = str(client.postJson("/api/snapshots/${s.id}/$snap/open").obj(), "jobId")
        assertEquals(a, b, "the second request joins the running analysis job")
        gate.countDown()
        assertEquals("DONE", str(client.waitJob(a), "state"))
        assertEquals(1, opener.calls.get())
    }

    @Test fun lruKeepsTwoSnapshotsAndClosesTheEvictedOne() = server {
        val s = store.newAttachSession()
        val ids = (1..3).map { store.addFakeSnapshot(s.id) }
        ids.forEach { assertEquals("DONE", str(client.openSnapshot(s.id, it), "state")) }
        assertTrue(opener.opened[0].closed)
        assertFalse(opener.opened[1].closed)
        assertFalse(opener.opened[2].closed)
        val evicted = client.get("/api/snapshots/${s.id}/${ids[0]}/summary")
        assertEquals(HttpStatusCode.Conflict, evicted.status)
        assertEquals("not analysed", str(evicted.obj(), "error"))
        assertEquals(HttpStatusCode.OK, client.get("/api/snapshots/${s.id}/${ids[1]}/summary").status)
        // using [1] made [2] the least recently used: re-opening [0] evicts it
        assertEquals("DONE", str(client.openSnapshot(s.id, ids[0]), "state"))
        assertTrue(opener.opened[2].closed)
        assertEquals(HttpStatusCode.Conflict, client.get("/api/snapshots/${s.id}/${ids[2]}/summary").status)
    }

    @Test fun diffComparesTwoOpenSnapshotsAndCachesTheResult() = server {
        val s = store.newAttachSession()
        val (a, b) = (1..2).map { store.addFakeSnapshot(s.id) }
        client.openSnapshot(s.id, a)
        client.openSnapshot(s.id, b)
        val r = client.get("/api/snapshots/${s.id}/$b/diff?base=$a")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val body = r.obj()
        assertEquals("$b.hprof vs $a.hprof", str(body["classes"]!!.jsonArray[0].jsonObject, "className"))
        assertEquals("Static fields", str(body["roots"]!!.jsonArray[0].jsonObject, "label"))
        assertEquals(1600, body["appHeapAfter"]!!.jsonPrimitive.int)
        assertEquals(1000, body["reachableBefore"]!!.jsonPrimitive.int)
        assertEquals(listOf("$a.hprof"), opener.opened[1].diffCalls, "the diff runs on the `after` view with the base as before")
        client.get("/api/snapshots/${s.id}/$b/diff?base=$a")
        assertEquals(1, opener.opened[1].diffCalls.size, "the second request is served from the cache")
        // The cached result does not need the snapshots open any more.
        val c = store.addFakeSnapshot(s.id)
        client.openSnapshot(s.id, c)
        client.openSnapshot(s.id, a) // evicts b
        assertEquals(HttpStatusCode.Conflict, client.get("/api/snapshots/${s.id}/$b/summary").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/snapshots/${s.id}/$b/diff?base=$a").status)
    }

    @Test fun diffNamesTheSnapshotThatIsNotAnalysed() = server {
        val s = store.newAttachSession()
        val (a, b) = (1..2).map { store.addFakeSnapshot(s.id) }
        client.openSnapshot(s.id, b)
        val base = client.get("/api/snapshots/${s.id}/$b/diff?base=$a")
        assertEquals(HttpStatusCode.Conflict, base.status)
        assertEquals("not analysed: $a", str(base.obj(), "error"))
        assertEquals("NOT_ANALYSED", str(base.obj(), "kind"))
        client.openSnapshot(s.id, a)
        assertEquals(HttpStatusCode.OK, client.get("/api/snapshots/${s.id}/$b/diff?base=$a").status)
        val c = store.addFakeSnapshot(s.id)
        client.openSnapshot(s.id, c) // evicts the least recently used: b
        val after = client.get("/api/snapshots/${s.id}/$a/diff?base=$b")
        assertEquals(HttpStatusCode.Conflict, after.status)
        assertEquals("not analysed: $b", str(after.obj(), "error"))
    }

    @Test fun diffNeedsAKnownBaseInTheSameSession() = server {
        val s = store.newAttachSession()
        val a = store.addFakeSnapshot(s.id)
        client.openSnapshot(s.id, a)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/snapshots/${s.id}/$a/diff").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/snapshots/${s.id}/$a/diff?base=s9").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/snapshots/${s.id}/s9/diff?base=$a").status)
    }

    @Test fun diffOfASnapshotWithItselfIsAllowed() = server {
        val s = store.newAttachSession()
        val a = store.addFakeSnapshot(s.id)
        client.openSnapshot(s.id, a)
        assertEquals(HttpStatusCode.OK, client.get("/api/snapshots/${s.id}/$a/diff?base=$a").status)
    }

    @Test fun unreadableHprofFailsTheJobPersistsStatusAndGives422() = server {
        val s = store.newAttachSession()
        val snap = store.addFakeSnapshot(s.id)
        val file = store.snapshotFile(s.id, snap).toFile()
        opener.failWith = HprofUnreadableException(file, "Heap dump ${file.path} is truncated or corrupt", null)
        val job = client.openSnapshot(s.id, snap)
        assertEquals("FAILED", str(job, "state"))
        assertTrue(str(job, "errorMessage").contains(file.name))
        assertTrue(str(job, "errorHint").contains("capture", ignoreCase = true))
        assertTrue((job["steps"]!!.jsonArray).any { str(it.jsonObject, "state") == "FAILED" }, "the step running when the failure came is FAILED: $job")
        assertEquals(SnapshotMeta.Status.UNREADABLE, store.get(s.id)!!.snapshots.single().status)
        val summary = client.get("/api/snapshots/${s.id}/$snap/summary")
        assertEquals(HttpStatusCode.UnprocessableEntity, summary.status)
        assertEquals("UNREADABLE", str(summary.obj(), "kind"))
        assertTrue(str(summary.obj(), "hint").contains("capture", ignoreCase = true))
        assertEquals(HttpStatusCode.UnprocessableEntity, client.postJson("/api/snapshots/${s.id}/$snap/open").status)
    }

    @Test fun emptyHprofIsRejectedAt422AndMarkedUnreadable() = server {
        val s = store.newAttachSession()
        val snap = store.addFakeSnapshot(s.id, bytes = 0)
        val r = client.postJson("/api/snapshots/${s.id}/$snap/open")
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
        assertEquals("UNREADABLE", str(r.obj(), "kind"))
        assertEquals(0, opener.calls.get())
        assertEquals(SnapshotMeta.Status.UNREADABLE, store.get(s.id)!!.snapshots.single().status)
    }

    @Test fun unreadableErrorSurfacingFromAQueryMarksTheSnapshot() = server {
        val s = store.newAttachSession()
        val snap = store.addFakeSnapshot(s.id)
        client.openSnapshot(s.id, snap)
        opener.opened.single().onChildren = { throw HprofUnreadableException(File("x.hprof"), "x.hprof is corrupt", null) }
        val r = client.get("/api/snapshots/${s.id}/$snap/tree?ref=root")
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
        assertEquals(SnapshotMeta.Status.UNREADABLE, store.get(s.id)!!.snapshots.single().status)
    }

    @Test fun badRefIs400WithHint() = server {
        val s = store.newAttachSession()
        val snap = store.addFakeSnapshot(s.id)
        client.openSnapshot(s.id, snap)
        val r = client.get("/api/snapshots/${s.id}/$snap/object?ref=cat:static")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val e = r.obj()
        assertEquals("BAD_REQUEST", str(e, "kind"))
        assertTrue(str(e, "error").contains("does not name a single object"))
        assertTrue(str(e, "hint").isNotBlank())
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/snapshots/${s.id}/$snap/tree").status, "ref is required")
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/snapshots/${s.id}/$snap/tree?ref=root&offset=abc").status)
    }

    @Test fun revealDeeperThan2000Is422() = server {
        val s = store.newAttachSession()
        val snap = store.addFakeSnapshot(s.id)
        client.openSnapshot(s.id, snap)
        val view = opener.opened.single()
        view.revealPath = List(2000) { "obj:$it" }
        assertEquals(HttpStatusCode.OK, client.get("/api/snapshots/${s.id}/$snap/reveal?ref=obj:1").status)
        view.revealPath = List(2001) { "obj:$it" }
        val r = client.get("/api/snapshots/${s.id}/$snap/reveal?ref=obj:1")
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
        assertEquals("too deep to reveal", str(r.obj(), "error"))
        assertEquals("use the path shown in the details panel", str(r.obj(), "hint"))
    }

    @Test fun viewReportingTooDeepIs422() = server {
        val s = store.newAttachSession()
        val snap = store.addFakeSnapshot(s.id)
        http.openSnapshot(s.id, snap)
        opener.opened.single().revealFails = TooDeepToReveal()
        val r = http.get("/api/snapshots/${s.id}/$snap/reveal?ref=obj:1")
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
        assertEquals("too deep to reveal", str(r.obj(), "error"))
    }

    @Test fun queryRacingWithEvictionGets409NotA500() = server {
        val s = store.newAttachSession()
        val snap = store.addFakeSnapshot(s.id)
        http.openSnapshot(s.id, snap)
        opener.opened.single().onChildren = { throw IllegalStateException("snapshot closed") }
        val r = http.get("/api/snapshots/${s.id}/$snap/tree?ref=root")
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertEquals("not analysed", str(r.obj(), "error"))
    }

    @Test fun finishedOpenWarmsTheSnapshotInTheBackground() = server {
        val s = store.newAttachSession()
        val snap = store.addFakeSnapshot(s.id)
        http.openSnapshot(s.id, snap)
        val view = opener.opened.single()
        val deadline = System.currentTimeMillis() + 5000
        while (view.detailsCalls.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(listOf("obj:1"), view.detailsCalls, "details of the first root row")
        assertTrue(Triple("root", 0, 1) in view.childrenCalls)
    }

    @Test fun cancelledOpenNeverOverlapsAReopenOfTheSameSnapshot() = server {
        val s = store.newAttachSession()
        val snap = store.addFakeSnapshot(s.id)
        val gate = CountDownLatch(1)
        opener.gate = gate
        val first = str(http.postJson("/api/snapshots/${s.id}/$snap/open").obj(), "jobId")
        waitUntil { opener.calls.get() == 1 }
        http.postJson("/api/jobs/$first/cancel")
        assertEquals("CANCELLED", str(http.waitJob(first), "state"))
        val second = str(http.postJson("/api/snapshots/${s.id}/$snap/open").obj(), "jobId")
        assertNotEquals(first, second)
        Thread.sleep(400)
        assertEquals(1, opener.calls.get(), "the re-open waits for the abandoned analysis to exit")
        gate.countDown()
        assertEquals("DONE", str(http.waitJob(second), "state"))
        assertEquals(2, opener.calls.get())
        assertEquals(1, opener.maxRunning.get(), "never two analyses of one snapshot at once")
        assertTrue(opener.opened.size == 2)
        assertEquals(HttpStatusCode.OK, http.get("/api/snapshots/${s.id}/$snap/summary").status)
    }

    private inline fun waitUntil(timeoutMs: Long = 5000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(cond(), "condition not met in ${timeoutMs}ms")
    }

    @Test fun internalErrorsAreLoggedWithStackTrace() = server {
        val s = store.newAttachSession()
        val snap = store.addFakeSnapshot(s.id)
        http.openSnapshot(s.id, snap)
        opener.opened.single().onChildren = { throw RuntimeException("boom in heap query") }
        val r = http.get("/api/snapshots/${s.id}/$snap/tree?ref=root")
        assertEquals(HttpStatusCode.InternalServerError, r.status)
        assertEquals("INTERNAL", str(r.obj(), "kind"))
        val line = logs.single { it.contains("boom in heap query") }
        assertTrue(line.contains("RoutesTest") || line.contains("at "), "stack trace is logged: $line")
        assertTrue(line.contains("/tree"), "the request is named: $line")
    }

    @Test fun failedJobsAreLoggedWithKindMessageAndHint() = server {
        val s = store.newAttachSession()
        runner.disconnected = true
        http.waitJob(str(http.postJson("/api/sessions/${s.id}/dump").obj(), "jobId"))
        waitUntil { logs.any { it.contains("failed") } }
        val line = logs.first { it.contains("failed") }
        assertTrue(line.contains("kind=") && line.contains("message=") && line.contains("hint="), line)
    }

    @Test fun crossSiteOriginIsRefusedOnStateChangingRequests() = server {
        val s = store.newAttachSession()
        for (path in listOf("/api/sessions/${s.id}/end", "/api/jobs/j1/cancel", "/api/snapshots/${s.id}/s1/open", "/api/sessions/${s.id}/dump")) {
            val r = http.post(path) { header(HttpHeaders.Origin, "http://evil.example.com") }
            assertEquals(HttpStatusCode.Forbidden, r.status, path)
            assertEquals("FORBIDDEN_ORIGIN", str(r.obj(), "kind"), path)
        }
        assertEquals(SessionMeta.State.ACTIVE, store.get(s.id)!!.state, "the end request never ran")
        assertEquals(HttpStatusCode.Forbidden, http.post("/api/sessions/${s.id}/end") { header(HttpHeaders.Origin, "http://localhost.evil.com") }.status)
        assertEquals(HttpStatusCode.Forbidden, http.post("/api/sessions/${s.id}/end") { header(HttpHeaders.Origin, "null") }.status)
        val own = http.post("/api/sessions/${s.id}/end") { header(HttpHeaders.Origin, "http://localhost") }
        assertEquals(HttpStatusCode.OK, own.status, "the page's own origin is accepted")
        assertEquals(HttpStatusCode.OK, http.get("/api/devices") { header(HttpHeaders.Origin, "http://evil.example.com") }.status, "reads are not origin-checked (the Host guard and missing CORS cover them)")
    }

    @Test fun postBodiesMustBeJson() = server {
        val create = http.post("/api/sessions") {
            contentType(ContentType.Text.Plain)
            setBody("""{"serial":"emulator-5554","packageName":"dev.app","mode":"ATTACH"}""")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, create.status)
        assertTrue(store.list().isEmpty())
        val s = store.newAttachSession()
        val dump = http.post("/api/sessions/${s.id}/dump") { contentType(ContentType.Text.Plain); setBody("""{"label":"x"}""") }
        assertEquals(HttpStatusCode.UnsupportedMediaType, dump.status)
        assertEquals(0, runner.count("shell", "am"), "nothing ran")
    }

    @Test fun portsThatCannotMatchTheHostGuardAreRejected() {
        assertTrue(checkPort(80)!!.contains("80"))
        assertTrue(checkPort(443) != null)
        assertTrue(checkPort(0) != null)
        assertTrue(checkPort(70000) != null)
        assertNull(checkPort(7070))
    }

    /** Opens, cancels while the opener is blocked on [gate], and returns once the abandoned thread is the only one left. */
    private suspend fun abandonedAnalysis(sid: String, snap: String, gate: CountDownLatch) {
        opener.gate = gate
        val first = str(http.postJson("/api/snapshots/$sid/$snap/open").obj(), "jobId")
        waitUntil { opener.calls.get() == 1 }
        http.postJson("/api/jobs/$first/cancel")
        assertEquals("CANCELLED", str(http.waitJob(first), "state"))
    }

    @Test fun cancellingTheWaitForAnAbandonedAnalysisEndsTheJobAndAllowsALaterOpen() = server {
        val s = store.newAttachSession()
        val snap = store.addFakeSnapshot(s.id)
        val gate = CountDownLatch(1)
        abandonedAnalysis(s.id, snap, gate)
        val waiting = str(http.postJson("/api/snapshots/${s.id}/$snap/open").obj(), "jobId")
        waitUntil { http.get("/api/jobs/$waiting").obj()["steps"]!!.jsonArray[0].jsonObject["detail"]?.jsonPrimitive?.content?.contains("waiting for the previous analysis") == true }
        val view = http.get("/api/jobs/$waiting").obj()
        assertEquals("RUNNING", str(view, "state"))
        assertEquals("RUNNING", str(view["steps"]!!.jsonArray[0].jsonObject, "state"))
        http.postJson("/api/jobs/$waiting/cancel")
        assertEquals("CANCELLED", str(http.waitJob(waiting), "state"))
        gate.countDown()
        waitUntil { opener.running.get() == 0 }
        val third = http.openSnapshot(s.id, snap)
        assertEquals("DONE", str(third, "state"), third.toString())
        assertEquals(1, opener.maxRunning.get())
    }

    @Test fun waitingTooLongForAnAbandonedAnalysisFailsWithARestartHint() {
        waitTimeoutMs = 300
        server {
        val s = store.newAttachSession()
        val snap = store.addFakeSnapshot(s.id)
        val gate = CountDownLatch(1)
        abandonedAnalysis(s.id, snap, gate)
        val job = http.openSnapshot(s.id, snap)
        gate.countDown()
        assertEquals("FAILED", str(job, "state"), job.toString())
        assertTrue(str(job, "errorHint").contains("Restart MemScope"), job.toString())
        assertEquals(1, opener.calls.get(), "the opener was not started a second time")
        }
    }

    // ---------------------------------------------------------------- web surface

    @Test fun servesTheUiWithNoStore() = server {
        val r = client.get("/")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("no-store", r.headers[HttpHeaders.CacheControl])
        val body = r.bodyAsText()
        assertTrue(body.contains("MemScope"))
    }

    @Test fun foreignHostHeaderIsRejected() = server {
        val r = client.get("/api/devices") { header(HttpHeaders.Host, "evil.example.com") }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals(0, runner.calls.size, "rejected before any handler ran")
        assertEquals(HttpStatusCode.Forbidden, client.get("/") { header(HttpHeaders.Host, "attacker.test:7070") }.status)
    }

    @Test fun noCorsHeadersEvenForCrossOriginRequests() = server {
        val r = client.get("/api/devices") { header(HttpHeaders.Origin, "http://evil.example.com") }
        assertNull(r.headers[HttpHeaders.AccessControlAllowOrigin])
        val preflight = client.post("/api/sessions") { header(HttpHeaders.Origin, "http://evil.example.com") }
        assertNull(preflight.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test fun serverBindsLoopbackOnly() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        store = SessionStore(home)
        deps = ServerDeps(store, { runner.adb }, opener)
        val engine = startServer(deps, port, wait = false)
        try {
            val http = java.net.http.HttpClient.newHttpClient()
            val ok = http.send(
                java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:$port/")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, ok.statusCode(), "reachable on 127.0.0.1 with Host 127.0.0.1:$port")
            val other = java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            if (other != null) {
                val refused = runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress(other, port), 2000) } }
                assertTrue(refused.isFailure, "must not accept connections on ${other.hostAddress}")
            }
        } finally {
            engine.stop(0, 1000)
            deps.close()
        }
    }
}
