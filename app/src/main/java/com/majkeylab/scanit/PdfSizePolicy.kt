package com.majkeylab.scanit

private const val KILOBYTE = 1_000L
private const val MEGABYTE = 1_000_000L
private const val MIN_LEGIBLE_PDF_EDGE = 1_280
internal const val MIN_CUSTOM_PDF_KILOBYTES = 1
internal const val MAX_CUSTOM_PDF_KILOBYTES = 500_000
private const val MAX_CUSTOM_PDF_MEGABYTES = MAX_CUSTOM_PDF_KILOBYTES / 1_000
private val CUSTOM_PDF_KILOBYTE_TARGET_PATTERN = Regex("custom_([1-9][0-9]{0,5})_kb")
private val LEGACY_CUSTOM_PDF_MEGABYTE_TARGET_PATTERN = Regex("custom_([1-9][0-9]{0,2})_mb")
private val CUSTOM_PDF_INPUT_PATTERN = Regex("[1-9][0-9]{0,5}")

internal enum class PdfSizeUnit {
    Kilobytes,
    Megabytes,
}

internal sealed class PdfSizeTarget(
    val wireValue: String,
    val maxBytes: Long?,
) {
    data object Original : PdfSizeTarget("original", null)

    data object Kb200 : PdfSizeTarget("200_kb", 200L * KILOBYTE)

    data object Kb500 : PdfSizeTarget("500_kb", 500L * KILOBYTE)

    data object Mb1 : PdfSizeTarget("1_mb", MEGABYTE)

    data object Mb5 : PdfSizeTarget("5_mb", 5L * MEGABYTE)

    data object Mb10 : PdfSizeTarget("10_mb", 10L * MEGABYTE)

    data object Mb20 : PdfSizeTarget("20_mb", 20L * MEGABYTE)

    data class Custom(val kilobytes: Int) :
        PdfSizeTarget(
            wireValue = "custom_${kilobytes}_kb",
            maxBytes = kilobytes.toLong() * KILOBYTE,
        ) {
        init {
            require(kilobytes in MIN_CUSTOM_PDF_KILOBYTES..MAX_CUSTOM_PDF_KILOBYTES) {
                "Custom PDF target must be between 1 and 500000 KB"
            }
        }
    }

    companion object {
        val presets: List<PdfSizeTarget> =
            listOf(Original, Kb200, Kb500, Mb1, Mb5, Mb10, Mb20)
    }
}

internal fun decodePdfSizeTarget(wireValue: String?): PdfSizeTarget? =
    when (wireValue) {
        PdfSizeTarget.Original.wireValue -> PdfSizeTarget.Original
        PdfSizeTarget.Kb200.wireValue -> PdfSizeTarget.Kb200
        PdfSizeTarget.Kb500.wireValue -> PdfSizeTarget.Kb500
        PdfSizeTarget.Mb1.wireValue -> PdfSizeTarget.Mb1
        PdfSizeTarget.Mb5.wireValue -> PdfSizeTarget.Mb5
        PdfSizeTarget.Mb10.wireValue -> PdfSizeTarget.Mb10
        PdfSizeTarget.Mb20.wireValue -> PdfSizeTarget.Mb20
        else -> {
            val value = wireValue.orEmpty()
            CUSTOM_PDF_KILOBYTE_TARGET_PATTERN.matchEntire(value)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?.takeIf { it <= MAX_CUSTOM_PDF_KILOBYTES }
                ?.let(PdfSizeTarget::Custom)
                ?: LEGACY_CUSTOM_PDF_MEGABYTE_TARGET_PATTERN.matchEntire(value)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                    ?.takeIf { it <= MAX_CUSTOM_PDF_MEGABYTES }
                    ?.let { PdfSizeTarget.Custom(it * 1_000) }
        }
    }

internal fun parsePdfSizeTarget(wireValue: String?): PdfSizeTarget =
    decodePdfSizeTarget(wireValue) ?: PdfSizeTarget.Original

internal fun parseCustomPdfKilobytes(
    value: String,
    unit: PdfSizeUnit,
): Int? {
    val wholeNumber =
        CUSTOM_PDF_INPUT_PATTERN.matchEntire(value)
            ?.value
            ?.toIntOrNull()
            ?: return null
    return when (unit) {
        PdfSizeUnit.Kilobytes -> wholeNumber.takeIf { it <= MAX_CUSTOM_PDF_KILOBYTES }
        PdfSizeUnit.Megabytes ->
            wholeNumber
                .takeIf { it <= MAX_CUSTOM_PDF_MEGABYTES }
                ?.times(1_000)
    }
}

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
