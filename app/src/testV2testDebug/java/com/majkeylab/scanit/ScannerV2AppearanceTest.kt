package com.majkeylab.scanit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScannerV2AppearanceTest {
    @Test
    fun presetsMapToTheExistingBoundedAppearanceEngine() {
        assertEquals(
            ScanAppearance(ScanColorMode.Natural, intensity = 0, shadows = 0),
            ScannerV2Appearance.original().asScanAppearance(),
        )
        assertEquals(
            ScanAppearance(ScanColorMode.Grayscale, intensity = 72, shadows = 18),
            ScannerV2Appearance(ScannerV2Filter.Grayscale, intensity = 72, shadows = 18)
                .asScanAppearance(),
        )
    }

    @Test
    fun appearanceRejectsInvalidOrModifiedOriginalSettings() {
        assertThrows(IllegalArgumentException::class.java) {
            ScannerV2Appearance(ScannerV2Filter.Color, intensity = 101, shadows = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScannerV2Appearance(ScannerV2Filter.Original, intensity = 1, shadows = 0)
        }
    }

    @Test
    fun eachEditablePresetHasAStableWireValue() {
        assertEquals(
            listOf("original", "natural", "color", "light_text", "grayscale", "black_white", "whiteboard"),
            ScannerV2Filter.entries.map { it.wireValue },
        )
    }
}
