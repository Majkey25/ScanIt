package com.majkeylab.scanit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
