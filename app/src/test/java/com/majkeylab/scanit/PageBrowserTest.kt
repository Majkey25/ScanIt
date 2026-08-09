package com.majkeylab.scanit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PageBrowserTest {
    @Test
    fun selectedPageInsideBoundsIsPreserved() {
        assertEquals(2, resolvedPageIndex(selectedIndex = 2, pageCount = 4))
    }

    @Test
    fun selectedPageOutsideBoundsIsClamped() {
        assertEquals(0, resolvedPageIndex(selectedIndex = -1, pageCount = 4))
        assertEquals(3, resolvedPageIndex(selectedIndex = 4, pageCount = 4))
    }

    @Test
    fun selectingFromEmptyPagesIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            resolvedPageIndex(selectedIndex = 0, pageCount = 0)
        }
    }

    @Test
    fun savedPageIsRestoredOnlyForTheSameDocument() {
        assertEquals(
            2,
            restoredResultPageIndex(
                savedCacheId = "Scan_A",
                targetCacheId = "Scan_A",
                savedPageIndex = 2,
                pageCount = 4,
            ),
        )
        assertEquals(
            0,
            restoredResultPageIndex(
                savedCacheId = "Scan_A",
                targetCacheId = "Scan_B",
                savedPageIndex = 2,
                pageCount = 4,
            ),
        )
        assertEquals(
            3,
            restoredResultPageIndex(
                savedCacheId = "Scan_A",
                targetCacheId = "Scan_A",
                savedPageIndex = 20,
                pageCount = 4,
            ),
        )
    }

    @Test
    fun pageLoadIsCurrentOnlyForTheSameDocumentEntryAndPage() {
        val request = ResultPageLoad("Scan_A", "entry-1", pageIndex = 2)

        assertTrue(request.isCurrent("Scan_A", "entry-1", selectedPageIndex = 2))
        assertFalse(request.isCurrent("Scan_B", "entry-1", selectedPageIndex = 2))
        assertFalse(request.isCurrent("Scan_A", "entry-2", selectedPageIndex = 2))
        assertFalse(request.isCurrent("Scan_A", "entry-1", selectedPageIndex = 1))
    }
}
