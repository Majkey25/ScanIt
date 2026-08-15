package com.majkeylab.scanit

import java.io.IOException
import java.util.concurrent.CancellationException

internal data class OutputReplacementJournalResult(
    val metadata: OutputMetadata,
    val warnings: List<UiMessage>,
)

internal data class OutputReplacementResult(
    val scan: SavedScan,
    val warnings: List<UiMessage>,
)

internal fun pdfReplacementIsUnchanged(
    current: PdfOutputRef?,
    treeUri: String?,
    fingerprint: OutputFingerprint,
): Boolean =
    current?.pending == false &&
        current.treeUri == treeUri &&
        current.outputFingerprint() == fingerprint

internal fun imageReplacementIsUnchanged(
    current: ImageOutputRef,
    treeUri: String?,
    mimeType: String,
    width: Int,
    height: Int,
    format: ImageExportFormat,
    sizePreset: ImageSizePreset = ImageSizePreset.Original,
    customMaxDimension: Int? = null,
    fingerprint: OutputFingerprint,
): Boolean =
    !current.pending &&
        current.treeUri == treeUri &&
        current.mimeType == mimeType &&
        current.width == width &&
        current.height == height &&
        current.format == format &&
        (current.sizePreset ?: ImageSizePreset.Original) == sizePreset &&
        current.customMaxDimension == customMaxDimension &&
        current.outputFingerprint() == fingerprint

internal fun exactReplacementMetadataUpdate(
    current: OutputMetadata,
    expected: OutputMetadata,
    updated: OutputMetadata,
): OutputMetadata {
    if (current != expected) throw IOException("Output metadata changed during replacement")
    if (
        updated.entryId != current.entryId ||
            updated.cacheId != current.cacheId ||
            updated.createdAtEpochMs != current.createdAtEpochMs
    ) {
        throw IOException("Output replacement changed cache identity")
    }
    return updated
}

