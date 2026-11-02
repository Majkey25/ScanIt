package com.majkeylab.scanit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentAnnotationRenderingDeviceTest {
    @Test
    fun highlighterUsesOneTransparentCoverageEvenWhenTheStrokeRetracesItself() {
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        try {
            val highlighter = PageAnnotation.Ink(
                listOf(AnnotationPoint(0.2f, 0.5f), AnnotationPoint(0.8f, 0.5f), AnnotationPoint(0.2f, 0.5f)),
                Color.YELLOW, 0.2f, highlighter = true,
            )
            drawPageAnnotations(Canvas(bitmap), 200, 100, listOf(highlighter))
            assertEquals(80, Color.alpha(bitmap.getPixel(100, 50)))
            assertEquals(80, Color.alpha(bitmap.getPixel(40, 50)))
            assertEquals(255, Color.red(bitmap.getPixel(100, 50)))
            assertEquals(255, Color.green(bitmap.getPixel(100, 50)))
            assertEquals(0, Color.blue(bitmap.getPixel(100, 50)))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun canonicalLandscapeRatioKeepsNearBoundaryTextOnOneLineAtFullAndSampledSizes() {
        val layoutAspectRatio = 3507f / 2481f
        val value = "MMMMMMMM"
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            textLocale = Locale.ROOT
            textSize = 1_000f
        }
        // The full layout allows 636 units; the rounded preview allows only 635 without its source ratio.
        val text = PageAnnotation.Text(value, 0.55f, 0.15f, 635.25f / paint.measureText(value), Color.BLUE)
        val fullBounds = checkNotNull(pageAnnotationTextBounds(text, 3507f, 2481f, layoutAspectRatio))
        val sampledBounds = checkNotNull(pageAnnotationTextBounds(text, 877f, 621f, layoutAspectRatio))
        val legacySampledBounds = checkNotNull(pageAnnotationTextBounds(text, 877f, 621f))
        assertTrue("Fixture must cross the sampled wrap boundary", legacySampledBounds.height() > sampledBounds.height() * 1.5f)
        assertEquals(fullBounds.width() / 2481f, sampledBounds.width() / 621f, 0.00001f)
        assertEquals(fullBounds.height() / 2481f, sampledBounds.height() / 621f, 0.00001f)
        val renderedBands = listOf(3507 to 2481, 877 to 621).map { (width, height) ->
            val bounds = checkNotNull(pageAnnotationTextBounds(text, width.toFloat(), height.toFloat(), layoutAspectRatio))
            assertEquals(text.x, bounds.left / width, 0.00001f)
            assertEquals(text.y, bounds.top / height, 0.00001f)
            assertEquals(setOf(0), annotationsAtPoint(
                listOf(text), AnnotationPoint(bounds.centerX() / width, bounds.centerY() / height),
                width.toFloat(), height.toFloat(), 0f, layoutAspectRatio,
            ))
            assertEquals(emptySet<Int>(), annotationsAtPoint(
                listOf(text), AnnotationPoint(bounds.centerX() / width, (bounds.bottom + bounds.height() / 2f) / height),
                width.toFloat(), height.toFloat(), 0f, layoutAspectRatio,
            ))
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                drawPageAnnotations(Canvas(bitmap), width, height, listOf(text), layoutAspectRatio)
                val bands = renderedLineBands(bitmap)
                assertEquals("Unexpected rendered line count at ${width}x$height", 1, bands.size)
                val band = bands.single()
                assertTrue(band.first >= bounds.top - 1f && band.last <= bounds.bottom + 1f)
                (band.first - bounds.top) / minOf(width, height) to (band.last - bounds.top) / minOf(width, height)
            } finally {
                bitmap.recycle()
            }
        }
        assertEquals(renderedBands[0].first, renderedBands[1].first, 2f / 621f)
        assertEquals(renderedBands[0].second, renderedBands[1].second, 2f / 621f)
    }

    @Test
    fun invalidCanonicalRatiosAreRejectedEvenWithoutVisibleText() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val hidden = PageAnnotation.Text("Text", 0f, 0f, 0.1f, Color.TRANSPARENT)
            for (ratio in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f)) {
                assertThrows(IllegalArgumentException::class.java) {
                    drawPageAnnotations(canvas, 8, 8, emptyList(), ratio)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    pageAnnotationTextBounds(hidden, 8f, 8f, ratio)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    annotationsAtPoint(emptyList(), AnnotationPoint(0.5f, 0.5f), 8f, 8f, 0f, ratio)
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun penPressureChangesWidthAndZeroPressureDoesNotBecomeAHairline() {
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        try {
            val stroke = PageAnnotation.Ink(
                listOf(AnnotationPoint(0.2f, 0.5f, 0.25f), AnnotationPoint(0.8f, 0.5f)),
                Color.RED, 0.2f,
            )
            val zero = PageAnnotation.Ink(listOf(AnnotationPoint(0.5f, 0.2f, 0f)), Color.RED, 0.2f)
            drawPageAnnotations(Canvas(bitmap), 200, 100, listOf(stroke, zero))
            assertEquals(Color.RED, bitmap.getPixel(40, 50))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(40, 55))
            assertEquals(Color.RED, bitmap.getPixel(160, 58))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(100, 20))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun czechMultilineTextWrapsAndItsSelectableBoundsStayInsideThePageAtBothScales() {
        val text = PageAnnotation.Text("Žluťoučký kůň\nPříliš žluťoučký kůň úpěl ďábelské ódy.", 0.55f, 0.15f, 0.12f, Color.BLUE)
        val bounds = pageAnnotationTextBounds(text, 240f, 160f)
        val largerBounds = pageAnnotationTextBounds(text, 480f, 320f)
        assertNotNull(bounds)
        assertNotNull(largerBounds)
        requireNotNull(bounds)
        requireNotNull(largerBounds)
        assertTrue(bounds.left >= 132f && bounds.top >= 24f && bounds.right <= 240f)
        assertEquals(160f, bounds.bottom, 0.01f)
        assertEquals(bounds.left * 2f, largerBounds.left, 0.01f)
        assertEquals(bounds.right * 2f, largerBounds.right, 0.01f)
        assertEquals(bounds.bottom * 2f, largerBounds.bottom, 0.01f)
        assertEquals(setOf(0), annotationsAtPoint(listOf(text), AnnotationPoint(bounds.centerX() / 240f, bounds.centerY() / 160f), 240f, 160f, 0f))
        assertEquals(emptySet<Int>(), annotationsAtPoint(listOf(text), AnnotationPoint(0.1f, 0.5f), 240f, 160f, 0f))
        for (glyph in listOf("Ž", "ř", "ů", "ě")) assertTrue("Missing Czech glyph $glyph", Paint().hasGlyph(glyph))
        val bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        try {
            drawPageAnnotations(Canvas(bitmap), 240, 160, listOf(text))
            var bluePixels = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (x < 132 || y < 24) assertEquals(Color.TRANSPARENT, pixel)
                if (Color.alpha(pixel) > 0) {
                    assertEquals(255, Color.blue(pixel))
                    assertEquals(0, Color.red(pixel))
                    bluePixels++
                }
            }
            assertTrue("No multiline Czech text rendered", bluePixels > 100)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun drawingPreservesThePageBackgroundClipAndCanvasState() {
        val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.DKGRAY)
            val canvas = Canvas(bitmap)
            val saved = canvas.saveCount
            val stroke = PageAnnotation.Ink(listOf(AnnotationPoint(1f, 1f)), Color.RED, 1f)
            drawPageAnnotations(canvas, 80, 80, listOf(stroke))
            assertEquals(saved, canvas.saveCount)
            assertEquals(Color.RED, bitmap.getPixel(79, 79))
            assertEquals(Color.DKGRAY, bitmap.getPixel(80, 80))
            assertEquals(Color.DKGRAY, bitmap.getPixel(0, 0))
            canvas.drawPoint(100f, 100f, Paint().apply { color = Color.GREEN; strokeWidth = 3f })
            assertEquals(Color.GREEN, bitmap.getPixel(100, 100))
            val text = PageAnnotation.Text("Text", 1f, 0f, 0.1f, Color.BLACK)
            assertNull(pageAnnotationTextBounds(text, 80f, 80f))
            assertNull(pageAnnotationTextBounds(text.copy(x = 0f, color = Color.TRANSPARENT), 80f, 80f))
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderedLineBands(bitmap: Bitmap): List<IntRange> {
        val bands = mutableListOf<IntRange>()
        val row = IntArray(bitmap.width)
        var start = -1
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { Color.alpha(it) > 0 }) {
                if (start < 0) start = y
            } else if (start >= 0) {
                bands += start until y
                start = -1
            }
        }
        if (start >= 0) bands += start until bitmap.height
        return bands
    }
}
