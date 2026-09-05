package com.majkeylab.scanit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.floor

internal const val MAX_MANUAL_CLEANUP_PIXELS = 2_000_000
private const val ARGB_CHANNEL_VALUES = 256
private const val MANUAL_CLEANUP_BACKGROUND_PADDING = 24
private const val MANUAL_CLEANUP_MASK_STROKE_FRACTION = 0.02f
private const val MIN_MANUAL_CLEANUP_MASK_STROKE = 16f
private const val MAX_MANUAL_CLEANUP_MASK_STROKE = 96f

internal fun renderManualCleanupJpeg(
    source: File,
    destination: File,
    strokes: List<MarkStroke>,
    isCancelled: () -> Boolean,
): JpegDimensions {
    throwIfManualCleanupCancelled(isCancelled)
    validateNormalizedMarkStrokes(strokes)
    require(strokes.all { it.points.size >= 3 }) { "Cleanup lassos must enclose an area" }
    var dimensions: JpegDimensions? = null
    publishImageExportAtomically(destination, isCancelled) { staging ->
        val rendered =
            ScanAppearanceRenderer.renderJpeg(
                source = source,
                destination = staging,
                appearance = ScanAppearance(ScanColorMode.Natural, intensity = 0, shadows = 0),
                transformBitmap = { bitmap ->
                    strokes.forEach { stroke ->
                        applyManualCleanup(bitmap, stroke.points, isCancelled)
                    }
                },
                isCancelled = isCancelled,
            )
        dimensions = JpegDimensions(rendered.width, rendered.height)
    }
    return checkNotNull(dimensions) { "Manual cleanup produced no JPEG" }
}

private fun applyManualCleanup(
    bitmap: Bitmap,
    points: List<MarkPoint>,
    isCancelled: () -> Boolean,
) {
    val maskStrokeWidth =
        (minOf(bitmap.width, bitmap.height) * MANUAL_CLEANUP_MASK_STROKE_FRACTION)
            .coerceIn(MIN_MANUAL_CLEANUP_MASK_STROKE, MAX_MANUAL_CLEANUP_MASK_STROKE)
    val bounds =
        manualCleanupPixelBounds(
            points,
            bitmap.width,
            bitmap.height,
            padding = ceil(maskStrokeWidth / 2f).toInt() + MANUAL_CLEANUP_BACKGROUND_PADDING,
        )
    val maskBitmap = createBitmap(bounds.width, bounds.height, Bitmap.Config.ARGB_8888)
    try {
        val path =
            Path().apply {
                val first = points.first()
                moveTo(first.x * bitmap.width - bounds.left, first.y * bitmap.height - bounds.top)
                points.drop(1).forEach { point ->
                    lineTo(point.x * bitmap.width - bounds.left, point.y * bitmap.height - bounds.top)
                }
                close()
            }
        Canvas(maskBitmap).drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = maskStrokeWidth
            },
        )
        val size = bounds.width * bounds.height
        val maskPixels = IntArray(size)
        maskBitmap.getPixels(maskPixels, 0, bounds.width, 0, 0, bounds.width, bounds.height)
        val maskAlpha = IntArray(size) { maskPixels[it] ushr 24 }
        if (maskAlpha.none { it > 0 }) throw IOException("Manual cleanup selection is empty")
        val pixels = IntArray(size)
        bitmap.getPixels(
            pixels,
            0,
            bounds.width,
            bounds.left,
            bounds.top,
            bounds.width,
            bounds.height,
        )
        val cleaned =
            inpaintMaskedPixels(
                pixels,
                bounds.width,
                bounds.height,
                maskAlpha,
                isCancelled,
            )
        bitmap.setPixels(
            cleaned,
            0,
            bounds.width,
            bounds.left,
            bounds.top,
            bounds.width,
            bounds.height,
        )
    } finally {
        maskBitmap.recycle()
    }
}

internal fun manualCleanupPixelBounds(
    points: List<MarkPoint>,
    width: Int,
    height: Int,
    padding: Int = MANUAL_CLEANUP_BACKGROUND_PADDING,
): PixelBounds {
    require(width > 0 && height > 0 && padding >= 0) { "Cleanup page bounds are invalid" }
    require(points.size >= 3) { "Cleanup lasso must enclose an area" }
    validateNormalizedMarkStrokes(listOf(MarkStroke(points)))
    val bounds =
        PixelBounds(
            left =
                (floor(points.minOf(MarkPoint::x) * width).toInt() - padding)
                    .coerceAtLeast(0),
            top =
                (floor(points.minOf(MarkPoint::y) * height).toInt() - padding)
                    .coerceAtLeast(0),
            right =
                (ceil(points.maxOf(MarkPoint::x) * width).toInt() + padding)
                    .coerceAtMost(width),
            bottom =
                (ceil(points.maxOf(MarkPoint::y) * height).toInt() + padding)
                    .coerceAtMost(height),
        )
    require(bounds.width.toLong() * bounds.height <= MAX_MANUAL_CLEANUP_PIXELS) {
        "Cleanup selection is too large"
    }
    return bounds
}

