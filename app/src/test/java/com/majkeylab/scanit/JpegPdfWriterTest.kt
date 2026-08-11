package com.majkeylab.scanit

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegPdfWriterTest {
    @Test
    fun writesJpegBytesIntoAParseableDctImageObject() {
        val root = Files.createTempDirectory("scanit-jpeg-pdf").toFile()
        try {
            val jpeg = File(root, "page.jpg").apply { writeBytes(JPEG_BYTES) }
            val pdf = File(root, "scan.pdf")

            JpegPdfWriter.write(pdf, listOf(JpegPdfPage(jpeg, width = 32, height = 48)))

            val bytes = pdf.readBytes()
            val text = bytes.toString(Charsets.ISO_8859_1)
            assertTrue(text.startsWith("%PDF-1.4\n"))
            assertTrue(text.contains("/Width 32 /Height 48"))
            assertTrue(text.contains("/MediaBox [0 0 7.68 11.52]"))
            assertTrue(text.contains("q\n7.68 0 0 11.52 0 0 cm\n/Im0 Do\nQ\n"))
            assertTrue(text.contains("/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode"))
            assertTrue(text.contains("\n${JPEG_BYTES.size}\nendobj\n"))
            assertArrayEquals(JPEG_BYTES, bytes.copyOfRange(bytes.indexOfSequence(JPEG_BYTES), bytes.indexOfSequence(JPEG_BYTES) + JPEG_BYTES.size))
            val startXref = text.substringAfterLast("startxref\n").substringBefore('\n').toInt()
            assertTrue(text.substring(startXref).startsWith("xref\n"))
        } finally {
            assertTrue(root.deleteRecursively())
        }
    }

    @Test
    fun downsampledImageKeepsOriginalPhysicalPageSize() {
        val root = Files.createTempDirectory("scanit-jpeg-pdf-physical").toFile()
        try {
            val jpeg = File(root, "page.jpg").apply { writeBytes(JPEG_BYTES) }
            val pdf = File(root, "scan.pdf")

            JpegPdfWriter.write(
                pdf,
                listOf(
                    JpegPdfPage(
                        jpeg,
                        width = 16,
                        height = 24,
                        physicalWidthPixels = 32,
                        physicalHeightPixels = 48,
                    ),
                ),
            )

            val text = pdf.readText(Charsets.ISO_8859_1)
            assertTrue(text.contains("/Width 16 /Height 24"))
            assertTrue(text.contains("/MediaBox [0 0 7.68 11.52]"))
            assertTrue(text.contains("q\n7.68 0 0 11.52 0 0 cm\n/Im0 Do\nQ\n"))
        } finally {
            assertTrue(root.deleteRecursively())
        }
    }

    @Test
    fun preservesExistingDestinationAndCleansStagingFile() {
        val root = Files.createTempDirectory("scanit-jpeg-pdf-existing").toFile()
        try {
            val jpeg = File(root, "page.jpg").apply { writeBytes(JPEG_BYTES) }
            val pdf = File(root, "scan.pdf").apply { writeText("sentinel") }

            assertThrows(Exception::class.java) {
                JpegPdfWriter.write(pdf, listOf(JpegPdfPage(jpeg, width = 1, height = 1)))
            }

            assertEquals("sentinel", pdf.readText())
            assertEquals(emptyList<String>(), root.listFiles()!!.map(File::getName).filter { it.startsWith(".scanit-pdf-") })
        } finally {
            assertTrue(root.deleteRecursively())
        }
    }

    @Test
    fun publicationRollsBackTargetWhenStagingCleanupFails() {
        val root = Files.createTempDirectory("scanit-pdf-publication").toFile()
        try {
            val staging = File(root, "staging.part").apply { writeText("complete") }
            val target = File(root, "scan.pdf")

            val failure =
                assertThrows(IOException::class.java) {
                    publishStagedFileNoReplace(
                        staging.toPath(),
                        target.toPath(),
                        deleteIfExists = { path ->
                            if (path == staging.toPath()) throw IOException("cleanup failed")
                            Files.deleteIfExists(path)
                        },
                    )
                }

            assertEquals("cleanup failed", failure.message)
            assertFalse(target.exists())
            assertTrue(staging.isFile)
        } finally {
            assertTrue(root.deleteRecursively())
        }
    }

    @Test
    fun cancellationDuringJpegCopyDoesNotPublishOrLeaveStaging() {
        val root = Files.createTempDirectory("scanit-jpeg-pdf-cancel").toFile()
        try {
            val jpeg = File(root, "page.jpg").apply { writeBytes(ByteArray(32_000) { 0x41 }) }
            val pdf = File(root, "scan.pdf")
            var checks = 0

            assertThrows(CancellationException::class.java) {
                JpegPdfWriter.write(
                    pdf,
                    listOf(JpegPdfPage(jpeg, width = 100, height = 100)),
                    isCancelled = { ++checks >= 5 },
                )
            }

            assertTrue(checks >= 5)
            assertFalse(pdf.exists())
            assertTrue(root.listFiles()!!.none { it.name.startsWith(".scanit-pdf-") })
        } finally {
            assertTrue(root.deleteRecursively())
        }
    }

    @Test
    fun rejectsMissingEmptyAndInvalidPagesBeforeCreatingOutput() {
        val root = Files.createTempDirectory("scanit-jpeg-pdf-invalid").toFile()
        try {
            val empty = File(root, "empty.jpg").apply { createNewFile() }
            val output = File(root, "scan.pdf")

            assertThrows(IllegalArgumentException::class.java) {
                JpegPdfWriter.write(output, emptyList())
            }
            assertThrows(IllegalArgumentException::class.java) {
                JpegPdfWriter.write(output, listOf(JpegPdfPage(empty, width = 1, height = 1)))
            }
            assertThrows(IllegalArgumentException::class.java) {
                JpegPdfWriter.write(output, listOf(JpegPdfPage(File(root, "missing.jpg"), 1, 1)))
            }
            assertTrue(!output.exists())
        } finally {
            assertTrue(root.deleteRecursively())
        }
    }

    private fun ByteArray.indexOfSequence(sequence: ByteArray): Int {
        for (index in 0..size - sequence.size) {
            if (sequence.indices.all { this[index + it] == sequence[it] }) return index
        }
        throw AssertionError("Embedded JPEG was not found")
    }

    private companion object {
        val JPEG_BYTES = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3, 0xff.toByte(), 0xd9.toByte())
    }
}
