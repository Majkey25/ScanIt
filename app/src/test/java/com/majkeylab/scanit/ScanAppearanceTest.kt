package com.majkeylab.scanit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanAppearanceTest {
    @Test
    fun freshAppearanceUsesFullBlackWhiteAndHalfShadows() {
        assertEquals(
            ScanAppearance(
                colorMode = ScanColorMode.BlackWhite,
                intensity = 100,
                shadows = 50,
            ),
            ScanAppearance(),
        )
    }

    @Test
    fun colorModesHaveStableWireValues() {
        assertEquals("color", ScanColorMode.Color.wireValue)
        assertEquals("grayscale", ScanColorMode.Grayscale.wireValue)
        assertEquals("black_white", ScanColorMode.BlackWhite.wireValue)
    }

    @Test
    fun storedAppearanceParsesKnownModeAndClampsPercentages() {
        assertEquals(
            ScanAppearance(ScanColorMode.Grayscale, intensity = 100, shadows = 0),
            parseScanAppearance("grayscale", intensity = 140, shadows = -20),
        )
    }

    @Test
    fun missingOrUnknownStoredAppearanceUsesSafeDefaults() {
        assertEquals(ScanAppearance(), parseScanAppearance(null, null, null))
        assertEquals(
            ScanAppearance(ScanColorMode.BlackWhite, intensity = 25, shadows = 75),
            parseScanAppearance("future_mode", intensity = 25, shadows = 75),
        )
        assertEquals(0, clampAppearancePercent(Int.MIN_VALUE))
        assertEquals(100, clampAppearancePercent(Int.MAX_VALUE))
    }

    @Test
    fun integerLumaUsesRoundedBt601Weights() {
        assertEquals(0, rgbLuma(0, 0, 0))
        assertEquals(255, rgbLuma(255, 255, 255))
        assertEquals(76, rgbLuma(255, 0, 0))
        assertEquals(150, rgbLuma(0, 255, 0))
        assertEquals(29, rgbLuma(0, 0, 255))
    }

    @Test
    fun localShadowCorrectionFlattensAWhitePaperGradient() {
        val gradient = intArrayOf(64, 96, 128, 160, 192, 224, 240, 252)

        assertArrayEquals(gradient, correctLocalShadows(gradient, 8, 1, strength = 0))
        assertArrayEquals(
            IntArray(gradient.size) { 255 },
            correctLocalShadows(gradient, 8, 1, strength = 100),
        )
        assertArrayEquals(
            intArrayOf(159, 175, 191, 207, 223, 239, 247, 253),
            correctLocalShadows(gradient, 8, 1, strength = 50),
        )
    }

    @Test
    fun localShadowCorrectionDoesNotCreateTileBoundaryReversals() {
        val gradient = IntArray(16) { 64 + it * 12 }

        val corrected = correctLocalShadows(gradient, 16, 1, strength = 50)

        assertTrue((1 until corrected.size).all { corrected[it - 1] <= corrected[it] })
    }

    @Test
    fun localShadowCorrectionStaysInRangeAcrossAHighContrastBoundary() {
        val boundary = intArrayOf(255, 255, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

        val corrected = correctLocalShadows(boundary, 16, 1, strength = 50)

        assertEquals(255, corrected[1])
        assertTrue(corrected.all { it in 0..255 })
    }

    @Test
    fun colorIntensityKeepsColorAndMovesContrastFromZeroToFull() {
        val source = intArrayOf(argb(alpha = 128, red = 64, green = 96, blue = 128))

        assertArrayEquals(
            source,
            processScanPixels(source, 1, 1, ScanAppearance(ScanColorMode.Color, 0, 0)),
        )
        assertArrayEquals(
            intArrayOf(argb(alpha = 128, red = 50, green = 76, blue = 101)),
            processScanPixels(source, 1, 1, ScanAppearance(ScanColorMode.Color, 50, 0)),
        )
        assertArrayEquals(
            intArrayOf(argb(alpha = 128, red = 37, green = 55, blue = 74)),
            processScanPixels(source, 1, 1, ScanAppearance(ScanColorMode.Color, 100, 0)),
        )
    }

    @Test
    fun grayscaleIntensityAlwaysReturnsNeutralPixels() {
        val source = intArrayOf(argb(alpha = 128, red = 64, green = 96, blue = 128))

        assertArrayEquals(
            intArrayOf(gray(alpha = 128, value = 90)),
            processScanPixels(source, 1, 1, ScanAppearance(ScanColorMode.Grayscale, 0, 0)),
        )
        assertArrayEquals(
            intArrayOf(gray(alpha = 128, value = 71)),
            processScanPixels(source, 1, 1, ScanAppearance(ScanColorMode.Grayscale, 50, 0)),
        )
        assertArrayEquals(
            intArrayOf(gray(alpha = 128, value = 52)),
            processScanPixels(source, 1, 1, ScanAppearance(ScanColorMode.Grayscale, 100, 0)),
        )
    }

    @Test
    fun blackWhiteIntensityMovesTheMeasuredThresholdWithoutGrayBlending() {
        val source = intArrayOf(gray(32), gray(96), gray(160), gray(224))

        val low =
            processScanPixels(
                source,
                4,
                1,
                ScanAppearance(ScanColorMode.BlackWhite, 0, 0),
            )
        val medium =
            processScanPixels(
                source,
                4,
                1,
                ScanAppearance(ScanColorMode.BlackWhite, 50, 0),
            )
        val high =
            processScanPixels(
                source,
                4,
                1,
                ScanAppearance(ScanColorMode.BlackWhite, 100, 0),
            )

        assertArrayEquals(intArrayOf(gray(0), gray(255), gray(255), gray(255)), low)
        assertArrayEquals(intArrayOf(gray(0), gray(0), gray(255), gray(255)), medium)
        assertArrayEquals(intArrayOf(gray(0), gray(0), gray(0), gray(255)), high)
        assertTrue((low + medium + high).all(::isOpaqueBlackOrWhite))
    }

    @Test
    fun shadowsRemainIndependentFromFilterIntensity() {
        val source = intArrayOf(gray(64), gray(96), gray(128), gray(160))

        assertArrayEquals(
            source,
            processScanPixels(source, 4, 1, ScanAppearance(ScanColorMode.Color, 0, 0)),
        )
        assertArrayEquals(
            IntArray(source.size) { gray(255) },
            processScanPixels(source, 4, 1, ScanAppearance(ScanColorMode.Color, 0, 100)),
        )
    }

    @Test
    fun otsuUsesNeutralFallbackForBlankInputAndMidpointForBimodalInput() {
        assertEquals(127, otsuThreshold(intArrayOf(255, 255, 255)))
        assertEquals(127, otsuThreshold(intArrayOf(0, 0, 255, 255)))
    }

    @Test
    fun blackMaskIsDeterministicAndPacksMostSignificantBitFirst() {
        assertArrayEquals(
            byteArrayOf(0xAA.toByte(), 0x80.toByte()),
            packBlackMask(
                intArrayOf(0, 200, 100, 255, 127, 128, 64, 192, 0),
                threshold = 127,
            ),
        )
    }

    private fun argb(
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int = 255,
    ): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    private fun gray(value: Int, alpha: Int = 255): Int = argb(value, value, value, alpha)

    private fun isOpaqueBlackOrWhite(pixel: Int): Boolean =
        pixel == gray(0) || pixel == gray(255)
}
