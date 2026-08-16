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
        editSource = current.editSource,
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
        editSource = current.editSource,
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
        editSource = current.editSource,
        updatedAtMillis = nextScannerV2PageTimestamp(current, updatedAtMillis),
    )
}

internal fun completeScannerV2PageRender(
    current: ScannerV2Manifest,
    pageId: PageId,
    crop: PageQuad,
    rotationQuarterTurns: Int,
    appearance: ScannerV2Appearance,
    renderFileId: String,
    renderedFingerprint: OutputFingerprint,
    updatedAtMillis: Long,
): ScannerV2Manifest {
    requireCleanScannerV2Pages(current)
    check(current.state.stage == ScannerSessionStage.Reviewing) { "Scanner session is not reviewable" }
    val selected = requireNotNull(current.state.selectedIndex) { "Selected scanner page is missing" }
    val page = current.pages[selected]
    require(page.pageId == pageId) { "Scanner appearance page changed" }
    require(isCanonicalUuid(renderFileId)) { "Scanner render id is invalid" }
    val pages = current.pages.toMutableList().apply {
        this[selected] = page.copy(
            crop = crop,
            rotationQuarterTurns = rotationQuarterTurns,
            appearance = appearance,
            renderedFingerprint = renderedFingerprint,
            renderFileId = renderFileId,
        )
    }
    return ScannerV2Manifest.create(
        sessionId = current.sessionId,
        state = current.state,
        pages = pages,
        editSource = current.editSource,
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
