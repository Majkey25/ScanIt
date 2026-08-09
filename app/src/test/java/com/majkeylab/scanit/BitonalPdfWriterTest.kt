package com.majkeylab.scanit

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.zip.InflaterInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BitonalPdfWriterTest {
    @Test
    fun oddWidthRowsArePackedMostSignificantBitFirstWithWhitePadding() =
        withTempDirectory { directory ->
            val output = File(directory, "odd.pdf")

            BitonalPdfWriter.write(
                output,
                listOf(
                    BitonalPdfPage(width = 3, height = 1) { _, pixels ->
                        pixels[0] = BLACK
                        pixels[1] = WHITE
                        pixels[2] = BLACK
                    },
                ),
            )

            assertArrayEquals(byteArrayOf(0x5f), inflatedImage(output.readBytes(), 4, 5))
        }

    @Test
    fun whiteBlackAndCheckerboardRowsKeepExactOneBitSamples() =
        withTempDirectory { directory ->
            val output = File(directory, "patterns.pdf")

            BitonalPdfWriter.write(
                output,
                listOf(
                    BitonalPdfPage(width = 9, height = 3) { row, pixels ->
                        when (row) {
                            0 -> pixels.fill(WHITE)
                            1 -> pixels.fill(BLACK)
                            else -> pixels.indices.forEach { x ->
                                pixels[x] = if (x % 2 == 0) BLACK else WHITE
                            }
                        }
                    },
                ),
            )

            assertArrayEquals(
                byteArrayOf(
                    0xff.toByte(),
                    0xff.toByte(),
                    0x00,
                    0x7f,
                    0x55,
                    0x7f,
                ),
                inflatedImage(output.readBytes(), 4, 5),
            )
        }

    @Test
    fun onePageUsesFixedBitonalImageAndIndirectStreamLengths() =
        withTempDirectory { directory ->
            val output = File(directory, "one.pdf")

            BitonalPdfWriter.write(
                output,
                listOf(BitonalPdfPage(width = 8, height = 2) { _, pixels -> pixels.fill(WHITE) }),
            )

            val pdf = output.readBytes()
            val text = pdf.toString(StandardCharsets.ISO_8859_1)
            assertTrue(text.startsWith("%PDF-1.4\n"))
            assertArrayEquals(
                "%PDF-1.4\n%".toByteArray(StandardCharsets.US_ASCII) +
                    byteArrayOf(0xe2.toByte(), 0xe3.toByte(), 0xcf.toByte(), 0xd3.toByte(), '\n'.code.toByte()),
                pdf.copyOfRange(0, 15),
            )
            assertTrue(text.contains("/ColorSpace /DeviceGray"))
            assertTrue(text.contains("/BitsPerComponent 1"))
            assertTrue(text.contains("/Filter /FlateDecode"))
            assertTrue(text.contains("/Length 5 0 R"))
            assertTrue(text.contains("/Length 7 0 R"))
            assertEquals(streamLength(pdf, 4, 5), objectNumber(text, 5))
            assertEquals(streamLength(pdf, 6, 7), objectNumber(text, 7))
            assertEquals("q\n8 0 0 2 0 0 cm\n/Im0 Do\nQ\n", stream(pdf, 6, 7).decodeToString())
        }

    @Test
    fun twoPagesKeepMixedDimensionsAndSeparateImageStreams() =
        withTempDirectory { directory ->
            val output = File(directory, "two.pdf")

            BitonalPdfWriter.write(
                output,
                listOf(
                    BitonalPdfPage(width = 8, height = 2) { row, pixels ->
                        pixels.fill(if (row == 0) BLACK else WHITE)
                    },
                    BitonalPdfPage(width = 3, height = 1) { _, pixels ->
                        pixels.fill(WHITE)
                    },
                ),
            )

            val pdf = output.readBytes()
            val text = pdf.toString(StandardCharsets.ISO_8859_1)
            assertTrue(text.contains("/Kids [3 0 R 8 0 R] /Count 2"))
            assertTrue(text.contains("/MediaBox [0 0 8 2]"))
            assertTrue(text.contains("/MediaBox [0 0 3 1]"))
            assertArrayEquals(byteArrayOf(0x00, 0xff.toByte()), inflatedImage(pdf, 4, 5))
            assertArrayEquals(byteArrayOf(0xff.toByte()), inflatedImage(pdf, 9, 10))
        }

    @Test
    fun xrefOffsetsSizeAndStartxrefPointToExactBytePositions() =
        withTempDirectory { directory ->
            val output = File(directory, "xref.pdf")
            BitonalPdfWriter.write(
                output,
                listOf(BitonalPdfPage(width = 1, height = 1) { _, pixels -> pixels[0] = WHITE }),
            )

            val pdf = output.readBytes()
            val text = pdf.toString(StandardCharsets.ISO_8859_1)
            val xrefOffset = text.indexOf("xref\n")
            assertTrue(xrefOffset > 0)
            assertEquals(xrefOffset.toLong(), startxref(text))
            assertTrue(text.contains("xref\n0 8\n"))
            assertTrue(text.contains("trailer\n<< /Size 8 /Root 1 0 R >>"))

            val lines = text.substring(xrefOffset).lineSequence().toList()
            assertEquals("0000000000 65535 f ", lines[2])
            (1..7).forEach { objectNumber ->
                val offset = lines[2 + objectNumber].substring(0, 10).toLong()
                assertEquals("$objectNumber 0 obj\n", text.substring(offset.toInt()).takeWhileIncludingNewline())
            }
        }

    @Test
    fun xrefEntriesUseLongOffsets() {
        assertEquals("3000000000 00000 n \n", pdfXrefEntry(3_000_000_000L))
    }

    @Test
    fun rowFailureRemovesStagingFileAndDoesNotPublishOutput() =
        withTempDirectory { directory ->
            val output = File(directory, "failed.pdf")

            try {
                BitonalPdfWriter.write(
                    output,
                    listOf(
                        BitonalPdfPage(width = 8, height = 3) { row, pixels ->
                            if (row == 1) throw IOException("injected row failure")
                            pixels.fill(WHITE)
                        },
                    ),
                )
                fail("Expected row failure")
            } catch (failure: IOException) {
                assertEquals("injected row failure", failure.message)
            }

            assertFalse(output.exists())
            assertTrue(directory.listFiles()?.isEmpty() == true)
        }

    @Test
    fun destinationCreatedWhileWritingIsNotOverwritten() =
        withTempDirectory { directory ->
            val output = File(directory, "raced.pdf")
            val existing = "existing".toByteArray()

            try {
                BitonalPdfWriter.write(
                    output,
                    listOf(
                        BitonalPdfPage(width = 8, height = 1) { _, pixels ->
                            output.writeBytes(existing)
                            pixels.fill(WHITE)
                        },
                    ),
                )
                fail("Expected destination collision")
            } catch (_: FileAlreadyExistsException) {}

            assertArrayEquals(existing, output.readBytes())
            assertEquals(listOf("raced.pdf"), directory.listFiles()?.map(File::getName))
        }

    private fun inflatedImage(pdf: ByteArray, objectNumber: Int, lengthObjectNumber: Int): ByteArray =
        InflaterInputStream(stream(pdf, objectNumber, lengthObjectNumber).inputStream()).use {
            it.readBytes()
        }

    private fun streamLength(pdf: ByteArray, objectNumber: Int, lengthObjectNumber: Int): Int =
        stream(pdf, objectNumber, lengthObjectNumber).size

    private fun stream(pdf: ByteArray, objectNumber: Int, lengthObjectNumber: Int): ByteArray {
        val text = pdf.toString(StandardCharsets.ISO_8859_1)
        val objectStart = text.indexOf("$objectNumber 0 obj\n")
        assertTrue("Missing object $objectNumber", objectStart >= 0)
        val streamStart = text.indexOf("stream\n", objectStart) + "stream\n".length
        assertTrue("Missing stream in object $objectNumber", streamStart >= "stream\n".length)
        val length = objectNumber(text, lengthObjectNumber)
        return pdf.copyOfRange(streamStart, streamStart + length)
    }

    private fun objectNumber(text: String, objectNumber: Int): Int {
        val match = Regex("$objectNumber 0 obj\\n([0-9]+)\\nendobj").find(text)
        assertTrue("Missing number object $objectNumber", match != null)
        return checkNotNull(match).groupValues[1].toInt()
    }

    private fun startxref(text: String): Long {
        val match = Regex("startxref\\n([0-9]+)\\n%%EOF\\n$").find(text)
        assertTrue("Missing startxref", match != null)
        return checkNotNull(match).groupValues[1].toLong()
    }

    private fun String.takeWhileIncludingNewline(): String {
        val newline = indexOf('\n')
        return if (newline < 0) this else substring(0, newline + 1)
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("bitonal-pdf").toFile()
        try {
            block(directory)
        } finally {
            assertTrue(directory.deleteRecursively())
        }
    }

    private companion object {
        const val BLACK: Byte = 0
        const val WHITE: Byte = 1
    }
}
