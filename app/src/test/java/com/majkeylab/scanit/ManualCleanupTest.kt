package com.majkeylab.scanit

import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ManualCleanupTest {
    @Test
    fun maskedStainUsesSurroundingPaperAndLeavesOtherPixelsUntouched() {
        val pixels =
            IntArray(15) { index ->
                when (index % 5) {
                    0 -> gray(100)
                    1 -> gray(120)
                    2 -> gray(10)
                    3 -> gray(160)
                    else -> gray(180)
                }
            }
        val mask = IntArray(15) { index -> if (index % 5 == 2) 255 else 0 }
        val expected = pixels.copyOf().apply {
            this[2] = gray(140)
            this[7] = gray(140)
            this[12] = gray(140)
        }

        assertArrayEquals(expected, inpaintMaskedPixels(pixels, width = 5, height = 3, mask))
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
    fun cancelledCleanupStopsBeforeChangingPixels() {
        assertThrows(CancellationException::class.java) {
            inpaintMaskedPixels(
                pixels = IntArray(9) { gray(200) },
                width = 3,
                height = 3,
                maskAlpha = IntArray(9).apply { this[4] = 255 },
                isCancelled = { true },
            )
        }
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
}
