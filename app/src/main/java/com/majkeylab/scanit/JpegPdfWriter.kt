package com.majkeylab.scanit

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

internal data class JpegPdfPage(
    val file: File,
    val width: Int,
    val height: Int,
    val physicalWidthPixels: Int = width,
    val physicalHeightPixels: Int = height,
)

internal object JpegPdfWriter {
    fun write(
        output: File,
        pages: List<JpegPdfPage>,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ) {
        validateJpegPages(pages, isCancelled)
        throwIfJpegPdfCancelled(isCancelled)
        val target = output.absoluteFile
        val parent = requireNotNull(target.parentFile) { "PDF destination must have a parent" }
        require(parent.isDirectory) { "PDF destination directory does not exist" }
        require(!target.exists()) { "PDF destination already exists" }
        val staging = Files.createTempFile(parent.toPath(), ".scanit-pdf-", ".part")
        var failure: Throwable? = null
        try {
            BufferedOutputStream(FileOutputStream(staging.toFile())).use { stream ->
                writeJpegPdf(JpegPdfOutput(stream), pages, isCancelled)
            }
            publishStagedFileNoReplace(staging, target.toPath())
        } catch (throwable: Throwable) {
            failure = throwable
            throw throwable
        } finally {
            try {
                Files.deleteIfExists(staging)
            } catch (cleanupFailure: Throwable) {
                failure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
            }
        }
    }
}

internal fun publishStagedFileNoReplace(
    staging: Path,
    target: Path,
    deleteIfExists: (Path) -> Boolean = Files::deleteIfExists,
) {
    var published = false
    try {
        Files.createLink(target, staging)
        published = true
        deleteIfExists(staging)
    } catch (failure: Throwable) {
        if (published) {
            try {
                if (
                    Files.exists(target) &&
                    (!Files.exists(staging) || Files.isSameFile(target, staging))
                ) {
                    deleteIfExists(target)
                }
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
            }
        }
        throw failure
    }
}

private fun writeJpegPdf(
    output: JpegPdfOutput,
    pages: List<JpegPdfPage>,
    isCancelled: () -> Boolean,
) {
    val objectCount = jpegPdfObjectCount(pages.size)
    val offsets = LongArray(Math.addExact(objectCount, 1))
    output.ascii("%PDF-1.4\n")
    output.write(
        byteArrayOf(
            '%'.code.toByte(),
            0xe2.toByte(),
            0xe3.toByte(),
            0xcf.toByte(),
            0xd3.toByte(),
            '\n'.code.toByte(),
        ),
    )
    output.objectStart(JPEG_CATALOG_OBJECT, offsets)
    output.ascii("<< /Type /Catalog /Pages $JPEG_PAGES_OBJECT 0 R >>\nendobj\n")
    output.objectStart(JPEG_PAGES_OBJECT, offsets)
    val kids = pages.indices.joinToString(" ") { "${jpegPageObject(it)} 0 R" }
    output.ascii("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>\nendobj\n")
    pages.forEachIndexed { index, page ->
        throwIfJpegPdfCancelled(isCancelled)
        writeJpegPage(output, offsets, index, page, isCancelled)
    }

    val xrefOffset = output.bytesWritten
    output.ascii("xref\n0 ${objectCount + 1}\n")
    output.ascii("0000000000 65535 f \n")
    (1..objectCount).forEach { output.ascii(pdfXrefEntry(offsets[it])) }
    output.ascii(
        "trailer\n<< /Size ${objectCount + 1} /Root $JPEG_CATALOG_OBJECT 0 R >>\n" +
            "startxref\n$xrefOffset\n%%EOF\n",
    )
}

