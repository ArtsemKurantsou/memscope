package dev.memscope.server

import dev.memscope.analysis.findings.FindingsHprof
import dev.memscope.capture.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/** The findings routes over a real synthetic snapshot (leak pass included). */
class FindingsRouteTest {
    @TempDir lateinit var home: Path

    private fun server(block: suspend ApplicationTestBuilder.(SessionStore, String) -> Unit) {
        val store = SessionStore(home)
        val deps = ServerDeps(store, { FakeAdbRunner().adb }, memoryPolling = false)
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

    private suspend fun HttpClient.waitJob(job: String): String {
        while (true) {
            val j = Json.parseToJsonElement(get("/api/jobs/$job").bodyAsText()).jsonObject
            val state = j["state"]!!.jsonPrimitive.content
            if (state != "RUNNING") return state
            Thread.sleep(20)
        }
    }

    private suspend fun HttpClient.open(sid: String, snap: String) {
        val r = post("/api/snapshots/$sid/$snap/open") { contentType(ContentType.Application.Json); setBody("{}") }
        assertEquals("DONE", waitJob(Json.parseToJsonElement(r.bodyAsText()).jsonObject["jobId"]!!.jsonPrimitive.content))
    }

    private fun SessionStore.snapshot(sid: String): String = addFakeSnapshot(sid).also { FindingsHprof.write(snapshotFile(sid, it).toFile()) }

    @Test fun anUnopenedSnapshotIs409() = server { store, sid ->
        val s = store.snapshot(sid)
        val r = client.get("/api/snapshots/$sid/$s/findings")
        assertEquals(HttpStatusCode.Conflict, r.status, r.bodyAsText())
        assertTrue("NOT_ANALYSED" in r.bodyAsText())
    }

    @Test fun firstRequestStartsAJobThenTheCachedListIsServed() = server { store, sid ->
        val s = store.snapshot(sid)
        client.open(sid, s)
        val first = client.get("/api/snapshots/$sid/$s/findings")
        assertEquals(HttpStatusCode.Accepted, first.status, first.bodyAsText())
        assertEquals("DONE", client.waitJob(Json.parseToJsonElement(first.bodyAsText()).jsonObject["jobId"]!!.jsonPrimitive.content))
        val second = client.get("/api/snapshots/$sid/$s/findings")
        assertEquals(HttpStatusCode.OK, second.status, second.bodyAsText())
        val list = Json.parseToJsonElement(second.bodyAsText()).jsonObject["findings"]!!.jsonArray.map { it.jsonObject }
        val ids = list.map { it["ruleId"]!!.jsonPrimitive.content }.toSet()
        assertTrue(ids.containsAll(setOf("large-static", "duplicate-arrays", "oversized-bitmap", "sparse-collection")), ids.toString())
        assertTrue(list.all { it["suggestion"]!!.jsonPrimitive.content.isNotBlank() && it["evidence"]!!.jsonPrimitive.content.isNotBlank() })
    }

    @Test fun findingsArePersistedAndRerunComputesThemAgain() = server { store, sid ->
        val s = store.snapshot(sid)
        client.open(sid, s)
        suspend fun compute(query: String) {
            val r = client.get("/api/snapshots/$sid/$s/findings$query")
            assertEquals(HttpStatusCode.Accepted, r.status, r.bodyAsText())
            assertEquals("DONE", client.waitJob(Json.parseToJsonElement(r.bodyAsText()).jsonObject["jobId"]!!.jsonPrimitive.content))
        }
        compute("")
        val sidecar = File(store.snapshotFile(sid, s).toString() + ".findings.json")
        assertTrue(sidecar.isFile, "complete findings are stored next to the hprof")
        val cached = client.get("/api/snapshots/$sid/$s/findings")
        assertEquals(HttpStatusCode.OK, cached.status)
        assertTrue("\"incomplete\":false" in cached.bodyAsText())
        sidecar.delete()
        compute("?rerun=1")
        assertTrue(sidecar.isFile)
    }

    @Test fun openingWritesTheSummarySidecarAndSessionFindingsReportPendingSnapshots() = server { store, sid ->
        val a = store.snapshot(sid)
        val b = store.snapshot(sid)
        client.open(sid, a)
        assertTrue(File(store.snapshotFile(sid, a).toString() + ".summary.json").isFile, "sidecar next to the hprof")
        val r = client.get("/api/sessions/$sid/findings")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(listOf(b), body["pending"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(body["findings"]!!.jsonArray.isEmpty(), "one summarised snapshot is not enough for a session rule")
    }
}
