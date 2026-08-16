package com.majkeylab.scanit

internal fun resolvedPageIndex(selectedIndex: Int, pageCount: Int): Int {
    require(pageCount > 0) { "Page count must be positive" }
    return selectedIndex.coerceIn(0, pageCount - 1)
}

internal fun resultPageStatus(currentIndex: Int, pageCount: Int): Pair<Int, Int> =
    resolvedPageIndex(currentIndex, pageCount) + 1 to pageCount

internal fun stackResultActions(fontScale: Float, availableWidthDp: Int): Boolean =
    availableWidthDp < 320 || (availableWidthDp < 360 && fontScale >= 1.3f)

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
