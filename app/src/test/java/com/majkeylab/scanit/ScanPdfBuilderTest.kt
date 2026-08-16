package com.majkeylab.scanit

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ScanPdfBuilderTest {
    @Test
    fun oversizedPageCountIsRejectedBeforeRenderingOrCreatingWorkingFiles() =
        withTempDirectory { directory ->
            val page =
                ScanPdfBuildPage(
                    longestEdge = 1,
                    renderJpeg = { _, _ -> error("Page rendered before count validation") },
                    createBitonal = { error("Bitonal page created before count validation") },
                )

            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    buildScanPdf(
                        File(directory, "oversized.pdf"),
                        List(MAX_SCAN_PAGES + 1) { page },
                        PdfSizeTarget.Original,
                        bitonalEligible = false,
                    )
                }

            assertEquals("PDF page count exceeds $MAX_SCAN_PAGES", failure.message)
            assertTrue(directory.listFiles()?.isEmpty() == true)
        }

    @Test
    fun mixedPageSizesOnlyDownsamplePagesThatStayLegible() =
        withTempDirectory { directory ->
            val largeSamples = mutableListOf<Int>()
            val smallSamples = mutableListOf<Int>()
            val pages =
                listOf(
                    fakePage(directory, "large", longestEdge = 6_000) { sample ->
                        largeSamples += sample
                        if (sample == 1) 4 * MB else MB
                    },
                    fakePage(directory, "small", longestEdge = 1_000) { sample ->
                        smallSamples += sample
                        2 * MB
                    },
                )

            val result =
                buildScanPdf(
                    File(directory, "mixed.pdf"),
                    pages,
                    PdfSizeTarget.Mb5,
                    bitonalEligible = false,
                )

            assertEquals(listOf(1, 2), largeSamples)
            assertEquals(listOf(1, 1), smallSamples)
            assertEquals(2, result.sampleMultiplier)
            assertTrue(result.targetMet)
        }

    @Test
    fun failedProfilesAreDeletedBeforeTheNextProfileRenders() =
        withTempDirectory { directory ->
            val liveProfileCounts = mutableListOf<Int>()
            val page =
                fakePage(directory, "page", longestEdge = 6_000) { sample ->
                    val buildDirectory =
                        checkNotNull(
                            directory.listFiles()?.singleOrNull {
                                it.isDirectory && it.name.startsWith(".scanit-pdf-build-")
                            },
                        )
                    liveProfileCounts +=
                        buildDirectory.listFiles()!!.count {
                            it.isDirectory && it.name.startsWith("profile-")
                        }
                    when (sample) {
                        1 -> 8 * MB
                        2 -> 7 * MB
                        else -> 6 * MB
                    }
                }

            val result =
                buildScanPdf(
                    File(directory, "smallest.pdf"),
                    listOf(page),
                    PdfSizeTarget.Mb5,
                    bitonalEligible = false,
                )

            assertEquals(listOf(1, 1, 1), liveProfileCounts)
            assertFalse(result.targetMet)
            assertEquals(4, result.sampleMultiplier)
        }

    @Test
    fun boundedTargetPublishesFirstMeasuredProfileThatFits() =
        withTempDirectory { directory ->
            val rendered = mutableListOf<Int>()
            val page =
                fakePage(directory, longestEdge = 5_000) { sample ->
                    rendered += sample
                    if (sample == 1) 6 * MIB else 2 * MIB
                }
            val output = File(directory, "scan.pdf")

            val result = buildScanPdf(output, listOf(page), PdfSizeTarget.Mb5, false)

            assertEquals(listOf(1, 2), rendered)
            assertEquals(2, result.sampleMultiplier)
            assertTrue(result.targetMet)
            assertEquals(PdfEncoding.Jpeg, result.encoding)
            assertEquals(output.length(), result.bytes)
            assertTrue(output.isFile)
            assertTrue(
                output.readText(Charsets.ISO_8859_1).contains("/MediaBox [0 0 1200 480]"),
            )
        }

    @Test
    fun subMegabyteTargetDownsamplesUntilMeasuredPdfFits() =
        withTempDirectory { directory ->
            val rendered = mutableListOf<Int>()
            val page =
                fakePage(directory, longestEdge = 5_000) { sample ->
                    rendered += sample
                    if (sample == 1) 600_000 else 180_000
                }
            val output = File(directory, "small-scan.pdf")

            val result = buildScanPdf(output, listOf(page), PdfSizeTarget.Kb200, false)

            assertEquals(listOf(1, 2), rendered)
            assertEquals(2, result.sampleMultiplier)
            assertTrue(result.targetMet)
            assertTrue(result.bytes <= 200_000L)
        }

    @Test
    fun blackWhiteKeepsSmallerValidBitonalCandidate() =
        withTempDirectory { directory ->
            val output = File(directory, "scan.pdf")
            val page = fakePage(directory, longestEdge = 1_280) { 64 * 1024 }

            val result = buildScanPdf(output, listOf(page), PdfSizeTarget.Original, true)

            assertEquals(PdfEncoding.Bitonal, result.encoding)
            assertTrue(result.bytes < 64 * 1024)
            assertTrue(output.readText(Charsets.ISO_8859_1).contains("/BitsPerComponent 1"))
        }

    @Test
    fun impossibleTargetKeepsSmallestLegibleCandidateAndReportsActualSize() =
        withTempDirectory { directory ->
            val page =
                fakePage(directory, longestEdge = 5_000) { sample ->
                    when (sample) {
                        1 -> 7 * MIB
                        2 -> 6 * MIB
                        else -> error("Unexpected sample")
                    }
                }
            val output = File(directory, "scan.pdf")

            val result = buildScanPdf(output, listOf(page), PdfSizeTarget.Mb5, false)

            assertFalse(result.targetMet)
            assertEquals(2, result.sampleMultiplier)
            assertEquals(output.length(), result.bytes)
            assertTrue(result.bytes > PdfSizeTarget.Mb5.maxBytes!!)
        }

    @Test
    fun cancellationDoesNotPublishOrLeaveWorkingFiles() =
        withTempDirectory { directory ->
            val output = File(directory, "scan.pdf")
            try {
                buildScanPdf(
                    output,
                    listOf(fakePage(directory, longestEdge = 1_280) { 1 }),
                    PdfSizeTarget.Original,
                    false,
                    isCancelled = { true },
                )
                fail("Expected cancellation")
            } catch (_: CancellationException) {}

            assertFalse(output.exists())
            assertTrue(directory.listFiles()?.none { it.name.startsWith(".scanit-pdf-build-") } == true)
        }

    @Test
    fun cancellationBetweenPageRendersStopsImmediatelyAndCleansWorkingFiles() =
        withTempDirectory { directory ->
            var rendered = 0
            val pages =
                List(3) { index ->
                    fakePage(directory, "page-$index", longestEdge = 1_280) {
                        rendered++
                        1
                    }
                }
            val output = File(directory, "scan.pdf")

            assertThrows(CancellationException::class.java) {
                buildScanPdf(
                    output,
                    pages,
                    PdfSizeTarget.Original,
                    bitonalEligible = false,
                    isCancelled = { rendered >= 1 },
                )
            }

            assertEquals(1, rendered)
            assertFalse(output.exists())
            assertTrue(directory.listFiles()?.none { it.name.startsWith(".scanit-pdf-build-") } == true)
        }

    private fun fakePage(
        root: File,
        pageName: String = "page",
        longestEdge: Int,
        jpegBytes: (sampleMultiplier: Int) -> Int,
    ): ScanPdfBuildPage =
        ScanPdfBuildPage(
            longestEdge = longestEdge,
            renderJpeg = { workingDirectory, sample ->
                val file = File(workingDirectory, "$pageName-$sample.jpg")
                file.outputStream().use { output ->
                    val remaining = jpegBytes(sample)
                    val block = ByteArray(minOf(remaining, 8_192)) { 0x41 }
                    var written = 0
                    while (written < remaining) {
                        val count = minOf(block.size, remaining - written)
                        output.write(block, 0, count)
                        written += count
                    }
                }
                JpegPdfPage(
                    file,
                    longestEdge / sample,
                    minOf(2_000, longestEdge) / sample,
                    physicalWidthPixels = longestEdge,
                    physicalHeightPixels = minOf(2_000, longestEdge),
                )
            },
            createBitonal = { jpeg ->
                BitonalPdfPage(
                    jpeg.width,
                    jpeg.height,
                    physicalWidthPixels = jpeg.physicalWidthPixels,
                    physicalHeightPixels = jpeg.physicalHeightPixels,
                ) { _, pixels -> pixels.fill(1) }
            },
        )

    private inline fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("scanit-pdf-builder-test-").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val MB = 1_000_000
        const val MIB = 1024 * 1024
    }
}
