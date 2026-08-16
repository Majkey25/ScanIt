package com.majkeylab.scanit

internal const val ACTION_SCANNER_V2_EDIT = "com.majkeylab.scanit.action.SCANNER_V2_EDIT"
internal const val ACTION_SCANNER_V2_NEW = "com.majkeylab.scanit.action.SCANNER_V2_NEW"
internal const val EXTRA_SCANNER_V2_EDIT_CACHE_ID = "scanner_v2_edit_cache_id"
internal const val EXTRA_SCANNER_V2_EDIT_ENTRY_ID = "scanner_v2_edit_entry_id"
internal const val SCANNER_V2_ACTIVITY_CLASS = "com.majkeylab.scanit.ScannerV2Activity"

internal fun isScannerV2ApplicationId(applicationId: String): Boolean =
    applicationId.endsWith(".v2test")
