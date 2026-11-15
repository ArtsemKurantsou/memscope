package dev.memscope.analysis

import java.io.File

/**
 * Performance check for the snapshot queries: `./gradlew :analysis:perfSnapshot -Phprof=<file>` (-Xmx6g).
 * Opens the snapshot (building the index cache first if missing), then times the first calls of the
 * UI's typical queries and prints the heap used after each lazily built structure.
 */
fun main(args: Array<String>) {
    val hprof = File(args.single())
    if (!IndexCache.cacheFileFor(hprof).isFile) IndexCache.loadOrBuild(hprof) { _, _ -> } // warm the cache
    System.gc()
    val base = usedMb()

    fun <T> timed(what: String, block: () -> T): T {
        val t0 = System.nanoTime()
        val result = block()
        println("%-48s %8.1f ms".format(what, (System.nanoTime() - t0) / 1e6))
        return result
    }

    val snapshot = timed("Snapshot.open (warm cache)") { Snapshot.open(hprof, listOf("dev.memscope.fixture")) }
    System.gc()
    println("  heap after open: ${usedMb() - base} MB above baseline")
    val top = timed("children(\"root\")") { snapshot.children("root") }
    for (r in top.rows) println("  ${r.label}: ${r.retainedBytes / 1048576} MB, ${r.childCount} rows")

    // Largest path, 5 levels below the largest category; the first object level opens the Shark graph.
    var ref = top.rows.maxBy { it.retainedBytes }.ref
    for (level in 1..5) {
        val page = timed("children(level $level)") { snapshot.children(ref) }
        val next = page.rows.firstOrNull() ?: break
        println("  -> ${next.label} [${next.edgeLabel}] ${next.retainedBytes / 1048576} MB (${page.total} rows)")
        ref = next.ref
    }
    println("  Shark graph open (inside the first object-level call): ${snapshot.graphOpenMillis} ms")
    System.gc()
    println("  heap after graph open: ${usedMb() - base} MB above baseline")

    val classes = timed("classes()") { snapshot.classes() }
    println("  ${classes.size} classes; top: ${classes.take(3).joinToString { "${it.className} ${it.retainedBytes / 1048576} MB" }}")
    timed("summary()") { snapshot.summary() }

    val target = snapshot.instances("dev.memscope.fixture.Node", 0, 1).rows.firstOrNull()?.ref ?: ref
    val details = timed("first details() (builds predecessor CSR)") { snapshot.details(target) }
    println("  ${details.className}: path ${details.pathFromRoot.size} steps, ${details.referrerCount} referrers")
    timed("second details()") { snapshot.details(ref) }
    System.gc()
    println("  heap after predecessors: ${usedMb() - base} MB above baseline")

    // Worst case: the object at the end of the longest dominator chain (the inflate list's tail).
    val nodes = snapshot.instances("dev.memscope.fixture.Node", 0, 0).total
    if (nodes > 0) {
        val tail = snapshot.instances("dev.memscope.fixture.Node", nodes - 1, 1).rows.single().ref
        val deep = timed("details() of the deepest Node") { snapshot.details(tail) }
        println("  path ${deep.pathFromRoot.size} steps shown (${deep.pathFromRoot.getOrNull(Snapshot.PATH_END_STEPS)?.className})")
        val path = timed("revealPath() of the deepest Node") { snapshot.revealPath(tail) }
        println("  ${path.size} refs")
    }
    snapshot.close()
}

private fun usedMb(): Long = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1048576 }
