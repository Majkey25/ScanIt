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
    fun solidStampInteriorsStayFilledAndPaperHolesStayTransparent() {
        val width = 128
        val height = 96
        val paper = 0xFFF0E8E0.toInt()
        for (color in intArrayOf(0xFF444444.toInt(), 0xFFB04040.toInt(), 0xFF4040B0.toInt())) {
            val pixels = IntArray(width * height) { paper }
            for (y in 18..77) for (x in 24..103) pixels[y * width + x] = color
            for (y in 36..59) for (x in 48..79) pixels[y * width + x] = paper

            val ink = extractMarkInk(width, height, pixels)

            for (y in 0 until height) for (x in 0 until width) {
                val inStamp = y in 18..77 && x in 24..103 && !(y in 36..59 && x in 48..79)
                if (inStamp) assertTrue("Missing solid ink at $x,$y", ink[y * width + x] ushr 24 >= 64)
                else assertEquals("Paper remained at $x,$y", 0, ink[y * width + x])
            }
        }
    }

    @Test
    fun pageEdgeCoordinatesStayNormalizedAndRejectLetterboxInput() {
        val edge = normalizedPageMarkPoint(195.47345f, 180f, 320, 180, 512, 1_299)
        assertEquals(MarkPoint(1f, 1f), edge)
        validateNormalizedMarkStrokes(listOf(MarkStroke(listOf(requireNotNull(edge)))))
        val center = requireNotNull(normalizedPageMarkPoint(160f, 90f, 320, 180, 512, 1_299))
        assertEquals(0.5f, center.x, 0.0001f)
        assertEquals(0.5f, center.y, 0.0001f)
        assertNull(normalizedPageMarkPoint(0f, 90f, 320, 180, 512, 1_299))
        assertNull(normalizedPageMarkPoint(160f, 90f, 0, 180, 512, 1_299))
        assertNull(normalizedPageMarkPoint(Float.NaN, 90f, 320, 180, 512, 1_299))
    }

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
    fun fittedRotatedMarkKeepsValidClampRangesAfterRounding() {
        listOf(
            Triple(692f to 1_715f, 56 to 989, -112.27244f),
            Triple(769f to 1_458f, 558 to 562, 116.60336f),
            Triple(1_347f to 1_886f, 208 to 797, 38.321228f),
        ).forEach { (page, mark, rotation) ->
            val placement = MarkPlacement(widthFraction = 0.8f, rotationDegrees = rotation)
            val moved =
                dragMarkPlacement(page.first, page.second, mark.first, mark.second, placement, 1f, -1f)
            val rect = resolveMarkRect(page.first, page.second, mark.first, mark.second, moved)

            assertTrue(moved.centerX in 0f..1f && moved.centerY in 0f..1f)
            assertRotatedBounds(rect, page.first, page.second, rotation)
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
    fun blankWhiteGreyAndTintedPaperContainNoInk() {
        for (paper in intArrayOf(-1, 0xFFF0E4CB.toInt(), 0xFFDCEDFF.toInt(), 0xFF808080.toInt())) {
            val pixels = IntArray(96 * 64) { paper }
            val original = pixels.copyOf()
            val ink = extractMarkInk(96, 64, pixels)

            assertTrue("Blank paper ${paper.toUInt().toString(16)} survived", ink.all { it == 0 })
            assertArrayEquals(original, pixels)
            assertNull(visiblePixelBounds(96, 64, ink))
        }
    }

    @Test
    fun unevenTintedPaperAndBroadShadowAreRemovedWithoutBorderNoise() {
        val width = 192
        val height = 128
        val pixels = shadedPaper(width, height)
        for (y in 0 until height) {
            for (x in 0 until width / 3) {
                val index = y * width + x
                val pixel = pixels[index]
                pixels[index] = opaqueRgb(
                    (pixel ushr 16 and 255) - 25,
                    (pixel ushr 8 and 255) - 25,
                    (pixel and 255) - 25,
                )
            }
        }

        val ink = extractMarkInk(width, height, pixels)

        assertTrue("Blank shaded paper retained ${ink.count { it != 0 }} pixels", ink.all { it == 0 })
    }

    @Test
    fun blackBlueAndRedStrokesAndIsolatedDotsSurviveShadedPaper() {
        val width = 192
        val height = 128
        val pixels = shadedPaper(width, height)
        val colors = intArrayOf(0xFF000000.toInt(), 0xFF153995.toInt(), 0xFFAE1630.toInt())
        for ((index, color) in colors.withIndex()) {
            for (y in 20..100) {
                for (x in 40 + index * 50..42 + index * 50) pixels[y * width + x] = color
            }
        }
        pixels[8 * width + 16] = colors[0]

        val ink = extractMarkInk(width, height, pixels)

        assertEquals(PixelBounds(16, 8, 143, 101), visiblePixelBounds(width, height, ink))
        assertEquals(colors[0], ink[8 * width + 16])
        assertEquals(colors[0], ink[60 * width + 41])
        val blue = ink[60 * width + 91]
        assertTrue(blue ushr 24 > 180 && (blue and 255) > (blue ushr 16 and 255) + 100)
        val red = ink[60 * width + 141]
        assertTrue(red ushr 24 > 180 && (red ushr 16 and 255) > (red and 255) + 100)
        for (index in pixels.indices) {
            if (pixels[index] !in colors) assertEquals("Paper remained at $index", 0, ink[index])
        }
    }

    @Test
    fun paperMattedAntialiasingBecomesColoredAlphaWithoutGreyOrWhiteFringe() {
        val width = 96
        val paper = 0xFFE0D0C0.toInt()
        for (color in intArrayOf(0xFF000000.toInt(), 0xFF0000C0.toInt(), 0xFFC00000.toInt())) {
            val pixels = IntArray(width * 64) { paper }
            for ((index, alpha) in listOf(64, 128, 192, 255).withIndex()) {
                pixels[32 * width + 40 + index] = compositeInk(color, alpha, paper)
            }

            val ink = extractMarkInk(width, 64, pixels)

            for ((index, alpha) in listOf(64, 128, 192, 255).withIndex()) {
                val pixel = ink[32 * width + 40 + index]
                assertEquals(alpha.toDouble(), (pixel ushr 24).toDouble(), 2.0)
                for (shift in listOf(16, 8, 0)) {
                    assertEquals((color ushr shift and 255).toDouble(), (pixel ushr shift and 255).toDouble(), 4.0)
                }
                val onPaper = compositeInk(pixel, pixel ushr 24, paper)
                val onDark = compositeInk(pixel, pixel ushr 24, 0xFF182838.toInt())
                val expectedDark = compositeInk(color, alpha, 0xFF182838.toInt())
                for (shift in listOf(16, 8, 0)) {
                    assertEquals(
                        (pixels[32 * width + 40 + index] ushr shift and 255).toDouble(),
                        (onPaper ushr shift and 255).toDouble(),
                        2.0,
                    )
                    assertEquals((expectedDark ushr shift and 255).toDouble(), (onDark ushr shift and 255).toDouble(), 2.0)
                }
            }
        }
    }

    @Test
    fun existingTransparencyPreservesWhitePaleInkAndAntialiasedEdgesExactly() {
        for (transparent in intArrayOf(0x00ABCDEF, 0x803366CC.toInt())) {
            val pixels = intArrayOf(transparent, -1, 0xFFF0E4CB.toInt(), 0x20406080, 0xFF000000.toInt(), 0x7FFFFFFF)

            assertArrayEquals(pixels, extractMarkInk(3, 2, pixels))
        }
    }

    @Test
    fun onePixelNeutralStrokesSurviveShadingWithCleanRgbAndNoPaperResidue() {
        val width = 192
        val height = 128
        val pixels = shadedPaper(width, height)
        for (y in 16..111) {
            val x = 48 + y / 4
            pixels[y * width + x] = compositeInk(0xFF000000.toInt(), 48, pixels[y * width + x])
        }

        val ink = extractMarkInk(width, height, pixels)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = ink[y * width + x]
                if (y in 16..111 && x == 48 + y / 4) {
                    assertTrue("Thin neutral stroke lost at ($x,$y)", pixel ushr 24 in 40..64)
                    for (shift in listOf(16, 8, 0)) {
                        assertTrue("Paper RGB remained at ($x,$y)", (pixel ushr shift and 255) <= 8)
                    }
                } else {
                    assertEquals("Paper remained at ($x,$y)", 0, pixel)
                }
            }
        }
    }

    @Test
    fun neutralInkAtTheExistingVisibilityLimitSurvives() {
        val pixels = IntArray(9 * 9) { -1 }
        pixels[40] = 0xFFEFEFEF.toInt()

        val ink = extractMarkInk(9, 9, pixels)

        assertEquals(0x10000000, ink[40])
        assertEquals(PixelBounds(4, 4, 5, 5), visiblePixelBounds(9, 9, ink))
    }

    @Test
    fun inkExtractionRejectsInvalidOrUnboundedImages() {
        assertThrows(IllegalArgumentException::class.java) { extractMarkInk(0, 1, intArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { extractMarkInk(2, 2, IntArray(3)) }
        assertThrows(IllegalArgumentException::class.java) {
            extractMarkInk(MARK_DECODE_MAX_SIDE + 1, 1, IntArray(MARK_DECODE_MAX_SIDE + 1))
        }
        assertArrayEquals(intArrayOf(0), extractMarkInk(1, 1, intArrayOf(-1)))
        assertArrayEquals(intArrayOf(0xFF000000.toInt()), extractMarkInk(1, 1, intArrayOf(0xFF000000.toInt())))
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
    fun paperRemovalThenCropRetainsOnlyColoredInk() {
        val converted =
            extractMarkInk(
                3,
                2,
                intArrayOf(
                    0xFFFFFFFF.toInt(),
                    0xFFFF0000.toInt(),
                    0xFFFFFFFF.toInt(),
                    0xFFFFFFFF.toInt(),
                    0xFF0000FF.toInt(),
                    0xFFFFFFFF.toInt(),
                ),
            )
        val bounds = visiblePixelBounds(3, 2, converted)

        assertEquals(PixelBounds(1, 0, 2, 2), bounds)
        assertArrayEquals(
            intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()),
            cropArgbPixels(3, 2, converted, bounds!!),
        )
    }

    @Test
    fun rotatedMarksKeepTheirSizeAndCenterAtEveryPageEdge() {
        for ((markWidth, markHeight) in listOf(400 to 200, 200 to 400, 100 to 1_500)) {
            for (rotation in listOf(-180f, -135f, -90f, -45f, 0f, 45f, 90f, 135f, 180f)) {
                val placement = MarkPlacement(centerY = 0.5f, widthFraction = 0.8f, rotationDegrees = rotation)
                val centered = resolveMarkRect(1_000f, 2_000f, markWidth, markHeight, placement)
                for (x in listOf(0f, 0.5f, 1f)) {
                    for (y in listOf(0f, 0.5f, 1f)) {
                        val atEdge = placement.copy(centerX = x, centerY = y)
                        val rect = resolveMarkRect(1_000f, 2_000f, markWidth, markHeight, atEdge)
                        assertEquals(centered.width, rect.width, 0.001f)
                        assertEquals(centered.height, rect.height, 0.001f)
                        assertEquals(markWidth.toFloat() / markHeight, rect.width / rect.height, 0.001f)
                        val bounds = assertRotatedBounds(rect, 1_000f, 2_000f, rotation)
                        assertEquals(x * 1_000f, when (x) {
                            0f -> bounds.left
                            1f -> bounds.right
                            else -> (rect.left + rect.right) / 2f
                        }, 0.001f)
                        assertEquals(y * 2_000f, when (y) {
                            0f -> bounds.top
                            1f -> bounds.bottom
                            else -> (rect.top + rect.bottom) / 2f
                        }, 0.001f)
                        val unmoved = dragMarkPlacement(1_000f, 2_000f, markWidth, markHeight, atEdge, 0f, 0f)
                        val resolved = resolveMarkRect(1_000f, 2_000f, markWidth, markHeight, unmoved)
                        assertEquals(rect.left, resolved.left, 0.001f)
                        assertEquals(rect.top, resolved.top, 0.001f)
                        assertEquals(rect.right, resolved.right, 0.001f)
                        assertEquals(rect.bottom, resolved.bottom, 0.001f)
                    }
                }
            }
        }
    }

    private fun shadedPaper(width: Int, height: Int): IntArray =
        IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val shade = 120 + 80 * x / (width - 1) + 30 * y / (height - 1)
            val noise = (x * 13 + y * 7) % 5 - 2
            opaqueRgb(shade + noise, shade - 8 + noise, shade - 16 + noise)
        }

    private fun opaqueRgb(red: Int, green: Int, blue: Int): Int =
        (255 shl 24) or (red shl 16) or (green shl 8) or blue

    private fun compositeInk(color: Int, alpha: Int, paper: Int): Int {
        fun channel(shift: Int): Int =
            ((color ushr shift and 255) * alpha + (paper ushr shift and 255) * (255 - alpha) + 127) / 255
        return opaqueRgb(channel(16), channel(8), channel(0))
    }

    private fun assertRotatedBounds(
        rect: MarkRect,
        pageWidth: Float,
        pageHeight: Float,
        rotation: Float,
    ): MarkRect {
        val radians = Math.toRadians(rotation.toDouble())
        val centerX = (rect.left + rect.right) / 2f
        val centerY = (rect.top + rect.bottom) / 2f
        val corners = listOf(rect.left, rect.right).flatMap { x ->
            listOf(rect.top, rect.bottom).map { y ->
                MarkPoint(
                    (centerX + (x - centerX) * Math.cos(radians) - (y - centerY) * Math.sin(radians)).toFloat(),
                    (centerY + (x - centerX) * Math.sin(radians) + (y - centerY) * Math.cos(radians)).toFloat(),
                )
            }
        }
        corners.forEach { point ->
            assertTrue("Rotated X outside page: $point", point.x in -0.001f..pageWidth + 0.001f)
            assertTrue("Rotated Y outside page: $point", point.y in -0.001f..pageHeight + 0.001f)
        }
        return MarkRect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
    }

    private fun assertRect(expected: MarkRect, actual: MarkRect) {
        assertEquals(expected.left, actual.left, 0.0001f)
        assertEquals(expected.top, actual.top, 0.0001f)
        assertEquals(expected.right, actual.right, 0.0001f)
        assertEquals(expected.bottom, actual.bottom, 0.0001f)
    }
}
