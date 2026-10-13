package com.majkeylab.scanit

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfSamplingDeviceTest {
    @Test
    fun copiedLargePageKeepsLegibleResolutionAndPhysicalSize() =
        withDirectory { directory ->
            val source = File(directory, "source.jpg")
            writeJpeg(source, 3_060, 4_080)
            assertSampledPdf(directory, source, source, 1_530, 2_040, "734.4 979.2")
        }

    @Test
    fun alreadyRenderedPageKeepsItsSourceScaleAndPhysicalSize() =
        withDirectory { directory ->
            val source = File(directory, "source.jpg")
            writeJpeg(source, 6_120, 4_080)
            val rendered = File(directory, "rendered.jpg")
            val result = ScanAppearanceRenderer.renderJpeg(
                source,
                rendered,
                googleScannerAppearanceSettings().selected(),
            )
            assertEquals(2, result.sourceSampleSize)
            assertEquals(3_060, result.width)
            assertEquals(2_040, result.height)
            assertSampledPdf(directory, source, rendered, 1_530, 1_020, "734.4 489.6")
        }

    private fun assertSampledPdf(
        directory: File,
        source: File,
        rendered: File,
        width: Int,
        height: Int,
        physicalSize: String,
    ) {
        val original = File(directory, "original.pdf")
        val output = File(directory, "sampled.pdf")
        val appearance = googleScannerAppearanceSettings().selected()
        val full = buildScanPdfFromPages(
            original, listOf(source), listOf(rendered), appearance, PdfSizeTarget.Original,
        )
        val sampled = buildScanPdfFromPages(
            output, listOf(source), listOf(rendered), appearance, PdfSizeTarget.Custom(1),
        )
        assertEquals(1, full.sampleMultiplier)
        assertEquals(2, sampled.sampleMultiplier)
        assertEquals(PdfEncoding.Jpeg, sampled.encoding)
        assertFalse(sampled.targetMet)
        assertTrue(sampled.bytes < full.bytes)
        val pdf = output.readText(Charsets.ISO_8859_1)
        assertTrue(pdf.contains("/Width $width /Height $height "))
        val mediaBox = "/MediaBox [0 0 $physicalSize]"
        assertTrue(pdf.contains(mediaBox))
        assertTrue(original.readText(Charsets.ISO_8859_1).contains(mediaBox))
    }

    private fun writeJpeg(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        try {
            bitmap.eraseColor(Color.WHITE)
            file.outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun withDirectory(test: (File) -> Unit) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val directory = Files.createTempDirectory(cache.toPath(), "pdf-sampling-test-").toFile()
        try {
            test(directory)
        } finally {
            assertTrue(directory.deleteRecursively())
        }
    }
}
