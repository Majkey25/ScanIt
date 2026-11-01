package com.majkeylab.scanit

import kotlin.math.abs
import kotlin.math.roundToInt

internal fun extractMarkInk(width: Int, height: Int, pixels: IntArray): IntArray {
    require(width in 1..MARK_DECODE_MAX_SIDE && height in 1..MARK_DECODE_MAX_SIDE) {
        "Mark extraction dimensions are invalid"
    }
    require(width.toLong() * height == pixels.size.toLong()) {
        "Pixel count does not match image dimensions"
    }
    // Preserve authored alpha, including intentional white or pale ink.
    if (pixels.any { it ushr 24 != 255 }) return pixels.copyOf()

    // Estimate paper from its edges instead of erasing wide stamp strokes as background.
    // shortcut: Keep a clear paper margin; isolated interior shadows or textured paper need review.
    val margin = (minOf(width, height) / 32).coerceIn(1, 32)
    val histogram = IntArray(256)
    // A frame with no interior cannot estimate separate paper edges around each ink pixel.
    if (width <= margin * 2 || height <= margin * 2) {
        val paper = paperSample(pixels, 0, 1, pixels.size, histogram)
        return IntArray(pixels.size) { unmixPaper(pixels[it], paper) }
    }
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
            paper = paper or ((horizontal + vertical - corners).roundToInt().coerceIn(0, 255) shl shift)
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
    // Without observable paper, retain the existing solid-black ink behavior.
    if (paper == 0) return pixel
    var alpha = 0
    var darkAlpha = 0
    for (shift in 16 downTo 0 step 8) {
        val channel = pixel ushr shift and 255
        val background = paper ushr shift and 255
        val difference = background - channel
        val span = if (difference > 0) background else 255 - background
        val required = if (difference == 0) 0 else (abs(difference) * 255 + span - 1) / span
        alpha = maxOf(alpha, required)
        if (difference > 0) darkAlpha = maxOf(darkAlpha, required)
    }
    // Bright JPEG noise alone is not evidence of ink.
    if (darkAlpha < MARK_VISIBLE_ALPHA) return 0
    // Remove paper from RGB too, so antialiased ink has no white or grey fringe.
    fun foreground(shift: Int): Int {
        val channel = pixel ushr shift and 255
        val background = paper ushr shift and 255
        return ((channel * 255 - background * (255 - alpha) + alpha / 2) / alpha).coerceIn(0, 255)
    }
    return (alpha shl 24) or
        (foreground(16) shl 16) or
        (foreground(8) shl 8) or
        foreground(0)
}
