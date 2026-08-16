package com.majkeylab.scanit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScannerContractTest {
    @Test
    fun applicationIdSelectsOnlyTheExplicitV2Scanner() {
        assertEquals(
            ScannerEngineKind.GoogleDocumentScanner,
            scannerEngineForApplicationId("com.majkeylab.scanit"),
        )
        assertEquals(
            ScannerEngineKind.GoogleDocumentScanner,
            scannerEngineForApplicationId("com.majkeylab.scanit.github"),
        )
        assertEquals(
            ScannerEngineKind.GoogleDocumentScanner,
            scannerEngineForApplicationId("com.majkeylab.scanit.internal"),
        )
        assertEquals(
            ScannerEngineKind.ScanItV2,
            scannerEngineForApplicationId("com.majkeylab.scanit.v2test"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            scannerEngineForApplicationId("com.majkeylab.scanit.v2test.fake")
        }
        assertThrows(IllegalArgumentException::class.java) {
            scannerEngineForApplicationId("")
        }
    }

    @Test
    fun launchSpecRejectsInvalidGenerationAndPageLimit() {
        val valid =
            ScannerLaunchSpec(
                generation = 1L,
                pageLimit = MAX_SCAN_PAGES,
                galleryImportAllowed = true,
                purpose = ScannerPurpose.Document,
            )

        assertEquals(MAX_SCAN_PAGES, valid.pageLimit)
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(generation = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(pageLimit = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(pageLimit = MAX_SCAN_PAGES + 1)
        }
    }
}
