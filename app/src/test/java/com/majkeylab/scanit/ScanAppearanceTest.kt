package com.majkeylab.scanit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanAppearanceTest {
    @Test
    fun cleanWhiteboardPresetTransformsFreshGoogleScannerPixels() {
        val google = googleScannerAppearanceSettings()
        val preset = cleanWhiteboardAppearanceSettings()
        val appearance = preset.selected()
        val before = intArrayOf(gray(32), gray(96), gray(160), gray(224))
        val after = processScanPixels(before, 4, 1, appearance)

        assertEquals(0, google.whiteboardIntensity)
        assertEquals(0, google.shadows)
        assertEquals(ScanColorMode.Whiteboard, appearance.colorMode)
        assertTrue(appearance.intensity > 0 || appearance.shadows > 0)
        assertFalse(before.contentEquals(after))
        assertTrue(after.all(::isOpaqueBlackOrWhite))
        assertEquals(preset, cleanWhiteboardAppearanceSettings())
    }

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
        assertEquals("natural", ScanColorMode.Natural.wireValue)
        assertEquals("color", ScanColorMode.Color.wireValue)
        assertEquals("light_text", ScanColorMode.LightText.wireValue)
        assertEquals("grayscale", ScanColorMode.Grayscale.wireValue)
        assertEquals("black_white", ScanColorMode.BlackWhite.wireValue)
        assertEquals("whiteboard", ScanColorMode.Whiteboard.wireValue)
    }

    @Test
    fun everyDocumentFilterHasAnAdjustableRememberedIntensity() {
        val settings =
            ScanAppearanceSettings(
                naturalIntensity = 11,
                colorIntensity = 22,
                lightTextIntensity = 33,
                grayscaleIntensity = 44,
                blackWhiteIntensity = 55,
                whiteboardIntensity = 66,
            )

        assertEquals(11, settings.intensity(ScanColorMode.Natural))
        assertEquals(22, settings.intensity(ScanColorMode.Color))
        assertEquals(33, settings.intensity(ScanColorMode.LightText))
        assertEquals(44, settings.intensity(ScanColorMode.Grayscale))
        assertEquals(55, settings.intensity(ScanColorMode.BlackWhite))
        assertEquals(66, settings.intensity(ScanColorMode.Whiteboard))
    }

    @Test
    fun changingOneFilterIntensityDoesNotChangeAnotherFilter() {
        val original = ScanAppearanceSettings()

        val customized =
            ScanColorMode.entries.foldIndexed(original) { index, settings, mode ->
                settings.withIntensity(mode, (index + 1) * 10)
            }

        assertEquals(listOf(10, 20, 30, 40, 50, 60), ScanColorMode.entries.map(customized::intensity))
        assertEquals(50, customized.shadows)
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
            intArrayOf(argb(alpha = 128, red = 51, green = 76, blue = 101)),
            processScanPixels(source, 1, 1, ScanAppearance(ScanColorMode.Color, 50, 0)),
        )
        assertArrayEquals(
            intArrayOf(argb(alpha = 128, red = 37, green = 55, blue = 74)),
            processScanPixels(source, 1, 1, ScanAppearance(ScanColorMode.Color, 100, 0)),
        )
    }

    @Test
    fun grayscaleIntensityBlendsFromSourceColorToNeutral() {
        val source = intArrayOf(argb(alpha = 128, red = 64, green = 96, blue = 128))

        assertArrayEquals(
            source,
            processScanPixels(source, 1, 1, ScanAppearance(ScanColorMode.Grayscale, 0, 0)),
        )
        assertArrayEquals(
            intArrayOf(argb(alpha = 128, red = 77, green = 93, blue = 109)),
            processScanPixels(source, 1, 1, ScanAppearance(ScanColorMode.Grayscale, 50, 0)),
        )
        assertArrayEquals(
            intArrayOf(gray(alpha = 128, value = 90)),
            processScanPixels(source, 1, 1, ScanAppearance(ScanColorMode.Grayscale, 100, 0)),
        )
    }

    @Test
    fun naturalLightTextAndWhiteboardProduceDistinctDocumentPreviews() {
        val source = intArrayOf(gray(32), gray(96), gray(160), gray(224))

        val natural =
            processScanPixels(source, 4, 1, ScanAppearance(ScanColorMode.Natural, 100, 0))
        val lightText =
            processScanPixels(source, 4, 1, ScanAppearance(ScanColorMode.LightText, 100, 0))
        val whiteboard =
            processScanPixels(source, 4, 1, ScanAppearance(ScanColorMode.Whiteboard, 100, 0))

        assertTrue(natural.contentEquals(source).not())
        assertTrue(lightText.zip(source).all { (result, original) -> argbLuma(result) >= argbLuma(original) })
        assertTrue(whiteboard.all(::isOpaqueBlackOrWhite))
        assertTrue(whiteboard.contentEquals(processScanPixels(source, 4, 1, ScanAppearance(ScanColorMode.BlackWhite, 100, 0))).not())
    }

    @Test
    fun blackWhiteIntensityBlendsTowardTheMeasuredBitonalTarget() {
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

        assertArrayEquals(source, low)
        assertArrayEquals(intArrayOf(gray(16), gray(48), gray(207), gray(239)), medium)
        assertArrayEquals(intArrayOf(gray(0), gray(0), gray(255), gray(255)), high)
        assertTrue(high.all(::isOpaqueBlackOrWhite))
    }

    @Test
    fun zeroFilterIntensityWithNoShadowsReturnsExactSourceForEveryMode() {
        val source =
            intArrayOf(
                argb(alpha = 17, red = 31, green = 127, blue = 223),
                argb(alpha = 231, red = 240, green = 80, blue = 16),
            )

        ScanColorMode.entries.forEach { mode ->
            assertArrayEquals(
                source,
                processScanPixels(
                    source,
                    2,
                    1,
                    ScanAppearance(mode, intensity = 0, shadows = 0),
                ),
            )
        }
    }

    @Test
    fun everyModePreservesAlphaAtZeroHalfAndFullIntensity() {
        val source = intArrayOf(argb(alpha = 37, red = 64, green = 96, blue = 128))

        ScanColorMode.entries.forEach { mode ->
            listOf(0, 50, 100).forEach { intensity ->
                val result =
                    processScanPixels(
                        source,
                        1,
                        1,
                        ScanAppearance(mode, intensity = intensity, shadows = 50),
                    )

                assertEquals(37, result.single() ushr 24)
            }
        }
    }

    @Test
    fun shadowsRemainIndependentFromFilterIntensity() {
        val source = intArrayOf(argb(red = 64, green = 96, blue = 128))
        val shadowCorrected = intArrayOf(argb(red = 181, green = 255, blue = 255))

        ScanColorMode.entries.forEach { mode ->
            assertArrayEquals(
                shadowCorrected,
                processScanPixels(
                    source,
                    1,
                    1,
                    ScanAppearance(mode, intensity = 0, shadows = 100),
                ),
            )
        }
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