internal class DurableOutputReplacement(
    private val readMetadata: () -> OutputMetadata,
    private val writeMetadata: (OutputMetadata, OutputMetadata) -> OutputMetadata,
    private val deletePdf: (PdfOutputRef) -> OutputDeleteStatus,
    private val deleteImage: (ImageOutputRef) -> OutputDeleteStatus,
) {
    fun replacePdf(
        create: () -> PdfOutputRef,
        onStaged: (PdfOutputRef) -> Unit = {},
        publish: (PdfOutputRef) -> PdfOutputRef,
        activePdfSizeTarget: PdfSizeTarget? = null,
    ): OutputReplacementJournalResult {
        var current = reconcile().metadata
        if (current.stagedPdf != null || current.retiredPdf != null) {
            throw IOException("PDF output cleanup is still pending")
        }
        val old = current.pdf
        var created: PdfOutputRef? = null
        var staged = false
        var activeCommitted = false
        try {
            created = create()
            current = commit(current, current.copy(stagedPdf = created, version = OUTPUT_METADATA_VERSION))
            staged = true
            onStaged(created)
            val published = publish(created)
            val active =
                current.copy(
                    pdf = published,
                    stagedPdf = null,
                    retiredPdf = old,
                    pdfSizeTarget = activePdfSizeTarget ?: current.pdfSizeTarget,
                    version = OUTPUT_METADATA_VERSION,
                )
            current = commit(current, active)
            activeCommitted = true
            return cleanup(current)
        } catch (failure: Throwable) {
            if (!activeCommitted) {
                rollbackPdf(current, created, staged, failure)
            }
            throw failure
        }
    }

    fun replaceImages(
        pageCount: Int,
        create: (Int) -> ImageOutputRef,
        onStaged: (ImageOutputRef) -> Unit = {},
        publish: (ImageOutputRef) -> ImageOutputRef,
    ): OutputReplacementJournalResult {
        require(pageCount in 1..MAX_SCAN_PAGES) { "Image page count is invalid" }
        var current = reconcile().metadata
        if (current.stagedImages.isNotEmpty() || current.retiredImages.isNotEmpty()) {
            throw IOException("Image output cleanup is still pending")
        }
        val old = current.images
        val created = mutableListOf<ImageOutputRef>()
        var activeCommitted = false
        try {
            for (page in 1..pageCount) {
                val output = create(page)
                created += output
                val expected = current
                current =
                    commit(
                        expected,
                        expected.copy(
                            stagedImages = expected.stagedImages + output,
                            version = OUTPUT_METADATA_VERSION,
                        ),
                    )
                onStaged(output)
            }
            val published = created.map(publish)
            current =
                commit(
                    current,
                    current.copy(
                        images = published,
                        stagedImages = emptyList(),
                        retiredImages = old,
                        version = OUTPUT_METADATA_VERSION,
                    ),
                )
            activeCommitted = true
            return cleanup(current)
        } catch (failure: Throwable) {
            if (!activeCommitted) {
                rollbackImages(current, created, failure)
            }
            throw failure
        }
    }

    fun reconcile(): OutputReplacementJournalResult = cleanup(readMetadata())

    private fun cleanup(initial: OutputMetadata): OutputReplacementJournalResult {
        var failed = false
        fun <T> failedRefs(references: Iterable<T>, delete: (T) -> OutputDeleteStatus): List<T> =
            references.filter { reference ->
                when (deleteSafely { delete(reference) }) {
                    OutputDeleteStatus.Deleted,
                    OutputDeleteStatus.Absent,
                    -> false
                    OutputDeleteStatus.IdentityMismatch,
                    OutputDeleteStatus.Failed,
                    -> {
                        failed = true
                        true
                    }
                }
            }
        val updated =
            initial.copy(
                stagedPdf = failedRefs(listOfNotNull(initial.stagedPdf), deletePdf).singleOrNull(),
                stagedImages = failedRefs(initial.stagedImages, deleteImage),
                retiredPdf = failedRefs(listOfNotNull(initial.retiredPdf), deletePdf).singleOrNull(),
                retiredImages = failedRefs(initial.retiredImages, deleteImage),
            )
        val stored = if (updated == initial) initial else commit(initial, updated)
        return OutputReplacementJournalResult(
            metadata = stored,
            warnings =
                listOf(UiMessage(R.string.shared_output_delete_failed)).takeIf { failed }.orEmpty(),
        )
    }

    private fun rollbackPdf(
        current: OutputMetadata,
        created: PdfOutputRef?,
        staged: Boolean,
        failure: Throwable,
    ) {
        if (created == null) return
        val status = deleteDuringRollback(failure) { deletePdf(created) }
        if (staged && status.isRemoved()) {
            clearStagedPdf(created, failure)
        } else if (!staged && !status.isRemoved()) {
            preserveUnjournaledPdf(current, created, failure)
        }
    }

    private fun rollbackImages(
        current: OutputMetadata,
        created: List<ImageOutputRef>,
        failure: Throwable,
    ) {
        val stagedUris = current.stagedImages.mapTo(mutableSetOf(), ImageOutputRef::uri)
        val removed = mutableSetOf<String>()
        created.forEach { image ->
            if (deleteDuringRollback(failure) { deleteImage(image) }.isRemoved()) {
                removed += image.uri
            }
        }
        if (removed.isNotEmpty() && current.stagedImages.any { it.uri in removed }) {
            suppressCleanupFailure(failure) {
                commit(
                    current,
                    current.copy(stagedImages = current.stagedImages.filterNot { it.uri in removed }),
                )
            }
        }
        val unjournaled = created.filter { it.uri !in stagedUris && it.uri !in removed }
        if (unjournaled.isNotEmpty()) {
            suppressCleanupFailure(failure) {
                val latest = readMetadata()
                commit(
                    latest,
                    latest.copy(
                        stagedImages = latest.stagedImages + unjournaled,
                        version = OUTPUT_METADATA_VERSION,
                    ),
                )
            }
        }
    }

    private fun clearStagedPdf(
        created: PdfOutputRef,
        failure: Throwable,
    ) {
        suppressCleanupFailure(failure) {
            val latest = readMetadata()
            if (latest.stagedPdf == created) commit(latest, latest.copy(stagedPdf = null))
        }
    }

    private fun preserveUnjournaledPdf(
        current: OutputMetadata,
        created: PdfOutputRef,
        failure: Throwable,
    ) {
        suppressCleanupFailure(failure) {
            val latest = readMetadata()
            if (latest == current && latest.stagedPdf == null) {
                commit(
                    latest,
                    latest.copy(stagedPdf = created, version = OUTPUT_METADATA_VERSION),
                )
            }
        }
    }

    private inline fun suppressCleanupFailure(failure: Throwable, cleanup: () -> Unit) {
        try {
            cleanup()
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
    }

    private fun deleteDuringRollback(
        failure: Throwable,
        delete: () -> OutputDeleteStatus,
    ): OutputDeleteStatus =
        try {
            deleteSafely(delete)
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            OutputDeleteStatus.Failed
        }

    private fun commit(expected: OutputMetadata, updated: OutputMetadata): OutputMetadata {
        if (
            updated.entryId != expected.entryId ||
                updated.cacheId != expected.cacheId ||
                updated.createdAtEpochMs != expected.createdAtEpochMs
        ) {
            throw IOException("Output replacement changed cache identity")
        }
        val stored = writeMetadata(expected, updated)
        if (stored != updated) throw IOException("Output replacement metadata was not committed")
        return stored
    }

    private fun deleteSafely(delete: () -> OutputDeleteStatus): OutputDeleteStatus =
        try {
            delete()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            OutputDeleteStatus.Failed
        }

    private fun OutputDeleteStatus.isRemoved(): Boolean =
        this == OutputDeleteStatus.Deleted || this == OutputDeleteStatus.Absent
}
