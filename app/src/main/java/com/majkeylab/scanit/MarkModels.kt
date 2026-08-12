package com.majkeylab.scanit

internal const val MAX_MARK_TEMPLATES = 12
internal const val MAX_MARK_INPUT_BYTES = 20_000_000L
internal const val MARK_DECODE_MAX_SIDE = 2_048
internal const val MARK_OUTPUT_MAX_SIDE = 1_024
internal const val MARK_VISIBLE_ALPHA = 16
internal const val MAX_MARK_DRAWING_STROKES = 128
internal const val MAX_MARK_DRAWING_POINTS = 8_192
internal const val MIN_MARK_WIDTH_FRACTION = 0.1f
internal const val MAX_MARK_WIDTH_FRACTION = 0.8f

private const val MARK_TEMPLATE_PREFIX = "mark_"
private const val MARK_TEMPLATE_SUFFIX = ".png"
private const val MAX_EPOCH_MILLIS_DIGITS = 19
private const val OPAQUE_PAPER_CHANNEL = 220
private const val TRANSPARENT_PAPER_CHANNEL = 245

internal data class MarkPoint(
    val x: Float,
    val y: Float,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Mark point must be finite" }
    }
}

internal data class MarkStroke(
    val points: List<MarkPoint>,
) {
    init {
        require(points.isNotEmpty()) { "Mark stroke must contain a point" }
    }
}

internal data class MarkPlacement(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.75f,
    val widthFraction: Float = 0.35f,
) {
    init {
        require(centerX.isFinite() && centerX in 0f..1f) { "Mark horizontal position is invalid" }
        require(centerY.isFinite() && centerY in 0f..1f) { "Mark vertical position is invalid" }
        require(
            widthFraction.isFinite() &&
                widthFraction in MIN_MARK_WIDTH_FRACTION..MAX_MARK_WIDTH_FRACTION,
        ) {
            "Mark width is invalid"
        }
    }
}

internal data class MarkEditorSource(
    val cacheId: String,
    val entryId: String,
    val pageIndex: Int,
) {
    init {
        require(isSafeCacheId(cacheId)) { "Mark source cache ID is unsafe" }
        require(isCanonicalUuid(entryId)) { "Mark source entry ID is invalid" }
        require(pageIndex >= 0) { "Mark source page is invalid" }
    }

    fun isCurrent(
        cacheId: String,
        entryId: String?,
        selectedPageIndex: Int,
    ): Boolean =
        this.cacheId == cacheId &&
            this.entryId == entryId &&
            pageIndex == selectedPageIndex
}

internal data class MarkRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "Mark rectangle must be finite"
        }
        require(right > left && bottom > top) { "Mark rectangle must have positive size" }
    }

    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top
}

internal data class PixelBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left >= 0 && top >= 0 && right > left && bottom > top) {
            "Pixel bounds are invalid"
        }
    }

    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top
}

internal fun resolveMarkRect(
    pageWidth: Float,
    pageHeight: Float,
    markWidth: Int,
    markHeight: Int,
    placement: MarkPlacement,
): MarkRect {
    require(pageWidth.isFinite() && pageWidth > 0f) { "Page width is invalid" }
    require(pageHeight.isFinite() && pageHeight > 0f) { "Page height is invalid" }
    require(markWidth > 0 && markHeight > 0) { "Mark dimensions are invalid" }

    val pageWidthDouble = pageWidth.toDouble()
    val pageHeightDouble = pageHeight.toDouble()
    val aspect = markHeight.toDouble() / markWidth.toDouble()
    var width = pageWidthDouble * placement.widthFraction.toDouble()
    var height = width * aspect
    if (height > pageHeightDouble) {
        height = pageHeightDouble
        width = height / aspect
    }
    val left =
        (pageWidthDouble * placement.centerX - width / 2.0)
            .coerceIn(0.0, pageWidthDouble - width)
    val top =
        (pageHeightDouble * placement.centerY - height / 2.0)
            .coerceIn(0.0, pageHeightDouble - height)
    return MarkRect(
        left = left.toFloat(),
        top = top.toFloat(),
        right = (left + width).coerceAtMost(pageWidthDouble).toFloat(),
        bottom = (top + height).coerceAtMost(pageHeightDouble).toFloat(),
    )
}

internal fun dragMarkPlacement(
    pageWidth: Float,
    pageHeight: Float,
    markWidth: Int,
    markHeight: Int,
    placement: MarkPlacement,
    deltaX: Float,
    deltaY: Float,
): MarkPlacement {
    require(deltaX.isFinite() && deltaY.isFinite()) { "Mark drag must be finite" }
    val rect = resolveMarkRect(pageWidth, pageHeight, markWidth, markHeight, placement)
    val centerX =
        ((rect.left + rect.right) / 2f + deltaX)
            .coerceIn(rect.width / 2f, pageWidth - rect.width / 2f)
    val centerY =
        ((rect.top + rect.bottom) / 2f + deltaY)
            .coerceIn(rect.height / 2f, pageHeight - rect.height / 2f)
    return placement.copy(centerX = centerX / pageWidth, centerY = centerY / pageHeight)
}

