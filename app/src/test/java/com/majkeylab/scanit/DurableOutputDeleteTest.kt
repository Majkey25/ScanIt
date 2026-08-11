package com.majkeylab.scanit

import android.provider.DocumentsContract
import android.service.chooser.ChooserResult
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableOutputDeleteTest {
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

    private companion object {
        const val CACHE_ID = "Scan_2026-08-09_12-12-00"
        const val ENTRY_ID = "00000000-0000-0000-0000-000000000001"
        const val OTHER_ENTRY_ID = "00000000-0000-0000-0000-000000000002"
        const val PACKAGE_NAME = "com.majkeylab.scanit.internal"
    }
}
