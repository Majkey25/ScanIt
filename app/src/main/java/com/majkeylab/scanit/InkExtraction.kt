package com.majkeylab.scanit

import kotlin.math.roundToInt

internal fun extractMarkInk(width: Int, height: Int, pixels: IntArray): IntArray {
    require(width in 1..MARK_DECODE_MAX_SIDE && height in 1..MARK_DECODE_MAX_SIDE) {
        "Mark extraction dimensions are invalid"
    }
    require(width.toLong() * height == pixels.size.toLong()) {
        "Pixel count does not match image dimensions"
    }
    if (pixels.any { it ushr 24 != 255 }) return pixels.copyOf()

    // Estimate paper from its edges instead of erasing wide stamp strokes as background.
    // shortcut: Keep a clear paper margin; isolated interior shadows or textured paper need review.
    val margin = (minOf(width, height) / 32).coerceIn(1, 32)
    val histogram = IntArray(256)
    val left = IntArray(height) { paperSample(pixels, it * width, 1, margin, histogram) }
    val right = IntArray(height) { paperSample(pixels, it * width + width - margin, 1, margin, histogram) }
    val top = IntArray(width) { paperSample(pixels, it, width, margin, histogram) }
    val bottom = IntArray(width) { paperSample(pixels, (height - margin) * width + it, width, margin, histogram) }
    return IntArray(pixels.size) { index ->
        val x = index % width
        val y = index / width
        val u = x.toFloat() / (width - 1).coerceAtLeast(1)
        val v = y.toFloat() / (height - 1).coerceAtLeast(1)
        var paper = 0
        for (shift in 16 downTo 0 step 8) {
            fun channel(color: Int) = color ushr shift and 255
            val horizontal = channel(left[y]) * (1f - u) + channel(right[y]) * u
            val vertical = channel(top[x]) * (1f - v) + channel(bottom[x]) * v
            val corners =
                (channel(left.first()) * (1f - u) + channel(right.first()) * u) * (1f - v) +
                    (channel(left.last()) * (1f - u) + channel(right.last()) * u) * v
            paper = paper or ((horizontal + vertical - corners).roundToInt().coerceIn(32, 255) shl shift)
        }
        unmixPaper(pixels[index], paper)
    }
}

private fun paperSample(
    pixels: IntArray,
    start: Int,
    stride: Int,
    count: Int,
    histogram: IntArray,
): Int {
    var paper = 0
    for (shift in 16 downTo 0 step 8) {
        histogram.fill(0)
        for (offset in 0 until count) histogram[pixels[start + offset * stride] ushr shift and 255]++
        var remaining = count * 3 / 4
        var value = 0
        while (value < 255 && remaining >= histogram[value]) remaining -= histogram[value++]
        paper = paper or (value shl shift)
    }
    return paper
}

private fun unmixPaper(pixel: Int, paper: Int): Int {
    val red = pixel ushr 16 and 255
    val green = pixel ushr 8 and 255
    val blue = pixel and 255
    val paperRed = paper ushr 16 and 255
    val paperGreen = paper ushr 8 and 255
    val paperBlue = paper and 255
    val alpha = maxOf(
        (paperRed - red) * 255 / paperRed,
        (paperGreen - green) * 255 / paperGreen,
        (paperBlue - blue) * 255 / paperBlue,
    )
    if (alpha < MARK_VISIBLE_ALPHA) return 0
    // Remove paper from RGB too, so antialiased ink has no white or grey fringe.
    fun foreground(channel: Int, background: Int): Int =
        ((channel * 255 - background * (255 - alpha) + alpha / 2) / alpha).coerceIn(0, 255)
    return (alpha shl 24) or
        (foreground(red, paperRed) shl 16) or
        (foreground(green, paperGreen) shl 8) or
        foreground(blue, paperBlue)
}
