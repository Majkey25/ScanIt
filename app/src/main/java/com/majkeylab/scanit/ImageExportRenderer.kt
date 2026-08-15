package com.majkeylab.scanit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import kotlin.math.floor
import kotlin.math.sqrt

private const val IMAGE_EXPORT_BITMAP_BYTES_PER_PIXEL = 4L
private const val MAX_IMAGE_EXPORT_BITMAP_BYTES =
    MAX_IMAGE_EXPORT_PIXELS * IMAGE_EXPORT_BITMAP_BYTES_PER_PIXEL
private const val MAX_IMAGE_EXPORT_PEAK_BITMAP_BYTES = 64L * 1024 * 1024
private const val MAX_IMAGE_EXPORT_TILE_PIXELS = 1_048_576L
private const val MAX_IMAGE_EXPORT_TILE_BITMAP_BYTES =
    MAX_IMAGE_EXPORT_TILE_PIXELS * IMAGE_EXPORT_BITMAP_BYTES_PER_PIXEL
private const val MAX_IMAGE_EXPORT_VERIFICATION_PIXELS = 2_000_000L
private const val PNG_ENCODER_IGNORED_QUALITY = 100

internal enum class EncodedImageFormat(
    val mimeType: String,
    val extension: String,
    val jpegQuality: Int?,
) {
    Jpeg("image/jpeg", "jpg", 95),
    Png("image/png", "png", null),
}

internal data class ImageExportDimensions(val width: Int, val height: Int)

internal data class ImageExportCorner(val x: Int, val y: Int)

internal enum class ImageExifOrientation(
    val swapsDimensions: Boolean,
    val destinationCorners: List<ImageExportCorner>,
) {
    Normal(
        false,
        listOf(ImageExportCorner(0, 0), ImageExportCorner(1, 0), ImageExportCorner(0, 1)),
    ),
    FlipHorizontal(
        false,
        listOf(ImageExportCorner(1, 0), ImageExportCorner(0, 0), ImageExportCorner(1, 1)),
    ),
    Rotate180(
        false,
        listOf(ImageExportCorner(1, 1), ImageExportCorner(0, 1), ImageExportCorner(1, 0)),
    ),
    FlipVertical(
        false,
        listOf(ImageExportCorner(0, 1), ImageExportCorner(1, 1), ImageExportCorner(0, 0)),
    ),
    Transpose(
        true,
        listOf(ImageExportCorner(0, 0), ImageExportCorner(0, 1), ImageExportCorner(1, 0)),
    ),
    Rotate90(
        true,
        listOf(ImageExportCorner(1, 0), ImageExportCorner(1, 1), ImageExportCorner(0, 0)),
    ),
    Transverse(
        true,
        listOf(ImageExportCorner(1, 1), ImageExportCorner(1, 0), ImageExportCorner(0, 1)),
    ),
    Rotate270(
        true,
        listOf(ImageExportCorner(0, 1), ImageExportCorner(0, 0), ImageExportCorner(1, 1)),
    ),
}

internal data class ImageExportRenderPlan(
    val target: ImageExportDimensions,
    val sampled: ImageExportDimensions,
    val sampleSize: Int,
    val tiled: Boolean,
    val peakBitmapBytes: Long,
)

internal data class RenderedImageExport(
    val file: File,
    val mimeType: String,
    val extension: String,
    val width: Int,
    val height: Int,
    val exactSourceCopy: Boolean,
)

internal fun renderImageExport(
    source: File,
    destination: File,
    options: ResolvedImageExport,
    isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
): RenderedImageExport {
    val input = requireReadableImageExportSource(source)
    val target = requireImageExportDestination(destination)
    if (input == target) throw IOException("Image export source and destination must differ")
    throwIfImageExportCancelled(isCancelled)

    val sourceLength = input.length()
    val sourceModified = input.lastModified()
    val bounds = readImageBounds(input)
    val orientation = readImageExifOrientation(input)
    val orientedBounds =
        orientedImageExportDimensions(bounds.width, bounds.height, orientation)
    val encoding = resolveImageExportEncoding(options.format, bounds.format)
    throwIfImageExportCancelled(isCancelled)

    if (isExactImageExportCopy(options, bounds.format)) {
        publishImageExportAtomically(target, isCancelled) { staging ->
            Files.copy(input.toPath(), staging.toPath(), StandardCopyOption.REPLACE_EXISTING)
            FileOutputStream(staging, true).use { it.fd.sync() }
            val stagedBounds = readImageBounds(staging)
            if (
                stagedBounds != bounds ||
                    staging.length() != sourceLength ||
                    !imageExportFilesMatch(input, staging, isCancelled)
            ) {
                throw IOException("Exact image export copy verification failed")
            }
            verifyExactImageExportDecode(staging, bounds)
            requireUnchangedImageExportSource(input, sourceLength, sourceModified)
        }
        return RenderedImageExport(
            file = target,
            mimeType = encoding.mimeType,
            extension = encoding.extension,
            width = orientedBounds.width,
            height = orientedBounds.height,
            exactSourceCopy = true,
        )
    }

    val plan =
        imageExportRenderPlan(
            bounds.width,
            bounds.height,
            orientation,
            options.maxDimension,
        )
    var decoded: Bitmap? = null
    var rendered: Bitmap? = null
    try {
        if (plan.tiled) {
            rendered =
                renderTiledImageExport(
                    input,
                    bounds,
                    plan,
                    orientation,
                    isCancelled,
                )
        } else {
            decoded = decodeBoundedImage(input, plan.sampleSize)
            val decodedBitmap = requireNotNull(decoded)
            validateDecodedImageExport(decodedBitmap)
            val decodedOriented =
                orientedImageExportDimensions(
                    decodedBitmap.width,
                    decodedBitmap.height,
                    orientation,
                )
            if (
                decodedOriented.width < plan.target.width ||
                    decodedOriented.height < plan.target.height
            ) {
                throw IOException("Decoded image sample is smaller than the export target")
            }
            throwIfImageExportCancelled(isCancelled)
            rendered = renderOrientedImageExport(decodedBitmap, plan.target, orientation)
            if (rendered !== decodedBitmap) {
                decodedBitmap.recycle()
                decoded = null
            }
        }
        val outputBitmap = requireNotNull(rendered)
        validateDecodedImageExport(outputBitmap)
        throwIfImageExportCancelled(isCancelled)

        publishImageExportAtomically(target, isCancelled) { staging ->
            encodeImageExport(outputBitmap, staging, encoding)
            outputBitmap.recycle()
            rendered = null
            if (decoded === outputBitmap) decoded = null
            verifyRenderedImageExport(staging, encoding, plan.target)
            requireUnchangedImageExportSource(input, sourceLength, sourceModified)
        }
        return RenderedImageExport(
            file = target,
            mimeType = encoding.mimeType,
            extension = encoding.extension,
            width = plan.target.width,
            height = plan.target.height,
            exactSourceCopy = false,
        )
    } finally {
        rendered?.takeUnless(Bitmap::isRecycled)?.recycle()
        decoded?.takeUnless(Bitmap::isRecycled)?.recycle()
    }
}

internal fun isExactImageExportCopy(
    options: ResolvedImageExport,
    sourceFormat: EncodedImageFormat?,
): Boolean =
    sourceFormat != null &&
        options.format == ImageExportFormat.Original &&
        options.maxDimension == null

internal fun resolveImageExportEncoding(
    requested: ImageExportFormat,
    sourceFormat: EncodedImageFormat,
): EncodedImageFormat =
    when (requested) {
        ImageExportFormat.Original -> sourceFormat
        ImageExportFormat.Jpeg -> EncodedImageFormat.Jpeg
        ImageExportFormat.Png -> EncodedImageFormat.Png
    }

internal fun imageExportDimensions(
    width: Int,
    height: Int,
    maxDimension: Int?,
): ImageExportDimensions {
    require(width > 0 && height > 0) { "Image dimensions must be positive" }
    require(maxDimension == null || maxDimension in MIN_IMAGE_EXPORT_DIMENSION..MAX_IMAGE_EXPORT_DIMENSION) {
        "Image export dimension is outside the supported range"
    }
    val pixelCount = width.toLong() * height
    val edgeLimit = maxDimension ?: MAX_IMAGE_EXPORT_DIMENSION
    val edgeScale = edgeLimit.toDouble() / maxOf(width, height)
    val pixelScale = sqrt(MAX_IMAGE_EXPORT_PIXELS.toDouble() / pixelCount)
    if (edgeScale < 1.0 && edgeScale <= pixelScale) {
        return if (width >= height) {
            ImageExportDimensions(
                width = edgeLimit,
                height = maxOf(1, (height.toLong() * edgeLimit / width).toInt()),
            )
        } else {
            ImageExportDimensions(
                width = maxOf(1, (width.toLong() * edgeLimit / height).toInt()),
                height = edgeLimit,
            )
        }
    }
    val scale = minOf(1.0, pixelScale)
    return ImageExportDimensions(
        width = maxOf(1, floor(width * scale).toInt()),
        height = maxOf(1, floor(height * scale).toInt()),
    )
}

internal fun imageExportDecodeSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int?,
): Int =
    imageExportRenderPlan(
        width,
        height,
        ImageExifOrientation.Normal,
        maxDimension,
    ).sampleSize

internal fun imageExifOrientation(value: Int): ImageExifOrientation =
    when (value) {
        ExifInterface.ORIENTATION_UNDEFINED,
        ExifInterface.ORIENTATION_NORMAL,
        -> ImageExifOrientation.Normal
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ImageExifOrientation.FlipHorizontal
        ExifInterface.ORIENTATION_ROTATE_180 -> ImageExifOrientation.Rotate180
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> ImageExifOrientation.FlipVertical
        ExifInterface.ORIENTATION_TRANSPOSE -> ImageExifOrientation.Transpose
        ExifInterface.ORIENTATION_ROTATE_90 -> ImageExifOrientation.Rotate90
        ExifInterface.ORIENTATION_TRANSVERSE -> ImageExifOrientation.Transverse
        ExifInterface.ORIENTATION_ROTATE_270 -> ImageExifOrientation.Rotate270
        else -> throw IllegalArgumentException("Unsupported EXIF orientation: $value")
    }

internal fun orientedImageExportDimensions(
    width: Int,
    height: Int,
    orientation: ImageExifOrientation,
): ImageExportDimensions {
    require(width > 0 && height > 0) { "Image dimensions must be positive" }
    return if (orientation.swapsDimensions) {
        ImageExportDimensions(height, width)
    } else {
        ImageExportDimensions(width, height)
    }
}

internal fun imageExportRenderPlan(
    width: Int,
    height: Int,
    orientation: ImageExifOrientation,
    maxDimension: Int?,
): ImageExportRenderPlan {
    val oriented = orientedImageExportDimensions(width, height, orientation)
    val target = imageExportDimensions(oriented.width, oriented.height, maxDimension)
    var sampleSize = 1
    while (sampleSize <= Int.MAX_VALUE / 2) {
        val nextSampleSize = sampleSize * 2
        val nextOriented =
            orientedImageExportDimensions(
                sampledImageExportLowerBound(width, nextSampleSize),
                sampledImageExportLowerBound(height, nextSampleSize),
                orientation,
            )
        if (nextOriented.width < target.width || nextOriented.height < target.height) break
        sampleSize = nextSampleSize
    }
    val sampled =
        ImageExportDimensions(
            sampledImageExportLowerBound(width, sampleSize),
            sampledImageExportLowerBound(height, sampleSize),
        )
    val sampledUpperBound =
        ImageExportDimensions(
            sampledImageExportUpperBound(width, sampleSize),
            sampledImageExportUpperBound(height, sampleSize),
        )
    val sampledOriented =
        orientedImageExportDimensions(sampled.width, sampled.height, orientation)
    check(sampledOriented.width >= target.width && sampledOriented.height >= target.height) {
        "Image sample cannot satisfy the export dimensions"
    }
    val needsTargetBitmap =
        orientation != ImageExifOrientation.Normal ||
            sampled != target ||
            sampledUpperBound != target
    val wholePeakBytes =
        imageExportBitmapBytes(sampledUpperBound) +
            if (needsTargetBitmap) imageExportBitmapBytes(target) else 0L
    val tiled =
        !sampledImageExportIsBounded(sampledUpperBound) ||
            wholePeakBytes > MAX_IMAGE_EXPORT_PEAK_BITMAP_BYTES
    val peakBytes =
        if (tiled) {
            imageExportBitmapBytes(target) + MAX_IMAGE_EXPORT_TILE_BITMAP_BYTES
        } else {
            wholePeakBytes
        }
    check(peakBytes <= MAX_IMAGE_EXPORT_PEAK_BITMAP_BYTES) {
        "Image export cannot satisfy the bitmap memory bound"
    }
    return ImageExportRenderPlan(target, sampled, sampleSize, tiled, peakBytes)
}

