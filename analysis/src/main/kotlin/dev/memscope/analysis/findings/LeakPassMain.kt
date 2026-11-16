package dev.memscope.analysis.findings

import kotlinx.serialization.json.Json
import shark.AndroidObjectInspectors
import shark.AndroidReferenceMatchers
import shark.FilteringLeakingObjectFinder
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.HeapAnalyzer
import shark.LeakTrace
import shark.MetadataExtractor
import shark.ObjectInspector
import shark.ObjectReporter
import shark.OnAnalysisProgressListener
import java.io.File
import kotlin.system.exitProcess

private const val ID_LABEL = "memscope-id="

/** Labels every inspected object with its hprof id, so a trace's leaking object can be mapped back exactly. */
private val idInspector = ObjectInspector { reporter: ObjectReporter -> reporter.labels += ID_LABEL + reporter.heapObject.objectId }

internal fun render(trace: LeakTrace): LeakTraceDto? {
    val leaking = trace.leakingObject
    val id = leaking.labels.firstOrNull { it.startsWith(ID_LABEL) }?.removePrefix(ID_LABEL)?.toLongOrNull() ?: return null
    val suspect = trace.suspectReferenceSubpath.firstOrNull() ?: trace.referencePath.lastOrNull()
    val holder = suspect?.let { "${it.owningClassName.substringAfterLast('.')}.${it.referenceDisplayName}" } ?: "its GC root"
    val lines = ArrayList<String>()
    lines += "GC root: ${trace.gcRootType.description}"
    trace.referencePath.forEachIndexed { i, ref ->
        val next = if (i + 1 < trace.referencePath.size) trace.referencePath[i + 1].originObject.className else leaking.className
        val mark = if (trace.referencePathElementIsSuspect(i)) "   <-- suspect reference" else ""
        lines += "${ref.originObject.className}.${ref.referenceDisplayName} -> $next$mark"
    }
    lines += "${leaking.className}: ${leaking.leakingStatusReason}"
    return LeakTraceDto(id, leaking.className, leaking.leakingStatusReason, lines, holder)
}

/** `LeakPassMain <hprof> <out.json>`: runs in its own JVM (see [LeakPass]); exit 3 = out of memory. */
fun main(args: Array<String>) {
    try {
        val analysis = HeapAnalyzer(OnAnalysisProgressListener { }).analyze(
            heapDumpFile = File(args[0]),
            leakingObjectFinder = FilteringLeakingObjectFinder(AndroidObjectInspectors.appLeakingObjectFilters),
            referenceMatchers = AndroidReferenceMatchers.appDefaults, computeRetainedHeapSize = false,
            objectInspectors = AndroidObjectInspectors.appDefaults + idInspector, metadataExtractor = MetadataExtractor.NO_OP,
        )
        when (analysis) {
            is HeapAnalysisSuccess -> File(args[1]).writeText(
                Json.encodeToString(LeakResultDto.serializer(), LeakResultDto(analysis.applicationLeaks.flatMap { it.leakTraces }.mapNotNull(::render))),
            )
            is HeapAnalysisFailure -> {
                val c = analysis.exception.cause ?: analysis.exception
                System.err.println("${c.javaClass.simpleName}: ${c.message} at ${c.stackTrace.take(3).joinToString(" < ")}")
                exitProcess(1)
            }
        }
    } catch (e: OutOfMemoryError) {
        exitProcess(LeakPass.CHILD_OOM_EXIT)
    }
}
