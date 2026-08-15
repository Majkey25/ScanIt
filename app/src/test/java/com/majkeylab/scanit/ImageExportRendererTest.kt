package com.majkeylab.scanit

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageExportRendererTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exactCopyRequiresOriginalSizeAndFormatWithSupportedSource() {
        val original = ResolvedImageExport(ImageExportFormat.Original, null, null)

        assertTrue(isExactImageExportCopy(original, EncodedImageFormat.Jpeg))
        assertTrue(isExactImageExportCopy(original, EncodedImageFormat.Png))
        assertFalse(
            isExactImageExportCopy(
                original.copy(format = ImageExportFormat.Jpeg),
                EncodedImageFormat.Jpeg,
            ),
        )
        assertFalse(
            isExactImageExportCopy(
                original.copy(maxDimension = 3840),
                EncodedImageFormat.Jpeg,
            ),
        )
        assertFalse(isExactImageExportCopy(original, null))
    }

    @Test
    fun presetAndCustomDimensionsScaleWithoutUpscaling() {
        assertEquals(
            ImageExportDimensions(3840, 2160),
            imageExportDimensions(7680, 4320, maxDimension = 3840),
        )
        assertEquals(
            ImageExportDimensions(2560, 1440),
            imageExportDimensions(7680, 4320, maxDimension = 2560),
        )
        assertEquals(
            ImageExportDimensions(1600, 900),
            imageExportDimensions(7680, 4320, maxDimension = 1600),
        )
        assertEquals(
            ImageExportDimensions(3200, 1800),
            imageExportDimensions(7680, 4320, maxDimension = 3200),
        )
        assertEquals(
            ImageExportDimensions(1200, 800),
            imageExportDimensions(1200, 800, maxDimension = 3840),
        )
    }

    @Test
    fun renderDimensionsStayWithinTwelveMegapixelsAndSixThousandPixels() {
        assertEquals(
            ImageExportDimensions(4242, 2828),
            imageExportDimensions(6000, 4000, maxDimension = null),
        )
        assertEquals(
            ImageExportDimensions(4898, 2449),
            imageExportDimensions(12000, 6000, maxDimension = 6000),
        )
        assertThrows(IllegalArgumentException::class.java) {
            imageExportDimensions(0, 4000, maxDimension = 3840)
        }
        assertThrows(IllegalArgumentException::class.java) {
            imageExportDimensions(4000, -1, maxDimension = 3840)
        }
        assertThrows(IllegalArgumentException::class.java) {
            imageExportDimensions(4000, 3000, maxDimension = 6001)
        }
    }

    @Test
    fun decodeSampleBoundsOversizedInputsBeforeBitmapAllocation() {
        assertEquals(1, imageExportDecodeSampleSize(4000, 3000, maxDimension = null))
        assertEquals(4, imageExportDecodeSampleSize(12000, 8000, maxDimension = 3840))
        assertEquals(8, imageExportDecodeSampleSize(16000, 12000, maxDimension = 1600))
    }

    @Test
    fun jpegAndPngEncodingContractsAreExact() {
        val jpeg = resolveImageExportEncoding(ImageExportFormat.Jpeg, EncodedImageFormat.Png)
        assertEquals("image/jpeg", jpeg.mimeType)
        assertEquals("jpg", jpeg.extension)
        assertEquals(95, jpeg.jpegQuality)

        val png = resolveImageExportEncoding(ImageExportFormat.Png, EncodedImageFormat.Jpeg)
        assertEquals("image/png", png.mimeType)
        assertEquals("png", png.extension)
        assertNull(png.jpegQuality)

        assertEquals(
            EncodedImageFormat.Png,
            resolveImageExportEncoding(ImageExportFormat.Original, EncodedImageFormat.Png),
        )
    }

    @Test
    fun missingEmptyAndUnsupportedSourcesAreRejected() {
        val missing = File(temporaryFolder.root, "missing.jpg")
        val empty = temporaryFolder.newFile("empty.png")
        val unsupported = temporaryFolder.newFile("not-an-image.jpg").apply { writeText("text") }

        assertThrows(IOException::class.java) { requireReadableImageExportSource(missing) }
        assertThrows(IOException::class.java) { requireReadableImageExportSource(empty) }
        assertThrows(IOException::class.java) { requireReadableImageExportSource(unsupported) }
    }

    @Test
    fun cancellationPreservesDestinationAndRemovesStagingFile() {
        val destination = temporaryFolder.newFile("page.jpg").apply { writeText("old") }

        assertThrows(CancellationException::class.java) {
            publishImageExportAtomically(destination, isCancelled = { true }) { staging ->
                staging.writeText("new")
            }
        }

        assertEquals("old", destination.readText())
        assertNoImageExportStagingFiles()
    }

    @Test
    fun failedVerificationPreservesDestinationAndRemovesStagingFile() {
        val destination = temporaryFolder.newFile("page.png").apply { writeText("old") }

        assertThrows(IOException::class.java) {
            publishImageExportAtomically(destination, isCancelled = { false }) { staging ->
                staging.writeText("incomplete")
                throw IOException("verification failed")
            }
        }

        assertEquals("old", destination.readText())
        assertNoImageExportStagingFiles()
    }

    @Test
    fun verifiedStagingAtomicallyReplacesDestination() {
        val destination = temporaryFolder.newFile("page.jpg").apply { writeText("old") }

        publishImageExportAtomically(destination, isCancelled = { false }) { staging ->
            staging.writeText("verified")
        }

        assertEquals("verified", destination.readText())
        assertNoImageExportStagingFiles()
    }

    private fun assertNoImageExportStagingFiles() {
        assertTrue(
            temporaryFolder.root.listFiles().orEmpty().none { it.name.startsWith(".scanit-image-") },
        )
    }
}
