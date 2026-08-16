package com.majkeylab.scanit

internal enum class ScannerEngineKind {
    GoogleDocumentScanner,
    ScanItV2,
}

internal fun scannerEngineForApplicationId(applicationId: String): ScannerEngineKind =
    when (applicationId) {
        "com.majkeylab.scanit",
        "com.majkeylab.scanit.github",
        "com.majkeylab.scanit.internal",
        -> ScannerEngineKind.GoogleDocumentScanner
        "com.majkeylab.scanit.v2test" -> ScannerEngineKind.ScanItV2
        else -> throw IllegalArgumentException("Unknown ScanIt application id")
    }

internal data class ScannerLaunchSpec(
    val generation: Long,
    val pageLimit: Int,
    val galleryImportAllowed: Boolean,
    val purpose: ScannerPurpose,
) {
    init {
        require(generation > 0L) { "Scanner generation must be positive" }
        require(pageLimit in 1..MAX_SCAN_PAGES) { "Scanner page limit is invalid" }
    }
}
