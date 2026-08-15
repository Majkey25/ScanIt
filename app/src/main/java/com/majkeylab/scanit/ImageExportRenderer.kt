package com.majkeylab.scanit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import kotlin.math.floor
import kotlin.math.sqrt

private const val IMAGE_EXPORT_BITMAP_BYTES = MAX_IMAGE_EXPORT_PIXELS * 4L
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
            width = bounds.width,
            height = bounds.height,
            exactSourceCopy = true,
        )
    }

    val sampleSize =
        imageExportDecodeSampleSize(
            bounds.width,
            bounds.height,
            options.maxDimension,
        )
    var decoded: Bitmap? = decodeBoundedImage(input, sampleSize)
    var rendered: Bitmap? = null
    try {
        val decodedBitmap = requireNotNull(decoded)
        validateDecodedImageExport(decodedBitmap)
        throwIfImageExportCancelled(isCancelled)
        val dimensions =
            imageExportDimensions(
                decodedBitmap.width,
                decodedBitmap.height,
                options.maxDimension,
            )
        rendered =
            if (dimensions.width == decodedBitmap.width && dimensions.height == decodedBitmap.height) {
                decodedBitmap
            } else {
                decodedBitmap.scale(dimensions.width, dimensions.height, filter = true)
            }
        val outputBitmap = requireNotNull(rendered)
        if (outputBitmap !== decodedBitmap) {
            decodedBitmap.recycle()
            decoded = null
        }
        validateDecodedImageExport(outputBitmap)
        throwIfImageExportCancelled(isCancelled)

        publishImageExportAtomically(target, isCancelled) { staging ->
            encodeImageExport(outputBitmap, staging, encoding)
            verifyRenderedImageExport(staging, encoding, dimensions)
            requireUnchangedImageExportSource(input, sourceLength, sourceModified)
        }
        return RenderedImageExport(
            file = target,
            mimeType = encoding.mimeType,
            extension = encoding.extension,
            width = dimensions.width,
            height = dimensions.height,
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
    val scale = minOf(1.0, edgeScale, pixelScale)
    return ImageExportDimensions(
        width = maxOf(1, floor(width * scale).toInt()),
        height = maxOf(1, floor(height * scale).toInt()),
    )
}

internal fun imageExportDecodeSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int?,
): Int {
    val target = imageExportDimensions(width, height, maxDimension)
    var sampleSize = 1
    while (!sampledImageExportIsBounded(width, height, sampleSize)) {
        check(sampleSize <= Int.MAX_VALUE / 2) { "Image dimensions cannot be sampled safely" }
        sampleSize *= 2
    }
    while (sampleSize <= Int.MAX_VALUE / 2) {
        val nextSampleSize = sampleSize * 2
        val nextWidth = sampledImageExportDimension(width, nextSampleSize)
        val nextHeight = sampledImageExportDimension(height, nextSampleSize)
        if (nextWidth < target.width || nextHeight < target.height) break
        sampleSize = nextSampleSize
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
            bitmap.allocationByteCount.toLong() > IMAGE_EXPORT_BITMAP_BYTES
    ) {
        throw IOException("Decoded image exceeds the export memory bound")
    }
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
    val options = BitmapFactory.Options().apply { inScaled = false }
    val verification =
        BitmapFactory.decodeFile(file.path, options)
            ?: throw IOException("Rendered image could not be decoded")
    try {
        if (
            verification.width != expected.width ||
                verification.height != expected.height ||
                encodedImageFormat(options.outMimeType) != encoding
        ) {
            throw IOException("Rendered image verification failed")
        }
    } finally {
        verification.recycle()
    }
}

private fun verifyExactImageExportDecode(file: File, bounds: ImageBounds) {
    val verification =
        decodeBoundedImage(
            file,
            imageExportDecodeSampleSize(bounds.width, bounds.height, maxDimension = null),
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

private fun sampledImageExportIsBounded(width: Int, height: Int, sampleSize: Int): Boolean {
    val sampledWidth = sampledImageExportDimension(width, sampleSize)
    val sampledHeight = sampledImageExportDimension(height, sampleSize)
    return sampledWidth <= MAX_IMAGE_EXPORT_DIMENSION &&
        sampledHeight <= MAX_IMAGE_EXPORT_DIMENSION &&
        sampledWidth.toLong() * sampledHeight <= MAX_IMAGE_EXPORT_PIXELS
}

private fun sampledImageExportDimension(size: Int, sampleSize: Int): Int =
    ((size.toLong() + sampleSize - 1L) / sampleSize).toInt()

private fun throwIfImageExportCancelled(isCancelled: () -> Boolean) {
    if (isCancelled()) throw CancellationException("Image export cancelled")
}
