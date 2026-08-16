package com.majkeylab.scanit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
        assertEquals(
            ScanAppearance(ScanColorMode.Grayscale, intensity = 100, shadows = 0),
            ScannerV2Appearance.defaultFor(ScannerV2Filter.Drawing).asScanAppearance(),
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
        assertThrows(IllegalArgumentException::class.java) {
            ScannerV2Appearance(ScannerV2Filter.Drawing, intensity = 100, shadows = 1)
        }
    }

    @Test
    fun eachEditablePresetHasAStableWireValue() {
        assertEquals(
            listOf(
                "original",
                "natural",
                "color",
                "light_text",
                "grayscale",
                "drawing",
                "black_white",
                "whiteboard",
            ),
            ScannerV2Filter.entries.map { it.wireValue },
        )
    }

    @Test
    fun drawingRemovesOnlyColorAndKeepsContinuousToneDetail() {
        val source = intArrayOf(0xff18324c.toInt(), 0xff7890a8.toInt(), 0xffd4c8bc.toInt())
        val drawing = processScanPixels(
            source,
            width = 3,
            height = 1,
            appearance = ScannerV2Appearance.defaultFor(ScannerV2Filter.Drawing).asScanAppearance(),
        )
        val grayscale = processScanPixels(
            source,
            width = 3,
            height = 1,
            appearance = ScanAppearance(ScanColorMode.Grayscale, intensity = 100, shadows = 0),
        )
        val blackWhite = processScanPixels(
            source,
            width = 3,
            height = 1,
            appearance = ScanAppearance(ScanColorMode.BlackWhite, intensity = 100, shadows = 0),
        )

        assertEquals(grayscale.toList(), drawing.toList())
        assertFalse(drawing.contentEquals(blackWhite))
    }

    @Test
    fun originalComparisonIsVisibleOnlyDuringAValidRenderedPageHold() {
        assertTrue(
            shouldShowScannerV2Original(
                rendered = true, busy = false, holding = true, originalAvailable = true,
            ),
        )
        assertFalse(shouldShowScannerV2Original(true, false, false, true))
        assertFalse(shouldShowScannerV2Original(true, true, true, true))
        assertFalse(shouldShowScannerV2Original(true, false, true, false))
        assertFalse(shouldShowScannerV2Original(false, false, true, true))
    }
}
