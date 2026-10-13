package com.majkeylab.scanit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VisualMarkRenderingDeviceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

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
