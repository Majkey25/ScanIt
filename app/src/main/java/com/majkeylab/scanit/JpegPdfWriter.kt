package com.majkeylab.scanit

import java.io.BufferedOutputStream
import java.io.File
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

internal data class JpegPdfPrintLayout(
    val pageWidth: Int,
    val pageHeight: Int,
    val contentLeft: Int,
    val contentTop: Int,
    val contentWidth: Int,
    val contentHeight: Int,
) {
    init {
        require(pageWidth > 0 && pageHeight > 0) { "Print paper dimensions must be positive" }
        require(contentLeft >= 0 && contentTop >= 0 && contentWidth > 0 && contentHeight > 0 &&
            contentWidth <= pageWidth - contentLeft && contentHeight <= pageHeight - contentTop
        ) { "Print content must fit inside the paper" }
    }
}

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
            scanCacheOutput(staging.toFile()).use { file ->
                BufferedOutputStream(file).use { stream ->
                    writeJpegPdf(JpegPdfOutput(stream), pages.size, isCancelled) { pages[it] }
                    stream.flush()
                    file.fd.sync()
                }
            }
            throwIfJpegPdfCancelled(isCancelled)
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

    fun writeForPrint(
        output: OutputStream,
        pageCount: Int,
        layout: JpegPdfPrintLayout,
        isCancelled: () -> Boolean,
        renderPage: (Int) -> JpegPdfPage,
    ) {
        writeJpegPdf(JpegPdfOutput(output), pageCount, isCancelled, layout) { index ->
            renderPage(index).also(::validateJpegPage)
        }
    }
}

internal fun publishStagedFileNoReplace(
    staging: Path,
    target: Path,
) {
    moveScanCacheFile(staging, target)
}

private fun writeJpegPdf(
    output: JpegPdfOutput,
    pageCount: Int,
    isCancelled: () -> Boolean,
    printLayout: JpegPdfPrintLayout? = null,
    pageAt: (Int) -> JpegPdfPage,
) {
    val objectCount = jpegPdfObjectCount(pageCount)
    throwIfJpegPdfCancelled(isCancelled)
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
    val pageIndexes = 0 until pageCount
    val kids = pageIndexes.joinToString(" ") { "${jpegPageObject(it)} 0 R" }
    output.ascii("<< /Type /Pages /Kids [$kids] /Count $pageCount >>\nendobj\n")
    pageIndexes.forEach { index ->
        throwIfJpegPdfCancelled(isCancelled)
        writeJpegPage(output, offsets, index, pageAt(index), isCancelled, printLayout)
    }
    throwIfJpegPdfCancelled(isCancelled)

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
    printLayout: JpegPdfPrintLayout?,
) {
    val pageObject = jpegPageObject(index)
    val imageObject = pageObject + 1
    val imageLengthObject = pageObject + 2
    val contentObject = pageObject + 3
    val contentLengthObject = pageObject + 4
    val pageWidth = printLayout?.pageWidth?.toString() ?: pdfPointsAt300Dpi(page.physicalWidthPixels)
    val pageHeight = printLayout?.pageHeight?.toString() ?: pdfPointsAt300Dpi(page.physicalHeightPixels)
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
    if (printLayout == null) {
        output.ascii("q\n$pageWidth 0 0 $pageHeight 0 0 cm\n/Im0 Do\nQ\n")
    } else {
        val placement = fitRect(page.width, page.height, printLayout.contentWidth, printLayout.contentHeight)
        val width = (placement.right - placement.left).toBigDecimal().toPlainString()
        val height = (placement.bottom - placement.top).toBigDecimal().toPlainString()
        val x = (printLayout.contentLeft + placement.left).toBigDecimal().toPlainString()
        // PDF measures vertical coordinates from the bottom of the sheet.
        val y = (printLayout.pageHeight - printLayout.contentTop - placement.bottom).toBigDecimal().toPlainString()
        output.ascii("q\n1 g\n0 0 $pageWidth $pageHeight re f\n$width 0 0 $height $x $y cm\n/Im0 Do\nQ\n")
    }
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
        validateJpegPage(page)
    }
}

private fun validateJpegPage(page: JpegPdfPage) {
    require(page.width > 0 && page.height > 0) { "Page dimensions must be positive" }
    require(page.physicalWidthPixels > 0 && page.physicalHeightPixels > 0) {
        "Physical page dimensions must be positive"
    }
    require(page.width <= MAX_ORIGINAL_IMAGE_DIMENSION && page.height <= MAX_ORIGINAL_IMAGE_DIMENSION) {
        "Page dimensions exceed the PDF bound"
    }
    require(
        page.physicalWidthPixels <= MAX_ORIGINAL_IMAGE_DIMENSION &&
            page.physicalHeightPixels <= MAX_ORIGINAL_IMAGE_DIMENSION,
    ) { "Physical page dimensions exceed the PDF bound" }
    require(page.width.toLong() * page.height <= MAX_ORIGINAL_IMAGE_PIXELS) {
        "Page pixel count exceeds the PDF bound"
    }
    require(page.file.isFile && page.file.length() > 0L) { "JPEG page is missing or empty" }
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