internal fun scaleNormalizedMarkStrokes(
    strokes: List<MarkStroke>,
    width: Int,
    height: Int,
): List<MarkStroke> {
    validateNormalizedMarkStrokes(strokes)
    require(width > 0 && height > 0) { "Mark drawing dimensions are invalid" }
    return strokes.map { stroke ->
        MarkStroke(
            stroke.points.map { point ->
                MarkPoint(point.x * width, point.y * height)
            },
        )
    }
}

internal fun validateNormalizedMarkStrokes(strokes: List<MarkStroke>) {
    require(strokes.isNotEmpty()) { "Mark drawing has no strokes" }
    require(strokes.size <= MAX_MARK_DRAWING_STROKES) { "Mark drawing has too many strokes" }
    require(strokes.sumOf { it.points.size.toLong() } <= MAX_MARK_DRAWING_POINTS) {
        "Mark drawing has too many points"
    }
    require(strokes.all { stroke -> stroke.points.all { it.x in 0f..1f && it.y in 0f..1f } }) {
        "Mark drawing points must be normalized"
    }
}

internal fun signedBaseName(
    source: String,
    existingNames: Set<String>,
): String {
    require(isSafeMarkBaseName(source)) { "Source name is unsafe" }
    val base = "${source}_Signed"
    if (base !in existingNames) return base
    var number = 2
    while (true) {
        val candidate = "${base}_$number"
        if (candidate !in existingNames) return candidate
        if (number == Int.MAX_VALUE) break
        number++
    }
    error("No signed name is available")
}

internal fun isValidMarkTemplateId(id: String): Boolean {
    if (!id.startsWith(MARK_TEMPLATE_PREFIX) || !id.endsWith(MARK_TEMPLATE_SUFFIX)) return false
    val epochMillis = id.substring(MARK_TEMPLATE_PREFIX.length, id.length - MARK_TEMPLATE_SUFFIX.length)
    return epochMillis.length in 1..MAX_EPOCH_MILLIS_DIGITS &&
        epochMillis.all { it in '0'..'9' } &&
        epochMillis.toLongOrNull() != null
}

internal fun whiteToTransparentArgb(pixel: Int): Int {
    val sourceAlpha = pixel ushr 24
    if (sourceAlpha == 0) return 0
    val minimumChannel =
        minOf(
            pixel ushr 16 and 0xff,
            pixel ushr 8 and 0xff,
            pixel and 0xff,
        )
    val paperAlpha =
        when {
            minimumChannel <= OPAQUE_PAPER_CHANNEL -> 255
            minimumChannel >= TRANSPARENT_PAPER_CHANNEL -> 0
            else ->
                (TRANSPARENT_PAPER_CHANNEL - minimumChannel) * 255 /
                    (TRANSPARENT_PAPER_CHANNEL - OPAQUE_PAPER_CHANNEL)
        }
    val alpha = (sourceAlpha * paperAlpha + 127) / 255
    return if (alpha == 0) 0 else (alpha shl 24) or (pixel and 0x00ffffff)
}

internal fun whiteToTransparentArgb(pixels: IntArray): IntArray =
    IntArray(pixels.size) { index -> whiteToTransparentArgb(pixels[index]) }

internal fun visiblePixelBounds(
    width: Int,
    height: Int,
    pixels: IntArray,
): PixelBounds? {
    requirePixelArray(width, height, pixels)
    var left = width
    var top = height
    var right = 0
    var bottom = 0
    pixels.forEachIndexed { index, pixel ->
        if (pixel ushr 24 >= MARK_VISIBLE_ALPHA) {
            val x = index % width
            val y = index / width
            if (x < left) left = x
            if (y < top) top = y
            if (x >= right) right = x + 1
            if (y >= bottom) bottom = y + 1
        }
    }
    return if (right == 0 || bottom == 0) null else PixelBounds(left, top, right, bottom)
}

internal fun cropArgbPixels(
    width: Int,
    height: Int,
    pixels: IntArray,
    bounds: PixelBounds,
): IntArray {
    requirePixelArray(width, height, pixels)
    require(bounds.right <= width && bounds.bottom <= height) { "Crop exceeds the image" }
    val cropped = IntArray(bounds.width * bounds.height)
    repeat(bounds.height) { row ->
        pixels.copyInto(
            destination = cropped,
            destinationOffset = row * bounds.width,
            startIndex = (bounds.top + row) * width + bounds.left,
            endIndex = (bounds.top + row) * width + bounds.right,
        )
    }
    return cropped
}

private fun requirePixelArray(
    width: Int,
    height: Int,
    pixels: IntArray,
) {
    require(width > 0 && height > 0) { "Image dimensions are invalid" }
    require(width.toLong() * height.toLong() == pixels.size.toLong()) {
        "Pixel count does not match image dimensions"
    }
}

private fun isSafeMarkBaseName(value: String): Boolean =
    value.isNotBlank() &&
        value != "." &&
        value != ".." &&
        !value.startsWith('.') &&
        value.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }
