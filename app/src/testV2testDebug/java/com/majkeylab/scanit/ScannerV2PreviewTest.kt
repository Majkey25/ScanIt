package com.majkeylab.scanit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScannerV2PreviewTest {
    @Test
    fun thumbnailSampleBoundsBothEdgesAndPixels() {
        assertEquals(32, scannerV2PreviewSampleSize(4032, 3024, 160, 25_600))
        assertEquals(8, scannerV2PreviewSampleSize(800, 1200, 160, 25_600))
        assertEquals(1, scannerV2PreviewSampleSize(120, 80, 160, 25_600))
    }

    @Test
    fun thumbnailSampleRejectsInvalidBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            scannerV2PreviewSampleSize(0, 800, 160, 25_600)
        }
        assertThrows(IllegalArgumentException::class.java) {
            scannerV2PreviewSampleSize(800, 600, 0, 25_600)
        }
        assertThrows(IllegalArgumentException::class.java) {
            scannerV2PreviewSampleSize(800, 600, 160, 0)
        }
    }
}
