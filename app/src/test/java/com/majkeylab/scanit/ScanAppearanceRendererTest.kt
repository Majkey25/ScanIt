package com.majkeylab.scanit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScanAppearanceRendererTest {
    @Test
    fun decodeSamplingKeepsOrdinaryDocumentAtItsOriginalDimensions() {
        assertEquals(1, appearanceDecodeSampleSize(width = 2_480, height = 3_508))
    }

    @Test
    fun decodeSamplingUsesPowersOfTwoUntilPixelAndEdgeBoundsHold() {
        assertEquals(2, appearanceDecodeSampleSize(width = 8_000, height = 6_000))
        assertEquals(4, appearanceDecodeSampleSize(width = 12_000, height = 9_000))
        assertEquals(4, appearanceDecodeSampleSize(width = 12_001, height = 1))
    }

    @Test
    fun sampledDimensionRoundsUpForAConservativeMemoryBound() {
        assertEquals(3, sampledDimensionUpperBound(size = 5, sampleSize = 2))
        assertEquals(1, sampledDimensionUpperBound(size = 1, sampleSize = 8))
    }

    @Test
    fun decodeSamplingRejectsInvalidBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            appearanceDecodeSampleSize(width = 0, height = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            sampledDimensionUpperBound(size = 1, sampleSize = 0)
        }
    }

    @Test
    fun streamedHistogramUsesNeutralAndBimodalThresholds() {
        val blank = IntArray(256).apply { this[255] = 3 }
        val bimodal = IntArray(256).apply {
            this[0] = 2
            this[255] = 2
        }

        assertEquals(127, otsuThresholdFromHistogram(blank))
        assertEquals(127, otsuThresholdFromHistogram(bimodal))
    }
}
