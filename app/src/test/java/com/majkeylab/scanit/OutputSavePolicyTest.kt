package com.majkeylab.scanit

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputSavePolicyTest {
    @Test
    fun automaticOutputTargetMatchesSavedOutputSettings() {
        assertEquals(
            SaveNowTarget.Both,
            automaticOutputTarget(AppSettings(savePdf = true, saveImages = true)),
        )
        assertEquals(
            SaveNowTarget.Pdf,
            automaticOutputTarget(AppSettings(savePdf = true, saveImages = false)),
        )
        assertEquals(
            SaveNowTarget.Images,
            automaticOutputTarget(AppSettings(savePdf = false, saveImages = true)),
        )
        assertNull(automaticOutputTarget(AppSettings(savePdf = false, saveImages = false)))
    }

    @Test
    fun persistedPdfTargetWarningUsesExactDecimalLimit() {
        assertNull(pdfSizeTargetWarning(PdfSizeTarget.Original, 99_000_000L))
        assertNull(pdfSizeTargetWarning(PdfSizeTarget.Mb5, 5_000_000L))
        assertEquals(
            UiMessage(
                R.string.pdf_size_target_not_met,
                listOf(5, 5.000001),
            ),
            pdfSizeTargetWarning(PdfSizeTarget.Mb5, 5_000_001L),
        )
    }

    @Test
    fun fileDetailsUseExactCachedImageByteTotal() {
        val root = Files.createTempDirectory("scanit-file-details-").toFile()
        try {
            val first = File(root, "page-1.jpg").apply { writeBytes(ByteArray(3)) }
            val second = File(root, "page-2.jpg").apply { writeBytes(ByteArray(5)) }

            assertEquals(8L, totalFileBytes(listOf(first, second)))
            assertEquals(0L, totalFileBytes(listOf(File(root, "missing.jpg"))))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun pdfTargetChangeRequiresExactEditableCurrentRevision() {
        val page = File("page-1.jpg")
        val source = File("source-1.jpg")
        val editable =
            SavedScan(
                cached =
                    CachedScan(
                        baseName = "Scan_current",
                        pages = listOf(page),
                        pdf = File("Scan_current.pdf"),
                        entryId = "123e4567-e89b-12d3-a456-426614174000",
                        sourcePages = listOf(source),
                        appearanceSettings = ScanAppearanceSettings(),
                        pdfSizeTarget = PdfSizeTarget.Original,
                    ),
                galleryPages = emptyList(),
                savedPdf = null,
                outputMetadataValid = true,
            )

        assertTrue(canChangePdfSize(editable, PdfSizeTarget.Mb5))
        assertFalse(canChangePdfSize(editable, PdfSizeTarget.Original))
        assertFalse(canChangePdfSize(editable.copy(outputMetadataValid = false), PdfSizeTarget.Mb5))
        assertFalse(
            canChangePdfSize(
                editable.copy(cached = editable.cached.copy(sourcePages = emptyList())),
                PdfSizeTarget.Mb5,
            ),
        )
    }
}
