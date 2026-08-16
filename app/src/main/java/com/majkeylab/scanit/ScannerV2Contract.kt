package com.majkeylab.scanit

internal const val ACTION_SCANNER_V2_EDIT = "com.majkeylab.scanit.action.SCANNER_V2_EDIT"
internal const val ACTION_SCANNER_V2_NEW = "com.majkeylab.scanit.action.SCANNER_V2_NEW"
internal const val EXTRA_SCANNER_V2_EDIT_CACHE_ID = "scanner_v2_edit_cache_id"
internal const val EXTRA_SCANNER_V2_EDIT_ENTRY_ID = "scanner_v2_edit_entry_id"
internal const val EXTRA_SCANNER_V2_RESULT_CACHE_ID = "scanner_v2_result_cache_id"
internal const val EXTRA_SCANNER_V2_RESULT_ENTRY_ID = "scanner_v2_result_entry_id"
internal const val SCANNER_V2_ACTIVITY_CLASS = "com.majkeylab.scanit.ScannerV2Activity"

internal fun isScannerV2ApplicationId(applicationId: String): Boolean =
    applicationId.endsWith(".v2test")

internal data class ScannerV2ResultLaunch(
    val cacheId: String,
    val entryId: String,
) {
    init {
        require(isSafeActiveResultCacheId(cacheId)) { "Scanner v2 result cache ID is unsafe" }
        require(isCanonicalUuid(entryId)) { "Scanner v2 result entry ID is unsafe" }
    }

    fun matches(
        activeCacheId: String?,
        openedCacheId: String?,
        openedEntryId: String?,
    ): Boolean =
        activeCacheId == cacheId && openedCacheId == cacheId && openedEntryId == entryId
}

internal fun scannerV2ResultLaunch(
    applicationId: String,
    cacheId: String?,
    entryId: String?,
): ScannerV2ResultLaunch? {
    if (!isScannerV2ApplicationId(applicationId) || cacheId == null || entryId == null) {
        return null
    }
    return try {
        ScannerV2ResultLaunch(cacheId, entryId)
    } catch (_: IllegalArgumentException) {
        null
    }
}
