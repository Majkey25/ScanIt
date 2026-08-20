package com.majkeylab.scanit

internal data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(
            left.isFinite() &&
                top.isFinite() &&
                right.isFinite() &&
                bottom.isFinite() &&
                left in 0f..1f &&
                top in 0f..1f &&
                right in 0f..1f &&
                bottom in 0f..1f &&
                left < right &&
                top < bottom,
        ) { "Normalized rectangle is invalid" }
    }
}

internal data class OcrElement(
    val page: Int,
    val value: String,
    val bounds: NormalizedRect,
) {
    init {
        require(page in 0 until MAX_SCAN_PAGES) { "OCR element page is invalid" }
        require(value.isNotBlank()) { "OCR element value is empty" }
    }
}

internal data class DocumentEntityCandidate(
    val page: Int,
    val kind: DocumentEntityKind,
    val value: String,
    val bounds: NormalizedRect,
) {
    init {
        require(page in 0 until MAX_SCAN_PAGES) { "Document entity page is invalid" }
        require(value.isNotBlank()) { "Document entity value is empty" }
    }
}

internal fun normalizedRect(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    sourceWidth: Int,
    sourceHeight: Int,
): NormalizedRect? {
    if (
        sourceWidth <= 0 ||
            sourceHeight <= 0 ||
            left < 0 ||
            top < 0 ||
            right > sourceWidth ||
            bottom > sourceHeight ||
            left >= right ||
            top >= bottom
    ) {
        return null
    }
    val normalizedLeft = left.toFloat() / sourceWidth
    val normalizedTop = top.toFloat() / sourceHeight
    val normalizedRight = right.toFloat() / sourceWidth
    val normalizedBottom = bottom.toFloat() / sourceHeight
    if (normalizedLeft >= normalizedRight || normalizedTop >= normalizedBottom) return null
    return NormalizedRect(normalizedLeft, normalizedTop, normalizedRight, normalizedBottom)
}
