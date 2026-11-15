package dev.memscope.analysis.findings.rules

import dev.memscope.analysis.Snapshot
import dev.memscope.analysis.findings.Finding
import dev.memscope.analysis.findings.Rule
import dev.memscope.analysis.findings.RuleContext
import dev.memscope.analysis.findings.Severity
import dev.memscope.analysis.findings.Thresholds
import dev.memscope.analysis.findings.formatBytes
import shark.HeapObject.HeapInstance
import java.util.Locale

internal class BitmapInfo(val d: Int, val width: Int, val height: Int, val bytesPerPixel: Int, val bytes: Long) {
    val pixels: Long get() = width.toLong() * height.toLong()
    val config: String get() = when (bytesPerPixel) { 1 -> "ALPHA_8"; 2 -> "RGB_565"; 4 -> "ARGB_8888"; 8 -> "RGBA_F16"; else -> "${bytesPerPixel}bpp" }
}

private const val BITMAP = "android.graphics.Bitmap"

/**
 * Reachable app-heap Bitmaps with their dimensions. Pixel memory is native, so the config is inferred
 * from the native size the NativeAllocationRegistry reported (bytes / pixels), 4 when there is none.
 */
internal fun readBitmaps(s: Snapshot): List<BitmapInfo> {
    val idx = s.index
    val isBitmap = idx.classTableIndices(BITMAP)
    val out = ArrayList<BitmapInfo>()
    for (d in 0 until idx.objectCount) {
        if (!isBitmap[idx.classOf[d]] || !idx.isReachableApp(d)) continue
        val id = idx.objectIds[d]
        val dims = s.withGraph { g ->
            val inst = g.findObjectByIdOrNull(id) as? HeapInstance ?: return@withGraph null
            val w = inst[BITMAP, "mWidth"]?.value?.asInt ?: return@withGraph null
            val h = inst[BITMAP, "mHeight"]?.value?.asInt ?: return@withGraph null
            w to h
        } ?: continue
        val (w, h) = dims
        if (w <= 0 || h <= 0) continue
        val native = idx.nativeEst[d].toLong()
        val pixels = w.toLong() * h.toLong()
        val bpp = if (native > 0) ((native + pixels / 2) / pixels).toInt().coerceIn(1, 16) else 4
        out += BitmapInfo(d, w, h, bpp, if (native > 0) native else pixels * 4)
    }
    return out
}

internal class OversizedBitmapRule : Rule {
    override val id = "oversized-bitmap"

    override fun evaluate(c: RuleContext): List<Finding> {
        val limit = Thresholds.OVERSIZED_BITMAP_SCREEN_FACTOR * c.screen.pixels
        return readBitmaps(c.snapshot).filter { it.pixels > limit }.sortedByDescending { it.bytes }.take(10).map { b ->
            val ratio = b.pixels.toDouble() / c.screen.pixels
            val fitted = b.bytes * c.screen.pixels / b.pixels
            Finding(
                ruleId = id, severity = Severity.MEDIUM,
                title = "Bitmap ${b.width}x${b.height} is ${String.format(Locale.US, "%.1f", ratio)}x the screen (${formatBytes(b.bytes)})",
                evidence = "${b.width}x${b.height} ${b.config} = ${formatBytes(b.bytes)} of pixel memory; the screen is ${c.screen.width}x${c.screen.height} " +
                    "(${c.screen.pixels} px), this bitmap has ${b.pixels} px. Limit: more than ${Thresholds.OVERSIZED_BITMAP_SCREEN_FACTOR}x the screen.",
                suggestion = "Decode it at the size it is displayed: BitmapFactory.Options.inSampleSize or ImageDecoder.setTargetSize, " +
                    "which would cut ${formatBytes(b.bytes)} to about ${formatBytes(fitted)} at screen size; for large images prefer a tiled region decoder.",
                bytes = b.bytes, refs = listOf("obj:${b.d}"),
            )
        }
    }
}

internal class DuplicateBitmapsRule : Rule {
    override val id = "duplicate-bitmaps"

    override fun evaluate(c: RuleContext): List<Finding> =
        readBitmaps(c.snapshot).groupBy { Triple(it.width, it.height, it.bytesPerPixel) }
            .filter { (_, g) -> g.size >= Thresholds.DUPLICATE_BITMAP_MIN_COUNT }
            .map { (_, g) ->
                val first = g.first()
                val total = g.sumOf { it.bytes }
                val wasted = total - first.bytes
                Finding(
                    ruleId = id, severity = Severity.MEDIUM,
                    title = "Possible duplicate bitmaps: ${g.size} x ${first.width}x${first.height} ${first.config} (${formatBytes(first.bytes)} each)",
                    evidence = "${g.size} Bitmaps have the same size and config, ${formatBytes(total)} in total; if they show the same image " +
                        "${formatBytes(wasted)} is wasted. Pixels are native memory, so MemScope cannot compare their content: this is a guess from the dimensions.",
                    suggestion = "If these are the same image, decode it once and share it through a cache (LruCache keyed by resource or URL, or an image loader that pools bitmaps) instead of decoding it per view.",
                    bytes = wasted, refs = g.take(3).map { "obj:${it.d}" },
                )
            }
}
