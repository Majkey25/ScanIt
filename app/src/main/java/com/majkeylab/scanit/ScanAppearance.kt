package com.majkeylab.scanit

private const val DEFAULT_FILTER_INTENSITY = 100
private const val DEFAULT_SHADOWS = 50
internal const val LOCAL_SHADOW_GRID_SIZE = 8

internal enum class ScanColorMode(val wireValue: String) {
    Natural("natural"),
    Color("color"),
    LightText("light_text"),
    Grayscale("grayscale"),
    BlackWhite("black_white"),
    Whiteboard("whiteboard"),
}

internal data class ScanAppearance(
    val colorMode: ScanColorMode = ScanColorMode.BlackWhite,
    val intensity: Int = DEFAULT_FILTER_INTENSITY,
    val shadows: Int = DEFAULT_SHADOWS,
)

internal data class ScanAppearanceSettings(
    val colorMode: ScanColorMode = ScanColorMode.BlackWhite,
    val naturalIntensity: Int = DEFAULT_FILTER_INTENSITY,
    val colorIntensity: Int = DEFAULT_FILTER_INTENSITY,
    val lightTextIntensity: Int = DEFAULT_FILTER_INTENSITY,
    val grayscaleIntensity: Int = DEFAULT_FILTER_INTENSITY,
    val blackWhiteIntensity: Int = DEFAULT_FILTER_INTENSITY,
    val whiteboardIntensity: Int = DEFAULT_FILTER_INTENSITY,
    val shadows: Int = DEFAULT_SHADOWS,
) {
    fun selected(): ScanAppearance =
        ScanAppearance(
            colorMode = colorMode,
            intensity = intensity(colorMode),
            shadows = clampAppearancePercent(shadows),
        )

    fun intensity(mode: ScanColorMode): Int =
        clampAppearancePercent(
            when (mode) {
                ScanColorMode.Natural -> naturalIntensity
                ScanColorMode.Color -> colorIntensity
                ScanColorMode.LightText -> lightTextIntensity
                ScanColorMode.Grayscale -> grayscaleIntensity
                ScanColorMode.BlackWhite -> blackWhiteIntensity
                ScanColorMode.Whiteboard -> whiteboardIntensity
            },
        )

    fun withApplied(appearance: ScanAppearance): ScanAppearanceSettings =
        copy(
            colorMode = appearance.colorMode,
            naturalIntensity =
                if (appearance.colorMode == ScanColorMode.Natural) {
                    clampAppearancePercent(appearance.intensity)
                } else {
                    naturalIntensity
                },
            colorIntensity =
                if (appearance.colorMode == ScanColorMode.Color) {
                    clampAppearancePercent(appearance.intensity)
                } else {
                    colorIntensity
                },
            lightTextIntensity =
                if (appearance.colorMode == ScanColorMode.LightText) {
                    clampAppearancePercent(appearance.intensity)
                } else {
                    lightTextIntensity
                },
            grayscaleIntensity =
                if (appearance.colorMode == ScanColorMode.Grayscale) {
                    clampAppearancePercent(appearance.intensity)
                } else {
                    grayscaleIntensity
                },
            blackWhiteIntensity =
                if (appearance.colorMode == ScanColorMode.BlackWhite) {
                    clampAppearancePercent(appearance.intensity)
                } else {
                    blackWhiteIntensity
                },
            whiteboardIntensity =
                if (appearance.colorMode == ScanColorMode.Whiteboard) {
                    clampAppearancePercent(appearance.intensity)
                } else {
                    whiteboardIntensity
                },
            shadows = clampAppearancePercent(appearance.shadows),
        )

    fun withIntensity(mode: ScanColorMode, value: Int): ScanAppearanceSettings {
        val normalized = clampAppearancePercent(value)
        return when (mode) {
            ScanColorMode.Natural -> copy(naturalIntensity = normalized)
            ScanColorMode.Color -> copy(colorIntensity = normalized)
            ScanColorMode.LightText -> copy(lightTextIntensity = normalized)
            ScanColorMode.Grayscale -> copy(grayscaleIntensity = normalized)
            ScanColorMode.BlackWhite -> copy(blackWhiteIntensity = normalized)
            ScanColorMode.Whiteboard -> copy(whiteboardIntensity = normalized)
        }
    }
}

