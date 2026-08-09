package com.majkeylab.scanit

internal fun resolvedPageIndex(selectedIndex: Int, pageCount: Int): Int {
    require(pageCount > 0) { "Page count must be positive" }
    return selectedIndex.coerceIn(0, pageCount - 1)
}
