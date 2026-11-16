package dev.memscope.e2e

import dev.memscope.capture.SessionStore
import dev.memscope.device.Adb
import dev.memscope.device.AdbLocator
import dev.memscope.server.ServerDeps
import dev.memscope.server.startServer
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The whole tool against emulator-5554 and the fixture app, through the real server's HTTP API: a startup recording,
 * analysis, timeline, findings, a dump of the live app, and a clean device afterwards.
 * Run with `./gradlew :e2e:emulatorTest`.
 */
@Tag("emulator")
class EndToEndTest {
    @TempDir lateinit var home: Path
    private val serial = "emulator-5554"
    private val pkg = "dev.memscope.fixture"
    private val adb = Adb(AdbLocator.find())
    private val http = HttpClient.newHttpClient()
    private lateinit var deps: ServerDeps
    private lateinit var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private var port = 0

    @BeforeEach
    fun start() {
        val installed = adb.shell(serial, listOf("pm", "list", "packages", pkg)).stdout.lineSequence().map { it.trim().removePrefix("package:") }
        if (pkg !in installed) throw AssertionError("fixture app not installed: run fixture-app/build-and-install.sh")
        cleanDevice()
        port = ServerSocket(0).use { it.localPort }
        deps = ServerDeps(SessionStore(home), { adb })
        engine = startServer(deps, port, wait = false)
    }

    @AfterEach
    fun stop() {
        runCatching { engine.stop(0, 1000) }
        runCatching { deps.close() }
        cleanDevice()
    }

    private fun cleanDevice() {
        adb.shell(serial, listOf("am", "clear-debug-app"))
        adb.shell(serial, listOf("am", "force-stop", pkg))
        forwardList().lineSequence().map { it.trim().split(Regex("\\s+")) }
            .filter { it.size == 3 && it[0] == serial && it[2].startsWith("jdwp:") }
            .forEach { adb.removeForward(serial, it[1].removePrefix("tcp:").toInt()) }
        adb.shell(serial, listOf("run-as", pkg, "ls", "cache")).stdout.lineSequence().map { it.trim() }.filter { it.startsWith("memscope-") }
            .forEach { adb.shell(serial, listOf("run-as", pkg, "rm", "-f", "cache/$it")) }
    }

    private fun forwardList(): String {
        val p = ProcessBuilder(adb.adbPath.toString(), "forward", "--list").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        check(p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) { "adb forward --list hung" }
        return text
    }

    // -- HTTP helpers --

    private class Reply(val status: Int, val body: JsonElement)

