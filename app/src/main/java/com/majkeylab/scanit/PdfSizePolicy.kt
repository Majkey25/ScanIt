package com.majkeylab.scanit

private const val MEGABYTE = 1_000_000L
private const val MIN_LEGIBLE_PDF_EDGE = 1_280

internal enum class PdfSizeTarget(
    val wireValue: String,
    val maxBytes: Long?,
) {
    Original("original", null),
    Mb5("5_mb", 5L * MEGABYTE),
    Mb10("10_mb", 10L * MEGABYTE),
    Mb20("20_mb", 20L * MEGABYTE),
}

internal fun parsePdfSizeTarget(wireValue: String?): PdfSizeTarget =
    PdfSizeTarget.entries.firstOrNull { it.wireValue == wireValue } ?: PdfSizeTarget.Original

internal enum class PdfEncoding {
    Jpeg,
    Bitonal,
}

internal fun selectPdfEncoding(
    jpegBytes: Long,
    bitonalBytes: Long?,
    bitonalEligible: Boolean,
): PdfEncoding {
    require(jpegBytes > 0L && (bitonalBytes == null || bitonalBytes > 0L)) {
        "PDF candidate sizes must be positive"
    }
    return if (bitonalEligible && bitonalBytes != null && bitonalBytes < jpegBytes) {
        PdfEncoding.Bitonal
    } else {
        PdfEncoding.Jpeg
    }
}

internal fun pdfSampleMultipliers(
    longestEdges: List<Int>,
    minimumEdge: Int = MIN_LEGIBLE_PDF_EDGE,
): List<Int> {
    require(longestEdges.isNotEmpty() && longestEdges.all { it > 0 }) {
        "PDF page edges must be positive"
    }
    require(minimumEdge > 0) { "Minimum legible edge must be positive" }
    val multipliers = mutableListOf(1)
    var next = 2
    while (longestEdges.any { it / next >= minimumEdge }) {
        multipliers += next
        if (next > Int.MAX_VALUE / 2) break
        next *= 2
    }
    return multipliers
}

internal fun legiblePdfSampleMultiplier(
    longestEdge: Int,
    requestedMultiplier: Int,
    minimumEdge: Int = MIN_LEGIBLE_PDF_EDGE,
): Int {
    require(longestEdge > 0 && minimumEdge > 0) { "PDF page edges must be positive" }
    require(
        requestedMultiplier > 0 &&
            requestedMultiplier and (requestedMultiplier - 1) == 0,
    ) { "PDF sample multiplier must be a positive power of two" }
    var selected = requestedMultiplier
    while (selected > 1 && longestEdge / selected < minimumEdge) {
        selected /= 2
    }
    return selected
}

internal fun pdfPointsAt300Dpi(pixels: Int): String {
    require(pixels > 0) { "PDF page dimension must be positive" }
    val hundredths = Math.multiplyExact(pixels.toLong(), 24L)
    val whole = hundredths / 100L
    val fraction = (hundredths % 100L).toInt()
    return when {
        fraction == 0 -> whole.toString()
        fraction % 10 == 0 -> "$whole.${fraction / 10}"
        else -> "$whole.${fraction.toString().padStart(2, '0')}"
    }
}

internal fun isBitonalPdfEligible(appearance: ScanAppearance): Boolean =
    appearance.colorMode == ScanColorMode.BlackWhite &&
        clampAppearancePercent(appearance.intensity) == 100

internal fun relativePdfSourceSampleSize(
    baseSampleSize: Int,
    profileMultiplier: Int,
): Int {
    require(
        baseSampleSize > 0 && baseSampleSize and (baseSampleSize - 1) == 0 &&
            profileMultiplier > 0 && profileMultiplier and (profileMultiplier - 1) == 0,
    ) { "PDF sample sizes must be positive powers of two" }
    require(baseSampleSize <= Int.MAX_VALUE / profileMultiplier) {
        "PDF sample size exceeds integer bounds"
    }
    return baseSampleSize * profileMultiplier
}
