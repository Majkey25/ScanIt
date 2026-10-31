package com.majkeylab.scanit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VisualMarkRenderingDeviceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun largeSolidStampKeepsItsInteriorAndTransparentLetterHole() {
        val source = Bitmap.createBitmap(2048, 1536, Bitmap.Config.ARGB_8888)
        val paper = Color.rgb(220, 210, 200)
        source.eraseColor(paper)
        val canvas = Canvas(source)
        val paint = Paint().apply { color = Color.rgb(68, 68, 68) }
        canvas.drawRect(400f, 400f, 1600f, 1100f, paint)
        paint.color = paper
        canvas.drawRect(600f, 600f, 800f, 900f, paint)
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            val result = normalizeMarkBitmap(source)
            try {
                assertEquals(1024, result.width)
                assertEquals(597, result.height)
                assertTrue(Color.alpha(result.getPixel(100, 100)) >= 64)
                assertEquals(0, Color.alpha(result.getPixel(256, 256)))
                println("MARK_EXTRACTION_2048_MS=${android.os.SystemClock.elapsedRealtime() - started}")
            } finally {
                result.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun normalizedPhotoRemovesShadedPaperAndKeepsColoredInkWithoutHalo() {
        val width = 192
        val height = 128
        val pixels = IntArray(width * height) { index ->
            val shade = 120 + 80 * (index % width) / (width - 1) + 30 * (index / width) / (height - 1)
            Color.rgb(shade, shade - 8, shade - 16)
        }
        for (y in 20..100) {
            pixels[y * width + 40] = Color.BLACK
            pixels[y * width + 90] = Color.BLUE
            pixels[y * width + 140] = Color.RED
        }
        pixels[60 * width + 41] = Color.rgb(80, 76, 72)
        val source = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            val normalized = normalizeMarkBitmap(source)
            try {
                assertEquals(101, normalized.width)
                assertEquals(81, normalized.height)
                assertEquals(Color.BLACK, normalized.getPixel(0, 40))
                assertEquals(Color.BLUE, normalized.getPixel(50, 40))
                assertEquals(Color.RED, normalized.getPixel(100, 40))
                assertEquals(0, Color.alpha(normalized.getPixel(25, 40)))
                val edge = normalized.getPixel(1, 40)
                assertTrue(Color.alpha(edge) in 100..160)
                assertTrue(Color.red(edge) < 12 && Color.green(edge) < 12 && Color.blue(edge) < 12)
                val dark = Bitmap.createBitmap(101, 81, Bitmap.Config.ARGB_8888)
                try {
                    val background = Color.rgb(24, 40, 56)
                    dark.eraseColor(background)
                    Canvas(dark).drawBitmap(normalized, 0f, 0f, null)
                    assertEquals(background, dark.getPixel(25, 40))
                    val renderedEdge = dark.getPixel(1, 40)
                    assertTrue(Color.red(renderedEdge) < Color.red(background))
                    assertTrue(Color.green(renderedEdge) < Color.green(background))
                    assertTrue(Color.blue(renderedEdge) < Color.blue(background))
                } finally {
                    dark.recycle()
                }
            } finally {
                normalized.recycle()
            }
            assertArrayEquals(pixels, bitmapPixels(source))
            assertFalse(source.isRecycled)
        } finally {
            source.recycle()
        }
    }

    @Test
    fun blankTintedBitmapIsRejectedEvenWhenItsFormatSupportsAlpha() {
        val source = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.rgb(220, 237, 255))
            assertTrue(source.hasAlpha())
            val error = assertThrows(IOException::class.java) { normalizeMarkBitmap(source) }
            assertEquals("The selected mark has no visible ink", error.message)
            assertFalse(source.isRecycled)
        } finally {
            source.recycle()
        }
    }

    @Test
    fun pretransparentPngAndDrawnSignatureKeepTheirAlphaAndRgbWhenNormalizedAgain() {
        val source = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        try {
            for (y in 4..7) {
                source.setPixel(5, y, Color.WHITE)
                source.setPixel(6, y, Color.argb(128, 240, 228, 204))
                source.setPixel(7, y, Color.argb(64, 0, 0, 192))
                source.setPixel(8, y, Color.BLACK)
            }
            val png = temporaryFolder.newFile("transparent-mark.png")
            png.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val decoded = requireNotNull(BitmapFactory.decodeFile(png.path))
            try {
                val normalized = normalizeMarkBitmap(decoded)
                try {
                    assertEquals(4, normalized.width)
                    assertEquals(4, normalized.height)
                    for (y in 0..3) for (x in 0..3) {
                        assertEquals(decoded.getPixel(x + 5, y + 4), normalized.getPixel(x, y))
                    }
                } finally {
                    normalized.recycle()
                }
            } finally {
                decoded.recycle()
            }
            val drawn = renderDrawnMark(listOf(MarkStroke(listOf(MarkPoint(0.2f, 0.5f), MarkPoint(0.8f, 0.5f)))))
            try {
                val normalized = normalizeMarkBitmap(drawn)
                try {
                    val again = normalizeMarkBitmap(normalized)
                    try {
                        assertEquals(normalized.width, again.width)
                        assertEquals(normalized.height, again.height)
                        assertArrayEquals(bitmapPixels(normalized), bitmapPixels(again))
                    } finally {
                        again.recycle()
                    }
                } finally {
                    normalized.recycle()
                }
            } finally {
                drawn.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun exportedLineHasSquareCapsWhileBrushHasRoundCapsAndSourceRemainsUnchanged() {
        val source = createSource()
        val original = source.readBytes()
        val baseline = requireNotNull(BitmapFactory.decodeFile(source.path))
        try {
            assertPixelChannels(baseline, 512, 256, 240..255)
            assertPixelChannels(baseline, 212, 212, 240..255)
            assertPixelChannels(baseline, 812, 300, 240..255)
            assertPixelChannels(baseline, 96, 800, 0..20)

            for (tool in listOf(RedactionTool.Line, RedactionTool.Brush)) {
                val destination = File(temporaryFolder.root, "${tool.name}.jpg")
                assertEquals(
                    JpegDimensions(1024, 1024),
                    renderRedactedJpeg(
                        source = source,
                        destination = destination,
                        regions = emptyList(),
                        strokes = listOf(
                            RedactionStroke(
                                points = listOf(MarkPoint(0.25f, 0.25f), MarkPoint(0.75f, 0.25f)),
                                widthFraction = 0.10f,
                                tool = tool,
                            ),
                        ),
                        isCancelled = { false },
                    ),
                )
                val decoded = requireNotNull(BitmapFactory.decodeFile(destination.path))
                try {
                    assertEquals(1024, decoded.width)
                    assertEquals(1024, decoded.height)
                    assertPixelChannels(decoded, 512, 256, 0..20)
                    assertPixelChannels(decoded, 212, 256, 0..20)
                    // Both corners lie inside square caps but outside round caps.
                    val cornerChannels = if (tool == RedactionTool.Line) 0..20 else 240..255
                    assertPixelChannels(decoded, 212, 212, cornerChannels)
                    assertPixelChannels(decoded, 812, 300, cornerChannels)
                    for ((x, y) in listOf(160 to 256, 512 to 160, 512 to 352, 96 to 800, 160 to 800)) {
                        assertEquals(
                            "$tool changed an unmarked pixel ($x,$y)",
                            baseline.getPixel(x, y),
                            decoded.getPixel(x, y),
                        )
                    }
                } finally {
                    decoded.recycle()
                }
                assertArrayEquals(original, source.readBytes())
            }
            assertEquals(
                setOf("source.jpg", "Line.jpg", "Brush.jpg"),
                requireNotNull(temporaryFolder.root.list()).toSet(),
            )
        } finally {
            baseline.recycle()
        }
    }

    @Test
    fun rotatedMarkReachesRightEdgeWithoutShrinkingAndPreservesSource() {
        val source = createSource()
        val original = source.readBytes()
        val mark = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        try {
            mark.eraseColor(Color.BLUE)
            for (rotation in listOf(-90f, 90f)) {
                val destination = File(temporaryFolder.root, "mark-$rotation.jpg")
                renderMarkedSourceJpeg(
                    source, destination, mark,
                    MarkPlacement(centerX = 1f, centerY = 0.5f, widthFraction = 0.5f, rotationDegrees = rotation),
                    isCancelled = { false },
                )
                val decoded = requireNotNull(BitmapFactory.decodeFile(destination.path))
                try {
                    for (y in listOf(280, 512, 744)) {
                        val pixel = decoded.getPixel(992, y)
                        assertTrue(Color.blue(pixel) > 230 && Color.red(pixel) < 25 && Color.green(pixel) < 25)
                    }
                    assertPixelChannels(decoded, 704, 512, 240..255)
                } finally {
                    decoded.recycle()
                }
            }
            assertArrayEquals(original, source.readBytes())
        } finally {
            mark.recycle()
        }
    }

    @Test
    fun thinRedactionPreviewDoesNotExceedScaledExportCoverage() {
        val points = listOf(MarkPoint(0.25f, 0.5f), MarkPoint(0.75f, 0.5f))
        for (tool in RedactionTool.entries) {
            val bitmap = Bitmap.createBitmap(60, 600, Bitmap.Config.ARGB_8888)
            try {
                androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                    density = androidx.compose.ui.unit.Density(1f),
                    layoutDirection = androidx.compose.ui.unit.LayoutDirection.Ltr,
                    canvas = androidx.compose.ui.graphics.Canvas(Canvas(bitmap)),
                    size = androidx.compose.ui.geometry.Size(60f, 600f),
                ) {
                    drawRedactionStroke(points, 0.01f, tool, 0f, 0f, 60f, 600f)
                }
                val exported = redactionPixelStrokes(
                    600, 6_000, listOf(RedactionStroke(points, 0.01f, tool)),
                ).single()
                val previewCoverage = (0 until bitmap.height).sumOf { Color.alpha(bitmap.getPixel(30, it)) }
                assertEquals(exported.width * 0.1f * 255f, previewCoverage.toFloat(), 8f)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun bitmapPixels(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

    private fun createSource(): File {
        val source = temporaryFolder.newFile("source.jpg")
        val bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            Canvas(bitmap).drawRect(64f, 768f, 128f, 832f, Paint().apply { color = Color.BLACK })
            source.outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
        return source
    }

    private fun assertPixelChannels(bitmap: Bitmap, x: Int, y: Int, expected: IntRange) {
        val pixel = bitmap.getPixel(x, y)
        assertTrue(
            "Pixel ($x,$y) was ${Integer.toHexString(pixel)}, expected RGB channels in $expected",
            Color.red(pixel) in expected && Color.green(pixel) in expected && Color.blue(pixel) in expected,
        )
    }
}