internal fun imageExportVerificationSampleSize(width: Int, height: Int): Int {
    require(width > 0 && height > 0) { "Image dimensions must be positive" }
    var sampleSize = 1
    while (
        sampledImageExportUpperBound(width, sampleSize).toLong() *
            sampledImageExportUpperBound(height, sampleSize) >
            MAX_IMAGE_EXPORT_VERIFICATION_PIXELS
    ) {
        check(sampleSize <= Int.MAX_VALUE / 2) { "Image dimensions cannot be sampled safely" }
        sampleSize *= 2
    }
    return sampleSize
}

internal fun requireReadableImageExportSource(source: File): File {
    val input = source.canonicalFile
    if (!input.isFile || input.length() <= 0L) {
        throw IOException("Image export source is missing or empty")
    }
    val header = ByteArray(8)
    var headerSize = 0
    input.inputStream().use { stream ->
        while (headerSize < header.size) {
            val count = stream.read(header, headerSize, header.size - headerSize)
            if (count < 0) break
            headerSize += count
        }
    }
    val isJpeg =
        headerSize >= 2 &&
            header[0] == 0xff.toByte() &&
            header[1] == 0xd8.toByte()
    val isPng =
        headerSize == header.size &&
            header.contentEquals(
                byteArrayOf(
                    0x89.toByte(),
                    0x50,
                    0x4e,
                    0x47,
                    0x0d,
                    0x0a,
                    0x1a,
                    0x0a,
                ),
            )
    if (!isJpeg && !isPng) throw IOException("Image export source must be JPEG or PNG")
    return input
}

internal fun publishImageExportAtomically(
    destination: File,
    isCancelled: () -> Boolean,
    writeAndVerify: (File) -> Unit,
) {
    val target = requireImageExportDestination(destination)
    val parent = requireNotNull(target.parentFile)
    val staging = Files.createTempFile(parent.toPath(), ".scanit-image-", ".part").toFile()
    var failure: Throwable? = null
    try {
        throwIfImageExportCancelled(isCancelled)
        writeAndVerify(staging)
        throwIfImageExportCancelled(isCancelled)
        Files.move(
            staging.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (throwable: Throwable) {
        failure = throwable
        throw throwable
    } finally {
        try {
            Files.deleteIfExists(staging.toPath())
        } catch (cleanupFailure: Throwable) {
            failure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
        }
    }
}

private data class ImageBounds(
    val width: Int,
    val height: Int,
    val format: EncodedImageFormat,
)

private fun readImageBounds(file: File): ImageBounds {
    val decode = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, decode)
    val format = encodedImageFormat(decode.outMimeType)
    if (decode.outWidth <= 0 || decode.outHeight <= 0 || format == null) {
        throw IOException("Source is not a readable JPEG or PNG image")
    }
    return ImageBounds(decode.outWidth, decode.outHeight, format)
}

private fun encodedImageFormat(mimeType: String?): EncodedImageFormat? =
    EncodedImageFormat.entries.firstOrNull { it.mimeType.equals(mimeType, ignoreCase = true) }

private fun readImageExifOrientation(file: File): ImageExifOrientation {
    val value =
        ExifInterface(file).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    return try {
        imageExifOrientation(value)
    } catch (exception: IllegalArgumentException) {
        throw IOException("Source image has an unsupported EXIF orientation", exception)
    }
}

private fun requireImageExportDestination(destination: File): File {
    val target = destination.canonicalFile
    val parent = target.parentFile ?: throw IOException("Image export destination has no parent")
    if (!parent.isDirectory || target.isDirectory) {
        throw IOException("Image export destination is unavailable")
    }
    return target
}

private fun decodeBoundedImage(file: File, sampleSize: Int): Bitmap {
    val options =
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
            inScaled = false
        }
    return BitmapFactory.decodeFile(file.path, options)
        ?: throw IOException("Source image could not be decoded into bounded memory")
}

