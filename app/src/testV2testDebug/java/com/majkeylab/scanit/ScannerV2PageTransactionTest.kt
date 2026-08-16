package com.majkeylab.scanit

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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

    @Test
    fun appearanceChangePublishesOnlyTheSelectedPageAfterTheNewRenderExists() {
        val first = record(rendered = true)
        val second = record(rendered = true)
        val current = manifest(listOf(first, second), selectedIndex = 1)
        val appearance = ScannerV2Appearance(
            ScannerV2Filter.Grayscale,
            intensity = 72,
            shadows = 18,
        )

        val renderFileId = UUID.randomUUID().toString()
        val fingerprint = OutputFingerprint(5, "c".repeat(64))
        val completed = completeScannerV2PageRender(
            current = current,
            pageId = second.pageId,
            crop = second.crop,
            rotationQuarterTurns = second.rotationQuarterTurns,
            appearance = appearance,
            renderFileId = renderFileId,
            renderedFingerprint = fingerprint,
            updatedAtMillis = 2,
        )

        assertEquals(first, completed.pages[0])
        assertEquals(appearance, completed.pages[1].appearance)
        assertEquals(renderFileId, completed.pages[1].renderFileId)
        assertEquals(fingerprint, completed.pages[1].renderedFingerprint)
        assertEquals(current.state, completed.state)
    }

    @Test
    fun appearanceChangeRequiresAnExactCurrentPageAndImmutableRenderIdentity() {
        val page = record(rendered = true)
        val current = manifest(listOf(page), selectedIndex = 0)
        val appearance = ScannerV2Appearance.defaultFor(ScannerV2Filter.Whiteboard)
        val renderFileId = UUID.randomUUID().toString()
        val fingerprint = OutputFingerprint(5, "c".repeat(64))

        val completed = completeScannerV2PageRender(
            current = current,
            pageId = page.pageId,
            crop = page.crop,
            rotationQuarterTurns = page.rotationQuarterTurns,
            appearance = appearance,
            renderFileId = renderFileId,
            renderedFingerprint = fingerprint,
            updatedAtMillis = 3,
        )

        assertEquals(appearance, completed.pages.single().appearance)
        assertEquals(renderFileId, completed.pages.single().renderFileId)
        assertEquals(fingerprint, completed.pages.single().renderedFingerprint)
        assertThrows(IllegalArgumentException::class.java) {
            completeScannerV2PageRender(
                current = current,
                pageId = page.pageId,
                crop = page.crop,
                rotationQuarterTurns = page.rotationQuarterTurns,
                appearance = appearance,
                renderFileId = "not-a-uuid",
                renderedFingerprint = fingerprint,
                updatedAtMillis = 3,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            completeScannerV2PageRender(
                current = current,
                pageId = PageId.parse(UUID.randomUUID().toString()),
                crop = page.crop,
                rotationQuarterTurns = page.rotationQuarterTurns,
                appearance = appearance,
                renderFileId = renderFileId,
                renderedFingerprint = fingerprint,
                updatedAtMillis = 3,
            )
        }
    }

    @Test
    fun firstCropAndLaterCropBothPublishOnlyACompleteImmutableRender() {
        val pending = record(rendered = false)
        val current = manifest(listOf(pending), selectedIndex = 0)
        val crop = PageQuad.create(
            NormalizedPoint(.1, .1),
            NormalizedPoint(.9, .1),
            NormalizedPoint(.9, .9),
            NormalizedPoint(.1, .9),
        )
        val renderFileId = UUID.randomUUID().toString()
        val fingerprint = OutputFingerprint(5, "d".repeat(64))

        val completed = completeScannerV2PageRender(
            current = current,
            pageId = pending.pageId,
            crop = crop,
            rotationQuarterTurns = 1,
            appearance = ScannerV2Appearance.original(),
            renderFileId = renderFileId,
            renderedFingerprint = fingerprint,
            updatedAtMillis = 2,
        )

        assertEquals(crop, completed.pages.single().crop)
        assertEquals(1, completed.pages.single().rotationQuarterTurns)
        assertEquals(renderFileId, completed.pages.single().renderFileId)
        assertEquals(fingerprint, completed.pages.single().renderedFingerprint)
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
        appearance = ScannerV2Appearance.original(),
        renderedFingerprint = if (rendered) OutputFingerprint(2, "b".repeat(64)) else null,
    )
}
