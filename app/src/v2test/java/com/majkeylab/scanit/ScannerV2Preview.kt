package com.majkeylab.scanit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

private const val SCANNER_V2_PREVIEW_MAX_EDGE = 1600
private const val SCANNER_V2_PREVIEW_MAX_PIXELS = 2_000_000L
private const val SCANNER_V2_DETECTOR_MAX_EDGE = 512

internal fun decodeScannerV2Preview(source: File): Bitmap {
    val dimensions = readJpegDimensions(source)
    var sample = 1
    while (
        max(dimensions.width / sample, dimensions.height / sample) > SCANNER_V2_PREVIEW_MAX_EDGE ||
            dimensions.width.toLong() / sample * (dimensions.height / sample) > SCANNER_V2_PREVIEW_MAX_PIXELS
    ) {
        sample *= 2
    }
    val decoded = BitmapFactory.decodeFile(
        source.path,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        },
    ) ?: throw IOException("Scanner preview could not be decoded")
    val orientation = readScannerV2PreviewOrientation(source)
    if (orientation == ImageExifOrientation.Normal) return decoded
    val target = orientedImageExportDimensions(decoded.width, decoded.height, orientation)
    val output = createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
    try {
        val sourcePoints = floatArrayOf(
            0f,
            0f,
            decoded.width.toFloat(),
            0f,
            0f,
            decoded.height.toFloat(),
        )
        val corners = orientation.destinationCorners
        val destinationPoints = floatArrayOf(
            corners[0].x * target.width.toFloat(),
            corners[0].y * target.height.toFloat(),
            corners[1].x * target.width.toFloat(),
            corners[1].y * target.height.toFloat(),
            corners[2].x * target.width.toFloat(),
            corners[2].y * target.height.toFloat(),
        )
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(sourcePoints, 0, destinationPoints, 0, 3)) {
            throw IOException("Scanner preview orientation could not be applied")
        }
        Canvas(output).drawBitmap(
            decoded,
            matrix,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return output
    } catch (failure: Throwable) {
        output.recycle()
        throw failure
    } finally {
        decoded.recycle()
    }
}

internal fun detectScannerV2Crop(preview: Bitmap): PageQuad? {
    val scale = minOf(1.0, SCANNER_V2_DETECTOR_MAX_EDGE.toDouble() / max(preview.width, preview.height))
    val width = (preview.width * scale).roundToInt().coerceAtLeast(32)
    val height = (preview.height * scale).roundToInt().coerceAtLeast(32)
    val analysis = if (width == preview.width && height == preview.height) preview else preview.scale(width, height)
    try {
        val argb = IntArray(analysis.width * analysis.height)
        analysis.getPixels(argb, 0, analysis.width, 0, 0, analysis.width, analysis.height)
        val luma = ByteArray(argb.size) { index ->
            val color = argb[index]
            ((Color.red(color) * 77 + Color.green(color) * 150 + Color.blue(color) * 29) shr 8).toByte()
        }
        return detectDocumentQuad(LumaFrame(analysis.width, analysis.height, luma))
    } finally {
        if (analysis !== preview) analysis.recycle()
    }
}

private fun readScannerV2PreviewOrientation(source: File): ImageExifOrientation {
    val raw = ExifInterface(source).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
    )
    return try {
        imageExifOrientation(raw)
    } catch (failure: IllegalArgumentException) {
        throw IOException("Scanner preview has an unsupported EXIF orientation", failure)
    }
}
