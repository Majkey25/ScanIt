package com.majkeylab.scanit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException

private const val MAX_RENDER_PIXELS = 12_000_000L
private const val MAX_RENDER_EDGE = 6_000
private const val MAX_RENDER_BITMAP_BYTES = MAX_RENDER_PIXELS * 4L
private const val JPEG_QUALITY = 92
private const val JPEG_MIME_TYPE = "image/jpeg"

internal data class RenderedJpeg(
    val width: Int,
    val height: Int,
    val sourceSampleSize: Int,
    val bytes: Long,
)

internal object ScanAppearanceRenderer {
    fun renderJpeg(
        source: File,
        destination: File,
        appearance: ScanAppearance,
        minimumSampleSize: Int = 1,
        transformBitmap: (Bitmap) -> Unit = {},
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): RenderedJpeg {
        val input = source.canonicalFile
        if (!input.isFile || input.length() <= 0L) throw IOException("Source JPEG is missing or empty")
        val target = destination.canonicalFile
        val parent = target.parentFile ?: throw IOException("JPEG destination has no parent")
        if (!parent.isDirectory || target.isDirectory) {
            throw IOException("JPEG destination is unavailable")
        }
        throwIfCancelled(isCancelled)

        val sourceLength = input.length()
        val sourceModified = input.lastModified()
        val bounds = readJpegBounds(input)
        val sampleSize =
            appearanceDecodeSampleSize(
                bounds.width,
                bounds.height,
                minimumSampleSize = minimumSampleSize,
            )
        val bitmap = decodeMutableBitmap(input, sampleSize)
        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = width.toLong() * height
        if (
            width > MAX_RENDER_EDGE ||
                height > MAX_RENDER_EDGE ||
                pixelCount > MAX_RENDER_PIXELS ||
                bitmap.allocationByteCount.toLong() > MAX_RENDER_BITMAP_BYTES
        ) {
            bitmap.recycle()
            throw IOException("Decoded JPEG exceeds the render memory bound")
        }
        if (sampleSize == 1 && (width != bounds.width || height != bounds.height)) {
            bitmap.recycle()
            throw IOException("JPEG dimensions changed during decode")
        }

        val normalizedAppearance =
            appearance.copy(
                intensity = clampAppearancePercent(appearance.intensity),
                shadows = clampAppearancePercent(appearance.shadows),
            )
        var staging: File? = null
        var failure: Throwable? = null
        try {
            transformBitmap(bitmap)
            throwIfCancelled(isCancelled)
            applyAppearance(bitmap, normalizedAppearance, isCancelled)
            throwIfCancelled(isCancelled)

            staging = Files.createTempFile(parent.toPath(), ".scanit-render-", ".jpg.tmp").toFile()
            FileOutputStream(staging).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    throw IOException("JPEG encoder rejected the rendered bitmap")
                }
                output.fd.sync()
            }
            bitmap.recycle()
            verifyRenderedJpeg(staging, width, height)
            throwIfCancelled(isCancelled)
            if (
                !input.isFile ||
                    input.length() != sourceLength ||
                    input.lastModified() != sourceModified
            ) {
                throw IOException("Source JPEG changed while it was rendered")
            }

            val outputBytes = staging.length()
            Files.move(
                staging.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            staging = null
            return RenderedJpeg(width, height, sampleSize, outputBytes)
        } catch (throwable: Throwable) {
            failure = throwable
            throw throwable
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            staging?.let { temporary ->
                if (temporary.exists() && !temporary.delete()) {
                    val cleanup = IOException("Incomplete rendered JPEG could not be deleted")
                    failure?.addSuppressed(cleanup) ?: throw cleanup
                }
            }
        }
    }
}

private data class JpegBounds(val width: Int, val height: Int)

private fun readJpegBounds(file: File): JpegBounds {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    if (
        options.outWidth <= 0 ||
            options.outHeight <= 0 ||
            !options.outMimeType.equals(JPEG_MIME_TYPE, ignoreCase = true)
    ) {
        throw IOException("Source is not a readable JPEG")
    }
    return JpegBounds(options.outWidth, options.outHeight)
}

private fun decodeMutableBitmap(file: File, sampleSize: Int): Bitmap {
    val options =
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
            inScaled = false
        }
    val bitmap =
        BitmapFactory.decodeFile(file.path, options)
            ?: throw IOException("Source JPEG could not be decoded into bounded mutable memory")
    if (!bitmap.isMutable || bitmap.config != Bitmap.Config.ARGB_8888) {
        bitmap.recycle()
        throw IOException("Source JPEG could not be decoded into bounded mutable memory")
    }
    return bitmap
}

