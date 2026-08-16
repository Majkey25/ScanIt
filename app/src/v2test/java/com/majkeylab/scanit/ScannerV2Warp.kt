package com.majkeylab.scanit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException

private const val SCANNER_V2_BITMAP_BYTES_PER_PIXEL = 4L
private const val SCANNER_V2_TILE_EDGE = 1024
private const val MAX_SCANNER_V2_PAGE_BYTES = 64L * 1024 * 1024
private const val MAX_SCANNER_V2_BITMAP_PEAK_BYTES = 64L * 1024 * 1024

internal data class ScannerV2WarpPoint(val x: Double, val y: Double)

internal data class ScannerV2WarpPlan(
    val output: WarpSize,
    val sourceCrop: List<ScannerV2WarpPoint>,
    val destinationCrop: List<ScannerV2WarpPoint>,
    val peakBitmapBytes: Long,
)

internal data class ScannerV2WarpResult(
    val width: Int,
    val height: Int,
    val fingerprint: OutputFingerprint,
)

internal fun scannerV2WarpPlan(
    sourceWidth: Int,
    sourceHeight: Int,
    crop: PageQuad,
    orientation: ImageExifOrientation,
    rotationQuarterTurns: Int,
): ScannerV2WarpPlan {
    require(sourceWidth in 1..MAX_IMAGE_EXPORT_DIMENSION) { "Scanner source width is invalid" }
    require(sourceHeight in 1..MAX_IMAGE_EXPORT_DIMENSION) { "Scanner source height is invalid" }
    require(sourceWidth.toLong() * sourceHeight <= MAX_IMAGE_EXPORT_PIXELS) {
        "Scanner source exceeds the pixel limit"
    }
    require(rotationQuarterTurns in 0..3) { "Scanner rotation is invalid" }
    val oriented = orientedImageExportDimensions(sourceWidth, sourceHeight, orientation)
    val unrotated = deriveWarpSize(oriented.width, oriented.height, crop)
    val output = if (rotationQuarterTurns % 2 == 0) {
        unrotated
    } else {
        WarpSize(unrotated.height, unrotated.width)
    }
    val peak =
        output.width.toLong() * output.height * SCANNER_V2_BITMAP_BYTES_PER_PIXEL +
            SCANNER_V2_TILE_EDGE.toLong() * SCANNER_V2_TILE_EDGE * SCANNER_V2_BITMAP_BYTES_PER_PIXEL
    require(peak <= MAX_SCANNER_V2_BITMAP_PEAK_BYTES) { "Scanner render exceeds the bitmap memory limit" }
    return ScannerV2WarpPlan(
        output = output,
        sourceCrop = scannerV2SourceCropPoints(sourceWidth, sourceHeight, crop, orientation),
        destinationCrop = scannerV2DestinationCropPoints(output, rotationQuarterTurns),
        peakBitmapBytes = peak,
    )
}

internal fun scannerV2SourceCropPoints(
    sourceWidth: Int,
    sourceHeight: Int,
    crop: PageQuad,
    orientation: ImageExifOrientation,
): List<ScannerV2WarpPoint> = listOf(
    crop.topLeft,
    crop.topRight,
    crop.bottomRight,
    crop.bottomLeft,
).map { point ->
    val raw = point.toRawImagePoint(orientation)
    ScannerV2WarpPoint(raw.x * sourceWidth, raw.y * sourceHeight)
}

internal fun scannerV2DestinationCropPoints(
    output: WarpSize,
    rotationQuarterTurns: Int,
): List<ScannerV2WarpPoint> {
    require(rotationQuarterTurns in 0..3) { "Scanner rotation is invalid" }
    val width = output.width.toDouble()
    val height = output.height.toDouble()
    return when (rotationQuarterTurns) {
        0 -> listOf(point(0.0, 0.0), point(width, 0.0), point(width, height), point(0.0, height))
        1 -> listOf(point(width, 0.0), point(width, height), point(0.0, height), point(0.0, 0.0))
        2 -> listOf(point(width, height), point(0.0, height), point(0.0, 0.0), point(width, 0.0))
        else -> listOf(point(0.0, height), point(0.0, 0.0), point(width, 0.0), point(width, height))
    }
}

internal fun renderScannerV2Page(
    source: File,
    destination: File,
    crop: PageQuad,
    rotationQuarterTurns: Int,
    isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
): ScannerV2WarpResult {
    val input = source.canonicalFile
    val target = destination.canonicalFile
    if (
        !input.isFile ||
            input.length() !in 1..MAX_SCANNER_V2_PAGE_BYTES ||
            input.parentFile != target.parentFile ||
            input == target
    ) {
        throw IOException("Scanner render paths are invalid")
    }
    throwIfScannerV2Cancelled(isCancelled)
    val dimensions = readJpegDimensions(input)
    val orientation = readScannerV2ExifOrientation(input)
    val plan = scannerV2WarpPlan(
        dimensions.width,
        dimensions.height,
        crop,
        orientation,
        rotationQuarterTurns,
    )
    val sourceFingerprint = input.inputStream().use { readOutputFingerprint(it, input.length()) }
    var output: Bitmap? = null
    var renderedFingerprint: OutputFingerprint? = null
    try {
        output = renderScannerV2Tiles(input, dimensions, plan, isCancelled)
        val rendered = requireNotNull(output)
        publishImageExportAtomically(target, isCancelled) { staging ->
            encodeScannerV2Jpeg(rendered, staging)
            rendered.recycle()
            output = null
            val stagedDimensions = readJpegDimensions(staging)
            if (stagedDimensions.width != plan.output.width || stagedDimensions.height != plan.output.height) {
                throw IOException("Scanner render dimensions changed during encoding")
            }
            renderedFingerprint = staging.inputStream().use { readOutputFingerprint(it, staging.length()) }
            val currentSource = input.inputStream().use { readOutputFingerprint(it, input.length()) }
            if (currentSource != sourceFingerprint) throw IOException("Scanner source changed during rendering")
        }
    } finally {
        output?.takeUnless(Bitmap::isRecycled)?.recycle()
    }
    return ScannerV2WarpResult(
        width = plan.output.width,
        height = plan.output.height,
        fingerprint = renderedFingerprint ?: throw IOException("Scanner render fingerprint is unavailable"),
    )
}

