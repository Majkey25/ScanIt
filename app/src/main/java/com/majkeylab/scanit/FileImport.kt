package com.majkeylab.scanit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.PorterDuff
import android.net.Uri
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val MAX_DOCUMENT_IMPORT_BYTES = 512L * 1024L * 1024L
private const val DOCUMENT_IMPORT_MAX_SIDE = 3508
private const val DOCUMENT_IMPORT_MAX_PIXELS = 8_000_000L

internal enum class DocumentImportAction {
    Open,
    PdfToImages,
    ImagesToPdf;

    val mimeTypes: Array<String>
        get() = when (this) {
            Open -> arrayOf("application/pdf", "image/*")
            PdfToImages -> arrayOf("application/pdf")
            ImagesToPdf -> arrayOf("image/*")
        }

    val saveTarget: SaveNowTarget?
        get() = when (this) {
            Open -> null
            PdfToImages -> SaveNowTarget.Images
            ImagesToPdf -> SaveNowTarget.Pdf
        }
}

internal fun documentImportFailureMessage(failure: Exception): UiMessage =
    when ((failure as? DocumentImportException)?.reason) {
        DocumentImportFailureReason.PasswordProtected -> UiMessage(R.string.file_import_password)
        DocumentImportFailureReason.TooManyPages ->
            UiMessage(R.string.file_import_page_limit, listOf(MAX_SCAN_PAGES))
        DocumentImportFailureReason.TimedOut -> UiMessage(R.string.file_import_timeout)
        DocumentImportFailureReason.TooLarge -> UiMessage(R.string.file_import_too_large)
        else -> UiMessage(R.string.file_import_failed)
    }

internal fun documentImportDimensions(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0) { "Document page dimensions are invalid" }
    val scale = minOf(
        1.0,
        DOCUMENT_IMPORT_MAX_SIDE.toDouble() / maxOf(width, height),
        sqrt(DOCUMENT_IMPORT_MAX_PIXELS.toDouble() / (width.toDouble() * height)),
    )
    return (width * scale).toInt().coerceAtLeast(1) to
        (height * scale).toInt().coerceAtLeast(1)
}

internal fun hasPdfImportHeader(file: File): Boolean {
    val header = ByteArray(minOf(file.length(), 1024L).toInt())
    DataInputStream(file.inputStream()).use { it.readFully(header) }
    return header.toString(Charsets.ISO_8859_1).contains("%PDF-")
}

internal suspend fun <T> withImportedDocumentPages(
    context: Context,
    uris: List<Uri>,
    action: DocumentImportAction,
    consume: (List<Uri>) -> T,
): T {
    if (uris.size !in 1..MAX_SCAN_PAGES) {
        throw DocumentImportException(DocumentImportFailureReason.TooManyPages)
    }
    if (uris.any { it.scheme != "content" || it.authority.isNullOrBlank() }) {
        throw DocumentImportException(DocumentImportFailureReason.Unreadable)
    }
    val directory = Files.createTempDirectory(
        providerInputStagingDirectory(context.cacheDir).toPath(), ".import_",
    ).toFile()
    try {
        var remainingBytes = MAX_DOCUMENT_IMPORT_BYTES
        val pages = mutableListOf<File>()
        for ((index, uri) in uris.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (pages.size >= MAX_SCAN_PAGES) {
                throw DocumentImportException(DocumentImportFailureReason.TooManyPages)
            }
            if (remainingBytes <= 0L) {
                throw DocumentImportException(DocumentImportFailureReason.TooLarge)
            }
            val source = File(directory, "input-$index")
            val copied = copyProviderInput(
                context.contentResolver, uri, source,
                minOf(MAX_SCAN_SOURCE_PAGE_BYTES, remainingBytes),
            )
            if (copied == 0L) throw DocumentImportException(DocumentImportFailureReason.Unreadable)
            remainingBytes -= copied
            val pdf = hasPdfImportHeader(source)
            if ((pdf && action == DocumentImportAction.ImagesToPdf) ||
                (!pdf && action == DocumentImportAction.PdfToImages)
            ) throw DocumentImportException(DocumentImportFailureReason.Unreadable)
            if (pdf) {
                val output = File(directory, "pdf-$index")
                if (!output.mkdir()) throw IOException("PDF import directory could not be created")
                pages += renderImportedPdf(context, source, output, MAX_SCAN_PAGES - pages.size)
            } else {
                val output = File(directory, "image-$index.jpg")
                renderImportedImage(source, output)
                pages += output
            }
        }
        currentCoroutineContext().ensureActive()
        return consume(pages.map(Uri::fromFile))
    } finally {
        deleteTreeWithoutFollowingLinks(directory)
    }
}

internal suspend fun renderImportedImage(source: File, destination: File) {
    currentCoroutineContext().ensureActive()
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
        if (!validOriginalImageDimensions(info.size.width, info.size.height)) {
            throw IOException("Imported image dimensions exceed the safety limit")
        }
        val (width, height) = documentImportDimensions(info.size.width, info.size.height)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = true
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        decoder.setTargetSize(width, height)
    }
    try {
        currentCoroutineContext().ensureActive()
        // Fill behind transparent artwork in place, avoiding another full-page bitmap.
        Canvas(bitmap).drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER)
        scanCacheOutput(destination).use { output ->
            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, output)) {
                throw IOException("Imported image could not be encoded")
            }
            output.fd.sync()
        }
        currentCoroutineContext().ensureActive()
    } finally {
        bitmap.recycle()
    }
}
