package com.majkeylab.scanit

import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

internal const val MAX_SCANNER_V2_PAGE_BYTES = 64L * 1024 * 1024
private const val SCANNER_V2_IMPORT_BUFFER_BYTES = 64 * 1024

private val SCANNER_V2_IMPORT_MIME_TYPES = setOf(
    "image/jpeg",
    "image/png",
    "image/webp",
)

internal fun isSupportedScannerV2ImportMimeType(mimeType: String?): Boolean =
    mimeType != null && SCANNER_V2_IMPORT_MIME_TYPES.any { it.equals(mimeType, ignoreCase = true) }

internal fun copyScannerV2ImportSource(
    input: InputStream,
    destination: File,
    maxBytes: Long = MAX_SCANNER_V2_PAGE_BYTES,
    isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
): Long {
    require(maxBytes > 0) { "Scanner import limit must be positive" }
    val target = destination.absoluteFile
    val parent = target.parentFile
    if (parent == null || !parent.isDirectory || target.exists()) {
        throw IOException("Scanner import destination is unavailable")
    }
    var failure: Throwable? = null
    try {
        var length = 0L
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(SCANNER_V2_IMPORT_BUFFER_BYTES)
            while (true) {
                if (isCancelled()) throw CancellationException("Scanner import cancelled")
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                length += read
                if (length > maxBytes) throw IOException("Scanner import exceeds the file limit")
                output.write(buffer, 0, read)
            }
            if (length == 0L) throw IOException("Scanner import is empty")
            output.fd.sync()
        }
        return length
    } catch (throwable: Throwable) {
        failure = throwable
        throw throwable
    } finally {
        if (failure != null && target.exists() && !target.delete()) {
            failure.addSuppressed(IOException("Incomplete scanner import could not be deleted"))
        }
    }
}

internal fun readScannerV2SourceDimensions(file: File): ImageExportDimensions {
    if (!file.isFile || file.length() !in 1..MAX_SCANNER_V2_PAGE_BYTES) {
        throw IOException("Scanner source is missing or too large")
    }
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    if (
        options.outWidth <= 0 ||
            options.outHeight <= 0 ||
            !isSupportedScannerV2ImportMimeType(options.outMimeType)
    ) {
        throw IOException("Scanner source is not a readable JPEG, PNG, or WebP image")
    }
    return ImageExportDimensions(options.outWidth, options.outHeight)
}