private fun applyAppearance(
    bitmap: Bitmap,
    appearance: ScanAppearance,
    isCancelled: () -> Boolean,
) {
    if (appearance.intensity == 0 && appearance.shadows == 0) return
    val width = bitmap.width
    val height = bitmap.height
    val row = IntArray(width)
    val columns = minOf(LOCAL_SHADOW_GRID_SIZE, width)
    val rows = minOf(LOCAL_SHADOW_GRID_SIZE, height)
    val backgrounds = IntArray(columns * rows)

    if (appearance.shadows > 0) {
        for (y in 0 until height) {
            throwIfCancelled(isCancelled)
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val luma = argbLuma(row[x])
                val tile = localShadowTileIndex(x, y, width, height, columns, rows)
                backgrounds[tile] = maxOf(backgrounds[tile], luma)
            }
        }
    }

    val threshold =
        if (appearance.colorMode == ScanColorMode.BlackWhite && appearance.intensity > 0) {
            val histogram = IntArray(256)
            for (y in 0 until height) {
                throwIfCancelled(isCancelled)
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    val rawLuma = argbLuma(row[x])
                    val corrected =
                        correctedLuma(
                            rawLuma,
                            x,
                            y,
                            width,
                            height,
                            columns,
                            rows,
                            backgrounds,
                            appearance.shadows,
                        )
                    histogram[corrected]++
                }
            }
            otsuThresholdFromHistogram(histogram)
        } else {
            127
        }

    for (y in 0 until height) {
        throwIfCancelled(isCancelled)
        bitmap.getPixels(row, 0, width, 0, y, width, 1)
        for (x in 0 until width) {
            val rawLuma = argbLuma(row[x])
            row[x] =
                processAppearancePixel(
                    pixel = row[x],
                    correctedLuma =
                        correctedLuma(
                            rawLuma,
                            x,
                            y,
                            width,
                            height,
                            columns,
                            rows,
                            backgrounds,
                            appearance.shadows,
                        ),
                    appearance = appearance,
                    blackWhiteThreshold = threshold,
                )
        }
        bitmap.setPixels(row, 0, width, 0, y, width, 1)
    }
}

private fun correctedLuma(
    rawLuma: Int,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    columns: Int,
    rows: Int,
    backgrounds: IntArray,
    shadowStrength: Int,
): Int {
    if (shadowStrength == 0) return rawLuma
    val background =
        localShadowBackgroundAt(backgrounds, columns, rows, x, y, width, height)
    return correctShadowLuma(rawLuma, background, shadowStrength)
}

private fun verifyRenderedJpeg(file: File, expectedWidth: Int, expectedHeight: Int) {
    if (!file.isFile || file.length() <= 0L) throw IOException("Rendered JPEG is empty")
    val verification =
        BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inScaled = false
            },
        ) ?: throw IOException("Rendered JPEG could not be decoded")
    try {
        if (verification.width != expectedWidth || verification.height != expectedHeight) {
            throw IOException("Rendered JPEG dimensions are incorrect")
        }
    } finally {
        verification.recycle()
    }
}

private fun throwIfCancelled(isCancelled: () -> Boolean) {
    if (isCancelled()) throw CancellationException("Scan appearance render cancelled")
}

internal fun appearanceDecodeSampleSize(
    width: Int,
    height: Int,
    maxPixels: Long = MAX_RENDER_PIXELS,
    maxEdge: Int = MAX_RENDER_EDGE,
    minimumSampleSize: Int = 1,
): Int {
    require(width > 0 && height > 0) { "Image dimensions must be positive" }
    require(maxPixels > 0 && maxEdge > 0) { "Render bounds must be positive" }
    require(
        minimumSampleSize > 0 &&
            minimumSampleSize and (minimumSampleSize - 1) == 0,
    ) { "Minimum sample size must be a positive power of two" }
    var sampleSize = minimumSampleSize
    while (true) {
        val sampledWidth = sampledDimensionUpperBound(width, sampleSize)
        val sampledHeight = sampledDimensionUpperBound(height, sampleSize)
        if (
            sampledWidth <= maxEdge &&
                sampledHeight <= maxEdge &&
                sampledWidth.toLong() * sampledHeight <= maxPixels
        ) {
            return sampleSize
        }
        check(sampleSize <= Int.MAX_VALUE / 2) { "Image dimensions cannot be sampled safely" }
        sampleSize *= 2
    }
}

internal fun sampledDimensionUpperBound(size: Int, sampleSize: Int): Int {
    require(size > 0 && sampleSize > 0) { "Dimensions and sample size must be positive" }
    return ((size.toLong() + sampleSize - 1L) / sampleSize).toInt()
}
