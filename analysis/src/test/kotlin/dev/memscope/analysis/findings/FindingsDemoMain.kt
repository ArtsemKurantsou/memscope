package dev.memscope.analysis.findings

import java.io.File

/**
 * Writes a MEMSCOPE_HOME with one ACTIVE session of three synthetic snapshots (all planted problems incl. a
 * leaked Activity) for checking the Findings tab in a browser without a device:
 * `./gradlew :analysis:demoHome -PdemoHome=/some/dir`, then `MEMSCOPE_HOME=/some/dir ./gradlew :server:run --args="--port 7099 --no-open"`.
 */
fun main(args: Array<String>) {
    val dir = File(args[0], "sessions/demo-1").also { it.mkdirs() }
    val now = System.currentTimeMillis()
    val snaps = (1..3).map { i ->
        val f = File(dir, "s$i.hprof").also { FindingsHprof.write(it, withLeak = true) }
        """{"id":"s$i","label":"Demo $i","milestoneId":null,"processName":"test.app","takenAtMs":${now + i * 1000},"file":"s$i.hprof","sizeBytes":${f.length()},"status":"OK"}"""
    }
    File(dir, "session.json").writeText(
        """{"id":"demo-1","serial":"emulator-5554","packageName":"test.app","mode":"ATTACH","createdAtMs":$now,"appPackagePrefixes":["test."],""" +
            """"snapshots":[${snaps.joinToString(",")}],"state":"ACTIVE","screenWidthPx":1080,"screenHeightPx":2400}""",
    )
    println("wrote ${dir.path}")
}