internal fun googleScannerAppearanceSettings(): ScanAppearanceSettings =
    ScanAppearanceSettings(
        colorMode = ScanColorMode.Natural,
        naturalIntensity = 0,
        colorIntensity = 0,
        lightTextIntensity = 0,
        grayscaleIntensity = 0,
        blackWhiteIntensity = 0,
        whiteboardIntensity = 0,
        shadows = 0,
    )

internal fun parseScanAppearanceSettings(
    colorModeWireValue: String?,
    colorIntensity: Int?,
    grayscaleIntensity: Int?,
    blackWhiteIntensity: Int?,
    shadows: Int?,
    naturalIntensity: Int? = null,
    lightTextIntensity: Int? = null,
    whiteboardIntensity: Int? = null,
): ScanAppearanceSettings =
    ScanAppearanceSettings(
        colorMode =
            ScanColorMode.entries.firstOrNull { it.wireValue == colorModeWireValue }
                ?: ScanColorMode.BlackWhite,
        naturalIntensity =
            clampAppearancePercent(naturalIntensity ?: colorIntensity ?: DEFAULT_FILTER_INTENSITY),
        colorIntensity = clampAppearancePercent(colorIntensity ?: DEFAULT_FILTER_INTENSITY),
        lightTextIntensity =
            clampAppearancePercent(lightTextIntensity ?: colorIntensity ?: DEFAULT_FILTER_INTENSITY),
        grayscaleIntensity = clampAppearancePercent(grayscaleIntensity ?: DEFAULT_FILTER_INTENSITY),
        blackWhiteIntensity = clampAppearancePercent(blackWhiteIntensity ?: DEFAULT_FILTER_INTENSITY),
        whiteboardIntensity =
            clampAppearancePercent(
                whiteboardIntensity ?: blackWhiteIntensity ?: DEFAULT_FILTER_INTENSITY,
            ),
        shadows = clampAppearancePercent(shadows ?: DEFAULT_SHADOWS),
    )

internal fun clampAppearancePercent(value: Int): Int = value.coerceIn(0, 100)

internal fun parseScanAppearance(
    colorModeWireValue: String?,
    intensity: Int?,
    shadows: Int?,
): ScanAppearance =
    ScanAppearance(
        colorMode =
            ScanColorMode.entries.firstOrNull { it.wireValue == colorModeWireValue }
                ?: ScanColorMode.BlackWhite,
        intensity = clampAppearancePercent(intensity ?: DEFAULT_FILTER_INTENSITY),
        shadows = clampAppearancePercent(shadows ?: DEFAULT_SHADOWS),
    )

internal fun rgbLuma(red: Int, green: Int, blue: Int): Int {
    require(red in 0..255 && green in 0..255 && blue in 0..255) {
        "RGB channels must be between 0 and 255"
    }
    return (299 * red + 587 * green + 114 * blue + 500) / 1_000
}

internal fun correctLocalShadows(
    luma: IntArray,
    width: Int,
    height: Int,
    strength: Int,
): IntArray {
    requireImageShape(luma.size, width, height)
    require(luma.all { it in 0..255 }) { "Luma values must be between 0 and 255" }
    val normalizedStrength = clampAppearancePercent(strength)
    if (normalizedStrength == 0) return luma.copyOf()

    val columns = minOf(LOCAL_SHADOW_GRID_SIZE, width)
    val rows = minOf(LOCAL_SHADOW_GRID_SIZE, height)
    val backgrounds = IntArray(columns * rows)
    luma.forEachIndexed { index, value ->
        val x = index % width
        val y = index / width
        val tile = localShadowTileIndex(x, y, width, height, columns, rows)
        backgrounds[tile] = maxOf(backgrounds[tile], value)
    }
    return IntArray(luma.size) { index ->
        val x = index % width
        val y = index / width
        val value = luma[index]
        val background =
            localShadowBackgroundAt(backgrounds, columns, rows, x, y, width, height)
        correctShadowLuma(value, background, normalizedStrength)
    }
}

