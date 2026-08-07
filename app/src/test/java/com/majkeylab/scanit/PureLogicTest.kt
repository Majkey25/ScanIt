package com.majkeylab.scanit

import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PureLogicTest {
    @Test
    fun appSettingsDefaultsAreStable() {
        assertEquals(
            AppSettings(
                savePdf = true,
                saveImages = true,
                albumName = "Scan to PDF",
                multipage = true,
                allowGallery = true,
                emailSubject = "Scanned document",
                emailBody = "",
                pdfTreeUri = null,
                aiEnabled = false,
                aiConsent = false,
            ),
            AppSettings(),
        )
    }

    @Test
    fun localizedDefaultEmailSubjectOnlyReplacesSupportedDefaults() {
        val defaults = setOf("Scanned document", "Naskenovaný dokument")

        assertEquals(
            "Scanned document",
            localizedDefaultEmailSubject("Naskenovaný dokument", "Scanned document", defaults),
        )
        assertEquals(
            "Naskenovaný dokument",
            localizedDefaultEmailSubject("Naskenovaný dokument", "Naskenovaný dokument", defaults),
        )
        assertEquals(
            "Invoice 42",
            localizedDefaultEmailSubject("Invoice 42", "Scanned document", defaults),
        )
        assertEquals("", localizedDefaultEmailSubject("", "Scanned document", defaults))
    }

    @Test
    fun scanBaseNameUsesSuppliedClock() {
        val clock = Clock.fixed(Instant.parse("2026-08-06T12:34:56Z"), ZoneOffset.UTC)

        assertEquals("Scan_2026-08-06_12-34-56", scanBaseName(clock))
    }

    @Test
    fun scannerPageLimitOnlyDisablesMultipageCapture() {
        assertEquals(null, scannerPageLimit(multipage = true))
        assertEquals(1, scannerPageLimit(multipage = false))
    }

    @Test
    fun scannerLaunchGateResumesPreparationAndRejectsItsStaleCallback() {
        val gate = ScannerLaunchGate()
        val original = gate.begin(processing = false)!!
        val resumed = gate.resumePreparing(processing = false)!!

        assertEquals(ScannerLaunchStage.Preparing, gate.stage)
        assertEquals(true, resumed > original)
        assertEquals(false, gate.fail(original))
        assertEquals(false, gate.markLaunched(original))
        assertEquals(true, gate.markLaunched(resumed))
        assertEquals(ScannerLaunchStage.Launched, gate.stage)
        assertEquals(null, gate.resumePreparing(processing = false))
    }

    @Test
    fun scannerLaunchGateBlocksNewScannerWhileStorageIsProcessing() {
        val gate = ScannerLaunchGate()

        assertEquals(null, gate.begin(processing = true))
        assertEquals(ScannerLaunchStage.Idle, gate.stage)
    }

    @Test
    fun scannerLaunchFailureResetsAndInvalidatesItsRequest() {
        val gate = ScannerLaunchGate()
        val failed = gate.begin(processing = false)!!

        assertEquals(true, gate.fail(failed))
        assertEquals(ScannerLaunchStage.Idle, gate.stage)
        assertEquals(false, gate.markLaunched(failed))
        assertEquals(true, gate.begin(processing = false)!! > failed)
    }

    @Test
    fun restoredPreparingScannerLaunchCanResume() {
        val gate = ScannerLaunchGate(ScannerLaunchStage.Preparing)

        assertEquals(true, gate.resumePreparing(processing = false) != null)
        assertEquals(ScannerLaunchStage.Preparing, gate.stage)
    }

    @Test
    fun restoredLaunchedScannerWaitsForResultUntilCompleted() {
        val gate = ScannerLaunchGate(ScannerLaunchStage.Launched)

        assertEquals(null, gate.begin(processing = false))
        assertEquals(null, gate.resumePreparing(processing = false))
        gate.complete()
        assertEquals(ScannerLaunchStage.Idle, gate.stage)
        assertEquals(true, gate.begin(processing = false) != null)
    }

    @Test
    fun albumNameIsTrimmedAndBlankFallsBack() {
        assertEquals("Rodinné skeny", normalizeAlbumName("  Rodinné skeny  "))
        assertEquals("Scan to PDF", normalizeAlbumName(" \t "))
    }

    @Test
    fun albumNameRejectsPathSeparators() {
        assertEquals("Scan to PDF", normalizeAlbumName("Skeny/2026"))
        assertEquals("Scan to PDF", normalizeAlbumName("Skeny\\2026"))
    }

    @Test
    fun albumNameRejectsDotPathComponents() {
        assertEquals("Scan to PDF", normalizeAlbumName(" . "))
        assertEquals("Scan to PDF", normalizeAlbumName(".."))
    }

    @Test
    fun albumNameRejectsIsoControlCharacters() {
        assertEquals("Scan to PDF", normalizeAlbumName("\u0000Skeny"))
        assertEquals("Scan to PDF", normalizeAlbumName("Skeny\n"))
        assertEquals("Scan to PDF", normalizeAlbumName("Sken\u0085y"))
    }

    @Test
    fun albumNameIsBoundedTo64Characters() {
        assertEquals("a".repeat(64), normalizeAlbumName("a".repeat(65)))
    }

    @Test
    fun preferenceReadReturnsStoredValue() {
        assertEquals("stored", readPreferenceOrDefault("default") { "stored" })
    }

    @Test
    fun preferenceReadFallsBackForWrongType() {
        assertEquals(true, readPreferenceOrDefault(true) { throw ClassCastException("wrong type") })
    }

    @Test
    fun validGeminiResponseUsesFinalModelOutputImage() {
        val oldBytes =
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x01, 0xff.toByte(), 0xd9.toByte())
        val expected =
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x02, 0xff.toByte(), 0xd9.toByte())
        val old = Base64.getEncoder().encodeToString(oldBytes)
        val current = Base64.getEncoder().encodeToString(expected)
        val response =
            """
            {
              "status": "completed",
              "steps": [
                {"type": "model_output", "content": [
                  {"type": "image", "mime_type": "image/jpeg", "data": "$old"}
                ]},
                {"type": "thought"},
                {"type": "model_output", "content": [
                  {"type": "text", "text": "done"},
                  {"type": "image", "mime_type": "image/jpeg", "data": "$current"}
                ]}
              ]
            }
            """.trimIndent()

        assertArrayEquals(expected, parseGeminiImageResponse(response))
    }

    @Test
    fun geminiResponseWithoutImageIsRejected() {
        val response =
            """
            {"status":"completed","steps":[
              {"type":"model_output","content":[{"type":"text","text":"none"}]}
            ]}
            """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            parseGeminiImageResponse(response)
        }
    }

    @Test
    fun geminiResponseWithInvalidBase64IsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            parseGeminiImageResponse(imageResponse(data = "not base64!"))
        }
    }

    @Test
    fun geminiResponseWithNullImageDataIsRejected() {
        val response =
            """
            {"status":"completed","steps":[
              {"type":"model_output","content":[
                {"type":"image","mime_type":"image/jpeg","data":null}
              ]}
            ]}
            """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            parseGeminiImageResponse(response)
        }
    }

    @Test
    fun geminiResponseWithNonStringImageDataIsRejected() {
        val response =
            """
            {"status":"completed","steps":[
              {"type":"model_output","content":[
                {"type":"image","mime_type":"image/jpeg","data":1234}
              ]}
            ]}
            """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            parseGeminiImageResponse(response)
        }
    }

    @Test
    fun geminiResponseWithBase64NonJpegIsRejected() {
        val data = Base64.getEncoder().encodeToString("not-jpeg".toByteArray())

        assertThrows(IllegalArgumentException::class.java) {
            parseGeminiImageResponse(imageResponse(data = data))
        }
    }

    @Test
    fun geminiResponseWithEmptyImageIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            parseGeminiImageResponse(imageResponse(data = ""))
        }
    }

    @Test
    fun geminiResponseWithWrongMimeIsRejected() {
        val data = Base64.getEncoder().encodeToString("png".toByteArray())

        assertThrows(IllegalArgumentException::class.java) {
            parseGeminiImageResponse(imageResponse(mimeType = "image/png", data = data))
        }
    }

    @Test
    fun incompleteGeminiResponseIsRejected() {
        val data = Base64.getEncoder().encodeToString("jpeg".toByteArray())

        assertThrows(IllegalArgumentException::class.java) {
            parseGeminiImageResponse(imageResponse(status = "in_progress", data = data))
        }
    }

    @Test
    fun scanFileNamesDistinguishOriginalAndAiCopies() {
        val baseName = "Scan_2026-08-06_12-34-56"

        assertEquals("${baseName}_01.jpg", scanPageFileName(baseName, 1))
        assertEquals("${baseName}_12.jpg", scanPageFileName(baseName, 12))
        assertEquals("${baseName}_01_AI.jpg", scanPageFileName(baseName, 1, isAiCopy = true))
        assertEquals("$baseName.pdf", scanPdfFileName(baseName))
        assertEquals("${baseName}_AI.pdf", scanPdfFileName(baseName, isAiCopy = true))
    }

    @Test
    fun scanPageFileNameRejectsInvalidPageNumber() {
        assertThrows(IllegalArgumentException::class.java) {
            scanPageFileName("Scan", 0)
        }
    }

    @Test
    fun aiReviewPageSelectionStaysInsideDocument() {
        assertEquals(0, aiReviewPageIndex(-1, 3))
        assertEquals(1, aiReviewPageIndex(1, 3))
        assertEquals(2, aiReviewPageIndex(8, 3))
        assertThrows(IllegalArgumentException::class.java) {
            aiReviewPageIndex(0, 0)
        }
    }

    @Test
    fun portraitBitmapIsCenteredInsideA4WithoutCropping() {
        val placement = fitRect(1000, 2000, 595, 842)

        assertEquals(87f, placement.left, 0.001f)
        assertEquals(0f, placement.top, 0.001f)
        assertEquals(508f, placement.right, 0.001f)
        assertEquals(842f, placement.bottom, 0.001f)
    }

    @Test
    fun landscapeBitmapIsCenteredInsideA4WithoutCropping() {
        val placement = fitRect(2000, 1000, 595, 842)

        assertEquals(0f, placement.left, 0.001f)
        assertEquals(272.25f, placement.top, 0.001f)
        assertEquals(595f, placement.right, 0.001f)
        assertEquals(569.75f, placement.bottom, 0.001f)
    }

    @Test
    fun fitRectRejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            fitRect(0, 1000, 595, 842)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fitRect(1000, 1000, -1, 842)
        }
    }

    @Test
    fun thumbnailSampleSizeIsPowerOfTwoAndBoundsTheLongestSide() {
        assertEquals(1, thumbnailSampleSize(800, 600, 1024))
        assertEquals(4, thumbnailSampleSize(4096, 3072, 1024))
        assertEquals(8, thumbnailSampleSize(4097, 1, 1024))
    }

    @Test
    fun thumbnailSampleSizeRejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            thumbnailSampleSize(0, 600, 1024)
        }
        assertThrows(IllegalArgumentException::class.java) {
            thumbnailSampleSize(800, 600, 0)
        }
    }

    @Test
    fun pdfPageSamplingReusesTheBoundedPowerOfTwoCalculation() {
        assertEquals(1, pdfPageSampleSize(2480, 3508))
        assertEquals(2, pdfPageSampleSize(3509, 2480))
        assertEquals(4, pdfPageSampleSize(7017, 4961))
    }

    @Test
    fun shareCachePruningKeepsNewestEntries() {
        val root = Files.createTempDirectory("scan-share-cache").toFile()
        try {
            val now = System.currentTimeMillis()
            val old = File(root, "old").apply { assertTrue(mkdir()) }
            val middle = File(root, "middle").apply { assertTrue(mkdir()) }
            val newest = File(root, "newest").apply { assertTrue(mkdir()) }
            assertTrue(old.setLastModified(now - 2_000L))
            assertTrue(middle.setLastModified(now - 1_000L))
            assertTrue(newest.setLastModified(now))

            assertEquals(
                listOf("old"),
                shareCacheEntriesToPrune(listOf(newest, old, middle), keep = 2)
                    .map { it.name },
            )
            assertEquals(emptyList<File>(), shareCacheEntriesToPrune(listOf(old), keep = 2))
        } finally {
            assertTrue(root.deleteRecursively())
        }
    }

    @Test
    fun printPageSelectionReturnsOnlyRequestedPages() {
        assertEquals(listOf(1, 2, 4), requestedPageIndexes(5, listOf(1..2, 4..4)))
    }

    @Test
    fun printPageSelectionClipsOverlapsAndOutOfBoundsRanges() {
        assertEquals(listOf(0, 1, 2), requestedPageIndexes(3, listOf(-2..1, 1..8)))
    }

    @Test
    fun printPageSelectionHandlesNoRequestedPages() {
        assertEquals(emptyList<Int>(), requestedPageIndexes(3, emptyList()))
        assertEquals(emptyList<Int>(), requestedPageIndexes(0, listOf(0..5)))
        assertThrows(IllegalArgumentException::class.java) {
            requestedPageIndexes(-1, listOf(0..1))
        }
    }

    private fun imageResponse(
        status: String = "completed",
        mimeType: String = "image/jpeg",
        data: String,
    ): String =
        """
        {"status":"$status","steps":[
          {"type":"model_output","content":[
            {"type":"image","mime_type":"$mimeType","data":"$data"}
          ]}
        ]}
        """.trimIndent()
}
