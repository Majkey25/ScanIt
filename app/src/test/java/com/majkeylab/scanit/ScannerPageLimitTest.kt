package com.majkeylab.scanit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerPageLimitTest {
    @Test
    fun scannerAlwaysHasAnExplicitBoundedPageLimit() {
        assertEquals(20, scannerPageLimit(multipage = true))
        assertEquals(1, scannerPageLimit(multipage = false))
    }

    @Test
    fun scanPageCountUsesTheSameBoundAtTheProcessingBoundary() {
        assertFalse(isAcceptedScanPageCount(0))
        assertTrue(isAcceptedScanPageCount(1))
        assertTrue(isAcceptedScanPageCount(20))
        assertFalse(isAcceptedScanPageCount(21))
    }
}
