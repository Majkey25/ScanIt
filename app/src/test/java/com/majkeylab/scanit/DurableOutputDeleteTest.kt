package com.majkeylab.scanit

import android.provider.DocumentsContract
import android.service.chooser.ChooserResult
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableOutputDeleteTest {
    @Test
    fun imageLocationReplacementCopiesExactBytesAndPreservesPdfState() {
        val directory = Files.createTempDirectory("scanit-image-relocation-").toFile()
        try {
            val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
            val fingerprint = readOutputFingerprint(ByteArrayInputStream(bytes), bytes.size.toLong())
            val copied = java.io.File(directory, "page.jpg")

            val pdf = exactPdf("content://media/external/downloads/1")
            val old =
                exactImage(1, "content://media/external/images/media/1").copy(
                    byteLength = fingerprint.byteLength,
                    sha256 = fingerprint.sha256,
                    sizePreset = ImageSizePreset.Original,
                )
            val relocated =
                old.copy(
                    uri = "content://docs/tree/new/document/new%3Apage.jpg",
                    treeUri = "content://docs/tree/new",
                    ownerPackageName = null,
                )
            var metadata = metadata().copy(pdf = pdf, images = listOf(old))
            val events = mutableListOf<String>()
            val replacement =
                replacement(
                    read = { metadata },
                    write = { expected, updated ->
                        assertEquals(expected, metadata)
                        metadata = updated
                        events += if (updated.retiredImages.isEmpty()) "stage" else "active"
                        updated
                    },
                    deleteImage = {
                        events += "delete:${it.uri}"
                        OutputDeleteStatus.Deleted
                    },
                )

            val result =
                replacement.replaceImages(
                    pageCount = 1,
                    create = {
                        copyExactOutput(
                            input = ByteArrayInputStream(bytes),
                            target = copied,
                            fingerprint = fingerprint,
                            isCancelled = { false },
                        )
                        relocated
                    },
                    publish = { it },
                )

            assertArrayEquals(bytes, copied.readBytes())
            assertEquals(pdf, result.metadata.pdf)
            assertEquals(listOf(relocated), result.metadata.images)
            assertEquals(ImageSizePreset.Original, result.metadata.images.single().sizePreset)
            assertEquals(listOf("stage", "active", "delete:${old.uri}", "stage"), events)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun pdfSizeReplacementCommitsTargetWithPdfAndPreservesImages() {
        val old = exactPdf("content://docs/tree/pdf/document/pdf%3Aold.pdf", tree = true)
        val created = exactPdf("content://docs/tree/pdf/document/pdf%3Anew.pdf", tree = true)
        val image = exactImage(1, "content://media/external/images/media/1")
        var metadata =
            metadata().copy(
                pdf = old,
                images = listOf(image),
                pdfSizeTarget = PdfSizeTarget.Original,
            )
        val deleted = mutableListOf<PdfOutputRef>()
        val replacement =
            replacement(
                read = { metadata },
                write = { expected, updated ->
                    assertEquals(expected, metadata)
                    metadata = updated
                    updated
                },
                deletePdf = {
                    deleted += it
                    OutputDeleteStatus.Deleted
                },
            )

        val result =
            replacement.replacePdf(
                create = { created },
                publish = { it },
                activePdfSizeTarget = PdfSizeTarget.Mb5,
            )

        assertEquals(ENTRY_ID, result.metadata.entryId)
        assertEquals(CACHE_ID, result.metadata.cacheId)
        assertEquals(created, result.metadata.pdf)
        assertEquals(old.treeUri, result.metadata.pdf?.treeUri)
        assertEquals(listOf(image), result.metadata.images)
        assertEquals(PdfSizeTarget.Mb5, result.metadata.pdfSizeTarget)
        assertEquals(listOf(old), deleted)
    }

    @Test
    fun pdfSizeReplacementFailureKeepsOldPdfTargetAndImages() {
        val old = exactPdf("content://media/external/downloads/1")
        val created = exactPdf("content://media/external/downloads/2", pending = true)
        val image = exactImage(1, "content://media/external/images/media/1")
        val original =
            metadata().copy(
                pdf = old,
                images = listOf(image),
                pdfSizeTarget = PdfSizeTarget.Original,
            )
        var metadata = original
        val replacement =
            replacement(
                read = { metadata },
                write = { expected, updated ->
                    assertEquals(expected, metadata)
                    metadata = updated
                    updated
                },
                deletePdf = { OutputDeleteStatus.Deleted },
            )

        assertThrows(IOException::class.java) {
            replacement.replacePdf(
                create = { created },
                publish = { throw IOException("publish") },
                activePdfSizeTarget = PdfSizeTarget.Mb5,
            )
        }

        assertEquals(original.pdf, metadata.pdf)
        assertEquals(original.images, metadata.images)
        assertEquals(original.pdfSizeTarget, metadata.pdfSizeTarget)
        assertNull(metadata.stagedPdf)
        assertNull(metadata.retiredPdf)
    }

    @Test
    fun pdfReplacementCommitsNewBeforeDeletingOldAndRetainsFailedCleanup() {
        val old = exactPdf("content://media/external/downloads/1")
        val staged = exactPdf("content://media/external/downloads/2", pending = true)
        val published = staged.copy(pending = false)
        val events = mutableListOf<String>()
        var metadata = metadata().copy(pdf = old)
        var deleteStatus = OutputDeleteStatus.Failed
        val replacement =
            replacement(
                read = { metadata },
                write = { expected, updated ->
                    assertEquals(expected, metadata)
                    metadata = updated
                    events +=
                        when {
                            updated.stagedPdf != null -> "staged"
                            updated.pdf == published && updated.retiredPdf == old -> "active"
                            else -> "cleanup"
                        }
                    updated
                },
                deletePdf = {
                    events += "delete:${it.uri}"
                    deleteStatus
                },
            )

        val first =
            replacement.replacePdf(
                create = {
                    events += "create"
                    staged
                },
                onStaged = {
                    assertEquals(staged, metadata.stagedPdf)
                    events += "marker-clear"
                },
                publish = {
                    events += "publish"
                    published
                },
            )

        assertEquals(
            listOf("create", "staged", "marker-clear", "publish", "active", "delete:${old.uri}"),
            events,
        )
        assertEquals(published, first.metadata.pdf)
        assertEquals(old, first.metadata.retiredPdf)
        assertEquals(R.string.shared_output_delete_failed, first.warnings.single().resourceId)

        deleteStatus = OutputDeleteStatus.Deleted
        val retried = replacement.reconcile()

        assertEquals(published, retried.metadata.pdf)
        assertNull(retried.metadata.retiredPdf)
        assertTrue(retried.warnings.isEmpty())
    }

    @Test
    fun unsavedPdfAndMissingRetiredOutputCompleteWithoutWarning() {
        val output = exactPdf("content://docs/tree/root/document/root%3Ascan.pdf", tree = true)
        var metadata = metadata().copy(pdf = null)
        val replacement =
            replacement(
                read = { metadata },
                write = { expected, updated ->
                    assertEquals(expected, metadata)
                    metadata = updated
                    updated
                },
                deletePdf = { OutputDeleteStatus.Absent },
            )

        val result = replacement.replacePdf(create = { output }, publish = { it })

        assertEquals(output, result.metadata.pdf)
        assertNull(result.metadata.stagedPdf)
        assertNull(result.metadata.retiredPdf)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun partialImageCreationRollsBackOnlyExactJournaledOutputs() {
        val old = exactImage(1, "content://media/external/images/media/1")
        val created = exactImage(1, "content://media/external/images/media/2", pending = true)
        var metadata = metadata().copy(images = listOf(old))
        val deleted = mutableListOf<ImageOutputRef>()
        val replacement =
            replacement(
                read = { metadata },
                write = { expected, updated ->
                    assertEquals(expected, metadata)
                    metadata = updated
                    updated
                },
                deleteImage = {
                    deleted += it
                    OutputDeleteStatus.Deleted
                },
            )

        assertThrows(IOException::class.java) {
            replacement.replaceImages(
                pageCount = 2,
                create = { page ->
                    if (page == 1) created else throw IOException("provider failed")
                },
                publish = { it.copy(pending = false) },
            )
        }

        assertEquals(listOf(created), deleted)
        assertEquals(listOf(old), metadata.images)
        assertTrue(metadata.stagedImages.isEmpty())
    }

    @Test
    fun metadataFailureAndCancellationPreserveOldOutputAndRollbackNew() {
        listOf<Exception>(IOException("metadata"), CancellationException("cancelled")).forEach { failure ->
            val old = exactPdf("content://media/external/downloads/1")
            val created = exactPdf("content://media/external/downloads/2", pending = true)
            var metadata = metadata().copy(pdf = old)
            var writes = 0
            val deleted = mutableListOf<PdfOutputRef>()
            val replacement =
                replacement(
                    read = { metadata },
                    write = { expected, updated ->
                        assertEquals(expected, metadata)
                        writes++
                        if (writes == 2) throw failure
                        metadata = updated
                        updated
                    },
                    deletePdf = {
                        deleted += it
                        OutputDeleteStatus.Deleted
                    },
                )

            assertThrows(failure::class.java) {
                replacement.replacePdf(
                    create = { created },
                    publish = { it.copy(pending = false) },
                )
            }

            assertEquals(old, metadata.pdf)
            assertNull(metadata.stagedPdf)
            assertEquals(listOf(created), deleted)
        }
    }

    @Test
    fun rollbackCancellationDoesNotMaskTheReplacementCancellation() {
        val old = exactPdf("content://media/external/downloads/1")
        val created = exactPdf("content://media/external/downloads/2", pending = true)
        val cancellation = CancellationException("replacement cancelled")
        val cleanupCancellation = CancellationException("cleanup cancelled")
        var metadata = metadata().copy(pdf = old)
        var writes = 0
        val replacement =
            replacement(
                read = { metadata },
                write = { _, updated ->
                    writes++
                    if (writes == 2) throw cancellation
                    metadata = updated
                    updated
                },
                deletePdf = { throw cleanupCancellation },
            )

        val thrown =
            assertThrows(CancellationException::class.java) {
                replacement.replacePdf(
                    create = { created },
                    publish = { it.copy(pending = false) },
                )
            }

        assertSame(cancellation, thrown)
        assertEquals(listOf(cleanupCancellation), thrown.suppressed.toList())
        assertEquals(old, metadata.pdf)
        assertEquals(created, metadata.stagedPdf)
    }

    @Test
    fun safChildCancellationIsNeverConvertedToAnIdentityMismatch() {
        val cancellation = CancellationException("cancelled")

        assertSame(
            cancellation,
            assertThrows(CancellationException::class.java) {
                guardedSafChildCheck { throw cancellation }
            },
        )
        assertFalse(guardedSafChildCheck { throw IOException("provider") })
    }

    @Test
    fun publishReconciliationAcceptsOnlyOneUpdateOrAnExactZeroRowRetry() {
        assertTrue(mediaPublishResultIsAcceptable(1, observedPending = false, sameIdentity = true))
        assertTrue(mediaPublishResultIsAcceptable(0, observedPending = false, sameIdentity = true))
        assertFalse(mediaPublishResultIsAcceptable(2, observedPending = false, sameIdentity = true))
        assertFalse(mediaPublishResultIsAcceptable(0, observedPending = true, sameIdentity = true))
        assertFalse(mediaPublishResultIsAcceptable(1, observedPending = true, sameIdentity = true))
        assertFalse(mediaPublishResultIsAcceptable(1, observedPending = false, sameIdentity = false))
    }

    @Test
    fun successfulReplacementTurnsScratchCleanupFailureIntoAWarning() {
        val result =
            OutputReplacementResult(
                scan = savedScan(),
                warnings = emptyList(),
            )

        val updated = replacementWithScratchCleanupWarning(result, cleanupFailed = true)

        assertEquals(R.string.output_scratch_cleanup_failed, updated.warnings.single().resourceId)
        assertEquals(updated.warnings, updated.scan.warnings)
        assertSame(result, replacementWithScratchCleanupWarning(result, cleanupFailed = false))
    }

    @Test
    fun imageStagingMetadataFailureRollsBackTheUnjournaledCreatedOutput() {
        val created = exactImage(1, "content://media/external/images/media/2", pending = true)
        var metadata = metadata().copy(images = emptyList())
        val deleted = mutableListOf<ImageOutputRef>()
        val replacement =
            replacement(
                read = { metadata },
                write = { _, _ -> throw IOException("metadata") },
                deleteImage = {
                    deleted += it
                    OutputDeleteStatus.Deleted
                },
            )

        assertThrows(IOException::class.java) {
            replacement.replaceImages(
                pageCount = 1,
                create = { created },
                publish = { it.copy(pending = false) },
            )
        }

        assertEquals(listOf(created), deleted)
        assertTrue(metadata.images.isEmpty())
        assertTrue(metadata.stagedImages.isEmpty())
    }

    @Test
    fun providerMismatchAndStaleGenerationFailClosed() {
        val old = exactPdf("content://media/external/downloads/1")
        val created = exactPdf("content://media/external/downloads/2", pending = true)
        var metadata = metadata().copy(pdf = old)
        var staleOnCommit = false
        val replacement =
            replacement(
                read = { metadata },
                write = { expected, updated ->
                    if (staleOnCommit) {
                        metadata = metadata.copy(entryId = OTHER_ENTRY_ID)
                        staleOnCommit = false
                    }
                    if (expected != metadata) throw IOException("stale generation")
                    metadata = updated
                    updated
                },
                deletePdf = { OutputDeleteStatus.Deleted },
            )

        assertThrows(IOException::class.java) {
            replacement.replacePdf(
                create = { created },
                publish = { throw IOException("provider identity mismatch") },
            )
        }
        assertEquals(old, metadata.pdf)
        assertNull(metadata.stagedPdf)

        staleOnCommit = true
        assertThrows(IOException::class.java) {
            replacement.replacePdf(create = { created }, publish = { it.copy(pending = false) })
        }
        assertEquals(OTHER_ENTRY_ID, metadata.entryId)
        assertEquals(old, metadata.pdf)
    }

    @Test
    fun processRecoveryRollsBackStagedAndCleansRetiredIndependently() {
        val active = exactPdf("content://media/external/downloads/1")
        val staged = exactPdf("content://media/external/downloads/2", pending = true)
        val retiredImage = exactImage(1, "content://docs/tree/root/document/root%3Aold.png", tree = true)
        var metadata =
            metadata().copy(
                pdf = active,
                stagedPdf = staged,
                retiredImages = listOf(retiredImage),
                version = OUTPUT_METADATA_VERSION,
            )
        val replacement =
            replacement(
                read = { metadata },
                write = { expected, updated ->
                    assertEquals(expected, metadata)
                    metadata = updated
                    updated
                },
                deletePdf = { OutputDeleteStatus.Absent },
                deleteImage = { OutputDeleteStatus.Deleted },
            )

        val result = replacement.reconcile()

        assertEquals(active, result.metadata.pdf)
        assertNull(result.metadata.stagedPdf)
        assertTrue(result.metadata.retiredImages.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun imageDeleteAndTreeInventorySupportSafPngAndAllJournals() {
        val safPng = exactImage(1, "content://docs/tree/root/document/root%3Apage.png", tree = true)
        val mediaJpeg = exactImage(1, "content://media/external/images/media/8")
        val live =
            metadata().copy(
                images = listOf(safPng),
                stagedPdf = exactPdf("content://docs/tree/stage/document/stage%3Ascan.pdf", tree = true),
                retiredImages = listOf(mediaJpeg.copy(treeUri = "content://docs/tree/retired", ownerPackageName = null)),
                version = OUTPUT_METADATA_VERSION,
            )

        assertTrue(live.hasCompleteExactDeleteInventory(PACKAGE_NAME))
        assertEquals(
            setOf("content://docs/tree/root", "content://docs/tree/stage", "content://docs/tree/retired"),
            completeOutputTreeGrantInventory(
                listOf(OutputMetadataInventoryEntry(sidecarPresent = true, metadata = live)),
            ),
        )
        assertTrue(isExactSafDocument(
            SafDocumentRow(
                documentId = "root:page.png",
                displayName = "page.png",
                mimeType = "image/png",
                flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE,
            ),
            expectedDocumentId = "root:page.png",
            expectedDisplayName = "page.png",
            expectedMimeType = "image/png",
        ))
    }

    @Test
    fun activeImageShareMimeUsesConcreteTypeUnlessSetIsMixed() {
        assertEquals("image/jpeg", activeImageShareMimeType(listOf("image/jpeg")))
        assertEquals("image/png", activeImageShareMimeType(listOf("image/png", "image/png")))
        assertEquals("image/*", activeImageShareMimeType(listOf("image/jpeg", "image/png")))
        assertEquals("image/jpeg", activeImageShareMimeType(listOf(null)))
        assertThrows(IllegalArgumentException::class.java) {
            activeImageShareMimeType(listOf("application/pdf"))
        }
    }

    @Test
    fun unchangedReplacementRequiresExactDestinationFingerprintAndImageShape() {
        val pdf = exactPdf("content://media/external/downloads/1")
        val pdfFingerprint = requireNotNull(pdf.outputFingerprint())
        assertTrue(pdfReplacementIsUnchanged(pdf, null, pdfFingerprint))
        assertFalse(pdfReplacementIsUnchanged(pdf, "content://docs/tree/root", pdfFingerprint))
        assertFalse(
            pdfReplacementIsUnchanged(
                pdf,
                null,
                OutputFingerprint(pdfFingerprint.byteLength, "03".repeat(32)),
            ),
        )

        val image = exactImage(1, "content://media/external/images/media/1")
        assertTrue(
            imageReplacementIsUnchanged(
                image,
                treeUri = null,
                mimeType = "image/jpeg",
                width = 10,
                height = 20,
                format = ImageExportFormat.Jpeg,
                fingerprint = requireNotNull(image.outputFingerprint()),
            ),
        )
        assertFalse(
            imageReplacementIsUnchanged(
                image,
                treeUri = null,
                mimeType = "image/png",
                width = 10,
                height = 20,
                format = ImageExportFormat.Png,
                fingerprint = requireNotNull(image.outputFingerprint()),
            ),
        )
    }
    @Test
    fun mediaItemParserAcceptsOnlyExactNumericImageAndDownloadItems() {
        assertEquals(
            MediaItemAddress(MediaOutputCollection.Images, "external_primary", 12L),
            parseMediaItemAddress("content://media/external_primary/images/media/12"),
        )
        assertEquals(
            MediaItemAddress(MediaOutputCollection.Downloads, "external", 34L),
            parseMediaItemAddress("content://media/external/downloads/34"),
        )
        assertEquals(
            MediaItemAddress(MediaOutputCollection.Downloads, "ABCD-1234", 56L),
            parseMediaItemAddress("content://media/ABCD-1234/downloads/56"),
        )
        listOf(
            "content://media/external/images/media/../12",
            "content://media/external/images/media/not-a-number",
            "content://media/external/images/media/12/extra",
            "content://media/external/downloads/34?query=1",
            "content://media/external/downloads/34#fragment",
            "content://other/external/downloads/34",
        ).forEach { assertNull(parseMediaItemAddress(it)) }
    }

    @Test
    fun mediaRowMustMatchIdNameMimeAndOwner() {
        val expected =
            ExpectedMediaItem(
                id = 12L,
                displayName = "Scan_2026-08-09_12-12-00_01.jpg",
                mimeType = "image/jpeg",
                ownerPackageName = "com.majkeylab.scanit.internal",
            )
        val exact =
            MediaItemRow(
                id = 12L,
                displayName = expected.displayName,
                mimeType = expected.mimeType,
                ownerPackageName = expected.ownerPackageName,
            )

        assertTrue(isExactMediaItem(exact, expected))
        assertFalse(isExactMediaItem(exact.copy(id = 13L), expected))
        assertFalse(isExactMediaItem(exact.copy(displayName = "other.jpg"), expected))
        assertFalse(isExactMediaItem(exact.copy(mimeType = "image/png"), expected))
        assertFalse(isExactMediaItem(exact.copy(ownerPackageName = "other.app"), expected))
        assertFalse(isExactMediaItem(exact.copy(ownerPackageName = null), expected))
    }

    @Test
    fun reopenedOutputMustMatchTheSourceFingerprint() {
        val source =
            readOutputFingerprint(
                ByteArrayInputStream("source".toByteArray()),
                "source".length.toLong(),
            )
        val exact =
            readOutputFingerprint(
                ByteArrayInputStream("source".toByteArray()),
                "source".length.toLong(),
            )
        val corrupted =
            readOutputFingerprint(
                ByteArrayInputStream("broken".toByteArray()),
                "broken".length.toLong(),
            )

        assertTrue(savedOutputMatchesSource(source, exact))
        assertFalse(savedOutputMatchesSource(source, corrupted))
    }

    @Test
    fun fingerprintMismatchAndReplacementFailBeforeDelete() {
        val bytes = "original".toByteArray()
        val fingerprint = readOutputFingerprint(ByteArrayInputStream(bytes), bytes.size.toLong())
        var deleteCalls = 0

        assertEquals(
            OutputDeleteStatus.IdentityMismatch,
            deleteVerifiedMediaOutput(
                fingerprint = fingerprint,
                query = { ExactItemQuery.Exact },
                open = { ByteArrayInputStream("replacement".toByteArray()) },
                delete = { deleteCalls++; 1 },
            ),
        )
        assertEquals(0, deleteCalls)

        var queryCalls = 0
        assertEquals(
            OutputDeleteStatus.IdentityMismatch,
            deleteVerifiedMediaOutput(
                fingerprint = fingerprint,
                query = {
                    queryCalls++
                    if (queryCalls == 1) {
                        ExactItemQuery.Exact
                    } else {
                        ExactItemQuery.IdentityMismatch
                    }
                },
                open = { ByteArrayInputStream(bytes) },
                delete = { deleteCalls++; 1 },
            ),
        )
        assertEquals(0, deleteCalls)
    }

    @Test
    fun mediaBoundaryHandlesDeleteZeroAndProviderFailures() {
        val bytes = "exact".toByteArray()
        val fingerprint = readOutputFingerprint(ByteArrayInputStream(bytes), bytes.size.toLong())
        var queryCalls = 0
        assertEquals(
            OutputDeleteStatus.Absent,
            deleteVerifiedMediaOutput(
                fingerprint = fingerprint,
                query = {
                    queryCalls++
                    if (queryCalls < 3) ExactItemQuery.Exact else ExactItemQuery.Absent
                },
                open = { ByteArrayInputStream(bytes) },
                delete = { 0 },
            ),
        )
        listOf<() -> ExactItemQuery>(
            { throw IOException("query") },
            { ExactItemQuery.Failed },
        ).forEach { query ->
            assertEquals(
                OutputDeleteStatus.Failed,
                deleteVerifiedMediaOutput(fingerprint, query, { ByteArrayInputStream(bytes) }) { 1 },
            )
        }
        assertEquals(
            OutputDeleteStatus.IdentityMismatch,
            deleteVerifiedMediaOutput(
                fingerprint,
                { ExactItemQuery.IdentityMismatch },
                { ByteArrayInputStream(bytes) },
            ) { 1 },
        )
        assertEquals(
            OutputDeleteStatus.Failed,
            deleteVerifiedMediaOutput(fingerprint, { ExactItemQuery.Exact }, { throw IOException("open") }) { 1 },
        )
        assertEquals(
            OutputDeleteStatus.Failed,
            deleteVerifiedMediaOutput(
                fingerprint,
                { ExactItemQuery.Exact },
                {
                    object : java.io.InputStream() {
                        override fun read(): Int = throw IOException("read")
                    }
                },
            ) { 1 },
        )
        assertEquals(
            OutputDeleteStatus.Failed,
            deleteVerifiedMediaOutput(fingerprint, { ExactItemQuery.Exact }, { ByteArrayInputStream(bytes) }) {
                throw IOException("delete")
            },
        )
    }

    @Test
    fun safBoundaryRequiresFingerprintAndIndependentlyConfirmsFalseOrMissingDelete() {
        val bytes = "exact".toByteArray()
        val fingerprint = readOutputFingerprint(ByteArrayInputStream(bytes), bytes.size.toLong())

        assertEquals(
            OutputDeleteStatus.Absent,
            deleteVerifiedSafOutput(
                fingerprint,
                query = { ExactItemQuery.Exact },
                open = { ByteArrayInputStream(bytes) },
                isChild = { true },
                delete = { false },
                confirmAbsent = { OutputDeleteStatus.Absent },
            ),
        )
        assertEquals(
            OutputDeleteStatus.Absent,
            deleteVerifiedSafOutput(
                fingerprint,
                query = { ExactItemQuery.Exact },
                open = { ByteArrayInputStream(bytes) },
                isChild = { true },
                delete = { throw FileNotFoundException("gone") },
                confirmAbsent = { OutputDeleteStatus.Absent },
            ),
        )
        assertEquals(
            OutputDeleteStatus.IdentityMismatch,
            deleteVerifiedSafOutput(
                fingerprint,
                query = { ExactItemQuery.Exact },
                open = { ByteArrayInputStream(bytes) },
                isChild = { false },
                delete = { true },
                confirmAbsent = { OutputDeleteStatus.Absent },
            ),
        )
        assertEquals(
            OutputDeleteStatus.Failed,
            deleteVerifiedSafOutput(
                fingerprint,
                query = { ExactItemQuery.Exact },
                open = { ByteArrayInputStream(bytes) },
                isChild = { true },
                delete = { true },
                confirmAbsent = { OutputDeleteStatus.Failed },
            ),
        )
    }

    @Test
    fun mediaDeleteMustAffectExactlyOneRow() {
        assertTrue(mediaDeleteSucceeded(1))
        assertFalse(mediaDeleteSucceeded(0))
        assertFalse(mediaDeleteSucceeded(2))
    }

    @Test
    fun operationResultDistinguishesPartialMetadataAndCacheFailures() {
        assertEquals(
            OutputDeleteOperationResult.Partial,
            outputDeleteOperationResult(
                listOf(OutputDeleteStatus.Deleted, OutputDeleteStatus.Failed),
                metadataCommitted = true,
                cacheDeletionRequested = true,
                cacheDeleted = false,
            ),
        )
        assertEquals(
            OutputDeleteOperationResult.MetadataFailed,
            outputDeleteOperationResult(
                listOf(OutputDeleteStatus.Absent),
                metadataCommitted = false,
                cacheDeletionRequested = true,
                cacheDeleted = false,
            ),
        )
        assertEquals(
            OutputDeleteOperationResult.CacheFailed,
            outputDeleteOperationResult(
                listOf(OutputDeleteStatus.Deleted),
                metadataCommitted = true,
                cacheDeletionRequested = true,
                cacheDeleted = false,
            ),
        )
        assertEquals(
            OutputDeleteOperationResult.Completed,
            outputDeleteOperationResult(
                listOf(OutputDeleteStatus.Absent),
                metadataCommitted = true,
                cacheDeletionRequested = false,
                cacheDeleted = false,
            ),
        )
        assertEquals(
            OutputDeleteOperationResult.Failed,
            outputDeleteOperationResult(
                listOf(OutputDeleteStatus.Failed),
                metadataCommitted = false,
                cacheDeletionRequested = true,
                cacheDeleted = false,
            ),
        )
        assertEquals(
            OutputDeleteOperationResult.IdentityMismatch,
            outputDeleteOperationResult(
                listOf(OutputDeleteStatus.IdentityMismatch),
                metadataCommitted = true,
                cacheDeletionRequested = false,
                cacheDeleted = false,
            ),
        )
        assertEquals(
            R.string.recent_delete_partial,
            recentDeleteMessage(OutputDeleteOperationResult.Partial)?.resourceId,
        )
        assertEquals(
            R.string.recent_delete_metadata_failed,
            recentDeleteMessage(OutputDeleteOperationResult.MetadataFailed)?.resourceId,
        )
        assertEquals(
            R.string.recent_delete_cache_failed,
            recentDeleteMessage(OutputDeleteOperationResult.CacheFailed)?.resourceId,
        )
    }

    @Test
    fun mediaDeleteIsBoundToTheValidatedRowIdentity() {
        val expected =
            ExpectedMediaItem(
                id = 12L,
                displayName = "Scan_2026-08-09_12-12-00.pdf",
                mimeType = "application/pdf",
                ownerPackageName = "com.majkeylab.scanit.internal",
            )

        assertEquals(
            "_id = ? AND _display_name = ? AND mime_type = ? AND owner_package_name = ?",
            MEDIA_DELETE_SELECTION,
        )
        assertArrayEquals(
            arrayOf("12", expected.displayName, expected.mimeType, expected.ownerPackageName),
            mediaDeleteSelectionArgs(expected),
        )
        assertEquals(
            "$MEDIA_DELETE_SELECTION AND is_pending = 1",
            MEDIA_PUBLISH_SELECTION,
        )
    }

    @Test
    fun safRowMustMatchDocumentNameMimeAndDeleteCapability() {
        val exact =
            SafDocumentRow(
                documentId = "primary:Scans/Scan_2026-08-09_12-12-00.pdf",
                displayName = "Scan_2026-08-09_12-12-00.pdf",
                mimeType = "application/pdf",
                flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE,
            )

        assertTrue(
            isExactSafDocument(
                exact,
                expectedDocumentId = exact.documentId,
                expectedDisplayName = exact.displayName,
            ),
        )
        assertFalse(
            isExactSafDocument(
                exact.copy(documentId = "primary:Scans/other.pdf"),
                expectedDocumentId = exact.documentId,
                expectedDisplayName = exact.displayName,
            ),
        )
        assertFalse(
            isExactSafDocument(
                exact.copy(flags = 0),
                expectedDocumentId = exact.documentId,
                expectedDisplayName = exact.displayName,
            ),
        )
    }

    @Test
    fun safMissingDocumentIsAbsentOnlyWhenRootIsExactAndItIsNoLongerAChild() {
        assertEquals(
            OutputDeleteStatus.Absent,
            safMissingDocumentStatus(rootExact = true, documentIsChild = false),
        )
        assertEquals(
            OutputDeleteStatus.Failed,
            safMissingDocumentStatus(rootExact = false, documentIsChild = false),
        )
        assertEquals(
            OutputDeleteStatus.Failed,
            safMissingDocumentStatus(rootExact = true, documentIsChild = true),
        )
    }

    @Test
    fun deleteRequestRejectsCacheReuseAndUnsafeIdentity() {
        val metadata = metadata()
        val exact = OutputDeleteRequest(CACHE_ID, ENTRY_ID, RecentDeleteTarget.Pdf)

        assertSame(metadata, matchingDeleteMetadata(metadata, exact, PACKAGE_NAME))
        assertNull(matchingDeleteMetadata(metadata.copy(entryId = OTHER_ENTRY_ID), exact, PACKAGE_NAME))
        assertNull(
            matchingDeleteMetadata(
                metadata.copy(pdf = metadata.pdf?.copy(ownerPackageName = null)),
                exact,
                PACKAGE_NAME,
            ),
        )
        assertNull(
            matchingDeleteMetadata(
                metadata.copy(
                    images = listOf(ImageOutputRef(1, "content://media/external/images/media/2")),
                ),
                exact,
                PACKAGE_NAME,
            ),
        )
        assertNull(
            matchingDeleteMetadata(
                metadata,
                exact.copy(cacheId = "../$CACHE_ID"),
                PACKAGE_NAME,
            ),
        )
        assertTrue(outputDeleteTargetIsAbsent(metadata.copy(pdf = null), RecentDeleteTarget.Pdf))
        assertFalse(outputDeleteTargetIsAbsent(metadata, RecentDeleteTarget.Pdf))
    }

    @Test
    fun recentDeleteRequestMustMatchVisibleGenerationAndAvailableChoice() {
        val current = recent(entryId = ENTRY_ID, hasPdf = true, savedImageCount = 1)

        assertTrue(
            recentDeleteRequestAvailable(
                current,
                OutputDeleteRequest(CACHE_ID, ENTRY_ID, RecentDeleteTarget.Pdf),
            ),
        )
        assertFalse(
            recentDeleteRequestAvailable(
                current.copy(entryId = OTHER_ENTRY_ID),
                OutputDeleteRequest(CACHE_ID, ENTRY_ID, RecentDeleteTarget.Pdf),
            ),
        )
        assertFalse(
            recentDeleteRequestAvailable(
                current,
                OutputDeleteRequest(CACHE_ID, ENTRY_ID, RecentDeleteTarget.RemoveFromRecent),
            ),
        )
        assertTrue(
            recentDeleteRequestAvailable(
                recent(entryId = null, hasPdf = false, savedImageCount = 0),
                OutputDeleteRequest(CACHE_ID, null, RecentDeleteTarget.RemoveFromRecent),
            ),
        )
    }

    @Test
    fun cacheDeletionRequiresEveryOutputGoneAndMetadataCommit() {
        assertTrue(mayDeleteRecentCache(allRequestedRemoved = true, metadataCommitted = true))
        assertFalse(mayDeleteRecentCache(allRequestedRemoved = false, metadataCommitted = true))
        assertFalse(mayDeleteRecentCache(allRequestedRemoved = true, metadataCommitted = false))
    }

    @Test
    fun chooserCleanupRequiresSelectedComponentResultAndComponent() {
        assertTrue(
            chooserResultAllowsCleanup(
                ChooserResult.CHOOSER_RESULT_SELECTED_COMPONENT,
                selectedComponentPresent = true,
            ),
        )
        assertFalse(
            chooserResultAllowsCleanup(
                ChooserResult.CHOOSER_RESULT_SELECTED_COMPONENT,
                selectedComponentPresent = false,
            ),
        )
        assertFalse(
            chooserResultAllowsCleanup(
                ChooserResult.CHOOSER_RESULT_COPY,
                selectedComponentPresent = true,
            ),
        )
        assertFalse(
            chooserResultAllowsCleanup(
                ChooserResult.CHOOSER_RESULT_EDIT,
                selectedComponentPresent = true,
            ),
        )
        assertFalse(
            chooserResultAllowsCleanup(
                ChooserResult.CHOOSER_RESULT_UNKNOWN,
                selectedComponentPresent = true,
            ),
        )
    }

    @Test
    fun legacyChooserCleanupRequiresChosenComponent() {
        assertTrue(
            chooserResultAllowsCleanup(
                resultType = null,
                selectedComponentPresent = true,
            ),
        )
        assertFalse(
            chooserResultAllowsCleanup(
                resultType = null,
                selectedComponentPresent = false,
            ),
        )
    }

    @Test
    fun shareCleanupRequestRequiresExactGenerationAndAvailableSavedKind() {
        assertEquals(
            ShareCleanupRequest(CACHE_ID, ENTRY_ID, ShareCleanupKind.Pdf),
            shareCleanupRequest(
                cacheId = CACHE_ID,
                entryId = ENTRY_ID,
                metadataValid = true,
                kind = ShareCleanupKind.Pdf,
                available = true,
                enabled = true,
            ),
        )
        assertEquals(
            ShareCleanupRequest(CACHE_ID, ENTRY_ID, ShareCleanupKind.Images),
            shareCleanupRequest(
                cacheId = CACHE_ID,
                entryId = ENTRY_ID,
                metadataValid = true,
                kind = ShareCleanupKind.Images,
                available = true,
                enabled = true,
            ),
        )
        assertNull(
            shareCleanupRequest(
                CACHE_ID,
                ENTRY_ID,
                metadataValid = true,
                kind = ShareCleanupKind.Pdf,
                available = true,
                enabled = false,
            ),
        )
        assertNull(
            shareCleanupRequest(
                CACHE_ID,
                ENTRY_ID,
                metadataValid = false,
                kind = ShareCleanupKind.Pdf,
                available = true,
                enabled = true,
            ),
        )
        assertNull(
            shareCleanupRequest(
                CACHE_ID,
                ENTRY_ID,
                metadataValid = true,
                kind = ShareCleanupKind.Pdf,
                available = false,
                enabled = true,
            ),
        )
    }

    @Test
    fun shareCleanupExtrasRejectTraversalBadGenerationAndUnknownKind() {
        assertEquals(
            ShareCleanupRequest(CACHE_ID, ENTRY_ID, ShareCleanupKind.Pdf),
            decodeShareCleanupRequest(CACHE_ID, ENTRY_ID, "pdf"),
        )
        assertNull(decodeShareCleanupRequest("../$CACHE_ID", ENTRY_ID, "pdf"))
        assertNull(decodeShareCleanupRequest(CACHE_ID, "not-a-uuid", "pdf"))
        assertNull(decodeShareCleanupRequest(CACHE_ID, ENTRY_ID, "both"))
    }

    @Test
    fun treeGrantReconciliationKeepsCurrentAndLiveSidecarTrees() {
        assertEquals(
            setOf("content://docs/tree/stale"),
            pdfTreeGrantsToRelease(
                persisted =
                    setOf(
                        "content://docs/tree/current",
                        "content://docs/tree/live",
                        "content://docs/tree/stale",
                    ),
                current = "content://docs/tree/current",
                live = setOf("content://docs/tree/live"),
            ),
        )
    }

    @Test
    fun treeGrantInventoryFailsClosedWhenAnyPresentSidecarIsUnreadable() {
        val live =
            metadata().copy(
                pdf =
                    PdfOutputRef(
                        "content://docs/document/1",
                        "content://docs/tree/live",
                        "scan.pdf",
                    ),
            )

        assertEquals(
            setOf("content://docs/tree/live"),
            completePdfTreeGrantInventory(
                listOf(OutputMetadataInventoryEntry(sidecarPresent = true, metadata = live)),
            ),
        )
        assertNull(
            completePdfTreeGrantInventory(
                listOf(
                    OutputMetadataInventoryEntry(sidecarPresent = true, metadata = live),
                    OutputMetadataInventoryEntry(sidecarPresent = true, metadata = null),
                ),
            ),
        )
        assertEquals(
            emptySet<String>(),
            completePdfTreeGrantInventory(
                listOf(OutputMetadataInventoryEntry(sidecarPresent = false, metadata = null)),
            ),
        )
    }

    private fun replacement(
        read: () -> OutputMetadata,
        write: (OutputMetadata, OutputMetadata) -> OutputMetadata,
        deletePdf: (PdfOutputRef) -> OutputDeleteStatus = { OutputDeleteStatus.Deleted },
        deleteImage: (ImageOutputRef) -> OutputDeleteStatus = { OutputDeleteStatus.Deleted },
    ) = DurableOutputReplacement(read, write, deletePdf, deleteImage)

    private fun exactPdf(
        uri: String,
        pending: Boolean = false,
        tree: Boolean = false,
    ) =
        PdfOutputRef(
            uri = uri,
            treeUri = "content://docs/tree/${uri.substringAfter("tree/").substringBefore('/') }".takeIf { tree },
            displayName = "scan.pdf",
            mimeType = "application/pdf",
            ownerPackageName = PACKAGE_NAME.takeUnless { tree },
            byteLength = 4L,
            sha256 = "01".repeat(32),
            pending = pending,
        )

    private fun exactImage(
        page: Int,
        uri: String,
        pending: Boolean = false,
        tree: Boolean = false,
    ) =
        ImageOutputRef(
            page = page,
            uri = uri,
            displayName = if (tree) "page.png" else "page.jpg",
            mimeType = if (tree) "image/png" else "image/jpeg",
            ownerPackageName = PACKAGE_NAME.takeUnless { tree },
            byteLength = 4L,
            sha256 = "02".repeat(32),
            pending = pending,
            treeUri = "content://docs/tree/${uri.substringAfter("tree/").substringBefore('/') }".takeIf { tree },
            width = 10,
            height = 20,
            format = if (tree) ImageExportFormat.Png else ImageExportFormat.Jpeg,
        )

    private fun metadata() =
        OutputMetadata(
            entryId = ENTRY_ID,
            cacheId = CACHE_ID,
            createdAtEpochMs = 1L,
            pdf =
                PdfOutputRef(
                    "content://media/external/downloads/1",
                    null,
                    displayName = "Scan_2026-08-09_12-12-00.pdf",
                    mimeType = "application/pdf",
                    ownerPackageName = PACKAGE_NAME,
                    byteLength = 1L,
                    sha256 = "00".repeat(32),
                ),
        )

    private fun recent(
        entryId: String?,
        hasPdf: Boolean,
        savedImageCount: Int,
    ) =
        RecentScan(
            cacheId = CACHE_ID,
            displayName = CACHE_ID,
            createdAt = java.time.Instant.EPOCH,
            pageCount = 2,
            pdfBytes = 1L,
            firstPage = java.io.File("page.jpg"),
            entryId = entryId,
            hasSavedPdf = hasPdf,
            savedImageCount = savedImageCount,
        )

    private fun savedScan() =
        SavedScan(
            cached =
                CachedScan(
                    baseName = CACHE_ID,
                    pages = listOf(java.io.File("page.jpg")),
                    pdf = java.io.File("scan.pdf"),
                    entryId = ENTRY_ID,
                ),
            savedImages = emptyList(),
            savedPdf = null,
        )

    private companion object {
        const val CACHE_ID = "Scan_2026-08-09_12-12-00"
        const val ENTRY_ID = "00000000-0000-0000-0000-000000000001"
        const val OTHER_ENTRY_ID = "00000000-0000-0000-0000-000000000002"
        const val PACKAGE_NAME = "com.majkeylab.scanit.internal"
    }
}
