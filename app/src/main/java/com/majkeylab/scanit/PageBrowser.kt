package com.majkeylab.scanit

internal fun resolvedPageIndex(selectedIndex: Int, pageCount: Int): Int {
    require(pageCount > 0) { "Page count must be positive" }
    return selectedIndex.coerceIn(0, pageCount - 1)
}

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
