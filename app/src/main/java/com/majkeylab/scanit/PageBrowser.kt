package com.majkeylab.scanit

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

internal const val RESULT_ACTION_MIN_HEIGHT_DP = 48

internal fun appearanceEditorUsesSplitLayout(widthPx: Int, heightPx: Int): Boolean =
    widthPx > heightPx

internal fun resolvedPageIndex(selectedIndex: Int, pageCount: Int): Int {
    require(pageCount > 0) { "Page count must be positive" }
    return selectedIndex.coerceIn(0, pageCount - 1)
}

internal fun resultPageStatus(currentIndex: Int, pageCount: Int): Pair<Int, Int> =
    resolvedPageIndex(currentIndex, pageCount) + 1 to pageCount

internal fun stackResultActions(fontScale: Float, availableWidthDp: Int): Boolean =
    fontScale >= 1.3f || availableWidthDp < 360

internal fun restoredResultPageIndex(
    savedCacheId: String?,
    targetCacheId: String,
    savedPageIndex: Int?,
    pageCount: Int,
): Int =
    resolvedPageIndex(
        selectedIndex = if (savedCacheId == targetCacheId) savedPageIndex ?: 0 else 0,
        pageCount = pageCount,
    )

internal fun manualCleanupAvailable(
    previewLoading: Boolean,
    hasPreview: Boolean,
    sourceEditable: Boolean,
): Boolean = !previewLoading && hasPreview && sourceEditable

internal fun clampFullscreenOffset(
    offset: Offset,
    viewport: IntSize,
    bitmap: IntSize,
    scale: Float,
): Offset {
    if (scale <= 1f || viewport.width <= 0 || viewport.height <= 0 ||
        bitmap.width <= 0 || bitmap.height <= 0) return Offset.Zero
    val fit = minOf(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height)
    val maxX = ((bitmap.width * fit * scale - viewport.width) / 2f).coerceAtLeast(0f)
    val maxY = ((bitmap.height * fit * scale - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(
        if (maxX == 0f) 0f else offset.x.coerceIn(-maxX, maxX),
        if (maxY == 0f) 0f else offset.y.coerceIn(-maxY, maxY),
    )
}

internal data class ResultPageLoad(
    val cacheId: String,
    val entryId: String?,
    val pageIndex: Int,
) {
    fun isCurrent(
        cacheId: String,
        entryId: String?,
        selectedPageIndex: Int,
    ): Boolean =
        this.cacheId == cacheId &&
            this.entryId == entryId &&
            pageIndex == selectedPageIndex
}
