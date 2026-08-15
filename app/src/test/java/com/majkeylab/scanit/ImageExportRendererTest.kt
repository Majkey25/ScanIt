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
        assertEquals(2, imageExportDecodeSampleSize(12000, 8000, maxDimension = 3840))
        assertEquals(8, imageExportDecodeSampleSize(16000, 12000, maxDimension = 1600))
    }

    @Test
    fun exifOrientationsMapEveryMirrorAndRotation() {
        assertEquals(
            listOf(
                listOf(ImageExportCorner(1, 0), ImageExportCorner(0, 0), ImageExportCorner(1, 1)),
                listOf(ImageExportCorner(1, 1), ImageExportCorner(0, 1), ImageExportCorner(1, 0)),
                listOf(ImageExportCorner(0, 1), ImageExportCorner(1, 1), ImageExportCorner(0, 0)),
                listOf(ImageExportCorner(0, 0), ImageExportCorner(0, 1), ImageExportCorner(1, 0)),
                listOf(ImageExportCorner(1, 0), ImageExportCorner(1, 1), ImageExportCorner(0, 0)),
                listOf(ImageExportCorner(1, 1), ImageExportCorner(1, 0), ImageExportCorner(0, 1)),
                listOf(ImageExportCorner(0, 1), ImageExportCorner(0, 0), ImageExportCorner(1, 1)),
            ),
            (2..8).map { imageExifOrientation(it).destinationCorners },
        )
        assertEquals(
            listOf(
                ImageExportDimensions(4000, 3000),
                ImageExportDimensions(4000, 3000),
                ImageExportDimensions(4000, 3000),
                ImageExportDimensions(3000, 4000),
                ImageExportDimensions(3000, 4000),
                ImageExportDimensions(3000, 4000),
                ImageExportDimensions(3000, 4000),
            ),
            (2..8).map { orientation ->
                orientedImageExportDimensions(4000, 3000, imageExifOrientation(orientation))
            },
        )
    }

    @Test
    fun targetDimensionsComeFromOrientedSourceBoundsNotSampledBitmap() {
        val plan =
            imageExportRenderPlan(
                width = 12000,
                height = 8000,
                orientation = ImageExifOrientation.Normal,
                maxDimension = 3840,
            )

        assertEquals(ImageExportDimensions(3840, 2560), plan.target)
        assertEquals(2, plan.sampleSize)
        assertEquals(ImageExportDimensions(6000, 4000), plan.sampled)
        assertTrue(plan.tiled)
        assertEquals(43_515_904L, plan.peakBitmapBytes)
    }

    @Test
    fun orientationTransformSampleAccountsForDecodedAndTargetBitmapOverlap() {
        val plan =
            imageExportRenderPlan(
                width = 6000,
                height = 4000,
                orientation = ImageExifOrientation.Rotate90,
                maxDimension = null,
            )

        assertEquals(ImageExportDimensions(2828, 4242), plan.target)
        assertEquals(1, plan.sampleSize)
        assertEquals(ImageExportDimensions(6000, 4000), plan.sampled)
        assertTrue(plan.tiled)
        assertEquals(52_179_808L, plan.peakBitmapBytes)
    }

    @Test
    fun oddSourceDimensionsNeverSelectASampleBelowTheTarget() {
        val plan =
            imageExportRenderPlan(
                width = 11999,
                height = 1000,
                orientation = ImageExifOrientation.Normal,
                maxDimension = 6000,
            )

        assertEquals(ImageExportDimensions(6000, 500), plan.target)
        assertEquals(1, plan.sampleSize)
        assertEquals(ImageExportDimensions(11999, 1000), plan.sampled)
        assertTrue(plan.tiled)
        assertEquals(16_194_304L, plan.peakBitmapBytes)
    }

    @Test
    fun stagedOutputVerificationBoundsPixelsAndEdges() {
        assertEquals(4, imageExportVerificationSampleSize(3840, 2560))
        assertEquals(4, imageExportVerificationSampleSize(20000, 100))
    }

    @Test
    fun regionDecoderFailureCannotAllocateOutput() {
        var outputCreated = false

        assertThrows(IOException::class.java) {
            withImageExportRegionResources<String, String>(
                createDecoder = { throw IOException("decoder failed") },
                createOutput = {
                    outputCreated = true
                    "output"
                },
                releaseDecoder = {},
                releaseOutput = {},
                render = { _, _ -> },
            )
        }

        assertFalse(outputCreated)
    }

    @Test
    fun regionResourcesCleanUpEveryAcquiredResourceOnFailure() {
        var decoderReleases = 0
        var outputReleases = 0

        assertThrows(IOException::class.java) {
            withImageExportRegionResources<String, String>(
                createDecoder = { "decoder" },
                createOutput = { throw IOException("output failed") },
                releaseDecoder = { decoderReleases++ },
                releaseOutput = { outputReleases++ },
                render = { _, _ -> },
            )
        }
        assertEquals(1, decoderReleases)
        assertEquals(0, outputReleases)

        assertThrows(CancellationException::class.java) {
            withImageExportRegionResources<String, String>(
                createDecoder = { "decoder" },
                createOutput = { "output" },
                releaseDecoder = { decoderReleases++ },
                releaseOutput = { outputReleases++ },
                render = { _, _ -> throw CancellationException("cancelled") },
            )
        }
        assertEquals(2, decoderReleases)
        assertEquals(1, outputReleases)
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
