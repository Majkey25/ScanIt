package com.majkeylab.scanit

import android.provider.DocumentsContract
import android.service.chooser.ChooserResult
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
    }

    @Test
    fun mediaDeleteMustAffectExactlyOneRow() {
        assertTrue(mediaDeleteSucceeded(1))
        assertFalse(mediaDeleteSucceeded(0))
        assertFalse(mediaDeleteSucceeded(2))
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
    fun deleteRequestRejectsCacheReuseAndUnsafeIdentity() {
        val metadata = metadata()
        val exact = OutputDeleteRequest(CACHE_ID, ENTRY_ID, RecentDeleteTarget.Pdf)

        assertSame(metadata, matchingDeleteMetadata(metadata, exact))
        assertNull(matchingDeleteMetadata(metadata.copy(entryId = OTHER_ENTRY_ID), exact))
        assertNull(
            matchingDeleteMetadata(
                metadata,
                exact.copy(cacheId = "../$CACHE_ID"),
            ),
        )
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

    private fun metadata() =
        OutputMetadata(
            entryId = ENTRY_ID,
            cacheId = CACHE_ID,
            createdAtEpochMs = 1L,
            pdf = PdfOutputRef("content://media/external/downloads/1", null),
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
    }
}
