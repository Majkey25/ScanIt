package com.majkeylab.scanit

internal enum class ScannerV2ResultStatus {
    Restoring,
    Busy,
    Other,
    MatchingResult,
    Failed,
}

internal enum class ScannerV2BridgeAction {
    Wait,
    StartImport,
    OpenResult,
    RecoverReview,
}

internal enum class ScannerV2EditLaunchAction {
    Wait,
    Resume,
    AlreadyApplied,
    Reject,
}

internal fun scannerV2EditLaunchAction(
    navigationReady: Boolean,
    stage: ScannerSessionStage,
    resultCacheId: String?,
    manifestEditSource: ScannerV2EditSource?,
    requestedEditSource: ScannerV2EditSource?,
    activeCacheId: String?,
    activeEntryId: String?,
): ScannerV2EditLaunchAction {
    if (!navigationReady) return ScannerV2EditLaunchAction.Wait
    val requested = requestedEditSource ?: return ScannerV2EditLaunchAction.Reject
    val activeMatches =
        activeCacheId == requested.cacheId && activeEntryId == requested.entryId
    return when {
        !activeMatches -> ScannerV2EditLaunchAction.Reject
        stage == ScannerSessionStage.Finishing && resultCacheId == requested.cacheId ->
            ScannerV2EditLaunchAction.Resume
        stage == ScannerSessionStage.Reviewing &&
            resultCacheId == null &&
            manifestEditSource == requested -> ScannerV2EditLaunchAction.AlreadyApplied
        else -> ScannerV2EditLaunchAction.Reject
    }
}

internal fun scannerV2ResultStatus(
    navigationReady: Boolean,
    processingState: Boolean,
    processingActive: Boolean,
    failed: Boolean,
    matchingResult: Boolean,
): ScannerV2ResultStatus = when {
    !navigationReady -> ScannerV2ResultStatus.Restoring
    processingState && processingActive -> ScannerV2ResultStatus.Busy
    failed -> ScannerV2ResultStatus.Failed
    matchingResult -> ScannerV2ResultStatus.MatchingResult
    else -> ScannerV2ResultStatus.Other
}

internal fun scannerV2ResultMatches(
    expectedCacheId: String?,
    resultCacheId: String?,
): Boolean = expectedCacheId != null && resultCacheId == expectedCacheId

internal fun scannerV2BridgeAction(
    resultCacheId: String?,
    resultStatus: ScannerV2ResultStatus,
    importRequested: Boolean,
): ScannerV2BridgeAction = when {
    resultStatus == ScannerV2ResultStatus.MatchingResult -> ScannerV2BridgeAction.OpenResult
    resultStatus == ScannerV2ResultStatus.Busy -> ScannerV2BridgeAction.Wait
    resultStatus == ScannerV2ResultStatus.Restoring -> ScannerV2BridgeAction.Wait
    resultCacheId != null -> ScannerV2BridgeAction.RecoverReview
    resultStatus == ScannerV2ResultStatus.Failed && importRequested ->
        ScannerV2BridgeAction.RecoverReview
    importRequested -> ScannerV2BridgeAction.Wait
    else -> ScannerV2BridgeAction.StartImport
}