private fun validateDecodedImageExport(bitmap: Bitmap) {
    val pixels = bitmap.width.toLong() * bitmap.height
    if (
        bitmap.width <= 0 ||
            bitmap.height <= 0 ||
            bitmap.width > MAX_IMAGE_EXPORT_DIMENSION ||
            bitmap.height > MAX_IMAGE_EXPORT_DIMENSION ||
            pixels > MAX_IMAGE_EXPORT_PIXELS ||
            bitmap.allocationByteCount.toLong() > MAX_IMAGE_EXPORT_BITMAP_BYTES
    ) {
        throw IOException("Decoded image exceeds the export memory bound")
    }
}

private fun renderOrientedImageExport(
    source: Bitmap,
    target: ImageExportDimensions,
    orientation: ImageExifOrientation,
): Bitmap {
    if (
        orientation == ImageExifOrientation.Normal &&
            source.width == target.width &&
            source.height == target.height
    ) {
        return source
    }
    val targetBytes = imageExportBitmapBytes(target)
    if (source.allocationByteCount.toLong() + targetBytes > MAX_IMAGE_EXPORT_PEAK_BITMAP_BYTES) {
        throw IOException("Image export bitmap peak exceeds the memory bound")
    }
    val output = createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
    try {
        if (
            source.allocationByteCount.toLong() + output.allocationByteCount.toLong() >
                MAX_IMAGE_EXPORT_PEAK_BITMAP_BYTES
        ) {
            throw IOException("Image export bitmap peak exceeds the memory bound")
        }
        val sourcePoints =
            floatArrayOf(
                0f,
                0f,
                source.width.toFloat(),
                0f,
                0f,
                source.height.toFloat(),
            )
        val first = orientation.destinationCorners[0]
        val second = orientation.destinationCorners[1]
        val third = orientation.destinationCorners[2]
        val destinationPoints =
            floatArrayOf(
                first.x * target.width.toFloat(),
                first.y * target.height.toFloat(),
                second.x * target.width.toFloat(),
                second.y * target.height.toFloat(),
                third.x * target.width.toFloat(),
                third.y * target.height.toFloat(),
            )
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(sourcePoints, 0, destinationPoints, 0, 3)) {
            throw IOException("Image orientation transform could not be created")
        }
        Canvas(output).drawBitmap(
            source,
            matrix,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return output
    } catch (throwable: Throwable) {
        output.recycle()
        throw throwable
    }
}