private fun writeJpegPage(
    output: JpegPdfOutput,
    offsets: LongArray,
    index: Int,
    page: JpegPdfPage,
    isCancelled: () -> Boolean,
) {
    val pageObject = jpegPageObject(index)
    val imageObject = pageObject + 1
    val imageLengthObject = pageObject + 2
    val contentObject = pageObject + 3
    val contentLengthObject = pageObject + 4
    val pageWidth = pdfPointsAt300Dpi(page.physicalWidthPixels)
    val pageHeight = pdfPointsAt300Dpi(page.physicalHeightPixels)
    output.objectStart(pageObject, offsets)
    output.ascii(
        "<< /Type /Page /Parent $JPEG_PAGES_OBJECT 0 R " +
            "/MediaBox [0 0 $pageWidth $pageHeight] " +
            "/Resources << /XObject << /Im0 $imageObject 0 R >> >> " +
            "/Contents $contentObject 0 R >>\nendobj\n",
    )
    output.objectStart(imageObject, offsets)
    output.ascii(
        "<< /Type /XObject /Subtype /Image /Width ${page.width} /Height ${page.height} " +
            "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode " +
            "/Length $imageLengthObject 0 R >>\nstream\n",
    )
    val imageStart = output.bytesWritten
    val expectedLength = page.file.length()
    val expectedModified = page.file.lastModified()
    val copied = copyJpegPage(page.file, output, isCancelled)
    if (copied != expectedLength || page.file.length() != expectedLength) {
        throw IOException("JPEG page changed while the PDF was written")
    }
    if (page.file.lastModified() != expectedModified) {
        throw IOException("JPEG page changed while the PDF was written")
    }
    val imageLength = output.bytesWritten - imageStart
    output.ascii("\nendstream\nendobj\n")
    output.numberObject(imageLengthObject, imageLength, offsets)
    output.objectStart(contentObject, offsets)
    output.ascii("<< /Length $contentLengthObject 0 R >>\nstream\n")
    val contentStart = output.bytesWritten
    output.ascii("q\n$pageWidth 0 0 $pageHeight 0 0 cm\n/Im0 Do\nQ\n")
    val contentLength = output.bytesWritten - contentStart
    output.ascii("endstream\nendobj\n")
    output.numberObject(contentLengthObject, contentLength, offsets)
}

private fun copyJpegPage(
    file: File,
    output: OutputStream,
    isCancelled: () -> Boolean,
): Long {
    val buffer = ByteArray(JPEG_COPY_BUFFER_SIZE)
    var copied = 0L
    file.inputStream().use { input ->
        while (true) {
            throwIfJpegPdfCancelled(isCancelled)
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            copied += count
        }
    }
    return copied
}

private fun throwIfJpegPdfCancelled(isCancelled: () -> Boolean) {
    if (isCancelled()) throw CancellationException("PDF build cancelled")
}

private fun validateJpegPages(
    pages: List<JpegPdfPage>,
    isCancelled: () -> Boolean,
) {
    jpegPdfObjectCount(pages.size)
    pages.forEach { page ->
        throwIfJpegPdfCancelled(isCancelled)
        require(page.width > 0 && page.height > 0) { "Page dimensions must be positive" }
        require(page.physicalWidthPixels > 0 && page.physicalHeightPixels > 0) {
            "Physical page dimensions must be positive"
        }
        require(page.width <= JPEG_MAX_PAGE_SIDE && page.height <= JPEG_MAX_PAGE_SIDE) {
            "Page dimensions exceed the PDF bound"
        }
        require(
            page.physicalWidthPixels <= JPEG_MAX_PAGE_SIDE &&
                page.physicalHeightPixels <= JPEG_MAX_PAGE_SIDE,
        ) { "Physical page dimensions exceed the PDF bound" }
        require(page.width.toLong() * page.height <= JPEG_MAX_PAGE_PIXELS) {
            "Page pixel count exceeds the PDF bound"
        }
        require(page.file.isFile && page.file.length() > 0L) { "JPEG page is missing or empty" }
    }
}

private fun jpegPdfObjectCount(pageCount: Int): Int {
    require(pageCount > 0) { "PDF must contain at least one page" }
    require(pageCount <= MAX_SCAN_PAGES) { "PDF page count exceeds $MAX_SCAN_PAGES" }
    return JPEG_BASE_OBJECT_COUNT + pageCount * JPEG_OBJECTS_PER_PAGE
}

private fun jpegPageObject(index: Int): Int =
    JPEG_FIRST_PAGE_OBJECT + index * JPEG_OBJECTS_PER_PAGE

private class JpegPdfOutput(output: OutputStream) : FilterOutputStream(output) {
    var bytesWritten: Long = 0
        private set

    override fun write(value: Int) {
        out.write(value)
        bytesWritten++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        out.write(bytes, offset, length)
        bytesWritten += length.toLong()
    }

    fun ascii(value: String) {
        write(value.toByteArray(StandardCharsets.US_ASCII))
    }

    fun objectStart(objectNumber: Int, offsets: LongArray) {
        offsets[objectNumber] = bytesWritten
        ascii("$objectNumber 0 obj\n")
    }

    fun numberObject(objectNumber: Int, value: Long, offsets: LongArray) {
        objectStart(objectNumber, offsets)
        ascii("$value\nendobj\n")
    }
}

private const val JPEG_CATALOG_OBJECT = 1
private const val JPEG_PAGES_OBJECT = 2
private const val JPEG_BASE_OBJECT_COUNT = 2
private const val JPEG_FIRST_PAGE_OBJECT = 3
private const val JPEG_OBJECTS_PER_PAGE = 5
private const val JPEG_COPY_BUFFER_SIZE = 8_192
private const val JPEG_MAX_PAGE_SIDE = 6_000
private const val JPEG_MAX_PAGE_PIXELS = 12_000_000L
