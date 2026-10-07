package com.majkeylab.scanit

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfSizeInitializationTest {
    @Test
    fun originalFirstAccessKeepsEveryPresetInitialized() {
        assertEquals("original", PdfSizeTarget.Original.wireValue)
        assertEquals(
            listOf("original", "200_kb", "500_kb", "1_mb", "5_mb", "10_mb", "20_mb"),
            PdfSizeTarget.presets.map { it.wireValue },
        )
    }
}