private fun renderTiledImageExport(
    source: File,
    bounds: ImageBounds,
    plan: ImageExportRenderPlan,
    orientation: ImageExifOrientation,
    isCancelled: () -> Boolean,
): Bitmap {
    val output = createBitmap(plan.target.width, plan.target.height, Bitmap.Config.ARGB_8888)
    if (
        output.allocationByteCount.toLong() + MAX_IMAGE_EXPORT_TILE_BITMAP_BYTES >
            MAX_IMAGE_EXPORT_PEAK_BITMAP_BYTES
    ) {
        output.recycle()
        throw IOException("Image export bitmap peak exceeds the memory bound")
    }
    val decoder = BitmapRegionDecoder.newInstance(source.path)
    try {
        if (decoder.width != bounds.width || decoder.height != bounds.height) {
            throw IOException("Image region decoder dimensions are incorrect")
        }
        val canvas = Canvas(output)
        val paint = Paint(Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
        val tileEdge =
            minOf(
                maxOf(bounds.width, bounds.height).toLong(),
                1_024L * plan.sampleSize,
            ).toInt()
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = plan.sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
                inScaled = false
            }
        var top = 0
        while (top < bounds.height) {
            val bottom = minOf(bounds.height.toLong(), top.toLong() + tileEdge).toInt()
            var left = 0
            while (left < bounds.width) {
                throwIfImageExportCancelled(isCancelled)
                val right = minOf(bounds.width.toLong(), left.toLong() + tileEdge).toInt()
                val region = Rect(left, top, right, bottom)
                val tile =
                    decoder.decodeRegion(region, options)
                        ?: throw IOException("Source image tile could not be decoded")
                try {
                    if (
                        tile.allocationByteCount.toLong() > MAX_IMAGE_EXPORT_TILE_BITMAP_BYTES ||
                            output.allocationByteCount.toLong() + tile.allocationByteCount.toLong() >
                            MAX_IMAGE_EXPORT_PEAK_BITMAP_BYTES
                    ) {
                        throw IOException("Image export tile exceeds the memory bound")
                    }
                    throwIfImageExportCancelled(isCancelled)
                    canvas.drawBitmap(
                        tile,
                        imageExportTileMatrix(tile, region, bounds, plan.target, orientation),
                        paint,
                    )
                } finally {
                    tile.recycle()
                }
                left = right
            }
            top = bottom
        }
        return output
    } catch (throwable: Throwable) {
        output.recycle()
        throw throwable
    } finally {
        decoder.recycle()
    }
}

private data class ImageExportPointF(val x: Float, val y: Float)

private fun imageExportTileMatrix(
    tile: Bitmap,
    region: Rect,
    bounds: ImageBounds,
    target: ImageExportDimensions,
    orientation: ImageExifOrientation,
): Matrix {
    val topLeft =
        imageExportDestinationPoint(region.left, region.top, bounds, target, orientation)
    val topRight =
        imageExportDestinationPoint(region.right, region.top, bounds, target, orientation)
    val bottomLeft =
        imageExportDestinationPoint(region.left, region.bottom, bounds, target, orientation)
    val sourcePoints =
        floatArrayOf(
            0f,
            0f,
            tile.width.toFloat(),
            0f,
            0f,
            tile.height.toFloat(),
        )
    val destinationPoints =
        floatArrayOf(
            topLeft.x,
            topLeft.y,
            topRight.x,
            topRight.y,
            bottomLeft.x,
            bottomLeft.y,
        )
    return Matrix().also { matrix ->
        if (!matrix.setPolyToPoly(sourcePoints, 0, destinationPoints, 0, 3)) {
            throw IOException("Image tile transform could not be created")
        }
    }
}

private fun imageExportDestinationPoint(
    x: Int,
    y: Int,
    bounds: ImageBounds,
    target: ImageExportDimensions,
    orientation: ImageExifOrientation,
): ImageExportPointF {
    val horizontal = x.toFloat() / bounds.width
    val vertical = y.toFloat() / bounds.height
    val topLeft = orientation.destinationCorners[0]
    val topRight = orientation.destinationCorners[1]
    val bottomLeft = orientation.destinationCorners[2]
    val destinationX =
        topLeft.x +
            (topRight.x - topLeft.x) * horizontal +
            (bottomLeft.x - topLeft.x) * vertical
    val destinationY =
        topLeft.y +
            (topRight.y - topLeft.y) * horizontal +
            (bottomLeft.y - topLeft.y) * vertical
    return ImageExportPointF(destinationX * target.width, destinationY * target.height)
}

