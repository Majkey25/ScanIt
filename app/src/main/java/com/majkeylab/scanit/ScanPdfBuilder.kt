package com.majkeylab.scanit

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

internal data class ScanPdfBuildPage(
    val longestEdge: Int,
    val renderJpeg: (workingDirectory: File, sampleMultiplier: Int) -> JpegPdfPage,
    val createBitonal: (JpegPdfPage) -> BitonalPdfPage,
)

internal data class ScanPdfBuildResult(
    val bytes: Long,
    val target: PdfSizeTarget,
    val targetMet: Boolean,
    val sampleMultiplier: Int,
    val encoding: PdfEncoding,
)

internal fun buildScanPdf(
    output: File,
    pages: List<ScanPdfBuildPage>,
    target: PdfSizeTarget,
    bitonalEligible: Boolean,
    isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
): ScanPdfBuildResult {
    require(pages.isNotEmpty() && pages.all { it.longestEdge > 0 }) {
        "PDF build pages must have positive dimensions"
    }
    require(pages.size <= MAX_SCAN_PAGES) {
        "PDF page count exceeds $MAX_SCAN_PAGES"
    }
    val destination = output.absoluteFile
    val parent = requireNotNull(destination.parentFile) { "PDF destination must have a parent" }
    require(parent.isDirectory) { "PDF destination directory does not exist" }
    require(!destination.exists()) { "PDF destination already exists" }
    val workingDirectory =
        Files.createTempDirectory(parent.toPath(), ".scanit-pdf-build-").toFile()
    var publicationStaging: Path? = null
    var failure: Throwable? = null
    try {
        throwIfPdfBuildCancelled(isCancelled)
        val multipliers =
            if (target == PdfSizeTarget.Original) {
                listOf(1)
            } else {
                pdfSampleMultipliers(pages.map(ScanPdfBuildPage::longestEdge))
            }
        var selected: ScanPdfCandidate? = null
        for (sampleMultiplier in multipliers) {
            throwIfPdfBuildCancelled(isCancelled)
            val profileDirectory = File(workingDirectory, "profile-$sampleMultiplier")
            check(profileDirectory.mkdir()) { "PDF profile directory could not be created" }
            var profileFailure: Throwable? = null
            try {
                val renderedPages =
                    pages.map { page ->
                        throwIfPdfBuildCancelled(isCancelled)
                        val pageMultiplier =
                            legiblePdfSampleMultiplier(page.longestEdge, sampleMultiplier)
                        page.renderJpeg(profileDirectory, pageMultiplier)
                    }
                val jpeg = File(profileDirectory, "jpeg.pdf")
                JpegPdfWriter.write(jpeg, renderedPages, isCancelled)
                throwIfPdfBuildCancelled(isCancelled)
                val bitonal =
                    if (bitonalEligible) {
                        writeBitonalCandidate(profileDirectory, renderedPages, pages, isCancelled)
                    } else {
                        null
                    }
                val encoding = selectPdfEncoding(jpeg.length(), bitonal?.length(), bitonalEligible)
                val chosen = if (encoding == PdfEncoding.Bitonal) requireNotNull(bitonal) else jpeg
                val candidateBytes = chosen.length()
                val targetMet = target.maxBytes?.let { candidateBytes <= it } ?: true
                if (selected == null || candidateBytes < selected.bytes) {
                    val retained = File(workingDirectory, "candidate-$sampleMultiplier.pdf")
                    Files.createLink(retained.toPath(), chosen.toPath())
                    val previous = selected
                    selected =
                        ScanPdfCandidate(sampleMultiplier, candidateBytes, encoding, retained)
                    if (previous != null && previous.file.exists() && !previous.file.delete()) {
                        throw IOException("Previous PDF candidate could not be deleted")
                    }
                }
                if (targetMet) break
            } catch (throwable: Throwable) {
                profileFailure = throwable
                throw throwable
            } finally {
                try {
                    deletePdfBuildDirectory(profileDirectory)
                } catch (cleanupFailure: Throwable) {
                    profileFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                }
            }
        }

        val completed = checkNotNull(selected) { "PDF build did not produce a candidate" }
        throwIfPdfBuildCancelled(isCancelled)
        publicationStaging = createPdfPublicationStaging(parent, completed.file)
        deletePdfBuildDirectory(workingDirectory)
        throwIfPdfBuildCancelled(isCancelled)
        publishStagedFileNoReplace(checkNotNull(publicationStaging), destination.toPath())
        publicationStaging = null
        return ScanPdfBuildResult(
            bytes = destination.length(),
            target = target,
            targetMet = target.maxBytes?.let { completed.bytes <= it } ?: true,
            sampleMultiplier = completed.sampleMultiplier,
            encoding = completed.encoding,
        )
    } catch (throwable: Throwable) {
        failure = throwable
        throw throwable
    } finally {
        var cleanupFailure: Throwable? = null
        try {
            deletePdfBuildDirectory(workingDirectory)
        } catch (throwable: Throwable) {
            cleanupFailure = throwable
        }
        try {
            publicationStaging?.let(Files::deleteIfExists)
        } catch (throwable: Throwable) {
            cleanupFailure?.addSuppressed(throwable) ?: run { cleanupFailure = throwable }
        }
        cleanupFailure?.let { throwable ->
            failure?.addSuppressed(throwable) ?: throw throwable
        }
    }
}

private fun writeBitonalCandidate(
    profileDirectory: File,
    renderedPages: List<JpegPdfPage>,
    pages: List<ScanPdfBuildPage>,
    isCancelled: () -> Boolean,
): File? {
    val bitonal = File(profileDirectory, "bitonal.pdf")
    return try {
        val bitonalPages =
            renderedPages.mapIndexed { index, page ->
                throwIfPdfBuildCancelled(isCancelled)
                pages[index].createBitonal(page)
            }
        throwIfPdfBuildCancelled(isCancelled)
        BitonalPdfWriter.write(bitonal, bitonalPages, isCancelled)
        bitonal
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun createPdfPublicationStaging(parent: File, candidate: File): Path {
    val staging = Files.createTempFile(parent.toPath(), ".scanit-pdf-ready-", ".part")
    try {
        Files.delete(staging)
        Files.createLink(staging, candidate.toPath())
        return staging
    } catch (failure: Throwable) {
        try {
            Files.deleteIfExists(staging)
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
}

private fun deletePdfBuildDirectory(directory: File) {
    directory.walkBottomUp().forEach { file ->
        if (file.exists() && !file.delete()) {
            throw IOException("PDF build working file could not be deleted")
        }
    }
}

private fun throwIfPdfBuildCancelled(isCancelled: () -> Boolean) {
    if (isCancelled()) throw CancellationException("PDF build cancelled")
}

private data class ScanPdfCandidate(
    val sampleMultiplier: Int,
    val bytes: Long,
    val encoding: PdfEncoding,
    val file: File,
)
