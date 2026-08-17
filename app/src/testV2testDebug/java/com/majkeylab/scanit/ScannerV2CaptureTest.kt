package com.majkeylab.scanit

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun cameraCaptureSizeStaysInsideTheRendererBoundary() {
        assertFalse(isSupportedScannerV2CaptureSize(width = 4080, height = 3060))
        assertTrue(isSupportedScannerV2CaptureSize(width = 4000, height = 3000))
        assertTrue(isSupportedScannerV2CaptureSize(width = 3264, height = 2448))
        assertFalse(isSupportedScannerV2CaptureSize(width = 6001, height = 1000))
        assertFalse(isSupportedScannerV2CaptureSize(width = 0, height = 3000))
    }

    @Test
    fun stableDocumentTriggersAutoCaptureOnce() {
        val gate = ScannerV2AutoCaptureGate()
        val quad = documentQuad()

        assertFalse(gate.update(quad).shouldCapture)
        assertFalse(gate.update(shifted(quad, 0.006)).shouldCapture)
        assertTrue(gate.update(shifted(quad, -0.004)).shouldCapture)
        assertFalse(gate.update(shifted(quad, 0.003)).shouldCapture)
        assertFalse(gate.update(quad).shouldCapture)
    }

    @Test
    fun captureModeDefaultsSafelyAndOnlyAutoModeTriggers() {
        val decision = ScannerV2AutoCaptureDecision(documentQuad(), ready = true, shouldCapture = true)

        assertEquals(ScannerV2CaptureMode.Auto, parseScannerV2CaptureMode(null))
        assertEquals(ScannerV2CaptureMode.Auto, parseScannerV2CaptureMode("unknown"))
        assertEquals(ScannerV2CaptureMode.Manual, parseScannerV2CaptureMode("Manual"))
        assertTrue(shouldScannerV2AutoCapture(ScannerV2CaptureMode.Auto, decision))
        assertFalse(shouldScannerV2AutoCapture(ScannerV2CaptureMode.Manual, decision))
    }

    @Test
    fun isolatedFalseEdgeDoesNotResetConsensusButLostDocumentDoes() {
        val gate = ScannerV2AutoCaptureGate(requiredStableFrames = 3)
        val quad = documentQuad()

        gate.update(quad)
        gate.update(shifted(quad, 0.005))
        assertFalse(gate.update(distractorQuad()).shouldCapture)
        assertTrue(gate.update(shifted(quad, -0.003)).shouldCapture)
        repeat(3) { assertFalse(gate.update(null).shouldCapture) }
        assertFalse(gate.update(quad).shouldCapture)
        assertFalse(gate.update(shifted(quad, 0.004)).shouldCapture)
        assertTrue(gate.update(shifted(quad, -0.003)).shouldCapture)
    }

    @Test
    fun briefMissKeepsGuideWithoutTriggeringCapture() {
        val gate = ScannerV2AutoCaptureGate(requiredStableFrames = 4)
        val quad = documentQuad()

        gate.update(quad)
        gate.update(shifted(quad, 0.004))
        val missed = gate.update(null)

        assertNotNull(missed.guide)
        assertFalse(missed.ready)
        assertFalse(missed.shouldCapture)
    }

    @Test
    fun interleavedFalseEdgesStillConvergeOnRepeatedDocument() {
        val gate = ScannerV2AutoCaptureGate(requiredStableFrames = 4)
        val quad = documentQuad()

        assertFalse(gate.update(quad).shouldCapture)
        assertFalse(gate.update(distractorQuad()).shouldCapture)
        assertFalse(gate.update(shifted(quad, 0.008)).shouldCapture)
        assertFalse(gate.update(distractorQuad()).shouldCapture)
        assertFalse(gate.update(shifted(quad, -0.006)).shouldCapture)
        val decision = gate.update(shifted(quad, 0.004))

        assertTrue(decision.shouldCapture)
        assertTrue(maximumScannerV2CornerMovement(quad, requireNotNull(decision.guide)) < 0.03)
    }

    @Test
    fun smallOrNearlyFullFrameNeverAutoCaptures() {
        val gate = ScannerV2AutoCaptureGate(requiredStableFrames = 2)
        val small = PageQuad.create(
            topLeft = NormalizedPoint(0.40, 0.40),
            topRight = NormalizedPoint(0.60, 0.40),
            bottomRight = NormalizedPoint(0.60, 0.60),
            bottomLeft = NormalizedPoint(0.40, 0.60),
        )
        val full = PageQuad.create(
            topLeft = NormalizedPoint(0.005, 0.005),
            topRight = NormalizedPoint(0.995, 0.005),
            bottomRight = NormalizedPoint(0.995, 0.995),
            bottomLeft = NormalizedPoint(0.005, 0.995),
        )

        repeat(5) { assertFalse(gate.update(small).shouldCapture) }
        repeat(5) { assertFalse(gate.update(full).shouldCapture) }
    }

    @Test
    fun compactDocumentCanAutoCapture() {
        val gate = ScannerV2AutoCaptureGate()
        val compact = PageQuad.create(
            topLeft = NormalizedPoint(0.55, 0.33),
            topRight = NormalizedPoint(0.88, 0.27),
            bottomRight = NormalizedPoint(0.87, 0.56),
            bottomLeft = NormalizedPoint(0.55, 0.55),
        )

        repeat(2) { assertFalse(gate.update(compact).shouldCapture) }
        assertTrue(gate.update(compact).shouldCapture)
    }

    @Test
    fun freshStillDetectionWinsOverOlderLiveGuide() {
        val live = documentQuad()
        val closeStill = shifted(live, 0.03)
        val divergentStill = shifted(live, 0.12)

        assertEquals(closeStill, resolveScannerV2CaptureCrop(live, closeStill, preferSuggested = false))
        assertEquals(
            divergentStill,
            resolveScannerV2CaptureCrop(live, divergentStill, preferSuggested = false),
        )
        assertEquals(live, resolveScannerV2CaptureCrop(live, divergentStill, preferSuggested = true))
        assertEquals(live, resolveScannerV2CaptureCrop(live, null))
        assertEquals(closeStill, resolveScannerV2CaptureCrop(null, closeStill))
        assertEquals(PageQuad.fullFrame(), resolveScannerV2CaptureCrop(null, null))
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
        appearance = ScannerV2Appearance.original(),
        renderedFingerprint = null,
    )

    private fun documentQuad(): PageQuad = PageQuad.create(
        topLeft = NormalizedPoint(0.14, 0.12),
        topRight = NormalizedPoint(0.86, 0.13),
        bottomRight = NormalizedPoint(0.88, 0.90),
        bottomLeft = NormalizedPoint(0.12, 0.89),
    )

    private fun distractorQuad(): PageQuad = PageQuad.create(
        topLeft = NormalizedPoint(0.35, 0.25),
        topRight = NormalizedPoint(0.75, 0.25),
        bottomRight = NormalizedPoint(0.75, 0.75),
        bottomLeft = NormalizedPoint(0.35, 0.75),
    )

    private fun shifted(quad: PageQuad, delta: Double): PageQuad = PageQuad.create(
        topLeft = NormalizedPoint(quad.topLeft.x + delta, quad.topLeft.y),
        topRight = NormalizedPoint(quad.topRight.x + delta, quad.topRight.y),
        bottomRight = NormalizedPoint(quad.bottomRight.x + delta, quad.bottomRight.y),
        bottomLeft = NormalizedPoint(quad.bottomLeft.x + delta, quad.bottomLeft.y),
    )
}