private fun encodeImageExport(
    bitmap: Bitmap,
    staging: File,
    encoding: EncodedImageFormat,
) {
    val format =
        when (encoding) {
            EncodedImageFormat.Jpeg -> Bitmap.CompressFormat.JPEG
            EncodedImageFormat.Png -> Bitmap.CompressFormat.PNG
        }
    val quality = encoding.jpegQuality ?: PNG_ENCODER_IGNORED_QUALITY
    FileOutputStream(staging).use { output ->
        if (!bitmap.compress(format, quality, output)) {
            throw IOException("Image encoder rejected the rendered bitmap")
        }
        output.fd.sync()
    }
}

private fun verifyRenderedImageExport(
    file: File,
    encoding: EncodedImageFormat,
    expected: ImageExportDimensions,
) {
    if (!file.isFile || file.length() <= 0L) throw IOException("Rendered image is empty")
    val bounds = readImageBounds(file)
    if (
        bounds.width != expected.width ||
            bounds.height != expected.height ||
            bounds.format != encoding ||
            readImageExifOrientation(file) != ImageExifOrientation.Normal
    ) {
        throw IOException("Rendered image verification failed")
    }
    val verification =
        decodeBoundedImage(
            file,
            imageExportVerificationSampleSize(bounds.width, bounds.height),
        )
    try {
        validateDecodedImageExport(verification)
    } finally {
        verification.recycle()
    }
}

private fun verifyExactImageExportDecode(file: File, bounds: ImageBounds) {
    val verification =
        decodeBoundedImage(
            file,
            imageExportVerificationSampleSize(bounds.width, bounds.height),
        )
    try {
        validateDecodedImageExport(verification)
        if (verification.width > bounds.width || verification.height > bounds.height) {
            throw IOException("Exact image export decode dimensions are invalid")
        }
    } finally {
        verification.recycle()
    }
}

private fun requireUnchangedImageExportSource(
    source: File,
    expectedLength: Long,
    expectedModified: Long,
) {
    if (
        !source.isFile ||
            source.length() != expectedLength ||
            source.lastModified() != expectedModified
    ) {
        throw IOException("Source image changed while it was rendered")
    }
}

private fun imageExportFilesMatch(
    first: File,
    second: File,
    isCancelled: () -> Boolean,
): Boolean {
    if (first.length() != second.length()) return false
    val firstBuffer = ByteArray(8_192)
    val secondBuffer = ByteArray(firstBuffer.size)
    first.inputStream().buffered().use { firstInput ->
        second.inputStream().buffered().use { secondInput ->
            while (true) {
                throwIfImageExportCancelled(isCancelled)
                val firstCount = firstInput.read(firstBuffer)
                val secondCount = secondInput.read(secondBuffer)
                if (firstCount != secondCount) return false
                if (firstCount < 0) return true
                for (index in 0 until firstCount) {
                    if (firstBuffer[index] != secondBuffer[index]) return false
                }
            }
        }
    }
}

private fun sampledImageExportIsBounded(sampled: ImageExportDimensions): Boolean =
    sampled.width <= MAX_IMAGE_EXPORT_DIMENSION &&
        sampled.height <= MAX_IMAGE_EXPORT_DIMENSION &&
        sampled.width.toLong() * sampled.height <= MAX_IMAGE_EXPORT_PIXELS

private fun imageExportBitmapBytes(dimensions: ImageExportDimensions): Long =
    dimensions.width.toLong() * dimensions.height * IMAGE_EXPORT_BITMAP_BYTES_PER_PIXEL

private fun sampledImageExportLowerBound(size: Int, sampleSize: Int): Int =
    maxOf(1, size / sampleSize)

private fun sampledImageExportUpperBound(size: Int, sampleSize: Int): Int =
    ((size.toLong() + sampleSize - 1L) / sampleSize).toInt()

private fun throwIfImageExportCancelled(isCancelled: () -> Boolean) {
    if (isCancelled()) throw CancellationException("Image export cancelled")
}
