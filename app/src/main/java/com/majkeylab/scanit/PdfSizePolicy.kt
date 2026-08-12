package com.majkeylab.scanit

private const val MEGABYTE = 1_000_000L
private const val MIN_LEGIBLE_PDF_EDGE = 1_280
internal const val MIN_CUSTOM_PDF_MEGABYTES = 1
internal const val MAX_CUSTOM_PDF_MEGABYTES = 500
private val CUSTOM_PDF_TARGET_PATTERN = Regex("custom_([1-9][0-9]{0,2})_mb")
private val CUSTOM_PDF_INPUT_PATTERN = Regex("[1-9][0-9]{0,2}")

internal sealed class PdfSizeTarget(
    val wireValue: String,
    val maxBytes: Long?,
) {
    data object Original : PdfSizeTarget("original", null)

    data object Mb5 : PdfSizeTarget("5_mb", 5L * MEGABYTE)

    data object Mb10 : PdfSizeTarget("10_mb", 10L * MEGABYTE)

    data object Mb20 : PdfSizeTarget("20_mb", 20L * MEGABYTE)

    data class Custom(val megabytes: Int) :
        PdfSizeTarget(
            wireValue = "custom_${megabytes}_mb",
            maxBytes = megabytes.toLong() * MEGABYTE,
        ) {
        init {
            require(megabytes in MIN_CUSTOM_PDF_MEGABYTES..MAX_CUSTOM_PDF_MEGABYTES) {
                "Custom PDF target must be between 1 and 500 MB"
            }
        }
    }

    companion object {
        val presets: List<PdfSizeTarget> = listOf(Original, Mb5, Mb10, Mb20)
    }
}

internal fun decodePdfSizeTarget(wireValue: String?): PdfSizeTarget? =
    when (wireValue) {
        PdfSizeTarget.Original.wireValue -> PdfSizeTarget.Original
        PdfSizeTarget.Mb5.wireValue -> PdfSizeTarget.Mb5
        PdfSizeTarget.Mb10.wireValue -> PdfSizeTarget.Mb10
        PdfSizeTarget.Mb20.wireValue -> PdfSizeTarget.Mb20
        else ->
            CUSTOM_PDF_TARGET_PATTERN.matchEntire(wireValue.orEmpty())
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?.takeIf { it <= MAX_CUSTOM_PDF_MEGABYTES }
                ?.let(PdfSizeTarget::Custom)
    }

internal fun parsePdfSizeTarget(wireValue: String?): PdfSizeTarget =
    decodePdfSizeTarget(wireValue) ?: PdfSizeTarget.Original

internal fun parseCustomPdfMegabytes(value: String): Int? =
    CUSTOM_PDF_INPUT_PATTERN.matchEntire(value)
        ?.value
        ?.toIntOrNull()
        ?.takeIf { it <= MAX_CUSTOM_PDF_MEGABYTES }

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
