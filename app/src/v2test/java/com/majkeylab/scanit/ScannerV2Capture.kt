package com.majkeylab.scanit

internal fun reserveScannerV2Capture(
    current: ScannerV2Manifest,
    pageId: PageId,
    updatedAtMillis: Long,
): ScannerV2Manifest {
    check(current.state.stage == ScannerSessionStage.Capturing) { "Scanner is not ready to capture" }
    check(current.pendingCaptureId == null) { "Scanner capture is already pending" }
    check(current.retiredPages.isEmpty()) { "Scanner page cleanup is pending" }
    require(current.pages.none { it.pageId == pageId }) { "Scanner capture id is duplicated" }
    return ScannerV2Manifest.create(
        sessionId = current.sessionId,
        state = current.state,
        pages = current.pages,
        retiredPages = current.retiredPages,
        pendingCaptureId = pageId,
        editSource = current.editSource,
        updatedAtMillis = nextScannerV2Timestamp(current, updatedAtMillis),
    )
}

internal fun completeScannerV2Capture(
    current: ScannerV2Manifest,
    callbackGeneration: Long,
    record: ScannerV2PageRecord,
    updatedAtMillis: Long,
): ScannerV2Manifest {
    if (
        current.state.stage != ScannerSessionStage.Capturing ||
            callbackGeneration != current.state.generation ||
            current.pendingCaptureId == null
    ) {
        return current
    }
    require(record.pageId == current.pendingCaptureId) { "Scanner capture identity changed" }
    val nextState = ScannerSessionGate.completeCapture(
        current = current.state,
        callbackGeneration = callbackGeneration,
        page = ScannerPage(record.pageId),
    )
    val replacementIndex = current.state.pendingReplacementIndex
    val retiredPages = if (replacementIndex == null) {
        current.retiredPages
    } else {
        current.retiredPages + current.pages[replacementIndex]
    }
    val pages = if (replacementIndex == null) {
        current.pages + record
    } else {
        current.pages.toMutableList().apply { this[replacementIndex] = record }
    }
    return ScannerV2Manifest.create(
        sessionId = current.sessionId,
        state = nextState,
        pages = pages,
        retiredPages = retiredPages,
        pendingCaptureId = null,
        editSource = current.editSource,
        updatedAtMillis = nextScannerV2Timestamp(current, updatedAtMillis),
    )
}

internal fun cancelScannerV2Capture(
    current: ScannerV2Manifest,
    callbackGeneration: Long,
    updatedAtMillis: Long,
): ScannerV2Manifest {
    if (
        current.state.stage != ScannerSessionStage.Capturing ||
            callbackGeneration != current.state.generation
    ) {
        return current
    }
    val nextState = ScannerSessionGate.cancelCapture(current.state, callbackGeneration)
    return ScannerV2Manifest.create(
        sessionId = current.sessionId,
        state = nextState,
        pages = current.pages,
        retiredPages = current.retiredPages,
        pendingCaptureId = null,
        editSource = current.editSource,
        updatedAtMillis = nextScannerV2Timestamp(current, updatedAtMillis),
    )
}

private fun nextScannerV2Timestamp(current: ScannerV2Manifest, requested: Long): Long {
    require(requested > current.updatedAtMillis) { "Scanner timestamp did not advance" }
    return requested
}