internal fun processScanPixels(
    pixels: IntArray,
    width: Int,
    height: Int,
    appearance: ScanAppearance,
): IntArray {
    requireImageShape(pixels.size, width, height)
    val intensity = clampAppearancePercent(appearance.intensity)
    val rawLuma = IntArray(pixels.size) { argbLuma(pixels[it]) }
    val correctedLuma = correctLocalShadows(rawLuma, width, height, appearance.shadows)
    val blackWhiteThreshold =
        when (appearance.colorMode) {
            ScanColorMode.BlackWhite -> otsuThreshold(correctedLuma)
            ScanColorMode.Whiteboard -> whiteboardThreshold(otsuThreshold(correctedLuma))
            else -> 127
        }
    val normalizedAppearance = appearance.copy(intensity = intensity)
    return IntArray(pixels.size) { index ->
        processAppearancePixel(
            pixel = pixels[index],
            correctedLuma = correctedLuma[index],
            appearance = normalizedAppearance,
            blackWhiteThreshold = blackWhiteThreshold,
        )
    }
}

internal fun processAppearancePixel(
    pixel: Int,
    correctedLuma: Int,
    appearance: ScanAppearance,
    blackWhiteThreshold: Int,
): Int {
    val intensity = clampAppearancePercent(appearance.intensity)
    val correctedPixel =
        scaleColorToLuma(
            pixel = pixel,
            sourceLuma = argbLuma(pixel),
            targetLuma = correctedLuma,
        )
    if (intensity == 0) return correctedPixel
    val target =
        when (appearance.colorMode) {
            ScanColorMode.Natural ->
                scaleColorToLuma(
                    pixel = correctedPixel,
                    sourceLuma = correctedLuma,
                    targetLuma = naturalContrastLuma(correctedLuma),
                )
            ScanColorMode.Color ->
                scaleColorToLuma(
                    pixel = correctedPixel,
                    sourceLuma = correctedLuma,
                    targetLuma = contrastLuma(correctedLuma),
                )
            ScanColorMode.LightText -> grayPixel(correctedPixel, lightTextLuma(correctedLuma))
            ScanColorMode.Grayscale -> grayPixel(correctedPixel, correctedLuma)
            ScanColorMode.BlackWhite,
            ScanColorMode.Whiteboard,
            ->
                grayPixel(correctedPixel, if (correctedLuma <= blackWhiteThreshold) 0 else 255)
        }
    return blendPixel(correctedPixel, target, intensity)
}

internal fun otsuThreshold(luma: IntArray): Int {
    require(luma.isNotEmpty()) { "Luma input must not be empty" }
    val histogram = IntArray(256)
    luma.forEach { value ->
        require(value in 0..255) { "Luma values must be between 0 and 255" }
        histogram[value]++
    }
    return otsuThresholdFromHistogram(histogram)
}

internal fun otsuThresholdFromHistogram(histogram: IntArray): Int {
    require(histogram.size == 256 && histogram.all { it >= 0 }) {
        "Histogram must have 256 non-negative bins"
    }
    val pixelCount = histogram.sumOf { it.toLong() }
    require(pixelCount > 0L) { "Histogram must contain at least one pixel" }
    val totalSum = histogram.indices.sumOf { it.toLong() * histogram[it] }

    var backgroundWeight = 0L
    var backgroundSum = 0L
    var bestVariance = -1.0
    var firstBest = 0
    var lastBest = 0
    for (threshold in 0 until 255) {
        backgroundWeight += histogram[threshold]
        backgroundSum += threshold.toLong() * histogram[threshold]
        val foregroundWeight = pixelCount - backgroundWeight
        if (backgroundWeight == 0L || foregroundWeight == 0L) continue
        val backgroundMean = backgroundSum.toDouble() / backgroundWeight
        val foregroundMean = (totalSum - backgroundSum).toDouble() / foregroundWeight
        val difference = backgroundMean - foregroundMean
        val variance = backgroundWeight.toDouble() * foregroundWeight * difference * difference
        when {
            variance > bestVariance -> {
                bestVariance = variance
                firstBest = threshold
                lastBest = threshold
            }

            variance == bestVariance -> lastBest = threshold
        }
    }
    return if (bestVariance <= 0.0) 127 else (firstBest + lastBest) / 2
}

