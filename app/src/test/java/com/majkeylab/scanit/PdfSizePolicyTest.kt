package com.majkeylab.scanit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfSizePolicyTest {
    @Test
    fun namedPdfLimitsUseDecimalMegabytes() {
        assertEquals(5_000_000L, PdfSizeTarget.Mb5.maxBytes)
        assertEquals(10_000_000L, PdfSizeTarget.Mb10.maxBytes)
        assertEquals(20_000_000L, PdfSizeTarget.Mb20.maxBytes)
    }

    @Test
    fun customPdfLimitUsesDecimalMegabytesAndRoundTrips() {
        val target = PdfSizeTarget.Custom(37)

        assertEquals(37_000_000L, target.maxBytes)
        assertEquals("custom_37_mb", target.wireValue)
        assertEquals(target, decodePdfSizeTarget(target.wireValue))
        assertEquals(target, parsePdfSizeTarget(target.wireValue))
    }

    @Test
    fun customPdfLimitRejectsMalformedAndOutOfRangeValues() {
        listOf(
            null,
            "",
            "custom_0_mb",
            "custom_01_mb",
            "custom_501_mb",
            "custom_7_mib",
            "custom_-7_mb",
        ).forEach { value ->
            assertEquals(null, decodePdfSizeTarget(value))
            assertEquals(PdfSizeTarget.Original, parsePdfSizeTarget(value))
        }

        assertThrows(IllegalArgumentException::class.java) { PdfSizeTarget.Custom(0) }
        assertThrows(IllegalArgumentException::class.java) { PdfSizeTarget.Custom(501) }
    }

    @Test
    fun customPdfInputAcceptsOnlyWholeMegabytesWithinBounds() {
        assertEquals(37, parseCustomPdfMegabytes("37"))
        assertEquals(500, parseCustomPdfMegabytes("500"))
        listOf("", "0", "01", "1.5", "501", "-1", "7 MB").forEach { value ->
            assertEquals(null, parseCustomPdfMegabytes(value))
        }
    }

    @Test
    fun eachFilterKeepsItsOwnIntensityAndSuccessfulModeBecomesCurrent() {
        val settings =
            ScanAppearanceSettings(
                colorMode = ScanColorMode.BlackWhite,
                colorIntensity = 20,
                grayscaleIntensity = 40,
                blackWhiteIntensity = 100,
                shadows = 50,
            )

        val applied =
            settings.withApplied(
                ScanAppearance(
                    colorMode = ScanColorMode.Grayscale,
                    intensity = 65,
                    shadows = 35,
                ),
            )

        assertEquals(20, applied.colorIntensity)
        assertEquals(65, applied.grayscaleIntensity)
        assertEquals(100, applied.blackWhiteIntensity)
        assertEquals(ScanAppearance(ScanColorMode.Grayscale, 65, 35), applied.selected())
    }

    @Test
    fun cleanInstallAppearanceIsBlackWhiteFullStrengthWithHalfShadows() {
        assertEquals(
            ScanAppearance(ScanColorMode.BlackWhite, intensity = 100, shadows = 50),
            ScanAppearanceSettings().selected(),
        )
    }

    @Test
    fun googleScannerOutputBypassesScanItEffects() {
        assertEquals(
            ScanAppearance(ScanColorMode.Natural, intensity = 0, shadows = 0),
            googleScannerAppearanceSettings().selected(),
        )
    }

    @Test
    fun resolutionProfilesNeverDropAnyPageBelowLegibleEdge() {
        assertEquals(listOf(1, 2), pdfSampleMultipliers(listOf(3_508, 3_508)))
        assertEquals(listOf(1, 2, 4), pdfSampleMultipliers(listOf(6_000, 5_200)))
        assertEquals(listOf(1, 2), pdfSampleMultipliers(listOf(1_000, 4_000)))
        assertEquals(listOf(1), pdfSampleMultipliers(listOf(2_559)))
        assertEquals(1, legiblePdfSampleMultiplier(longestEdge = 2_559, requestedMultiplier = 2))
    }

    @Test
    fun pdfPolicyRejectsInvalidMeasurements() {
        assertThrows(IllegalArgumentException::class.java) {
            pdfSampleMultipliers(listOf(0))
        }
    }

    @Test
    fun bitonalEncodingWinsOnlyWhenEligibleAndStrictlySmaller() {
        assertEquals(PdfEncoding.Bitonal, selectPdfEncoding(1_000, 600, bitonalEligible = true))
        assertEquals(PdfEncoding.Jpeg, selectPdfEncoding(1_000, 1_000, bitonalEligible = true))
        assertEquals(PdfEncoding.Jpeg, selectPdfEncoding(1_000, 600, bitonalEligible = false))
    }

    @Test
    fun bitonalCandidateRequiresFullStrengthBlackWhiteAppearance() {
        assertTrue(isBitonalPdfEligible(ScanAppearance(ScanColorMode.BlackWhite, 100, 50)))
        assertFalse(isBitonalPdfEligible(ScanAppearance(ScanColorMode.BlackWhite, 99, 50)))
        assertFalse(isBitonalPdfEligible(ScanAppearance(ScanColorMode.Grayscale, 100, 50)))
    }

    @Test
    fun relativePdfSamplingKeepsRendererBaseBound() {
        assertEquals(1, relativePdfSourceSampleSize(1, 1))
        assertEquals(8, relativePdfSourceSampleSize(2, 4))
        assertThrows(IllegalArgumentException::class.java) {
            relativePdfSourceSampleSize(3, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            relativePdfSourceSampleSize(1 shl 30, 4)
        }
    }
}
