package com.majkeylab.scanit

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ScannerV2CaptureTest {
    @Test
    fun reserveAndCompleteRequireExactPendingIdentity() {
        val initial = emptyManifest()
        val pageId = pageId()
        val reserved = reserveScannerV2Capture(initial, pageId, updatedAtMillis = 2)
        val record = record(pageId)

        val completed = completeScannerV2Capture(reserved, reserved.state.generation, record, updatedAtMillis = 3)

        assertEquals(listOf(record), completed.pages)
        assertEquals(ScannerSessionStage.Reviewing, completed.state.stage)
        assertNull(completed.pendingCaptureId)
        assertThrows(IllegalArgumentException::class.java) {
            completeScannerV2Capture(reserved, reserved.state.generation, record(pageId()), 3)
        }
    }

    @Test
    fun staleCaptureCannotMutateNewGeneration() {
        val initial = emptyManifest()
        val reserved = reserveScannerV2Capture(initial, pageId(), updatedAtMillis = 2)

        val stale = completeScannerV2Capture(
            reserved,
            callbackGeneration = reserved.state.generation + 1,
            record = record(requireNotNull(reserved.pendingCaptureId)),
            updatedAtMillis = 3,
        )

        assertEquals(reserved, stale)
    }

    @Test
    fun cancelClearsPendingAndReturnsToPagesOrCancelled() {
        val empty = reserveScannerV2Capture(emptyManifest(), pageId(), 2)
        val cancelled = cancelScannerV2Capture(empty, empty.state.generation, 3)

        assertEquals(ScannerSessionStage.Cancelled, cancelled.state.stage)
        assertNull(cancelled.pendingCaptureId)
    }

    private fun emptyManifest(): ScannerV2Manifest = ScannerV2Manifest.create(
        sessionId = UUID.randomUUID().toString(),
        state = ScannerSessionGate.start(),
        pages = emptyList(),
        updatedAtMillis = 1,
    )

    private fun pageId(): PageId = PageId.parse(UUID.randomUUID().toString())

    private fun record(pageId: PageId): ScannerV2PageRecord = ScannerV2PageRecord(
        pageId = pageId,
        sourceFingerprint = OutputFingerprint(3, "a".repeat(64)),
        crop = PageQuad.fullFrame(),
        rotationQuarterTurns = 0,
        filterId = "original",
        renderedFingerprint = null,
    )
}