private fun renderScannerV2Tiles(
    source: File,
    dimensions: JpegDimensions,
    plan: ScannerV2WarpPlan,
    isCancelled: () -> Boolean,
): Bitmap = withImageExportRegionResources(
    createDecoder = {
        BitmapRegionDecoder.newInstance(source.path)
    },
    createOutput = {
        createBitmap(plan.output.width, plan.output.height, Bitmap.Config.ARGB_8888)
    },
    releaseDecoder = { it.recycle() },
    releaseOutput = { it.recycle() },
) { decoder, output ->
    if (decoder.width != dimensions.width || decoder.height != dimensions.height) {
        throw IOException("Scanner source dimensions changed during rendering")
    }
    val canvas = Canvas(output).apply { drawColor(Color.WHITE) }
    val transform = scannerV2CropMatrix(plan.sourceCrop, plan.destinationCrop)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inScaled = false
    }
    var top = 0
    while (top < dimensions.height) {
        val bottom = minOf(dimensions.height, top + SCANNER_V2_TILE_EDGE)
        var left = 0
        while (left < dimensions.width) {
            throwIfScannerV2Cancelled(isCancelled)
            val right = minOf(dimensions.width, left + SCANNER_V2_TILE_EDGE)
            val region = Rect(left, top, right, bottom)
            val tile = decoder.decodeRegion(region, options)
                ?: throw IOException("Scanner source tile could not be decoded")
            try {
                if (
                    tile.allocationByteCount.toLong() >
                        SCANNER_V2_TILE_EDGE.toLong() * SCANNER_V2_TILE_EDGE * SCANNER_V2_BITMAP_BYTES_PER_PIXEL
                ) {
                    throw IOException("Scanner source tile exceeds the memory limit")
                }
                val destinationPoints = floatArrayOf(
                    left.toFloat(), top.toFloat(),
                    right.toFloat(), top.toFloat(),
                    right.toFloat(), bottom.toFloat(),
                    left.toFloat(), bottom.toFloat(),
                ).also { points -> transform.mapPoints(points) }
                val tilePoints = floatArrayOf(
                    0f, 0f,
                    tile.width.toFloat(), 0f,
                    tile.width.toFloat(), tile.height.toFloat(),
                    0f, tile.height.toFloat(),
                )
                val tileTransform = Matrix()
                if (!tileTransform.setPolyToPoly(tilePoints, 0, destinationPoints, 0, 4)) {
                    throw IOException("Scanner tile transform could not be created")
                }
                canvas.drawBitmap(tile, tileTransform, paint)
            } finally {
                tile.recycle()
            }
            left = right
        }
        top = bottom
    }
}

private fun scannerV2CropMatrix(
    source: List<ScannerV2WarpPoint>,
    destination: List<ScannerV2WarpPoint>,
): Matrix {
    val matrix = Matrix()
    val sourcePoints = source.flatMap { listOf(it.x.toFloat(), it.y.toFloat()) }.toFloatArray()
    val destinationPoints = destination.flatMap { listOf(it.x.toFloat(), it.y.toFloat()) }.toFloatArray()
    if (!matrix.setPolyToPoly(sourcePoints, 0, destinationPoints, 0, 4)) {
        throw IOException("Scanner perspective transform could not be created")
    }
    return matrix
}

private fun encodeScannerV2Jpeg(bitmap: Bitmap, destination: File) {
    FileOutputStream(destination).use { output ->
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
            throw IOException("Scanner JPEG encoder rejected the page")
        }
        output.fd.sync()
    }
}

private fun readScannerV2ExifOrientation(source: File): ImageExifOrientation {
    val raw = ExifInterface(source).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
    )
    return try {
        imageExifOrientation(raw)
    } catch (failure: IllegalArgumentException) {
        throw IOException("Scanner source has an unsupported EXIF orientation", failure)
    }
}

private fun NormalizedPoint.toRawImagePoint(orientation: ImageExifOrientation): NormalizedPoint =
    when (orientation) {
        ImageExifOrientation.Normal -> this
        ImageExifOrientation.FlipHorizontal -> NormalizedPoint(1.0 - x, y)
        ImageExifOrientation.Rotate180 -> NormalizedPoint(1.0 - x, 1.0 - y)
        ImageExifOrientation.FlipVertical -> NormalizedPoint(x, 1.0 - y)
        ImageExifOrientation.Transpose -> NormalizedPoint(y, x)
        ImageExifOrientation.Rotate90 -> NormalizedPoint(y, 1.0 - x)
        ImageExifOrientation.Transverse -> NormalizedPoint(1.0 - y, 1.0 - x)
        ImageExifOrientation.Rotate270 -> NormalizedPoint(1.0 - y, x)
    }

private fun point(x: Double, y: Double): ScannerV2WarpPoint = ScannerV2WarpPoint(x, y)

private fun throwIfScannerV2Cancelled(isCancelled: () -> Boolean) {
    if (isCancelled()) throw CancellationException("Scanner render cancelled")
}
