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
    desiredDisplayName: String? = null,
): Boolean =
    current?.pending == false &&
        current.treeUri == treeUri &&
        (desiredDisplayName == null || current.displayName == desiredDisplayName) &&
        current.outputFingerprint() == fingerprint

internal fun imageReplacementIsUnchanged(
    current: ImageOutputRef,
    treeUri: String?,
    mimeType: String,
    width: Int,
    height: Int,
    format: ImageExportFormat,
    sizePreset: ImageSizePreset? = null,
    customMaxDimension: Int? = null,
    fingerprint: OutputFingerprint,
    desiredDisplayName: String? = null,
): Boolean =
    !current.pending &&
        current.treeUri == treeUri &&
        (desiredDisplayName == null || current.displayName == desiredDisplayName) &&
        current.mimeType == mimeType &&
        current.width == width &&
        current.height == height &&
        current.format == format &&
        current.sizePreset == sizePreset &&
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

internal fun mergePublishedPdfIdentity(
    staged: PdfOutputRef,
    published: PdfOutputRef,
): PdfOutputRef {
    if (
        staged.uri != published.uri ||
            staged.treeUri != published.treeUri ||
            published.pending ||
            published.displayName == null ||
            published.mimeType == null ||
            published.ownerPackageName == null ||
            published.outputFingerprint() == null
    ) {
        throw IOException("Published PDF identity is incomplete")
    }
    return staged.copy(
        displayName = published.displayName,
        mimeType = published.mimeType,
        ownerPackageName = published.ownerPackageName,
        byteLength = published.byteLength,
        sha256 = published.sha256,
        pending = false,
    )
}

internal fun mergePublishedImageIdentity(
    staged: ImageOutputRef,
    published: ImageOutputRef,
): ImageOutputRef {
    if (
        staged.page != published.page ||
            staged.uri != published.uri ||
            staged.treeUri != published.treeUri ||
            published.pending ||
            published.displayName == null ||
            published.mimeType == null ||
            published.ownerPackageName == null ||
            published.outputFingerprint() == null
    ) {
        throw IOException("Published image identity is incomplete")
    }
    return staged.copy(
        displayName = published.displayName,
        mimeType = published.mimeType,
        ownerPackageName = published.ownerPackageName,
        byteLength = published.byteLength,
        sha256 = published.sha256,
        pending = false,
    )
}

internal class DurableOutputReplacement(
    private val readMetadata: () -> OutputMetadata,
    private val writeMetadata: (OutputMetadata, OutputMetadata) -> OutputMetadata,
    private val deletePdf: (PdfOutputRef) -> OutputDeleteStatus,
    private val deleteImage: (ImageOutputRef) -> OutputDeleteStatus,
    private val reconcileStagedPdf: (PdfOutputRef) -> PdfOutputRef = { it },
    private val reconcileStagedImage: (ImageOutputRef) -> ImageOutputRef = { it },
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
            created = publish(created)
            current = commitPublishedPdf(current, created)
            val active =
                current.copy(
                    pdf = created,
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
            created.indices.forEach { index ->
                val published = publish(created[index])
                created[index] = published
                current = commitPublishedImage(current, published)
            }
            val published = created.toList()
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
                rollbackImages(created, failure)
            }
            throw failure
        }
    }

    fun reconcile(): OutputReplacementJournalResult {
        val current = readMetadata()
        val reconciled =
            current.copy(
                stagedPdf = current.stagedPdf?.let(reconcileStagedPdf),
                stagedImages = current.stagedImages.map(reconcileStagedImage),
            )
        return cleanup(if (reconciled == current) current else commit(current, reconciled))
    }

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
        } else if (staged) {
            preserveStagedPdfIdentity(created, failure)
        } else if (!staged && !status.isRemoved()) {
            preserveUnjournaledPdf(current, created, failure)
        }
    }

    private fun rollbackImages(
        created: List<ImageOutputRef>,
        failure: Throwable,
    ) {
        val removed = mutableSetOf<String>()
        created.forEach { image ->
            if (deleteDuringRollback(failure) { deleteImage(image) }.isRemoved()) {
                removed += image.uri
            }
        }
        suppressCleanupFailure(failure) {
            val latest = readMetadata()
            val actualByUri = created.associateBy(ImageOutputRef::uri)
            if (actualByUri.size != created.size) throw IOException("Created image URI is duplicated")
            val updated =
                latest.stagedImages.mapNotNull { staged ->
                    when {
                        staged.uri in removed -> null
                        staged.uri in actualByUri -> actualByUri.getValue(staged.uri)
                        else -> staged
                    }
                }.toMutableList()
            val recordedUris = updated.mapTo(mutableSetOf(), ImageOutputRef::uri)
            updated += created.filter { it.uri !in removed && it.uri !in recordedUris }
            if (updated != latest.stagedImages) {
                commit(
                    latest,
                    latest.copy(
                        stagedImages = updated,
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
            if (latest.stagedPdf?.uri == created.uri) {
                commit(latest, latest.copy(stagedPdf = null))
            }
        }
    }

    private fun preserveStagedPdfIdentity(
        created: PdfOutputRef,
        failure: Throwable,
    ) {
        suppressCleanupFailure(failure) {
            val latest = readMetadata()
            if (latest.stagedPdf?.uri == created.uri && latest.stagedPdf != created) {
                commit(
                    latest,
                    latest.copy(stagedPdf = created, version = OUTPUT_METADATA_VERSION),
                )
            }
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

    private fun commitPublishedPdf(
        current: OutputMetadata,
        published: PdfOutputRef,
    ): OutputMetadata {
        val staged = current.stagedPdf
            ?: throw IOException("Staged PDF metadata is unavailable")
        if (staged.uri != published.uri) throw IOException("Published PDF URI changed")
        if (staged == published) return current
        val verified = mergePublishedPdfIdentity(staged, published)
        return commit(
            current,
            current.copy(stagedPdf = verified, version = OUTPUT_METADATA_VERSION),
        )
    }

    private fun commitPublishedImage(
        current: OutputMetadata,
        published: ImageOutputRef,
    ): OutputMetadata {
        val staged = current.stagedImages.filter { it.uri == published.uri }
        if (staged.size != 1) throw IOException("Staged image metadata is unavailable")
        val verified =
            if (staged.single() == published) published
            else mergePublishedImageIdentity(staged.single(), published)
        val updated =
            current.stagedImages.map { staged ->
                if (staged.uri == published.uri) verified else staged
            }
        if (updated == current.stagedImages) return current
        return commit(
            current,
            current.copy(stagedImages = updated, version = OUTPUT_METADATA_VERSION),
        )
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
