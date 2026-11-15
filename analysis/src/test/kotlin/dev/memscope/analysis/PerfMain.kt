package dev.memscope.analysis

import java.io.File
import java.lang.management.ManagementFactory

/**
 * Performance check for the heap indexer: `./gradlew :analysis:perfIndex -Phprof=<file>` (runs with -Xmx6g).
 * Builds the index (and its cache next to the dump), then loads the cache, printing per-step times
 * and the peak used heap (sampled every 20 ms).
 */
fun main(args: Array<String>) {
    val hprof = File(args.single())
    IndexCache.cacheFileFor(hprof).delete()
    val sampler = PeakSampler().also { it.start() }

    val marks = LinkedHashMap<String, Long>()
    val t0 = System.nanoTime()
    val index = IndexCache.loadOrBuild(hprof) { step, _ -> marks.putIfAbsent(step, System.nanoTime()) }
    val t1 = System.nanoTime()
    val buildPeak = sampler.peak
    val buildSteps = marks.entries.map { it.key to it.value }

    val t2 = System.nanoTime()
    IndexCache.loadOrBuild(hprof) { _, _ -> }
    val t3 = System.nanoTime()
    sampler.running = false

    val s = { a: Long, b: Long -> "%.2f s".format((b - a) / 1e9) }
    println("dump: ${hprof.name} ${"%.1f".format(hprof.length() / 1048576.0)} MB")
    println("objects: ${index.objectCount}, edges: ${index.graph.edgeTargets.size}, roots: ${index.graph.roots.size}, classes: ${index.classNames.size}")
    println("build + cache write: ${s(t0, t1)}")
    for ((k, step) in buildSteps.withIndex()) {
        val end = if (k + 1 < buildSteps.size) buildSteps[k + 1].second else t1
        println("  ${step.first}: ${s(step.second, end)}")
    }
    println("cache load: ${s(t2, t3)}")
    println("peak used heap during build: ${buildPeak / 1048576} MB (max heap ${Runtime.getRuntime().maxMemory() / 1048576} MB)")
    println("cache file: ${IndexCache.cacheFileFor(hprof).length() / 1048576} MB")
}

private class PeakSampler : Thread("peak-sampler") {
    @Volatile var running = true
    @Volatile var peak = 0L
    init { isDaemon = true }
    override fun run() {
        val mem = ManagementFactory.getMemoryMXBean()
        while (running) {
            val used = mem.heapMemoryUsage.used
            if (used > peak) peak = used
            sleep(20)
        }
    }
}
