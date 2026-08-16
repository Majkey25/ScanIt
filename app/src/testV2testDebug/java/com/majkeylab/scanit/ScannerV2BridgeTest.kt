package com.majkeylab.scanit

import org.junit.Assert.assertEquals
import org.junit.Test

class ScannerV2BridgeTest {
    @Test
    fun bridgeWaitsForRestoreAndActiveImport() {
        assertEquals(
            ScannerV2BridgeAction.Wait,
            scannerV2BridgeAction(null, ScannerV2ResultStatus.Restoring, importRequested = false),
        )
        assertEquals(
            ScannerV2BridgeAction.Wait,
            scannerV2BridgeAction(
                "Scan_20260816_120000",
                ScannerV2ResultStatus.Restoring,
                importRequested = false,
            ),
        )
        assertEquals(
            ScannerV2BridgeAction.Wait,
            scannerV2BridgeAction(null, ScannerV2ResultStatus.Busy, importRequested = true),
        )
        assertEquals(
            ScannerV2BridgeAction.Wait,
            scannerV2BridgeAction(null, ScannerV2ResultStatus.Busy, importRequested = false),
        )
        assertEquals(
            ScannerV2BridgeAction.Wait,
            scannerV2BridgeAction(
                "Scan_20260816_120000",
                ScannerV2ResultStatus.Busy,
                importRequested = false,
            ),
        )
    }

    @Test
    fun resultStatusDistinguishesRestoredPlaceholderFromLiveProcessing() {
        assertEquals(
            ScannerV2ResultStatus.Restoring,
            scannerV2ResultStatus(
                navigationReady = false,
                processingState = true,
                processingActive = false,
                failed = false,
                matchingResult = false,
            ),
        )
        assertEquals(
            ScannerV2ResultStatus.Other,
            scannerV2ResultStatus(
                navigationReady = true,
                processingState = true,
                processingActive = false,
                failed = false,
                matchingResult = false,
            ),
        )
        assertEquals(
            ScannerV2ResultStatus.Busy,
            scannerV2ResultStatus(
                navigationReady = true,
                processingState = true,
                processingActive = true,
                failed = false,
                matchingResult = false,
            ),
        )
    }

    @Test
    fun resultMatchRequiresTwoExactNonNullCacheIds() {
        assertEquals(false, scannerV2ResultMatches(null, null))
        assertEquals(false, scannerV2ResultMatches("Scan_20260816_120000", null))
        assertEquals(false, scannerV2ResultMatches(null, "Scan_20260816_120000"))
        assertEquals(false, scannerV2ResultMatches("Scan_20260816_120000", "Scan_other"))
        assertEquals(
            true,
            scannerV2ResultMatches("Scan_20260816_120000", "Scan_20260816_120000"),
        )
    }

    @Test
    fun stalePreparedCacheRecoversAfterProcessDeathInsteadOfWaitingForever() {
        assertEquals(
            ScannerV2BridgeAction.RecoverReview,
            scannerV2BridgeAction(
                "Scan_20260816_120000",
                scannerV2ResultStatus(
                    navigationReady = true,
                    processingState = true,
                    processingActive = false,
                    failed = false,
                    matchingResult = false,
                ),
                importRequested = false,
            ),
        )
    }

    @Test
    fun bridgeStartsOnceAndOnlyOpensTheExactPreparedCache() {
        assertEquals(
            ScannerV2BridgeAction.StartImport,
            scannerV2BridgeAction(null, ScannerV2ResultStatus.Other, importRequested = false),
        )
        assertEquals(
            ScannerV2BridgeAction.Wait,
            scannerV2BridgeAction(null, ScannerV2ResultStatus.Other, importRequested = true),
        )
        assertEquals(
            ScannerV2BridgeAction.OpenResult,
            scannerV2BridgeAction(
                "Scan_20260816_120000",
                ScannerV2ResultStatus.MatchingResult,
                importRequested = true,
            ),
        )
    }

    @Test
    fun bridgeRecoversFailedOrStalePreparedAuthorityBackToReview() {
        assertEquals(
            ScannerV2BridgeAction.RecoverReview,
            scannerV2BridgeAction(
                "Scan_20260816_120000",
                ScannerV2ResultStatus.Other,
                importRequested = false,
            ),
        )
        assertEquals(
            ScannerV2BridgeAction.RecoverReview,
            scannerV2BridgeAction(null, ScannerV2ResultStatus.Failed, importRequested = true),
        )
    }

    @Test
    fun stableAndV2BuildRoutesStaySeparate() {
        assertEquals(false, isScannerV2ApplicationId("com.majkeylab.scanit"))
        assertEquals(true, isScannerV2ApplicationId("com.majkeylab.scanit.v2test"))
    }

    @Test
    fun editLaunchWaitsForResultRestoreThenResumesExactFinishedScan() {
        val source = ScannerV2EditSource(
            cacheId = "Scan_20260816_120000",
            entryId = "78d24ed3-a901-4de9-ae8c-a16b09462e07",
        )
        assertEquals(
            ScannerV2EditLaunchAction.Wait,
            scannerV2EditLaunchAction(
                navigationReady = false,
                stage = ScannerSessionStage.Finishing,
                resultCacheId = source.cacheId,
                manifestEditSource = null,
                requestedEditSource = source,
                activeCacheId = source.cacheId,
                activeEntryId = source.entryId,
            ),
        )
        assertEquals(
            ScannerV2EditLaunchAction.Resume,
            scannerV2EditLaunchAction(
                navigationReady = true,
                stage = ScannerSessionStage.Finishing,
                resultCacheId = source.cacheId,
                manifestEditSource = null,
                requestedEditSource = source,
                activeCacheId = source.cacheId,
                activeEntryId = source.entryId,
            ),
        )
    }

    @Test
    fun editLaunchTreatsAnExactAppliedReviewAsIdempotent() {
        val source = ScannerV2EditSource(
            cacheId = "Scan_20260816_120000",
            entryId = "78d24ed3-a901-4de9-ae8c-a16b09462e07",
        )
        assertEquals(
            ScannerV2EditLaunchAction.AlreadyApplied,
            scannerV2EditLaunchAction(
                navigationReady = true,
                stage = ScannerSessionStage.Reviewing,
                resultCacheId = null,
                manifestEditSource = source,
                requestedEditSource = source,
                activeCacheId = source.cacheId,
                activeEntryId = source.entryId,
            ),
        )
    }

    @Test
    fun editLaunchRejectsStaleOrDifferentAuthority() {
        val source = ScannerV2EditSource(
            cacheId = "Scan_20260816_120000",
            entryId = "78d24ed3-a901-4de9-ae8c-a16b09462e07",
        )
        val other = source.copy(entryId = "9db323b4-9de4-442a-929f-41cac65ccceb")
        assertEquals(
            ScannerV2EditLaunchAction.Reject,
            scannerV2EditLaunchAction(
                navigationReady = true,
                stage = ScannerSessionStage.Finishing,
                resultCacheId = source.cacheId,
                manifestEditSource = null,
                requestedEditSource = source,
                activeCacheId = source.cacheId,
                activeEntryId = other.entryId,
            ),
        )
        assertEquals(
            ScannerV2EditLaunchAction.Reject,
            scannerV2EditLaunchAction(
                navigationReady = true,
                stage = ScannerSessionStage.Reviewing,
                resultCacheId = null,
                manifestEditSource = other,
                requestedEditSource = source,
                activeCacheId = source.cacheId,
                activeEntryId = source.entryId,
            ),
        )
    }
}
