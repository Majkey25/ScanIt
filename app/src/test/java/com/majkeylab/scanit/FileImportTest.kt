package com.majkeylab.scanit

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileImportTest {
    @Test
    fun pagesWithinBothLimitsAreNeverUpscaled() {
        listOf(1 to 1, 120 to 80, 3_508 to 1, 1 to 3_508, 3_200 to 2_500).forEach { (width, height) ->
            assertEquals(width to height, documentImportDimensions(width, height))
        }
    }

    @Test
    fun longEdgeLimitPreservesAspectAndFloorsFractionalPixels() {
        assertEquals(3_508 to 1_000, documentImportDimensions(7_016, 2_000))
        assertEquals(1_000 to 3_508, documentImportDimensions(2_000, 7_016))
        assertEquals(3_508 to 1_169, documentImportDimensions(4_000, 1_333))
        assertEquals(1_169 to 3_508, documentImportDimensions(1_333, 4_000))
    }

    @Test
    fun pixelLimitWinsBeforeEitherSideReachesTheLongEdgeLimit() {
        assertEquals(2_828 to 2_828, documentImportDimensions(4_000, 4_000))
        assertEquals(3_265 to 2_449, documentImportDimensions(4_000, 3_000))
        assertEquals(2_449 to 3_265, documentImportDimensions(3_000, 4_000))
    }

    @Test
    fun extremeAspectRatiosKeepAtLeastOnePixelWithoutOverflow() {
        assertEquals(3_508 to 1, documentImportDimensions(Int.MAX_VALUE, 1))
        assertEquals(1 to 3_508, documentImportDimensions(1, Int.MAX_VALUE))
        assertEquals(2_828 to 2_828, documentImportDimensions(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun boundaryAndExtremeSizesRespectBothCapsAndAspectWithinOnePixel() {
        val sizes = listOf(1, 2, 100, 2_828, 2_829, 3_508, 3_509, 10_000, Int.MAX_VALUE)
        for (width in sizes) for (height in sizes) {
            val (actualWidth, actualHeight) = documentImportDimensions(width, height)
            val label = "$width x $height became $actualWidth x $actualHeight"
            assertTrue(label, actualWidth in 1..minOf(width, 3_508))
            assertTrue(label, actualHeight in 1..minOf(height, 3_508))
            assertTrue(label, actualWidth.toLong() * actualHeight <= 8_000_000L)
            assertTrue(
                label,
                abs(actualWidth.toLong() * height - actualHeight.toLong() * width) <= maxOf(width, height).toLong(),
            )
        }
    }

    @Test
    fun nonpositiveDimensionsAreRejected() {
        listOf(
            0 to 1, 1 to 0, 0 to 0, -1 to 1, 1 to -1,
            Int.MIN_VALUE to Int.MAX_VALUE, Int.MAX_VALUE to Int.MIN_VALUE,
        ).forEach { (width, height) ->
            assertThrows(IllegalArgumentException::class.java) { documentImportDimensions(width, height) }
        }
    }

    @Test
    fun pickerTypesAndSaveTargetsMatchEachImportAction() {
        assertArrayEquals(arrayOf("application/pdf", "image/*"), DocumentImportAction.Open.mimeTypes)
        assertArrayEquals(arrayOf("application/pdf"), DocumentImportAction.PdfToImages.mimeTypes)
        assertArrayEquals(arrayOf("image/*"), DocumentImportAction.ImagesToPdf.mimeTypes)
        assertNull(DocumentImportAction.Open.saveTarget)
        assertEquals(SaveNowTarget.Images, DocumentImportAction.PdfToImages.saveTarget)
        assertEquals(SaveNowTarget.Pdf, DocumentImportAction.ImagesToPdf.saveTarget)
    }
}
