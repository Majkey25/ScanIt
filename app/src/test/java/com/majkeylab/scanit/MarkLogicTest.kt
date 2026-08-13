package com.majkeylab.scanit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkLogicTest {
    @Test
    fun markRectPreservesAspectAndNormalizedPlacement() {
        val rect =
            resolveMarkRect(
                pageWidth = 1_000f,
                pageHeight = 2_000f,
                markWidth = 400,
                markHeight = 200,
                placement = MarkPlacement(),
            )

        assertRect(MarkRect(325f, 1_412.5f, 675f, 1_587.5f), rect)
    }

    @Test
    fun markRectClampsInsideEveryPageEdge() {
        val topLeft =
            resolveMarkRect(1_000f, 2_000f, 200, 100, MarkPlacement(0f, 0f, 0.4f))
        val bottomRight =
            resolveMarkRect(1_000f, 2_000f, 200, 100, MarkPlacement(1f, 1f, 0.4f))

        assertRect(MarkRect(0f, 0f, 400f, 200f), topLeft)
        assertRect(MarkRect(600f, 1_800f, 1_000f, 2_000f), bottomRight)
    }

    @Test
    fun markDragMovesByPreviewPixelsInNormalizedPageCoordinates() {
        val moved =
            dragMarkPlacement(
                pageWidth = 1_000f,
                pageHeight = 2_000f,
                markWidth = 400,
                markHeight = 200,
                placement = MarkPlacement(),
                deltaX = 100f,
                deltaY = -200f,
            )

        assertEquals(0.6f, moved.centerX, 0.0001f)
        assertEquals(0.65f, moved.centerY, 0.0001f)
        assertEquals(0.35f, moved.widthFraction, 0f)
    }

    @Test
    fun markDragClampsActualRectangleToEdgesWithoutDeadZone() {
        val topLeft =
            dragMarkPlacement(
                pageWidth = 1_000f,
                pageHeight = 2_000f,
                markWidth = 200,
                markHeight = 100,
                placement = MarkPlacement(centerX = 0f, centerY = 0f, widthFraction = 0.4f),
                deltaX = -100f,
                deltaY = -100f,
            )
        val bottomRight =
            dragMarkPlacement(
                pageWidth = 1_000f,
                pageHeight = 2_000f,
                markWidth = 200,
                markHeight = 100,
                placement = topLeft,
                deltaX = 10_000f,
                deltaY = 10_000f,
            )

        assertEquals(0.2f, topLeft.centerX, 0.0001f)
        assertEquals(0.05f, topLeft.centerY, 0.0001f)
        assertRect(MarkRect(0f, 0f, 400f, 200f), resolveMarkRect(1_000f, 2_000f, 200, 100, topLeft))
        assertEquals(0.8f, bottomRight.centerX, 0.0001f)
        assertEquals(0.95f, bottomRight.centerY, 0.0001f)
        assertRect(
            MarkRect(600f, 1_800f, 1_000f, 2_000f),
            resolveMarkRect(1_000f, 2_000f, 200, 100, bottomRight),
        )
    }

    @Test
    fun markDragRejectsNonFiniteDeltaAndInvalidGeometry() {
        assertThrows(IllegalArgumentException::class.java) {
            dragMarkPlacement(1_000f, 2_000f, 200, 100, MarkPlacement(), Float.NaN, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            dragMarkPlacement(0f, 2_000f, 200, 100, MarkPlacement(), 0f, 0f)
        }
    }

    @Test
    fun markTransformCombinesPanZoomAndNormalizedRotation() {
        val transformed =
            transformMarkPlacement(
                pageWidth = 1_000f,
                pageHeight = 2_000f,
                markWidth = 400,
                markHeight = 200,
                placement = MarkPlacement(centerX = 0.5f, centerY = 0.5f, rotationDegrees = 170f),
                panX = 100f,
                panY = -200f,
                zoom = 2f,
                rotationDegrees = 30f,
            )

        assertEquals(0.6f, transformed.centerX, 0.0001f)
        assertEquals(0.4f, transformed.centerY, 0.0001f)
        assertEquals(0.7f, transformed.widthFraction, 0.0001f)
        assertEquals(-160f, transformed.rotationDegrees, 0.0001f)
    }

    @Test
    fun markTransformClampsZoomAndRotatedBounds() {
        val minimum =
            transformMarkPlacement(
                1_000f,
                2_000f,
                400,
                200,
                MarkPlacement(widthFraction = MIN_MARK_WIDTH_FRACTION),
                0f,
                0f,
                0.1f,
                0f,
            )
        val maximum =
            transformMarkPlacement(
                1_000f,
                2_000f,
                400,
                200,
                MarkPlacement(centerX = 0f, centerY = 0f, widthFraction = MAX_MARK_WIDTH_FRACTION),
                0f,
                0f,
                2f,
                90f,
            )

        assertEquals(MIN_MARK_WIDTH_FRACTION, minimum.widthFraction, 0f)
        assertEquals(MAX_MARK_WIDTH_FRACTION, maximum.widthFraction, 0f)
        assertEquals(0.2f, maximum.centerX, 0.0001f)
        assertEquals(0.2f, maximum.centerY, 0.0001f)
    }

    @Test
    fun markTransformRejectsMalformedGestureValues() {
        assertThrows(IllegalArgumentException::class.java) {
            transformMarkPlacement(1_000f, 2_000f, 400, 200, MarkPlacement(), 0f, 0f, 0f, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            transformMarkPlacement(
                1_000f,
                2_000f,
                400,
                200,
                MarkPlacement(),
                Float.NaN,
                0f,
                1f,
                0f,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MarkPlacement(rotationDegrees = Float.NaN)
        }
    }

    @Test
    fun tallMarkScalesDownWithoutChangingAspect() {
        val rect =
            resolveMarkRect(
                pageWidth = 1_000f,
                pageHeight = 2_000f,
                markWidth = 100,
                markHeight = 500,
                placement = MarkPlacement(centerX = 0.5f, centerY = 0.5f, widthFraction = 0.5f),
            )

        assertRect(MarkRect(300f, 0f, 700f, 2_000f), rect)
        assertEquals(0.2f, rect.width / rect.height, 0.0001f)
    }

    @Test
    fun markModelsRejectMalformedGeometryButAllowSinglePointDots() {
        assertEquals(MarkPoint(0.25f, 0.75f), MarkStroke(listOf(MarkPoint(0.25f, 0.75f))).points.single())
        assertThrows(IllegalArgumentException::class.java) { MarkPoint(Float.NaN, 0f) }
        assertThrows(IllegalArgumentException::class.java) { MarkStroke(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { MarkPlacement(centerX = -0.01f) }
        assertThrows(IllegalArgumentException::class.java) { MarkPlacement(centerY = Float.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { MarkPlacement(widthFraction = 0f) }
        assertThrows(IllegalArgumentException::class.java) { MarkPlacement(widthFraction = 1.01f) }
        assertThrows(IllegalArgumentException::class.java) { MarkRect(0f, 0f, Float.NaN, 1f) }
        assertThrows(IllegalArgumentException::class.java) { MarkRect(1f, 0f, 1f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { PixelBounds(-1, 0, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { PixelBounds(0, 0, 0, 1) }
    }

    @Test
    fun markRectRejectsInvalidPageAndMarkDimensions() {
        val placement = MarkPlacement()

        assertThrows(IllegalArgumentException::class.java) {
            resolveMarkRect(0f, 1f, 1, 1, placement)
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveMarkRect(1f, Float.NEGATIVE_INFINITY, 1, 1, placement)
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveMarkRect(1f, 1f, 0, 1, placement)
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveMarkRect(1f, 1f, 1, -1, placement)
        }
    }

    @Test
    fun signedBaseNameUsesFirstFreeCollisionSuffix() {
        assertEquals("Scan_2026_Signed", signedBaseName("Scan_2026", emptySet()))
        assertEquals(
            "Scan_2026_Signed_2",
            signedBaseName("Scan_2026", setOf("Scan_2026_Signed", "Scan_2026_Signed_3")),
        )
        assertEquals(
            "Scan_2026_Signed_4",
            signedBaseName(
                "Scan_2026",
                setOf("Scan_2026_Signed", "Scan_2026_Signed_2", "Scan_2026_Signed_3"),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            signedBaseName("../Scan_2026", emptySet())
        }
    }

    @Test
    fun templateIdsAcceptOnlyBoundedGeneratedFileNames() {
        assertTrue(isValidMarkTemplateId("mark_0.png"))
        assertTrue(isValidMarkTemplateId("mark_9223372036854775807.png"))
        assertFalse(isValidMarkTemplateId("mark_-1.png"))
        assertFalse(isValidMarkTemplateId("mark_9223372036854775808.png"))
        assertFalse(isValidMarkTemplateId("../mark_1.png"))
        assertFalse(isValidMarkTemplateId("mark_1.PNG"))
        assertFalse(isValidMarkTemplateId("mark_.png"))
    }

    @Test
    fun whiteRemovalUsesStableThresholdsAndKeepsColoredInk() {
        assertEquals(0x00000000, whiteToTransparentArgb(0xFFFFFFFF.toInt()))
        assertEquals(0xFF000000.toInt(), whiteToTransparentArgb(0xFF000000.toInt()))
        assertEquals(0xFFDCEDFF.toInt(), whiteToTransparentArgb(0xFFDCEDFF.toInt()))
        assertEquals(0x00000000, whiteToTransparentArgb(0xFFF5FAFF.toInt()))
        assertEquals(0x33F0F5FF, whiteToTransparentArgb(0xFFF0F5FF.toInt()))
        assertEquals(0xFF3366CC.toInt(), whiteToTransparentArgb(0xFF3366CC.toInt()))
        assertEquals(0x00000000, whiteToTransparentArgb(0x003366CC))
    }

    @Test
    fun visibleBoundsKeepSinglePixelDotsAndIgnoreFaintNoise() {
        val pixels = IntArray(12)
        pixels[1] = 0x0F000000
        pixels[6] = 0x10010203

        assertEquals(PixelBounds(2, 1, 3, 2), visiblePixelBounds(4, 3, pixels))
        pixels[6] = 0x0F010203
        assertNull(visiblePixelBounds(4, 3, pixels))
    }

    @Test
    fun cropCopiesExactRowsAndRejectsMismatchedInput() {
        val pixels = IntArray(12) { it }
        val bounds = PixelBounds(left = 1, top = 1, right = 4, bottom = 3)

        assertArrayEquals(intArrayOf(5, 6, 7, 9, 10, 11), cropArgbPixels(4, 3, pixels, bounds))
        assertThrows(IllegalArgumentException::class.java) {
            cropArgbPixels(4, 3, IntArray(11), bounds)
        }
        assertThrows(IllegalArgumentException::class.java) {
            cropArgbPixels(4, 3, pixels, PixelBounds(3, 2, 5, 3))
        }
    }

    @Test
    fun whiteRemovalThenCropRetainsOnlyColoredInk() {
        val converted =
            whiteToTransparentArgb(
                intArrayOf(
                    0xFFFFFFFF.toInt(),
                    0xFFFF0000.toInt(),
                    0xFFFFFFFF.toInt(),
                    0xFFFFFFFF.toInt(),
                    0xFFF0F5FF.toInt(),
                    0xFFFFFFFF.toInt(),
                ),
            )
        val bounds = visiblePixelBounds(3, 2, converted)

        assertEquals(PixelBounds(1, 0, 2, 2), bounds)
        assertArrayEquals(
            intArrayOf(0xFFFF0000.toInt(), 0x33F0F5FF),
            cropArgbPixels(3, 2, converted, bounds!!),
        )
    }

    private fun assertRect(expected: MarkRect, actual: MarkRect) {
        assertEquals(expected.left, actual.left, 0.0001f)
        assertEquals(expected.top, actual.top, 0.0001f)
        assertEquals(expected.right, actual.right, 0.0001f)
        assertEquals(expected.bottom, actual.bottom, 0.0001f)
    }
}
