package com.majkeylab.scanit

private const val DARK_LUMA = 32
private const val BRIGHT_LUMA = 245
private const val DARK_MEAN = 45.0
private const val BRIGHT_MEAN = 225.0
private const val CLIPPED_FRACTION = 0.70
private const val MIN_LAPLACIAN_VARIANCE = 100.0

internal enum class ScannerV2CaptureQualityIssue {
    Blurry,
    TooDark,
    Overexposed,
}

internal fun analyzeScannerV2CaptureQuality(
    frame: LumaFrame,
    crop: PageQuad? = null,
): Set<ScannerV2CaptureQualityIssue> {
    var count = 0
    var sum = 0L
    var dark = 0
    var bright = 0
    for (y in 0 until frame.height) {
        for (x in 0 until frame.width) {
            if (crop != null && !crop.containsPixel(x, y, frame.width, frame.height)) continue
            val value = frame.unsignedPixel(y * frame.width + x)
            count += 1
            sum += value
            if (value <= DARK_LUMA) dark += 1
            if (value >= BRIGHT_LUMA) bright += 1
        }
    }
    if (count == 0) return emptySet()
    val mean = sum.toDouble() / count
    if (mean <= DARK_MEAN || dark.toDouble() / count >= CLIPPED_FRACTION) {
        return setOf(ScannerV2CaptureQualityIssue.TooDark)
    }
    if (mean >= BRIGHT_MEAN || bright.toDouble() / count >= CLIPPED_FRACTION) {
        return setOf(ScannerV2CaptureQualityIssue.Overexposed)
    }

    var samples = 0
    var laplacianMean = 0.0
    var squaredDifference = 0.0
    for (y in 1 until frame.height - 1) {
        for (x in 1 until frame.width - 1) {
            if (
                crop != null &&
                (!crop.containsPixel(x, y, frame.width, frame.height) ||
                    !crop.containsPixel(x - 1, y, frame.width, frame.height) ||
                    !crop.containsPixel(x + 1, y, frame.width, frame.height) ||
                    !crop.containsPixel(x, y - 1, frame.width, frame.height) ||
                    !crop.containsPixel(x, y + 1, frame.width, frame.height))
            ) {
                continue
            }
            val index = y * frame.width + x
            val laplacian = 4 * frame.unsignedPixel(index) -
                frame.unsignedPixel(index - 1) -
                frame.unsignedPixel(index + 1) -
                frame.unsignedPixel(index - frame.width) -
                frame.unsignedPixel(index + frame.width)
            samples += 1
            val delta = laplacian - laplacianMean
            laplacianMean += delta / samples
            squaredDifference += delta * (laplacian - laplacianMean)
        }
    }
    val variance = if (samples > 1) squaredDifference / (samples - 1) else 0.0
    return if (variance < MIN_LAPLACIAN_VARIANCE) {
        setOf(ScannerV2CaptureQualityIssue.Blurry)
    } else {
        emptySet()
    }
}

private fun PageQuad.containsPixel(x: Int, y: Int, width: Int, height: Int): Boolean {
    val px = (x + 0.5) / width
    val py = (y + 0.5) / height
    fun cross(first: NormalizedPoint, second: NormalizedPoint): Double =
        (second.x - first.x) * (py - first.y) - (second.y - first.y) * (px - first.x)
    val first = cross(topLeft, topRight)
    val second = cross(topRight, bottomRight)
    val third = cross(bottomRight, bottomLeft)
    val fourth = cross(bottomLeft, topLeft)
    val hasNegative = first < -1e-9 || second < -1e-9 || third < -1e-9 || fourth < -1e-9
    val hasPositive = first > 1e-9 || second > 1e-9 || third > 1e-9 || fourth > 1e-9
    return !(hasNegative && hasPositive)
}