internal fun packBlackMask(luma: IntArray, threshold: Int): ByteArray {
    require(threshold in 0..255) { "Threshold must be between 0 and 255" }
    val packed = ByteArray((luma.size + 7) / 8)
    luma.forEachIndexed { index, value ->
        require(value in 0..255) { "Luma values must be between 0 and 255" }
        if (value <= threshold) {
            packed[index / 8] = (packed[index / 8].toInt() or (0x80 ushr (index % 8))).toByte()
        }
    }
    return packed
}

private fun requireImageShape(size: Int, width: Int, height: Int) {
    require(width > 0 && height > 0 && width.toLong() * height == size.toLong()) {
        "Pixel count must match positive image dimensions"
    }
}

internal fun localShadowBackgroundAt(
    backgrounds: IntArray,
    columns: Int,
    rows: Int,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
): Int {
    val left = tileCoordinate(x, width, columns)
    val top = tileCoordinate(y, height, rows)
    val right = minOf(left + 1, columns - 1)
    val bottom = minOf(top + 1, rows - 1)
    val xStart = (left.toLong() * width / columns).toInt()
    val xEnd = ((left + 1L) * width / columns).toInt().coerceAtLeast(xStart + 1)
    val yStart = (top.toLong() * height / rows).toInt()
    val yEnd = ((top + 1L) * height / rows).toInt().coerceAtLeast(yStart + 1)
    val topBackground =
        interpolate(
            backgrounds[top * columns + left],
            backgrounds[top * columns + right],
            x - xStart,
            xEnd - xStart,
        )
    val bottomBackground =
        interpolate(
            backgrounds[bottom * columns + left],
            backgrounds[bottom * columns + right],
            x - xStart,
            xEnd - xStart,
        )
    return interpolate(topBackground, bottomBackground, y - yStart, yEnd - yStart)
}

internal fun localShadowTileIndex(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    columns: Int,
    rows: Int,
): Int = tileCoordinate(y, height, rows) * columns + tileCoordinate(x, width, columns)

internal fun correctShadowLuma(value: Int, background: Int, strength: Int): Int {
    val normalized =
        (if (background == 0) value else (value * 255 + background / 2) / background)
            .coerceIn(0, 255)
    return (value + (normalized - value) * strength / 100).coerceIn(0, 255)
}

private fun tileCoordinate(position: Int, size: Int, tileCount: Int): Int =
    (position.toLong() * tileCount / size).toInt()

private fun interpolate(start: Int, end: Int, numerator: Int, denominator: Int): Int =
    ((start.toLong() * (denominator - numerator) + end.toLong() * numerator + denominator / 2) /
        denominator)
        .toInt()

internal fun argbLuma(pixel: Int): Int =
    rgbLuma(
        red = pixel ushr 16 and 0xFF,
        green = pixel ushr 8 and 0xFF,
        blue = pixel and 0xFF,
    )

private fun contrastLuma(value: Int): Int =
    (128 + (value - 128) * 2).coerceIn(0, 255)

private fun naturalContrastLuma(value: Int): Int =
    (128 + (value - 128) * 5 / 4).coerceIn(0, 255)

private fun lightTextLuma(value: Int): Int = value + (255 - value) / 2

private fun whiteboardThreshold(value: Int): Int = value * 3 / 4

private fun scaleColorToLuma(pixel: Int, sourceLuma: Int, targetLuma: Int): Int {
    if (sourceLuma == targetLuma) return pixel
    val alpha = pixel and 0xFF000000.toInt()
    if (sourceLuma == 0) return alpha
    fun scale(channel: Int): Int =
        ((channel * targetLuma + sourceLuma / 2) / sourceLuma).coerceIn(0, 255)
    return alpha or
        (scale(pixel ushr 16 and 0xFF) shl 16) or
        (scale(pixel ushr 8 and 0xFF) shl 8) or
        scale(pixel and 0xFF)
}

private fun blendPixel(source: Int, target: Int, intensity: Int): Int {
    if (intensity == 0) return source
    fun blend(shift: Int): Int {
        val start = source ushr shift and 0xFF
        val end = target ushr shift and 0xFF
        return start + (end - start) * intensity / 100
    }
    return (source and 0xFF000000.toInt()) or
        (blend(16) shl 16) or
        (blend(8) shl 8) or
        blend(0)
}

private fun grayPixel(pixel: Int, value: Int): Int =
    (pixel and 0xFF000000.toInt()) or (value shl 16) or (value shl 8) or value