    private fun call(method: String, path: String, body: JsonObject? = null): Reply {
        val b = HttpRequest.newBuilder(URI("http://localhost:$port/api$path")).timeout(Duration.ofSeconds(60))
        if (body != null) b.header("Content-Type", "application/json")
        b.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body.toString()))
        val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return Reply(r.statusCode(), Json.parseToJsonElement(r.body().ifBlank { "null" }))
    }

    private fun get(path: String) = call("GET", path).also { assertEquals(200, it.status, "GET $path: ${it.body}") }.body
    private fun post(path: String, body: JsonObject? = null) = call("POST", path, body)

    private fun JsonElement.str(key: String) = jsonObject[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content

    /** Polls a job until it leaves RUNNING; prints its steps. */
    private fun awaitJob(jobId: String, timeoutSec: Long): JsonElement {
        val deadline = System.nanoTime() + timeoutSec * 1_000_000_000L
        while (true) {
            val job = get("/jobs/$jobId")
            if (job.str("state") != "RUNNING") return job
            check(System.nanoTime() < deadline) { "job $jobId still RUNNING after $timeoutSec s: $job" }
            Thread.sleep(1_000)
        }
    }

    private fun printJob(job: JsonElement) {
        println("job ${job.str("title")}: ${job.str("state")} in ${job.str("elapsedMs")} ms")
        job.jsonObject["steps"]!!.jsonArray.forEach { println("  %-34s %-7s %s".format(it.str("name"), it.str("state"), it.str("detail") ?: "")) }
        job.str("errorMessage")?.let { println("  error: ${it.lineSequence().first()}\n  hint: ${job.str("errorHint")}") }
    }

    private fun openSnapshot(sid: String, snap: String) {
        val ref = post("/snapshots/$sid/$snap/open")
        assertEquals(200, ref.status, ref.body.toString())
        val job = awaitJob(ref.body.str("jobId")!!, 300)
        printJob(job)
        assertEquals("DONE", job.str("state"), job.toString())
    }

    /** The finding rule ids of a snapshot, via its job when the server answers 202. */
    private fun snapshotFindingRules(sid: String, snap: String): Set<String> {
        var r = call("GET", "/snapshots/$sid/$snap/findings")
        if (r.status == 202) {
            val job = awaitJob(r.body.str("jobId")!!, 600)
            assertEquals("DONE", job.str("state"), job.toString())
            r = call("GET", "/snapshots/$sid/$snap/findings")
        }
        assertEquals(200, r.status, r.body.toString())
        return r.body.jsonObject["findings"]!!.jsonArray.map { it.str("ruleId")!! }.toSet()
    }

    private fun startRecording(): Pair<String, JsonElement> {
        val launcher = get("/devices/$serial/apps/$pkg/launcher").str("component") ?: "$pkg/.MainActivity"
        val started = post("/sessions", buildJsonObject {
            put("serial", serial); put("packageName", pkg); put("mode", "STARTUP")
            put("startup", buildJsonObject { put("launch", "-n $launcher --es actions leak_activity,static_cache") })
        })
        assertEquals(200, started.status, started.body.toString())
        val sid = started.body.jsonObject["session"]!!.str("id")!!
        val job = awaitJob(started.body.str("jobId")!!, 300)
        printJob(job)
        return sid to job
    }

    @Test
    fun `startup recording to findings on the fixture app and a clean device`() {
        val devices = get("/devices").jsonArray
        assertTrue(devices.any { it.str("serial") == serial && it.str("state") == "device" }, devices.toString())

        var (sid, job) = startRecording()
        val wedged = job.str("state") == "FAILED" && job.str("errorMessage").orEmpty().let { "stopped answering the debugger" in it || "past its debugger wait" in it }
        if (wedged) {
            // MemScope already retried once by itself; the known wedge race can still hit twice in a row (about 3-7 % per recording)
            println("NOTE: the recording failed with the known debugger wedge even after the built-in retry; recording the whole thing once more.")
            post("/sessions/$sid/end")
            cleanDevice()
            val again = startRecording()
            sid = again.first; job = again.second
        }
        assertEquals("DONE", job.str("state"), job.toString())

        val session = get("/sessions/$sid")
        val snapshots = session.jsonObject["snapshots"]!!.jsonArray
        assertEquals(5, snapshots.size, snapshots.toString())
        assertTrue(snapshots.all { it.str("status") == "OK" }, snapshots.toString())
        val first = snapshots.first { it.str("milestoneId") == "after_app_oncreate" }.str("id")!!

        openSnapshot(sid, first)
        val summary = get("/snapshots/$sid/$first/summary")
        val rows = get("/snapshots/$sid/$first/tree?ref=root&limit=1000").jsonObject["rows"]!!.jsonArray
        val sum = rows.sumOf { it.jsonObject["retainedBytes"]!!.jsonPrimitive.content.toLong() }
        assertEquals(summary.jsonObject["reachableBytes"]!!.jsonPrimitive.content.toLong(), sum, "root rows must add up to the reachable heap")

        val timeline = get("/sessions/$sid/timeline").jsonObject
        val samples = timeline["samples"]!!.jsonArray.size
        val milestones = timeline["markers"]!!.jsonArray.count { it.str("kind") == "milestone" }
        assertTrue(samples >= 5, "samples=$samples")
        assertEquals(5, milestones)

        // the interval rule reads every milestone's summary: analyse the rest, then ask for the session findings
        snapshots.map { it.str("id")!! }.filter { it != first }.forEach { openSnapshot(sid, it) }
        val sessionFindings = get("/sessions/$sid/findings").jsonObject
        assertEquals(JsonArray(emptyList()), sessionFindings["pending"], "all snapshots analysed: $sessionFindings")
        val sessionRules = sessionFindings["findings"]!!.jsonArray.map { it.str("ruleId") }
        println("session findings: $sessionRules")
        assertTrue("startup-interval" in sessionRules, sessionRules.toString())

        // the app still runs: a dump of the live process is the 6th snapshot
        val dump = post("/sessions/$sid/dump")
        assertEquals(200, dump.status, dump.body.toString())
        val dumpJob = awaitJob(dump.body.str("jobId")!!, 300)
        printJob(dumpJob)
        assertEquals("DONE", dumpJob.str("state"), dumpJob.toString())
        val after = get("/sessions/$sid").jsonObject["snapshots"]!!.jsonArray
        assertEquals(6, after.size)
        val sixth = after.last().str("id")!!
        openSnapshot(sid, sixth)
        val rules = snapshotFindingRules(sid, sixth)
        println("findings of the live dump: $rules")
        assertTrue("leaked-component" in rules, rules.toString())

        assertEquals(200, post("/sessions/$sid/end").status)
        val debugApp = adb.shell(serial, listOf("dumpsys", "activity"), 30.seconds).stdout.lineSequence().filter { "mDebugApp" in it }.toList()
        assertTrue(debugApp.isEmpty(), "debug app still set: $debugApp")
        assertFalse(forwardList().lineSequence().any { serial in it && "jdwp:" in it }, "a JDWP forward is left")
    }
}
