package com.majkeylab.scanit

import java.io.File
import java.time.Instant

internal data class RecentScan(
    val cacheId: String,
    val displayName: String,
    val createdAt: Instant,
    val pageCount: Int,
    val pdfBytes: Long,
    val firstPage: File,
)
