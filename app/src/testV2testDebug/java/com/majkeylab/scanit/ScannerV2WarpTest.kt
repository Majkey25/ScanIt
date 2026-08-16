package com.majkeylab.scanit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerV2WarpTest {
    @Test
    fun exifRotationMapsVisibleCropBackToRawPixels() {
        val crop = PageQuad.create(
            NormalizedPoint(.1, .2),
            NormalizedPoint(.8, .1),
            NormalizedPoint(.9, .7),
            NormalizedPoint(.2, .8),
        )

        val raw = scannerV2SourceCropPoints(4000, 3000, crop, ImageExifOrientation.Rotate90)

        assertPoint(800.0, 2700.0, raw[0])
        assertPoint(400.0, 600.0, raw[1])
        assertPoint(2800.0, 300.0, raw[2])
        assertPoint(3200.0, 2400.0, raw[3])
    }

    @Test
    fun quarterTurnMapsCropCornersToRotatedDestination() {
        val destination = scannerV2DestinationCropPoints(WarpSize(2400, 3200), rotationQuarterTurns = 1)

        assertPoint(2400.0, 0.0, destination[0])
        assertPoint(2400.0, 3200.0, destination[1])
        assertPoint(0.0, 3200.0, destination[2])
        assertPoint(0.0, 0.0, destination[3])
    }

    @Test
    fun planUsesVisibleDimensionsAndBoundsPeakMemory() {
        val plan = scannerV2WarpPlan(
            sourceWidth = 4000,
            sourceHeight = 3000,
            crop = PageQuad.fullFrame(),
            orientation = ImageExifOrientation.Rotate90,
            rotationQuarterTurns = 1,
        )

        assertEquals(WarpSize(4000, 3000), plan.output)
        assertTrue(plan.output.width <= MAX_IMAGE_EXPORT_DIMENSION)
        assertTrue(plan.output.width.toLong() * plan.output.height <= MAX_IMAGE_EXPORT_PIXELS)
        assertTrue(plan.peakBitmapBytes <= 64L * 1024 * 1024)
    }

    @Test
    fun planRejectsOversizedOrInvalidSource() {
        assertThrows(IllegalArgumentException::class.java) {
            scannerV2WarpPlan(
                sourceWidth = 6001,
                sourceHeight = 1000,
                crop = PageQuad.fullFrame(),
                orientation = ImageExifOrientation.Normal,
                rotationQuarterTurns = 0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            scannerV2WarpPlan(
                sourceWidth = 4000,
                sourceHeight = 4000,
                crop = PageQuad.fullFrame(),
                orientation = ImageExifOrientation.Normal,
                rotationQuarterTurns = 4,
            )
        }
    }

    private fun assertPoint(x: Double, y: Double, actual: ScannerV2WarpPoint) {
        assertEquals(x, actual.x, .01)
        assertEquals(y, actual.y, .01)
    }
}
