package com.majkeylab.scanit

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** [readRow] receives a white row; set a byte to zero for a black pixel. */
internal class BitonalPdfPage(
    val width: Int,
    val height: Int,
    internal val readRow: (row: Int, grayscale: ByteArray) -> Unit,
)

internal object BitonalPdfWriter {
    fun write(output: File, pages: List<BitonalPdfPage>) {
        validatePages(pages)
        val target = output.absoluteFile
        val parent = requireNotNull(target.parentFile) { "PDF destination must have a parent" }
        require(parent.isDirectory) { "PDF destination directory does not exist" }
        require(!target.exists()) { "PDF destination already exists" }
        val staging = Files.createTempFile(parent.toPath(), ".scanit-pdf-", ".part")
        var failure: Throwable? = null
        try {
            BufferedOutputStream(FileOutputStream(staging.toFile())).use { stream ->
                writePdf(PdfOutput(stream), pages)
            }
            Files.createLink(target.toPath(), staging)
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

    private fun writePdf(output: PdfOutput, pages: List<BitonalPdfPage>) {
        val objectCount = bitonalPdfObjectCount(pages.size)
        val offsets = LongArray(Math.addExact(objectCount, 1))
        output.ascii("%PDF-1.4\n")
        output.write(
            byteArrayOf('%'.code.toByte(), 0xe2.toByte(), 0xe3.toByte(), 0xcf.toByte(), 0xd3.toByte(), '\n'.code.toByte()),
        )

        output.objectStart(CATALOG_OBJECT, offsets)
        output.ascii("<< /Type /Catalog /Pages $PAGES_OBJECT 0 R >>\nendobj\n")

        output.objectStart(PAGES_OBJECT, offsets)
        val kids = pages.indices.joinToString(" ") { "${pageObject(it)} 0 R" }
        output.ascii("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>\nendobj\n")

        pages.forEachIndexed { index, page ->
            writePage(output, offsets, index, page)
        }

        val xrefOffset = output.bytesWritten
        output.ascii("xref\n0 ${objectCount + 1}\n")
        output.ascii("0000000000 65535 f \n")
        (1..objectCount).forEach { objectNumber ->
            output.ascii(pdfXrefEntry(offsets[objectNumber]))
        }
        output.ascii(
            "trailer\n<< /Size ${objectCount + 1} /Root $CATALOG_OBJECT 0 R >>\n" +
                "startxref\n$xrefOffset\n%%EOF\n",
        )
    }

    private fun writePage(
        output: PdfOutput,
        offsets: LongArray,
        index: Int,
        page: BitonalPdfPage,
    ) {
        val pageObject = pageObject(index)
        val imageObject = pageObject + 1
        val imageLengthObject = pageObject + 2
        val contentObject = pageObject + 3
        val contentLengthObject = pageObject + 4

        output.objectStart(pageObject, offsets)
        output.ascii(
            "<< /Type /Page /Parent $PAGES_OBJECT 0 R /MediaBox [0 0 ${page.width} ${page.height}] " +
                "/Resources << /XObject << /Im0 $imageObject 0 R >> >> " +
                "/Contents $contentObject 0 R >>\nendobj\n",
        )

        output.objectStart(imageObject, offsets)
        output.ascii(
            "<< /Type /XObject /Subtype /Image /Width ${page.width} /Height ${page.height} " +
                "/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /FlateDecode " +
                "/Length $imageLengthObject 0 R >>\nstream\n",
        )
        val imageStart = output.bytesWritten
        writeCompressedRows(output, page)
        val imageLength = output.bytesWritten - imageStart
        output.ascii("\nendstream\nendobj\n")
        output.numberObject(imageLengthObject, imageLength, offsets)

        output.objectStart(contentObject, offsets)
        output.ascii("<< /Length $contentLengthObject 0 R >>\nstream\n")
        val contentStart = output.bytesWritten
        output.ascii("q\n${page.width} 0 0 ${page.height} 0 0 cm\n/Im0 Do\nQ\n")
        val contentLength = output.bytesWritten - contentStart
        output.ascii("endstream\nendobj\n")
        output.numberObject(contentLengthObject, contentLength, offsets)
    }

    private fun writeCompressedRows(output: OutputStream, page: BitonalPdfPage) {
        val grayscale = ByteArray(page.width)
        val packed = ByteArray((page.width.toLong() + 7L).div(8L).toInt())
        val deflater = Deflater(Deflater.BEST_COMPRESSION, false)
        try {
            val compressed = DeflaterOutputStream(output, deflater, COMPRESSION_BUFFER_SIZE)
            repeat(page.height) { row ->
                grayscale.fill(WHITE)
                page.readRow(row, grayscale)
                packRow(grayscale, packed)
                compressed.write(packed)
            }
            compressed.finish()
        } finally {
            deflater.end()
        }
    }

    private fun packRow(grayscale: ByteArray, packed: ByteArray) {
        packed.fill(0xff.toByte())
        grayscale.indices.forEach { x ->
            if (grayscale[x] == BLACK) {
                val byteIndex = x ushr 3
                val mask = 0x80 ushr (x and 7)
                packed[byteIndex] = (packed[byteIndex].toInt() and mask.inv()).toByte()
            }
        }
    }

    private fun pageObject(index: Int): Int = FIRST_PAGE_OBJECT + index * OBJECTS_PER_PAGE

    private fun validatePages(pages: List<BitonalPdfPage>) {
        bitonalPdfObjectCount(pages.size)
        pages.forEach { page ->
            require(page.width > 0) { "Page width must be positive" }
            require(page.height > 0) { "Page height must be positive" }
            require(page.width.toLong() * page.height <= MAX_PAGE_PIXELS) {
                "Page pixel count exceeds $MAX_PAGE_PIXELS"
            }
            require(page.width <= MAX_PAGE_SIDE) { "Page width exceeds $MAX_PAGE_SIDE pixels" }
            require(page.height <= MAX_PAGE_SIDE) { "Page height exceeds $MAX_PAGE_SIDE pixels" }
        }
    }
}

internal fun bitonalPdfObjectCount(pageCount: Int): Int {
    require(pageCount > 0) { "PDF must contain at least one page" }
    require(pageCount <= MAX_PAGE_COUNT) { "PDF page count exceeds $MAX_PAGE_COUNT" }
    return BASE_OBJECT_COUNT + pageCount * OBJECTS_PER_PAGE
}

internal fun pdfXrefEntry(offset: Long): String {
    require(offset in 0..MAX_XREF_OFFSET) { "PDF object offset exceeds classic xref limits" }
    return "${offset.toString().padStart(10, '0')} 00000 n \n"
}

private class PdfOutput(output: OutputStream) : FilterOutputStream(output) {
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

private const val BLACK: Byte = 0
private const val WHITE: Byte = 1
private const val CATALOG_OBJECT = 1
private const val PAGES_OBJECT = 2
private const val BASE_OBJECT_COUNT = 2
private const val FIRST_PAGE_OBJECT = 3
private const val OBJECTS_PER_PAGE = 5
private const val COMPRESSION_BUFFER_SIZE = 8192
private const val OFFSET_TABLE_BUDGET_BYTES = 1024 * 1024
private const val OFFSET_BYTES = 8
private const val MAX_PAGE_COUNT =
    (OFFSET_TABLE_BUDGET_BYTES / OFFSET_BYTES - BASE_OBJECT_COUNT - 1) / OBJECTS_PER_PAGE
private const val MAX_PAGE_SIDE = 3508
private const val MAX_XREF_OFFSET = 9_999_999_999L
private val MAX_PAGE_PIXELS = MAX_PAGE_SIDE.toLong() * MAX_PAGE_SIDE
