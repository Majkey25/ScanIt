package com.majkeylab.scanit

import java.lang.management.ManagementFactory
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ManualCleanupTest {
    @Test
    fun evenBoundaryMedianAveragesLowerAndUpperSamples() {
        val pixels =
            intArrayOf(
                argb(10, 20, 200, 40),
                argb(255, 1, 2, 3),
                argb(30, 100, 20, 200),
            )
        val mask = IntArray(pixels.size).apply { this[1] = 255 }
        val expected = intArrayOf(pixels[0], argb(20, 60, 110, 120), pixels[2])

        assertArrayEquals(expected, inpaintMaskedPixels(pixels, width = 3, height = 1, mask))
    }

    @Test
    fun oddBoundaryMedianUsesMiddleSamplePerChannel() {
        val pixels =
            intArrayOf(
                argb(255, 1, 2, 3),
                argb(10, 20, 200, 40),
                argb(255, 4, 5, 6),
                argb(30, 100, 20, 200),
                argb(20, 60, 100, 80),
                argb(255, 7, 8, 9),
            )
        val mask = IntArray(pixels.size).apply { this[0] = 255 }

        val cleaned = inpaintMaskedPixels(pixels, width = 3, height = 2, mask)

        assertEquals(argb(20, 60, 100, 80), cleaned[0])
    }

    @Test
    fun featheredMaskBlendsReplacementIntoTheOriginalEdge() {
        val pixels = IntArray(9) { gray(200) }.apply { this[4] = gray(10) }
        val mask = IntArray(9).apply { this[4] = 128 }

        assertEquals(gray(105), inpaintMaskedPixels(pixels, width = 3, height = 3, mask)[4])
    }

    @Test
    fun fullyMaskedSelectionWithoutBackgroundIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            inpaintMaskedPixels(IntArray(4) { gray(100) }, width = 2, height = 2, IntArray(4) { 255 })
        }
    }

    @Test
    fun lassoBoundsIncludeBackgroundPaddingAndStayInsideThePage() {
        assertEquals(
            PixelBounds(left = 16, top = 16, right = 44, bottom = 44),
            manualCleanupPixelBounds(
                points =
                    listOf(
                        MarkPoint(0.2f, 0.25f),
                        MarkPoint(0.4f, 0.25f),
                        MarkPoint(0.4f, 0.5f),
                    ),
                width = 100,
                height = 80,
                padding = 4,
            ),
        )
        assertEquals(
            PixelBounds(left = 0, top = 0, right = 14, bottom = 14),
            manualCleanupPixelBounds(
                points = listOf(MarkPoint(0f, 0f), MarkPoint(0.1f, 0f), MarkPoint(0f, 0.1f)),
                width = 100,
                height = 100,
                padding = 4,
            ),
        )
    }

    @Test
    fun lassoRequiresAClosedAreaRatherThanAFreehandLine() {
        assertThrows(IllegalArgumentException::class.java) {
            manualCleanupPixelBounds(
                listOf(MarkPoint(0.2f, 0.2f), MarkPoint(0.4f, 0.4f)),
                width = 100,
                height = 100,
            )
        }
    }

    @Test
    fun cancellationDuringBoundaryScanStopsBeforeChangingPixels() {
        val pixels = IntArray(5_000) { gray(200) }.apply { this[4_500] = gray(10) }
        val original = pixels.copyOf()
        val mask = IntArray(pixels.size).apply { this[4_500] = 255 }
        var checks = 0

        assertThrows(CancellationException::class.java) {
            inpaintMaskedPixels(
                pixels = pixels,
                width = pixels.size,
                height = 1,
                maskAlpha = mask,
                isCancelled = { ++checks == 3 },
            )
        }
        assertEquals(3, checks)
        assertArrayEquals(original, pixels)
    }

    @Test
    fun boundaryMedianScratchAllocationDoesNotScaleWithSelectionArea() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(bean?.isThreadAllocatedMemorySupported == true)
        val allocationBean = requireNotNull(bean)
        allocationBean.isThreadAllocatedMemoryEnabled = true
        repeat(3) { measuredInpaintAllocation(allocationBean, 20_000) }

        val small = measuredInpaintAllocation(allocationBean, 100_000)
        val large = measuredInpaintAllocation(allocationBean, 200_000)

        assertTrue(
            "Boundary scratch allocation grew with the selection: small=$small, large=$large",
            large - small < 600_000L,
        )
    }

    @Test
    fun isolatedDarkBoundaryPixelDoesNotCreateDirectionalFillArtifacts() {
        val pixels = IntArray(25) { gray(200) }.apply { this[0] = gray(20) }
        val mask =
            IntArray(25) { index ->
                val x = index % 5
                val y = index / 5
                if (x in 1..3 && y in 1..3) 255 else 0
            }

        val cleaned = inpaintMaskedPixels(pixels, width = 5, height = 5, mask)

        assertEquals(gray(20), cleaned[0])
        assertArrayEquals(IntArray(9) { gray(200) }, intArrayOf(
            cleaned[6], cleaned[7], cleaned[8],
            cleaned[11], cleaned[12], cleaned[13],
            cleaned[16], cleaned[17], cleaned[18],
        ))
    }

    private fun gray(value: Int): Int =
        0xFF000000.toInt() or (value shl 16) or (value shl 8) or value

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    private fun measuredInpaintAllocation(
        bean: com.sun.management.ThreadMXBean,
        size: Int,
    ): Long {
        val pixels = IntArray(size) { gray(200) }.apply { this[size / 2] = gray(10) }
        val mask = IntArray(size).apply { this[size / 2] = 255 }
        @Suppress("DEPRECATION")
        val threadId = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(threadId)
        val output = inpaintMaskedPixels(pixels, width = size, height = 1, mask)
        val allocated = bean.getThreadAllocatedBytes(threadId) - before
        assertEquals(gray(200), output[size / 2])
        return allocated
    }
}
