package dev.memscope.server

import dev.memscope.analysis.DiffHprof
import dev.memscope.analysis.ManyClassesHprof
import dev.memscope.analysis.Progress
import dev.memscope.capture.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** The diff route over real (small, synthetic) snapshots, not [FakeView]: the real `Snapshot.asView().diff` path. */
class DiffRouteTest {
    @TempDir lateinit var home: Path

    private fun server(opener: SnapshotOpener = SnapshotOpener.real, block: suspend ApplicationTestBuilder.(SessionStore, String) -> Unit) {
        val store = SessionStore(home)
        val deps = ServerDeps(store, { FakeAdbRunner().adb }, opener, memoryPolling = false)
        val sid = store.newAttachSession().id
        try {
            testApplication {
                application { memscopeModule(deps, setOf("localhost")) }
                block(store, sid)
            }
        } finally {
            deps.close()
        }
    }

    private fun SessionStore.snapshot(sid: String, write: (File) -> Unit): String =
        addFakeSnapshot(sid).also { write(snapshotFile(sid, it).toFile()) }

    private suspend fun HttpClient.open(sid: String, snap: String) {
        val r = post("/api/snapshots/$sid/$snap/open") { contentType(ContentType.Application.Json); setBody("{}") }
        val job = Json.parseToJsonElement(r.bodyAsText()).jsonObject["jobId"]!!.jsonPrimitive.content
        while (true) {
            val j = Json.parseToJsonElement(get("/api/jobs/$job").bodyAsText()).jsonObject
            if (j["state"]!!.jsonPrimitive.content != "RUNNING") { assertEquals("DONE", j["state"]!!.jsonPrimitive.content, j.toString()); return }
            Thread.sleep(20)
        }
    }

    @Test fun realSnapshotsDiffThroughTheRouteWithZeroFieldsOmitted() = server { store, sid ->
        val a = store.snapshot(sid) { DiffHprof.write(it, itemCount = 2, goneCount = 1) }
        val b = store.snapshot(sid) { DiffHprof.write(it, itemCount = 4, lateCount = 2) }
        client.open(sid, a); client.open(sid, b)
        val r = client.get("/api/snapshots/$sid/$b/diff?base=$a")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        val classes = body["classes"]!!.jsonArray.map { it.jsonObject }.associateBy { it["className"]!!.jsonPrimitive.content }
        val late = classes.getValue("test.Late")
        assertFalse("countBefore" in late, "a zero is not sent: $late")
        assertEquals("2", late["countAfter"]!!.jsonPrimitive.content)
        assertEquals("2", classes.getValue("test.Item")["countBefore"]!!.jsonPrimitive.content)
        assertFalse("countAfter" in classes.getValue("test.Gone"))
        assertTrue(body["roots"]!!.jsonArray.any { it.jsonObject["label"]!!.jsonPrimitive.content == "Static fields / test.App" })
        assertFalse(body["roots"]!!.jsonArray.any { it.jsonObject["label"]!!.jsonPrimitive.content == "Static fields / test.Cache" }, "unchanged roots are not sent")
    }

    @Test fun aBaseThatIsNotARealAnalysisIs400WithAClearMessage() {
        val real = SnapshotOpener.real
        val mixed = SnapshotOpener { file, prefixes, progress: Progress -> if (file.name == "s1.hprof") FakeView(file.name) else real.open(file, prefixes, progress) }
        server(mixed) { store, sid ->
            val a = store.snapshot(sid) { DiffHprof.write(it, itemCount = 1) } // s1: opened as a FakeView
            val b = store.snapshot(sid) { DiffHprof.write(it, itemCount = 2) }
            client.open(sid, a); client.open(sid, b)
            val r = client.get("/api/snapshots/$sid/$b/diff?base=$a")
            assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
            assertTrue("cannot be compared" in r.bodyAsText(), r.bodyAsText())
        }
    }

    @Test fun aLargeDiffIsGzippedWhenTheClientAccepts() = server { store, sid ->
        val a = store.snapshot(sid) { ManyClassesHprof.write(it, 2000, 1) }
        val b = store.snapshot(sid) { ManyClassesHprof.write(it, 2000, 2) }
        client.open(sid, a); client.open(sid, b)
        val gz: HttpResponse = client.get("/api/snapshots/$sid/$b/diff?base=$a") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals("gzip", gz.headers[HttpHeaders.ContentEncoding])
        val bytes = gz.body<ByteArray>()
        val text = GZIPInputStream(bytes.inputStream()).readBytes().decodeToString()
        assertEquals(2000, Json.parseToJsonElement(text).jsonObject["classes"]!!.jsonArray.count { it.jsonObject["className"]!!.jsonPrimitive.content.startsWith("gen.") })
        assertTrue(bytes.size * 10 < text.length, "gzip ${bytes.size} vs ${text.length}")
        val plain = client.get("/api/snapshots/$sid/$b/diff?base=$a")
        assertEquals(null, plain.headers[HttpHeaders.ContentEncoding])
    }
}
