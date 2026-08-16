package com.majkeylab.scanit

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerV2PageTransactionTest {
    @Test
    fun deleteJournalsOnlyRemovedPageBeforeCleanup() {
        val first = record()
        val second = record()
        val current = manifest(listOf(first, second), selectedIndex = 0)

        val deleted = deleteScannerV2Page(current, 0, updatedAtMillis = 2)

        assertEquals(listOf(second), deleted.pages)
        assertEquals(listOf(first), deleted.retiredPages)
        assertEquals(0, deleted.state.selectedIndex)
    }

    @Test
    fun retakeKeepsOldPageJournaledUntilReplacementCleanup() {
        val first = record(rendered = true)
        val second = record(rendered = true)
        val current = manifest(listOf(first, second), selectedIndex = 1)
        val capturing = beginScannerV2Retake(current, updatedAtMillis = 2)
        val pendingId = PageId.parse(UUID.randomUUID().toString())
        val reserved = reserveScannerV2Capture(capturing, pendingId, updatedAtMillis = 3)
        val replacement = record(pageId = pendingId, rendered = false)

        val completed = completeScannerV2Capture(
            reserved,
            reserved.state.generation,
            replacement,
            updatedAtMillis = 4,
        )

        assertEquals(listOf(first, replacement), completed.pages)
        assertEquals(listOf(second), completed.retiredPages)
        assertEquals(1, completed.state.selectedIndex)
    }

    @Test
    fun reorderRetainsExactSelectedPageAndRecords() {
        val pages = List(3) { record(rendered = true) }
        val current = manifest(pages, selectedIndex = 1)

        val reordered = reorderScannerV2Pages(current, fromIndex = 0, toIndex = 2, updatedAtMillis = 2)

        assertEquals(listOf(pages[1], pages[2], pages[0]), reordered.pages)
        assertEquals(0, reordered.state.selectedIndex)
        assertTrue(reordered.retiredPages.isEmpty())
    }

    private fun manifest(
        pages: List<ScannerV2PageRecord>,
        selectedIndex: Int,
    ): ScannerV2Manifest = ScannerV2Manifest.create(
        sessionId = UUID.randomUUID().toString(),
        state = ScannerSessionState.restore(
            generation = 1,
            pages = pages.map { ScannerPage(it.pageId) },
            selectedIndex = selectedIndex,
            stage = ScannerSessionStage.Reviewing,
            pendingReplacementIndex = null,
        ),
        pages = pages,
        updatedAtMillis = 1,
    )

    private fun record(
        pageId: PageId = PageId.parse(UUID.randomUUID().toString()),
        rendered: Boolean = false,
    ): ScannerV2PageRecord = ScannerV2PageRecord(
        pageId = pageId,
        sourceFingerprint = OutputFingerprint(3, "a".repeat(64)),
        crop = PageQuad.fullFrame(),
        rotationQuarterTurns = 0,
        filterId = "original",
        renderedFingerprint = if (rendered) OutputFingerprint(2, "b".repeat(64)) else null,
    )
}
