package com.majkeylab.scanit

private const val DEFAULT_FILTER_INTENSITY = 100
private const val DEFAULT_SHADOWS = 50
private const val BACKGROUND_GRID_SIZE = 8
private const val MAX_THRESHOLD_SHIFT = 48

internal enum class ScanColorMode(val wireValue: String) {
    Color("color"),
    Grayscale("grayscale"),
    BlackWhite("black_white"),
}

internal data class ScanAppearance(
    val colorMode: ScanColorMode = ScanColorMode.BlackWhite,
    val intensity: Int = DEFAULT_FILTER_INTENSITY,
    val shadows: Int = DEFAULT_SHADOWS,
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

    val columns = minOf(BACKGROUND_GRID_SIZE, width)
    val rows = minOf(BACKGROUND_GRID_SIZE, height)
    val backgrounds = IntArray(columns * rows)
    luma.forEachIndexed { index, value ->
        val x = index % width
        val y = index / width
        val tile = tileCoordinate(y, height, rows) * columns + tileCoordinate(x, width, columns)
        backgrounds[tile] = maxOf(backgrounds[tile], value)
    }
    return IntArray(luma.size) { index ->
        val x = index % width
        val y = index / width
        val value = luma[index]
        val background = interpolatedBackground(backgrounds, columns, rows, x, y, width, height)
        val normalized =
            (if (background == 0) value else (value * 255 + background / 2) / background)
                .coerceIn(0, 255)
        (value + (normalized - value) * normalizedStrength / 100).coerceIn(0, 255)
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
    return when (appearance.colorMode) {
        ScanColorMode.Color ->
            IntArray(pixels.size) { index ->
                scaleColorToLuma(
                    pixel = pixels[index],
                    sourceLuma = rawLuma[index],
                    targetLuma = contrastLuma(correctedLuma[index], intensity),
                )
            }

        ScanColorMode.Grayscale ->
            IntArray(pixels.size) { index ->
                grayPixel(pixels[index], contrastLuma(correctedLuma[index], intensity))
            }

        ScanColorMode.BlackWhite -> {
            val threshold =
                (otsuThreshold(correctedLuma) +
                    (intensity - 50) * (MAX_THRESHOLD_SHIFT * 2) / 100)
                    .coerceIn(0, 255)
            IntArray(pixels.size) { index ->
                grayPixel(pixels[index], if (correctedLuma[index] <= threshold) 0 else 255)
            }
        }
    }
}

internal fun otsuThreshold(luma: IntArray): Int {
    require(luma.isNotEmpty()) { "Luma input must not be empty" }
    val histogram = IntArray(256)
    var totalSum = 0L
    luma.forEach { value ->
        require(value in 0..255) { "Luma values must be between 0 and 255" }
        histogram[value]++
        totalSum += value
    }

    var backgroundWeight = 0L
    var backgroundSum = 0L
    var bestVariance = -1.0
    var firstBest = 0
    var lastBest = 0
    for (threshold in 0 until 255) {
        backgroundWeight += histogram[threshold]
        backgroundSum += threshold.toLong() * histogram[threshold]
        val foregroundWeight = luma.size.toLong() - backgroundWeight
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

private fun interpolatedBackground(
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

private fun tileCoordinate(position: Int, size: Int, tileCount: Int): Int =
    (position.toLong() * tileCount / size).toInt()

private fun interpolate(start: Int, end: Int, numerator: Int, denominator: Int): Int =
    ((start.toLong() * (denominator - numerator) + end.toLong() * numerator + denominator / 2) /
        denominator)
        .toInt()

private fun argbLuma(pixel: Int): Int =
    rgbLuma(
        red = pixel ushr 16 and 0xFF,
        green = pixel ushr 8 and 0xFF,
        blue = pixel and 0xFF,
    )

private fun contrastLuma(value: Int, intensity: Int): Int =
    (128 + (value - 128) * (100 + intensity) / 100).coerceIn(0, 255)

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

private fun grayPixel(pixel: Int, value: Int): Int =
    (pixel and 0xFF000000.toInt()) or (value shl 16) or (value shl 8) or value
