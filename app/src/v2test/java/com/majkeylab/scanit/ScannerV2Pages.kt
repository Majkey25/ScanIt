package com.majkeylab.scanit

internal fun beginScannerV2Retake(
    current: ScannerV2Manifest,
    updatedAtMillis: Long,
): ScannerV2Manifest {
    requireCleanScannerV2Pages(current)
    return ScannerV2Manifest.create(
        sessionId = current.sessionId,
        state = ScannerSessionGate.beginRetake(current.state),
        pages = current.pages,
        updatedAtMillis = nextScannerV2PageTimestamp(current, updatedAtMillis),
    )
}

internal fun deleteScannerV2Page(
    current: ScannerV2Manifest,
    index: Int,
    updatedAtMillis: Long,
): ScannerV2Manifest {
    requireCleanScannerV2Pages(current)
    require(index in current.pages.indices) { "Scanner page deletion is invalid" }
    val retired = current.pages[index]
    val pages = current.pages.toMutableList().apply { removeAt(index) }
    return ScannerV2Manifest.create(
        sessionId = current.sessionId,
        state = ScannerSessionGate.delete(current.state, index),
        pages = pages,
        retiredPages = listOf(retired),
        updatedAtMillis = nextScannerV2PageTimestamp(current, updatedAtMillis),
    )
}

internal fun reorderScannerV2Pages(
    current: ScannerV2Manifest,
    fromIndex: Int,
    toIndex: Int,
    updatedAtMillis: Long,
): ScannerV2Manifest {
    requireCleanScannerV2Pages(current)
    val state = ScannerSessionGate.reorder(current.state, fromIndex, toIndex)
    if (state === current.state) return current
    val byId = current.pages.associateBy(ScannerV2PageRecord::pageId)
    val pages = state.pages.map { page -> requireNotNull(byId[page.id]) }
    return ScannerV2Manifest.create(
        sessionId = current.sessionId,
        state = state,
        pages = pages,
        updatedAtMillis = nextScannerV2PageTimestamp(current, updatedAtMillis),
    )
}

private fun requireCleanScannerV2Pages(current: ScannerV2Manifest) {
    check(current.pendingCaptureId == null) { "Scanner capture is pending" }
    check(current.retiredPages.isEmpty()) { "Scanner page cleanup is pending" }
}

private fun nextScannerV2PageTimestamp(current: ScannerV2Manifest, requested: Long): Long {
    require(requested > current.updatedAtMillis) { "Scanner timestamp did not advance" }
    return requested
}