internal fun inpaintMaskedPixels(
    pixels: IntArray,
    width: Int,
    height: Int,
    maskAlpha: IntArray,
    isCancelled: () -> Boolean = { false },
): IntArray {
    throwIfManualCleanupCancelled(isCancelled)
    val size = width.toLong() * height
    require(width > 0 && height > 0 && size == pixels.size.toLong()) {
        "Cleanup image bounds are invalid"
    }
    require(maskAlpha.size == pixels.size && maskAlpha.all { it in 0..255 }) {
        "Cleanup mask is invalid"
    }
    require(size <= MAX_MANUAL_CLEANUP_PIXELS) { "Cleanup selection is too large" }
    if (maskAlpha.none { it > 0 }) return pixels.copyOf()
    require(maskAlpha.any { it == 0 }) { "Cleanup selection has no surrounding background" }

    val alphaHistogram = IntArray(ARGB_CHANNEL_VALUES)
    val redHistogram = IntArray(ARGB_CHANNEL_VALUES)
    val greenHistogram = IntArray(ARGB_CHANNEL_VALUES)
    val blueHistogram = IntArray(ARGB_CHANNEL_VALUES)
    var boundarySize = 0
    for (index in pixels.indices) {
        if (index and 0xFFF == 0) throwIfManualCleanupCancelled(isCancelled)
        if (
            maskAlpha[index] == 0 &&
                hasMaskedNeighbor(maskAlpha, width, height, index)
        ) {
            val pixel = pixels[index]
            alphaHistogram[pixel ushr 24]++
            redHistogram[pixel ushr 16 and 0xFF]++
            greenHistogram[pixel ushr 8 and 0xFF]++
            blueHistogram[pixel and 0xFF]++
            boundarySize++
        }
    }
    require(boundarySize > 0) { "Cleanup mask cannot reach its background" }

    // ponytail: ring median assumes a paper-like local background; add gradient fitting only if real scans show patches.
    val replacement =
        medianArgb(
            alphaHistogram,
            redHistogram,
            greenHistogram,
            blueHistogram,
            boundarySize,
        )
    val output = pixels.copyOf()
    for (index in output.indices) {
        if (index and 0xFFF == 0) throwIfManualCleanupCancelled(isCancelled)
        if (maskAlpha[index] > 0) {
            output[index] = blendArgb(pixels[index], replacement, maskAlpha[index])
        }
    }
    return output
}

private fun throwIfManualCleanupCancelled(isCancelled: () -> Boolean) {
    if (isCancelled()) throw CancellationException("Manual cleanup cancelled")
}

private fun hasMaskedNeighbor(
    maskAlpha: IntArray,
    width: Int,
    height: Int,
    index: Int,
): Boolean {
    var found = false
    forEachNeighbor(width, height, index) { neighbor ->
        if (maskAlpha[neighbor] > 0) found = true
    }
    return found
}

private fun medianArgb(
    alpha: IntArray,
    red: IntArray,
    green: IntArray,
    blue: IntArray,
    size: Int,
): Int {
    fun median(histogram: IntArray): Int {
        val lowerRank = (size - 1) / 2
        val upperRank = size / 2
        var cumulative = 0
        var lower = -1
        for (value in histogram.indices) {
            cumulative += histogram[value]
            if (lower < 0 && cumulative > lowerRank) lower = value
            if (cumulative > upperRank) return (lower + value) / 2
        }
        error("Cleanup boundary histogram is incomplete")
    }
    return (median(alpha) shl 24) or
        (median(red) shl 16) or
        (median(green) shl 8) or
        median(blue)
}

private inline fun forEachNeighbor(
    width: Int,
    height: Int,
    index: Int,
    action: (Int) -> Unit,
) {
    val x = index % width
    val y = index / width
    for (neighborY in maxOf(0, y - 1)..minOf(height - 1, y + 1)) {
        for (neighborX in maxOf(0, x - 1)..minOf(width - 1, x + 1)) {
            val neighbor = neighborY * width + neighborX
            if (neighbor != index) action(neighbor)
        }
    }
}

private fun blendArgb(source: Int, replacement: Int, alpha: Int): Int {
    val inverse = 255 - alpha
    fun channel(shift: Int): Int =
        (((source ushr shift and 0xFF) * inverse) +
            ((replacement ushr shift and 0xFF) * alpha)) / 255
    return (channel(24) shl 24) or
        (channel(16) shl 16) or
        (channel(8) shl 8) or
        channel(0)
}
